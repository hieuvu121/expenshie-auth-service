package com.be9expensphie.auth.outbox;

import com.be9expensphie.common.event.EmailEvent;
import com.be9expensphie.common.event.UserEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The publisher's contract is narrow and entirely about failure: publish in id
 * order, stamp only what the broker accepted, and stop the batch at the first
 * refusal rather than skipping past it.
 *
 * KafkaTemplate is subclassed rather than mocked — it is a concrete class the
 * inline mock maker cannot instrument on JDK 25. The producer factory it is
 * built on never creates a producer here, so nothing tries to reach a broker.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock private OutboxRepository repository;

    private RecordingTemplate<UserEvent> userTemplate;
    private RecordingTemplate<EmailEvent> emailTemplate;
    private OutboxPublisher publisher;
    private ObjectMapper objectMapper;

    private static class RecordingTemplate<V> extends KafkaTemplate<String, V> {
        final List<String> sent = new ArrayList<>();
        boolean fails;

        RecordingTemplate() {
            super(new DefaultKafkaProducerFactory<>(Map.of()));
        }

        @Override
        public CompletableFuture<SendResult<String, V>> send(String topic, String key, V data) {
            if (fails) {
                return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
            }
            sent.add(topic + "|" + key);
            return CompletableFuture.completedFuture(null);
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        userTemplate = new RecordingTemplate<>();
        emailTemplate = new RecordingTemplate<>();
        publisher = new OutboxPublisher(repository, objectMapper, userTemplate, emailTemplate);
        ReflectionTestUtils.setField(publisher, "retentionDays", 7);
    }

    private OutboxEvent row(long id, String topic, Object payload) {
        try {
            return OutboxEvent.builder()
                    .id(id)
                    .aggregateId("dana@example.com")
                    .topic(topic)
                    .eventId("evt-" + id)
                    .payload(objectMapper.writeValueAsString(payload))
                    .createdAt(Instant.now())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static UserEvent userEvent() {
        return UserEvent.builder()
                .userId(7L).email("dana@example.com").fullName("Dana")
                .eventType("USER_REGISTERED")
                .build();
    }

    private static EmailEvent emailEvent() {
        return new EmailEvent("dana@example.com", "Activate your account", "link", "ACTIVATION", null);
    }

    @Test
    void drainSendsEachRowToTheTemplateForItsTopicAndStampsIt() {
        List<OutboxEvent> batch = List.of(
                row(1, "user-events", userEvent()),
                row(2, "email-events", emailEvent()));
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(batch);

        publisher.drain();

        assertThat(userTemplate.sent).containsExactly("user-events|dana@example.com");
        assertThat(emailTemplate.sent).containsExactly("email-events|dana@example.com");
        assertThat(batch).allSatisfy(r -> assertThat(r.getPublishedAt()).isNotNull());
        verify(repository).saveAll(batch);
    }

    @Test
    void anEmptyOutboxDoesNothing() {
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of());

        publisher.drain();

        assertThat(userTemplate.sent).isEmpty();
        verify(repository, never()).saveAll(any());
    }

    /*
     * The ordering guarantee. Skipping a failed row and publishing the next one
     * would deliver a user's events out of order.
     */
    @Test
    void aRefusedSendStopsTheBatchAndLeavesEverythingAfterItUnpublished() {
        OutboxEvent first = row(1, "user-events", userEvent());
        OutboxEvent second = row(2, "user-events", userEvent());
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(first, second));
        userTemplate.fails = true;

        publisher.drain();

        assertThat(first.getPublishedAt()).isNull();
        assertThat(second.getPublishedAt()).isNull();
        verify(repository, never()).saveAll(any());
    }

    /** A row that fails must not be lost: the next tick has to find it again. */
    @Test
    void aPartiallyPublishedBatchSavesOnlyWhatWasAccepted() {
        OutboxEvent good = row(1, "user-events", userEvent());
        OutboxEvent bad = row(2, "email-events", emailEvent());
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(good, bad));
        emailTemplate.fails = true;

        publisher.drain();

        assertThat(good.getPublishedAt()).isNotNull();
        assertThat(bad.getPublishedAt()).isNull();
        verify(repository).saveAll(List.of(good));
    }

    @Test
    void purgeDeletesPublishedRowsOlderThanTheRetentionWindow() {
        when(repository.deletePublishedBefore(any())).thenReturn(3);

        publisher.purgePublished();

        verify(repository).deletePublishedBefore(any(Instant.class));
    }
}
