package com.disruption.companion.domain;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;

final class TestReferenceData {

    static final Map<String, Airport> AIRPORTS = Map.of(
            "SCL", new Airport("SCL", ZoneId.of("America/Santiago"), "CL"),
            "LIM", new Airport("LIM", ZoneId.of("America/Lima"), "PE"),
            "MEX", new Airport("MEX", ZoneId.of("America/Mexico_City"), "MX")
    );

    static final Duration MINIMUM_CONNECTION = Duration.ofMinutes(60);
    static final Duration AT_RISK_MARGIN = Duration.ofMinutes(30);

    private TestReferenceData() {
    }

    static ConnectionAnalyzer connectionAnalyzer() {
        return new ConnectionAnalyzer(AIRPORTS, MINIMUM_CONNECTION, AT_RISK_MARGIN);
    }
}
