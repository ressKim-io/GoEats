package com.goeats.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Find unpublished events ordered by creation time (FIFO).
     * The relay polls this to send events to Kafka.
     */
    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();
}
