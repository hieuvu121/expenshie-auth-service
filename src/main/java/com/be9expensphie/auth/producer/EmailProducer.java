package com.be9expensphie.auth.producer;

import com.be9expensphie.common.event.EmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailProducer {
    private final KafkaTemplate<String, EmailEvent> emailKafkaTemplate;

    /**
     * Publishes directly, so it stamps its own event id.
     *
     * The registration path goes through the outbox, which does this for it.
     * This one is still ForgotPasswordService's, and without an id
     * email-service cannot dedup a retry -- @RetryableTopic(attempts = "4")
     * makes those routine rather than exceptional.
     */
    public void sendEmailEvent(String to, String subject, String body, String eventType) {
        EmailEvent event = EmailEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .to(to)
                .subject(subject)
                .body(body)
                .eventType(eventType)
                .build();
        log.info("Sending email event to Kafka topic: email-events for {}", to);
        emailKafkaTemplate.send("email-events", to, event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Email event sent successfully: {}", result.getRecordMetadata());
                    } else {
                        log.error("Failed to send email event", ex);
                    }
                });
    }
}
