package com.disruption.companion.application;

import com.disruption.companion.domain.HumanCaseReceipt;
import com.disruption.companion.domain.NotificationResult;

public record ActionExecutionResult(
        String bookingId,
        String idempotencyKey,
        Status status,
        String requestId,
        String optionId,
        int attempts,
        HumanCaseReceipt humanCase,
        NotificationResult notification,
        String message) {

    public enum Status {
        REBOOKING_REQUESTED,
        ALREADY_REQUESTED,
        BLOCKED_PENDING_RESOLUTION,
        ESCALATED_TO_HUMAN,
        NO_ACTION_TAKEN
    }
}
