package com.disruption.companion.domain;

import java.time.Instant;
import java.util.List;

public record EventFeed(List<OperationalEvent> events, Instant retrievedAt,
                        List<String> sourcesAvailable, List<String> sourcesDegraded) {
}
