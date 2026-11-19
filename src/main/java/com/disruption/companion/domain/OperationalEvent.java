package com.disruption.companion.domain;

import java.time.Instant;
import java.time.LocalTime;

public record OperationalEvent(String eventId, String segmentId, String type, LocalTime newDeparture,
                                Integer delayMinutes, String gate, String status, String source,
                                Instant recordedAt) {

    public EventField field() {
        return "GATE_CHANGE".equals(type) ? EventField.GATE : EventField.SCHEDULE;
    }

    public String value() {
        return switch (type) {
            case "DELAY" -> "DEPARTS " + newDeparture;
            case "GATE_CHANGE" -> "GATE " + gate;
            default -> "STATUS " + status;
        };
    }
}