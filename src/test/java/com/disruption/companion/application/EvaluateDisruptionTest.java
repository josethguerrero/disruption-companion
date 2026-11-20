package com.disruption.companion.application;

import com.disruption.companion.adapters.fixture.FixtureReadAdapter;
import com.disruption.companion.domain.Airport;
import com.disruption.companion.domain.ConnectionAnalyzer;
import com.disruption.companion.domain.ConnectionAssessment;
import com.disruption.companion.domain.OptionVerdict;
import com.disruption.companion.domain.RuleOutcome;
import com.disruption.companion.domain.SourceReconciler;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluateDisruptionTest {

    private static final Instant BASE_NOW = Instant.parse("2026-09-15T11:03:00Z");

    private static final Map<String, Airport> AIRPORTS = Map.of(
            "SCL", new Airport("SCL", ZoneId.of("America/Santiago"), "CL"),
            "LIM", new Airport("LIM", ZoneId.of("America/Lima"), "PE"),
            "MEX", new Airport("MEX", ZoneId.of("America/Mexico_City"), "MX")
    );

    private static EvaluateDisruption useCaseAt(Instant now) {
        FixtureReadAdapter fixtures = new FixtureReadAdapter();
        ConnectionAnalyzer analyzer = new ConnectionAnalyzer(
                AIRPORTS, Duration.ofMinutes(60), Duration.ofMinutes(30));
        return new EvaluateDisruption(
                fixtures, fixtures, fixtures, fixtures,
                SourceReconciler.withDefaults(), analyzer, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void baseCaseDetects160MinDelayAndMissedConnection() {
        DisruptionAssessment assessment = useCaseAt(BASE_NOW).evaluate("ABC123", false);

        assertThat(assessment.delayMinutes()).isEqualTo(160);
        assertThat(assessment.connections()).hasSize(1);
        assertThat(assessment.connections().getFirst().status()).isEqualTo(ConnectionAssessment.Status.MISSED);
    }

    @Test
    void baseCaseAppliesOnlyR3AndR5() {
        DisruptionAssessment assessment = useCaseAt(BASE_NOW).evaluate("ABC123", false);

        List<String> appliedRuleIds = assessment.ruleOutcomes().stream()
                .filter(RuleOutcome::applies)
                .map(RuleOutcome::ruleId)
                .toList();

        assertThat(appliedRuleIds).containsExactlyInAnyOrder("R3", "R5");
    }

    @Test
    void baseCaseValidatesO1AndRejectsO2AndO3WithExpectedReasons() {
        DisruptionAssessment assessment = useCaseAt(BASE_NOW).evaluate("ABC123", false);

        OptionVerdict o1 = verdict(assessment, "O1");
        OptionVerdict o2 = verdict(assessment, "O2");
        OptionVerdict o3 = verdict(assessment, "O3");

        assertThat(o1.valid()).isTrue();
        assertThat(o1.reasons()).isEmpty();

        assertThat(o2.valid()).isFalse();
        assertThat(o2.reasons()).anyMatch(r -> r.contains("1") && r.contains("2"));
        assertThat(o2.reasons()).anyMatch(r -> r.contains("180000"));

        assertThat(o3.valid()).isFalse();
        assertThat(o3.reasons()).anyMatch(r -> r.contains("not refundable"));
        assertThat(o3.reasons()).anyMatch(r -> r.contains("POL-REFUND-2026-01"));
    }

    @Test
    void baseCaseRecommendsLA2412WithAutoRebookAndHumanReviewForR5AndOvernight() {
        DisruptionAssessment assessment = useCaseAt(BASE_NOW).evaluate("ABC123", false);

        assertThat(assessment.recommended()).isNotNull();
        assertThat(assessment.recommended().flightNumber()).isEqualTo("LA2412");
        assertThat(assessment.autoRebook()).isTrue();
        assertThat(assessment.humanReview()).anyMatch(r -> r.contains("R5"));
        assertThat(assessment.humanReview()).anyMatch(r -> r.contains("LIM") && r.contains("overnight"));
    }

    @Test
    void expiredOfferAt1108LeavesNoRecommendation() {
        DisruptionAssessment assessment = useCaseAt(Instant.parse("2026-09-15T11:08:00Z"))
                .evaluate("ABC123", false);

        assertThat(assessment.recommended()).isNull();
        assertThat(assessment.autoRebook()).isFalse();
        assertThat(assessment.humanReview()).anyMatch(r -> r.contains("REBOOK_NO_FEE"));
    }

    @Test
    void compensationRequestedAddsR4ToHumanReview() {
        DisruptionAssessment assessment = useCaseAt(BASE_NOW).evaluate("ABC123", true);

        assertThat(assessment.humanReview()).anyMatch(r -> r.contains("R4"));
    }

    @Test
    void serializedAssessmentContainsNoPii() {
        DisruptionAssessment assessment = useCaseAt(BASE_NOW).evaluate("ABC123", false);
        String json = JsonMapper.builder().build().writeValueAsString(assessment);

        assertThat(json)
                .doesNotContain("maria.silva@example.com")
                .doesNotContain("+56 9 8765 4321")
                .doesNotContain("12.345.678-9")
                .doesNotContain("María")
                .doesNotContain("Silva")
                .doesNotContain("Tomás");
    }

    private static OptionVerdict verdict(DisruptionAssessment assessment, String optionId) {
        return assessment.options().stream()
                .filter(v -> optionId.equals(v.option().optionId()))
                .findFirst()
                .orElseThrow();
    }
}
