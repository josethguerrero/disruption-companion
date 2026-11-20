package com.disruption.companion.domain;

import java.time.Instant;

public sealed interface RebookingResult {

    record Created(String requestId, String status, Instant expiresAt) implements RebookingResult {
    }

    record Duplicate(String requestId) implements RebookingResult {
    }

    record UpstreamTimeout(String error) implements RebookingResult {
    }
}
