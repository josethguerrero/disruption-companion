package com.disruption.companion.domain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEvaluatorTest {

    private final RuleEvaluator evaluator = new RuleEvaluator();

    private static final PolicyRule R1 = new PolicyRule("R1", "delay_minutes >= 180", "REBOOK_NO_FEE", false);
    private static final PolicyRule R2 = new PolicyRule("R2", "delay_minutes >= 240 AND international", "MEAL_VOUCHER", false);
    private static final PolicyRule R3 = new PolicyRule("R3", "missed_connection == true", "REBOOK_NO_FEE", false);
    private static final PolicyRule R5 = new PolicyRule("R5", "group_includes_minor == true", "PRIORITY_HANDLING", true);

    private static Map<String, Object> baseCase() {
        Map<String, Object> f = new HashMap<>();
        f.put("delay_minutes", 160);
        f.put("international", true);
        f.put("missed_connection", true);
        f.put("compensation_requested", false);
        f.put("group_includes_minor", true);
        return f;
    }

    @Test
    void delayOf160DoesNotTriggerDelayRules() {
        assertThat(evaluator.evaluate(R1, baseCase()).result()).isEqualTo(RuleOutcome.Result.DOES_NOT_APPLY);
        assertThat(evaluator.evaluate(R2, baseCase()).result()).isEqualTo(RuleOutcome.Result.DOES_NOT_APPLY);
    }

    @Test
    void missedConnectionAndMinorApply() {
        assertThat(evaluator.evaluate(R3, baseCase()).result()).isEqualTo(RuleOutcome.Result.APPLIES);
        assertThat(evaluator.evaluate(R5, baseCase()).result()).isEqualTo(RuleOutcome.Result.APPLIES);
    }

    @Test
    void thresholdIsInclusive() {
        Map<String, Object> f = baseCase();
        f.put("delay_minutes", 180);
        assertThat(evaluator.evaluate(R1, f).result()).isEqualTo(RuleOutcome.Result.APPLIES);
    }

    @Test
    void andRequiresBothTerms() {
        Map<String, Object> f = baseCase();
        f.put("delay_minutes", 300);
        f.put("international", false);
        assertThat(evaluator.evaluate(R2, f).result()).isEqualTo(RuleOutcome.Result.DOES_NOT_APPLY);
    }

    @Test
    void missingFactCannotBeEvaluated() {
        Map<String, Object> f = baseCase();
        f.put("missed_connection", null);
        RuleOutcome o = evaluator.evaluate(R3, f);
        assertThat(o.result()).isEqualTo(RuleOutcome.Result.CANNOT_EVALUATE);
        assertThat(o.detail()).contains("missed_connection");
    }

    @Test
    void unknownSyntaxCannotBeEvaluated() {
        PolicyRule weird = new PolicyRule("RX", "delay_minutes BETWEEN 1 AND 2", "X", false);
        assertThat(evaluator.evaluate(weird, baseCase()).result()).isEqualTo(RuleOutcome.Result.CANNOT_EVALUATE);
    }
}
