package com.disruption.companion.domain;

import java.time.Duration;
import java.time.ZonedDateTime;

public record DepartureEstimate(ZonedDateTime departure, Duration delay) {
}
