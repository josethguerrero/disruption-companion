package com.disruption.companion.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record Policy(String scenario, String version, LocalDate effectiveFrom, List<PolicyRule> rules,
        Instant retrievedAt) {
}
