package com.goeats.order.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ★ Idempotent Consumer Pattern
 *
 * Problem: Kafka delivers at-least-once → duplicate events under traffic
 * Solution: Track processed eventIds in DB, skip duplicates
 *
 * Flow:
 *   1. Receive event with eventId
 *   2. Check if eventId exists in processed_events table
 *   3. If exists → skip (idempotent)
 *   4. If not → process event + insert eventId (same transaction)
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    @Column(nullable = false)
    private String eventId;

    @Column(nullable = false)
    private LocalDateTime processedAt;

    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = LocalDateTime.now();
    }
}
