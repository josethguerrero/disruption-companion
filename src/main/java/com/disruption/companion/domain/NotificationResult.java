package com.disruption.companion.domain;

public sealed interface NotificationResult {

    record Queued(String notificationId, String channel, String status) implements NotificationResult {
    }

    record Rejected(String error, String detail) implements NotificationResult {
    }
}
