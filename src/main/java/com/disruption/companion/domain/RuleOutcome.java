package com.disruption.companion.domain;

public record RuleOutcome(String ruleId, String condition, String entitlement, boolean requiresHumanApproval,
                          Result result, String detail) {

    public enum Result { APPLIES, DOES_NOT_APPLY, CANNOT_EVALUATE }

    public boolean applies() {
        return result == Result.APPLIES;
    }
}
