package com.disruption.companion.domain;

import java.util.List;

public record FieldResolution(String segmentId, EventField field, Status status, OperationalEvent winner,
                              boolean stale, List<DiscardedEvent> discarded) {

    public enum Status { CONFIRMED, CONFLICT, UNCONFIRMED }

    public record DiscardedEvent(OperationalEvent event, String reason) {
    }
}
