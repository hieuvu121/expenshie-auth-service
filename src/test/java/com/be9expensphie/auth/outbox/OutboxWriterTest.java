package com.be9expensphie.auth.outbox;

import com.be9expensphie.common.event.UserEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The behaviour that makes the outbox worth having: the event row lives or dies
 * with the transaction that produced it.
 *
 * Runs against a real transaction on H2 rather than mocks, because a mocked
 * repository cannot roll anything back and would pass no matter what the
 * writer does.
 */
@DataJpaTest
@Import({OutboxWriter.class, OutboxWriterTest.TestConfig.class})
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        /*
         * Pinned, not inherited. auth-service does not set this today -- see
         * the note in application.properties about deliberately leaving
         * hikari.auto-commit alone -- but expense-service does, and if this one
         * ever follows, a true value would tell Hibernate not to disable
         * autocommit on the datasource @DataJpaTest substitutes, which has it
         * ON. Every statement would then self-commit and the rollback test
         * below would quietly stop testing anything.
         */
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
class OutboxWriterTest {

    static class TestConfig {
        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }

    @Autowired private OutboxWriter writer;
    @Autowired private OutboxRepository repository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    /*
     * These tests commit for real (NOT_SUPPORTED opts out of @DataJpaTest's
     * automatic rollback, which would otherwise hide the very thing under
     * test), so rows survive into the next test unless cleared here.
     */
    @BeforeEach
    void clearOutbox() {
        repository.deleteAll();
    }

    private static UserEvent event() {
        return UserEvent.builder()
                .userId(7L)
                .email("dana@example.com")
                .fullName("Dana")
                .eventType("USER_REGISTERED")
                .build();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aCommittedTransactionLeavesTheEventBehind() {
        new TransactionTemplate(txManager).executeWithoutResult(
                status -> writer.write("user-events", "dana@example.com", event()));

        List<OutboxEvent> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.get(0);
        assertThat(row.getTopic()).isEqualTo("user-events");
        assertThat(row.getAggregateId()).isEqualTo("dana@example.com");
        assertThat(row.getEventId()).isNotBlank();
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getPayload()).contains("\"userId\":7", "USER_REGISTERED");
    }

    /*
     * The failure the outbox exists to prevent. registerUser used to save the
     * user and then publish best-effort, so a broker outage produced a user
     * that household_db.user_summary never learned about, with no repair path.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRolledBackTransactionLeavesNoEvent() {
        assertThatThrownBy(() ->
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    writer.write("user-events", "dana@example.com", event());
                    throw new IllegalStateException("something failed after the write");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void eachEventGetsItsOwnId() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            writer.write("user-events", "dana@example.com", event());
            writer.write("email-events", "dana@example.com", event());
        });

        assertThat(repository.findAll())
                .extracting(OutboxEvent::getEventId)
                .doesNotHaveDuplicates()
                .hasSize(2);
    }
}
