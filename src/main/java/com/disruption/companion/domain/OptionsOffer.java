package com.disruption.companion.domain;

import java.time.Instant;
import java.util.List;

public record OptionsOffer(List<RebookOption> options, Instant retrievedAt, int ttlSeconds) {

    public Instant expiresAt() {
        return retrievedAt.plusSeconds(ttlSeconds);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt());
    }
}
