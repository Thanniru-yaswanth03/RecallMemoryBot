package com.recallbot.admin.activity;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, in-memory bounded circular buffer retaining recent operational events.
 */
@Component
public class AdminActivityBuffer {

    private static final int DEFAULT_CAPACITY = 500;
    private final int capacity;
    private final ConcurrentLinkedDeque<BotActivityEvent> buffer = new ConcurrentLinkedDeque<>();
    private final AtomicLong eventSequence = new AtomicLong(1);

    public AdminActivityBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public AdminActivityBuffer(int capacity) {
        this.capacity = Math.max(50, capacity);
    }

    public void recordEvent(BotActivityEvent event) {
        if (event == null) {
            return;
        }

        // If event doesn't have an ID, assign one
        if (event.id() == null || event.id().isBlank()) {
            event = new BotActivityEvent(
                    "evt-" + eventSequence.getAndIncrement(),
                    event.timestamp(),
                    event.eventType(),
                    event.groupId(),
                    event.groupTitle(),
                    event.status(),
                    event.durationMs(),
                    event.details()
            );
        }

        buffer.addFirst(event);

        // Trim buffer to capacity
        while (buffer.size() > capacity) {
            buffer.pollLast();
        }
    }

    public List<BotActivityEvent> getRecentEvents(int limit, String eventType, Long groupId) {
        int max = (limit <= 0 || limit > capacity) ? 50 : limit;
        List<BotActivityEvent> results = new ArrayList<>();

        for (BotActivityEvent event : buffer) {
            if (eventType != null && !eventType.isBlank() && !eventType.equalsIgnoreCase(event.eventType())) {
                continue;
            }
            if (groupId != null && !groupId.equals(event.groupId())) {
                continue;
            }

            results.add(event);
            if (results.size() >= max) {
                break;
            }
        }

        return results;
    }

    public int size() {
        return buffer.size();
    }

    public void clear() {
        buffer.clear();
    }
}
