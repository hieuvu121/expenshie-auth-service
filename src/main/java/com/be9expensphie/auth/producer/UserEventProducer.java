package com.be9expensphie.auth.producer;

import com.be9expensphie.common.event.UserEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserEventProducer {
    private final KafkaTemplate<String, UserEvent> userKafkaTemplate;

    public void publishUserEvent(UserEvent event) {
        log.info("Publishing user event to Kafka topic: user-events for {}", event.getEmail());
        userKafkaTemplate.send("user-events", event.getEmail(), event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("User event sent successfully: {}", result.getRecordMetadata());
                    } else {
                        log.error("Failed to send user event", ex);
                    }
                });
    }
}
