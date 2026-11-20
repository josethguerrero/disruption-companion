package com.disruption.companion.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates policy condition strings (e.g. "delay_minutes >= 180 AND international") against a fact map.
 * Thresholds live in the versioned policy rather than in code; missing facts or unsupported syntax
 * resolve to CANNOT_EVALUATE so the case is routed to human review.
 */
public final class RuleEvaluator {

    private static final Pattern TERM =
            Pattern.compile("^\\s*([a-z_]+)\\s*(?:(>=|<=|==|!=|>|<)\\s*(\\S+))?\\s*$");

    public RuleOutcome evaluate(PolicyRule rule, Map<String, Object> facts) {
        List<String> detail = new ArrayList<>();
        try {
            boolean result = true;
            for (String term : rule.condition().split("\\s+AND\\s+")) {
                boolean t = evaluateTerm(term, facts, detail);
                if (!t) {
                    result = false;
                    break;
                }
            }
            return outcome(rule, result ? RuleOutcome.Result.APPLIES : RuleOutcome.Result.DOES_NOT_APPLY,
                    String.join("; ", detail));
        } catch (IllegalArgumentException e) {
            return outcome(rule, RuleOutcome.Result.CANNOT_EVALUATE, e.getMessage());
        }
    }

    private static boolean evaluateTerm(String term, Map<String, Object> facts, List<String> detail) {
        Matcher m = TERM.matcher(term);
        if (!m.matches()) {
            throw new IllegalArgumentException("unrecognized condition: " + term.trim());
        }
        String name = m.group(1);
        if (!facts.containsKey(name) || facts.get(name) == null) {
            throw new IllegalArgumentException("missing fact: " + name);
        }
        Object actual = facts.get(name);
        String op = m.group(2);
        boolean r;
        if (op == null) {
            r = asBoolean(actual, name);
            detail.add(name + "=" + actual);
        } else {
            r = compare(actual, op, m.group(3), name);
            detail.add(name + "=" + actual + " " + op + " " + m.group(3) + " -> " + r);
        }
        return r;
    }

    private static boolean compare(Object actual, String op, String expected, String name) {
        if (actual instanceof Boolean b) {
            boolean e = Boolean.parseBoolean(expected);
            return switch (op) {
                case "==" -> b == e;
                case "!=" -> b != e;
                default -> throw new IllegalArgumentException("operator " + op + " does not apply to " + name);
            };
        }
        if (actual instanceof Number n) {
            double a = n.doubleValue();
            double e;
            try {
                e = Double.parseDouble(expected);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("non-numeric value in condition: " + expected);
            }
            return switch (op) {
                case ">=" -> a >= e;
                case "<=" -> a <= e;
                case ">" -> a > e;
                case "<" -> a < e;
                case "==" -> a == e;
                case "!=" -> a != e;
                default -> throw new IllegalArgumentException("unknown operator: " + op);
            };
        }
        throw new IllegalArgumentException("unsupported data type for " + name);
    }

    private static boolean asBoolean(Object v, String name) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException(name + " is not a boolean");
    }

    private static RuleOutcome outcome(PolicyRule r, RuleOutcome.Result result, String detail) {
        return new RuleOutcome(r.ruleId(), r.condition(), r.entitlement(), r.requiresHumanApproval(), result, detail);
    }
}
