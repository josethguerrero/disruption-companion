package com.disruption.companion.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionAnalyzerTest {

    private static final Segment S1 = new Segment("S1", "LA500", "SCL", "LIM",
            LocalDate.of(2026, 9, 15), LocalTime.of(14, 30), LocalTime.of(17, 55));
    private static final Segment S2 = new Segment("S2", "LA2410", "LIM", "MEX",
            LocalDate.of(2026, 9, 15), LocalTime.of(19, 5), LocalTime.of(23, 40));

    private final ConnectionAnalyzer analyzer = TestReferenceData.connectionAnalyzer();

    private static Optional<FieldResolution> confirmed(OperationalEvent event) {
        return Optional.of(new FieldResolution(event.segmentId(), EventField.SCHEDULE,
                FieldResolution.Status.CONFIRMED, event, false, List.of()));
    }

    @Test
    void baseCaseIsMissedWithMinus90SlackAnd2035LimaArrival() {
        OperationalEvent s1Delay = new OperationalEvent("E-1001", "S1", "DELAY", LocalTime.of(17, 10),
                160, null, null, "OPS_CORE", Instant.parse("2026-09-15T10:48:00Z"));
        OperationalEvent s2Scheduled = new OperationalEvent("E-2003", "S2", "STATUS", null,
                null, null, "SCHEDULED", "OPS_CORE", Instant.parse("2026-09-15T10:48:00Z"));

        ConnectionAssessment assessment = analyzer.assess(S1, confirmed(s1Delay), S2, confirmed(s2Scheduled));

        assertThat(assessment.status()).isEqualTo(ConnectionAssessment.Status.MISSED);
        assertThat(assessment.slackMinutes()).isEqualTo(-90L);
        assertThat(assessment.estimatedArrival().getZone()).isEqualTo(ZoneId.of("America/Lima"));
        assertThat(assessment.estimatedArrival().toLocalTime()).isEqualTo(LocalTime.of(20, 35));
    }

    @Test
    void segment1DelayIs160MinutesAndDepartsAt2010Utc() {
        OperationalEvent s1Delay = new OperationalEvent("E-1001", "S1", "DELAY", LocalTime.of(17, 10),
                160, null, null, "OPS_CORE", Instant.parse("2026-09-15T10:48:00Z"));

        DepartureEstimate estimate = analyzer.departure(S1, confirmed(s1Delay)).orElseThrow();

        assertThat(estimate.delay()).isEqualTo(Duration.ofMinutes(160));
        assertThat(estimate.departure().toInstant()).isEqualTo(Instant.parse("2026-09-15T20:10:00Z"));
    }

    @Test
    void departureAt1440ProducesAtRiskWith60MinutesSlack() {
        OperationalEvent smallDelay = new OperationalEvent("E-1002", "S1", "DELAY", LocalTime.of(14, 40),
                10, null, null, "OPS_CORE", Instant.parse("2026-09-15T10:48:00Z"));

        ConnectionAssessment assessment = analyzer.assess(S1, confirmed(smallDelay), S2, Optional.empty());

        assertThat(assessment.status()).isEqualTo(ConnectionAssessment.Status.AT_RISK);
        assertThat(assessment.slackMinutes()).isEqualTo(60L);
    }

    @Test
    void conflictingScheduleProducesUnknown() {
        OperationalEvent opsCore = new OperationalEvent("E-1001", "S1", "DELAY", LocalTime.of(17, 10),
                160, null, null, "OPS_CORE", Instant.parse("2026-09-15T10:48:00Z"));
        FieldResolution conflict = new FieldResolution("S1", EventField.SCHEDULE,
                FieldResolution.Status.CONFLICT, opsCore, false, List.of());

        ConnectionAssessment assessment = analyzer.assess(S1, Optional.of(conflict), S2, Optional.empty());

        assertThat(assessment.status()).isEqualTo(ConnectionAssessment.Status.UNKNOWN);
        assertThat(assessment.slackMinutes()).isNull();
    }

    @Test
    void sclLimMexIsInternational() {
        assertThat(analyzer.isInternational(List.of(S1, S2))).isTrue();
    }

    @Test
    void overnightNewDepartureRollsToNextDay() {
        Segment lateFlight = new Segment("S9", "LA900", "SCL", "LIM",
                LocalDate.of(2026, 9, 15), LocalTime.of(23, 30), LocalTime.of(1, 55));
        OperationalEvent overnightDelay = new OperationalEvent("E-9001", "S9", "DELAY", LocalTime.of(0, 30),
                60, null, null, "OPS_CORE", Instant.parse("2026-09-15T20:00:00Z"));

        DepartureEstimate estimate = analyzer.departure(lateFlight, confirmed(overnightDelay)).orElseThrow();

        assertThat(estimate.departure().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(estimate.delay()).isEqualTo(Duration.ofMinutes(60));
    }
}
