package com.be9expensphie.auth.producer;

import com.be9expensphie.common.event.EmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailProducer {
    private final KafkaTemplate<String, EmailEvent> emailKafkaTemplate;

    public void sendEmailEvent(String to, String subject, String body, String eventType) {
        EmailEvent event = new EmailEvent(to, subject, body, eventType, null);
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
