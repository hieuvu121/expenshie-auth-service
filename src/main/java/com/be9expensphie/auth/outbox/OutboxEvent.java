package com.be9expensphie.auth.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An event that has been decided but not yet published.
 *
 * Written in the same transaction as the state change it describes, which is
 * the whole point: the row and the change commit together or not at all.
 * registerUser previously saved the user and then published on a best-effort
 * basis, catching and logging any failure -- so a broker outage produced a user
 * in auth_db that household_db.user_summary never learned about. Permanently:
 * UserEventConsumer handles only USER_REGISTERED and there is no repair path.
 */
@Entity
@Table(name = "outbox_event", indexes = {
        @Index(name = "idx_outbox_unpublished", columnList = "published_at,id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Becomes the Kafka message key, so per-aggregate ordering survives. */
    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(nullable = false, length = 64)
    private String topic;

    /**
     * Identifies this event for consumers that need to discard duplicates.
     *
     * OutboxPublisher is at-least-once by design — it republishes anything it
     * could not confirm — so without this a redelivery is indistinguishable
     * from a new event.
     */
    @Column(name = "event_id", nullable = false, length = 36, unique = true)
    private String eventId;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null until the broker has acknowledged it. */
    @Column(name = "published_at")
    private Instant publishedAt;
}
