package com.disruption.companion.domain;

public record PolicyRule(String ruleId, String condition, String entitlement,
                        boolean requiresHumanApproval) {
}
