package com.disruption.companion.domain;

public record IdempotencyKey(String value) {

    public static IdempotencyKey of(String bookingId, String segmentId, String eventId) {
        return new IdempotencyKey(bookingId + ":" + segmentId + ":" + eventId);
    }
}
