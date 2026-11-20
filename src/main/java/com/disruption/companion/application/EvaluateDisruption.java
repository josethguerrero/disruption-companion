package com.disruption.companion.application;

import com.disruption.companion.application.port.EligibleOptionsPort;
import com.disruption.companion.application.port.OperationalEventsPort;
import com.disruption.companion.application.port.PolicyRulesPort;
import com.disruption.companion.application.port.TripContextPort;
import com.disruption.companion.domain.ConnectionAnalyzer;
import com.disruption.companion.domain.ConnectionAssessment;
import com.disruption.companion.domain.DepartureEstimate;
import com.disruption.companion.domain.EventFeed;
import com.disruption.companion.domain.EventField;
import com.disruption.companion.domain.FieldResolution;
import com.disruption.companion.domain.OptionValidator;
import com.disruption.companion.domain.OptionVerdict;
import com.disruption.companion.domain.OptionsOffer;
import com.disruption.companion.domain.Policy;
import com.disruption.companion.domain.PolicyRule;
import com.disruption.companion.domain.RebookOption;
import com.disruption.companion.domain.RuleEvaluator;
import com.disruption.companion.domain.RuleOutcome;
import com.disruption.companion.domain.Segment;
import com.disruption.companion.domain.SourceReconciler;
import com.disruption.companion.domain.TripContext;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class EvaluateDisruption {

    private static final String DELAY_SCENARIO = "DELAY";
    private static final String REBOOK_NO_FEE = "REBOOK_NO_FEE";

    private final TripContextPort tripContextPort;
    private final OperationalEventsPort operationalEventsPort;
    private final EligibleOptionsPort eligibleOptionsPort;
    private final PolicyRulesPort policyRulesPort;
    private final SourceReconciler reconciler;
    private final ConnectionAnalyzer connectionAnalyzer;
    private final Clock clock;
    private final RuleEvaluator ruleEvaluator = new RuleEvaluator();
    private final OptionValidator optionValidator = new OptionValidator();

    public EvaluateDisruption(TripContextPort tripContextPort,
                              OperationalEventsPort operationalEventsPort,
                              EligibleOptionsPort eligibleOptionsPort,
                              PolicyRulesPort policyRulesPort,
                              SourceReconciler reconciler,
                              ConnectionAnalyzer connectionAnalyzer,
                              Clock clock) {
        this.tripContextPort = tripContextPort;
        this.operationalEventsPort = operationalEventsPort;
        this.eligibleOptionsPort = eligibleOptionsPort;
        this.policyRulesPort = policyRulesPort;
        this.reconciler = reconciler;
        this.connectionAnalyzer = connectionAnalyzer;
        this.clock = clock;
    }

    public DisruptionAssessment evaluate(String bookingId, boolean compensationRequested) {
        Instant now = clock.instant();
        List<String> notes = new ArrayList<>();
        List<String> humanReview = new ArrayList<>();

        // 1. Trip context
        TripContext trip = tripContextPort.getTripContext(bookingId);
        if ("PARTIAL".equalsIgnoreCase(trip.completeness())
                || (trip.missingFields() != null && !trip.missingFields().isEmpty())) {
            String missing = trip.missingFields() == null ? "" : String.join(", ", trip.missingFields());
            notes.add("partial trip context; missing fields: " + missing);
        }

        // 2. Operational events and source reconciliation
        List<String> segmentIds = trip.segments().stream().map(Segment::segmentId).toList();
        EventFeed feed = operationalEventsPort.getOperationalEvents(segmentIds);
        List<FieldResolution> resolutions = reconciler.reconcile(feed);

        Map<String, FieldResolution> scheduleBySegment = new HashMap<>();
        for (FieldResolution resolution : resolutions) {
            if (resolution.field() == EventField.SCHEDULE) {
                scheduleBySegment.put(resolution.segmentId(), resolution);
            }
            if (resolution.status() == FieldResolution.Status.CONFLICT) {
                humanReview.add("conflicting healthy sources for " + resolution.segmentId() + " " + resolution.field());
            } else if (resolution.status() == FieldResolution.Status.UNCONFIRMED) {
                notes.add("unconfirmed operational data for " + resolution.segmentId() + " " + resolution.field());
            }
            if (resolution.stale() && resolution.winner() != null) {
                notes.add("stale operational data for " + resolution.segmentId() + " " + resolution.field()
                        + " (recorded_at=" + resolution.winner().recordedAt() + ")");
            }
        }

        // 3. Compute facts
        Integer delayMinutes = computeMaxDelayMinutes(trip.segments(), scheduleBySegment);
        List<ConnectionAssessment> connections = assessConnections(trip.segments(), scheduleBySegment);
        Boolean missedConnection = resolveMissedConnection(connections);
        boolean international = connectionAnalyzer.isInternational(trip.segments());

        Map<String, Object> facts = new HashMap<>();
        facts.put("delay_minutes", delayMinutes);
        facts.put("international", international);
        facts.put("missed_connection", missedConnection);
        facts.put("compensation_requested", compensationRequested);
        facts.put("group_includes_minor", trip.groupIncludesMinor());

        // 4. Evaluate policy rules
        Policy policy = policyRulesPort.getPolicyRules(DELAY_SCENARIO);
        List<RuleOutcome> outcomes = new ArrayList<>(policy.rules().size());
        Set<String> activeEntitlements = new HashSet<>();
        for (PolicyRule rule : policy.rules()) {
            RuleOutcome outcome = ruleEvaluator.evaluate(rule, facts);
            outcomes.add(outcome);
            if (outcome.applies()) {
                activeEntitlements.add(outcome.entitlement());
                if (outcome.requiresHumanApproval()) {
                    humanReview.add(outcome.ruleId() + " (" + outcome.entitlement() + ") requires human approval");
                }
            } else if (outcome.result() == RuleOutcome.Result.CANNOT_EVALUATE) {
                humanReview.add(outcome.ruleId() + " cannot be evaluated: " + outcome.detail());
            }
        }

        // 5. Validate options and pick earliest valid REBOOK
        OptionsOffer offer = eligibleOptionsPort.getEligibleOptions(trip);
        List<OptionVerdict> verdicts = optionValidator.validate(
                offer, trip, activeEntitlements, Set.of(policy.version()), now);

        RebookOption recommended = verdicts.stream()
                .filter(OptionVerdict::valid)
                .map(OptionVerdict::option)
                .filter(o -> "REBOOK".equals(o.type()) && o.date() != null && o.departure() != null)
                .min(Comparator.comparing(RebookOption::date).thenComparing(RebookOption::departure))
                .orElse(null);

        // 6. Additional human review conditions
        if (activeEntitlements.contains(REBOOK_NO_FEE) && recommended == null) {
            humanReview.add("eligible for " + REBOOK_NO_FEE + " but no valid rebooking option is available");
        }
        if (recommended != null) {
            findReplacedSegment(trip.segments(), recommended).ifPresent(original -> {
                if (recommended.date().isAfter(original.date())) {
                    humanReview.add("overnight stay in " + recommended.origin()
                            + " (" + original.date() + " to " + recommended.date()
                            + ") has no policy rule covering accommodation");
                }
            });
        }

        // 7. Auto-rebook eligibility
        boolean hasAutomaticRebookRule = outcomes.stream()
                .anyMatch(o -> o.applies() && REBOOK_NO_FEE.equals(o.entitlement()) && !o.requiresHumanApproval());
        boolean autoRebook = hasAutomaticRebookRule && recommended != null;

        return new DisruptionAssessment(
                trip.bookingId(),
                now,
                policy.version(),
                delayMinutes,
                international,
                List.copyOf(resolutions),
                List.copyOf(connections),
                List.copyOf(outcomes),
                List.copyOf(verdicts),
                recommended,
                autoRebook,
                List.copyOf(humanReview),
                List.copyOf(notes));
    }

    private Integer computeMaxDelayMinutes(List<Segment> segments, Map<String, FieldResolution> scheduleBySegment) {
        int maxDelay = 0;
        for (Segment segment : segments) {
            Optional<DepartureEstimate> estimate = connectionAnalyzer.departure(
                    segment, Optional.ofNullable(scheduleBySegment.get(segment.segmentId())));
            if (estimate.isEmpty()) {
                return null;
            }
            int minutes = (int) estimate.get().delay().toMinutes();
            if (minutes > maxDelay) {
                maxDelay = minutes;
            }
        }
        return maxDelay;
    }

    private List<ConnectionAssessment> assessConnections(List<Segment> segments,
                                                         Map<String, FieldResolution> scheduleBySegment) {
        List<ConnectionAssessment> results = new ArrayList<>();
        for (int i = 0; i < segments.size() - 1; i++) {
            Segment from = segments.get(i);
            Segment to = segments.get(i + 1);
            results.add(connectionAnalyzer.assess(
                    from, Optional.ofNullable(scheduleBySegment.get(from.segmentId())),
                    to, Optional.ofNullable(scheduleBySegment.get(to.segmentId()))));
        }
        return results;
    }

    private static Boolean resolveMissedConnection(List<ConnectionAssessment> connections) {
        boolean hasUnknown = false;
        for (ConnectionAssessment connection : connections) {
            if (connection.status() == ConnectionAssessment.Status.MISSED) {
                return Boolean.TRUE;
            }
            if (connection.status() == ConnectionAssessment.Status.UNKNOWN) {
                hasUnknown = true;
            }
        }
        return hasUnknown ? null : Boolean.FALSE;
    }

    private static Optional<Segment> findReplacedSegment(List<Segment> segments, RebookOption recommended) {
        return segments.stream()
                .filter(s -> s.origin().equals(recommended.origin())
                        && s.destination().equals(recommended.destination()))
                .findFirst();
    }
}
