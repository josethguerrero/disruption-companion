package com.disruption.companion.domain;

import java.util.List;

public record Evidence(
        String bookingId,
        String idempotencyKey,
        String optionId,
        int attempts,
        String policyVersion,
        List<String> reasons,
        String requestId) {
}
