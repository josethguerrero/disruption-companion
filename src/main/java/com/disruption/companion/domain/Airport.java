package com.disruption.companion.domain;

import java.time.ZoneId;

// IANA zone
public record Airport(String code, ZoneId zone, String country) {
}
