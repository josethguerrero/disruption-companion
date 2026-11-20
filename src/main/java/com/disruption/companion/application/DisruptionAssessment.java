package com.disruption.companion.application;

import com.disruption.companion.domain.ConnectionAssessment;
import com.disruption.companion.domain.FieldResolution;
import com.disruption.companion.domain.OptionVerdict;
import com.disruption.companion.domain.RebookOption;
import com.disruption.companion.domain.RuleOutcome;

import java.time.Instant;
import java.util.List;

public record DisruptionAssessment(
        String bookingId,
        Instant evaluatedAt,
        String policyVersion,
        Integer delayMinutes,
        boolean international,
        List<FieldResolution> resolutions,
        List<ConnectionAssessment> connections,
        List<RuleOutcome> ruleOutcomes,
        List<OptionVerdict> options,
        RebookOption recommended,
        boolean autoRebook,
        List<String> humanReview,
        List<String> notes) {
}
