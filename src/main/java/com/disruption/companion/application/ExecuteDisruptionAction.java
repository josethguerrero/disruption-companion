package com.disruption.companion.application;

import com.disruption.companion.application.port.EligibleOptionsPort;
import com.disruption.companion.application.port.HumanCasePort;
import com.disruption.companion.application.port.NotificationPort;
import com.disruption.companion.application.port.RebookingPort;
import com.disruption.companion.application.port.TripContextPort;
import com.disruption.companion.domain.EventField;
import com.disruption.companion.domain.Evidence;
import com.disruption.companion.domain.FieldResolution;
import com.disruption.companion.domain.HumanCaseReceipt;
import com.disruption.companion.domain.IdempotencyKey;
import com.disruption.companion.domain.NotificationResult;
import com.disruption.companion.domain.OptionValidator;
import com.disruption.companion.domain.OptionVerdict;
import com.disruption.companion.domain.OptionsOffer;
import com.disruption.companion.domain.PassengerMessage;
import com.disruption.companion.domain.RebookOption;
import com.disruption.companion.domain.RebookingResult;
import com.disruption.companion.domain.RuleOutcome;
import com.disruption.companion.domain.TripContext;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class ExecuteDisruptionAction {

    private final EvaluateDisruption evaluateDisruption;
    private final TripContextPort tripContextPort;
    private final EligibleOptionsPort eligibleOptionsPort;
    private final RebookingPort rebookingPort;
    private final HumanCasePort humanCasePort;
    private final NotificationPort notificationPort;
    private final Clock clock;
    private final RetryPolicy retryPolicy;
    private final OptionValidator optionValidator = new OptionValidator();

    // Known limitation: PENDING_UNKNOWN lock lives in memory; in production this state must be persisted.
    private final Map<String, String> pendingUnknownByKey = new ConcurrentHashMap<>();

    public ExecuteDisruptionAction(EvaluateDisruption evaluateDisruption,
                                   TripContextPort tripContextPort,
                                   EligibleOptionsPort eligibleOptionsPort,
                                   RebookingPort rebookingPort,
                                   HumanCasePort humanCasePort,
                                   NotificationPort notificationPort,
                                   Clock clock,
                                   RetryPolicy retryPolicy) {
        this.evaluateDisruption = evaluateDisruption;
        this.tripContextPort = tripContextPort;
        this.eligibleOptionsPort = eligibleOptionsPort;
        this.rebookingPort = rebookingPort;
        this.humanCasePort = humanCasePort;
        this.notificationPort = notificationPort;
        this.clock = clock;
        this.retryPolicy = retryPolicy;
    }

    public ActionExecutionResult execute(String bookingId, boolean compensationRequested) {
        return execute(bookingId, compensationRequested, null);
    }

    public ActionExecutionResult execute(String bookingId, boolean compensationRequested, String preferredOptionId) {
        DisruptionAssessment assessment = evaluateDisruption.evaluate(bookingId, compensationRequested);
        String idempotencyKey = computeIdempotencyKey(bookingId, assessment);

        RebookOption targetOption = resolveTargetOption(assessment, preferredOptionId);
        String requestedOptionId = preferredOptionId != null
                ? preferredOptionId
                : (targetOption != null ? targetOption.optionId() : null);

        if (idempotencyKey != null && pendingUnknownByKey.containsKey(idempotencyKey)) {
            String lockedOptionId = pendingUnknownByKey.get(idempotencyKey);
            if (requestedOptionId != null && !requestedOptionId.equals(lockedOptionId)) {
                return new ActionExecutionResult(
                        bookingId,
                        idempotencyKey,
                        ActionExecutionResult.Status.BLOCKED_PENDING_RESOLUTION,
                        null,
                        requestedOptionId,
                        0,
                        null,
                        null,
                        "Unresolved upstream timeout for option " + lockedOptionId
                                + "; cannot accept option " + requestedOptionId + " until resolved");
            }
        }

        if (!assessment.autoRebook() || targetOption == null || idempotencyKey == null) {
            if (!assessment.humanReview().isEmpty()) {
                Evidence evidence = new Evidence(bookingId, idempotencyKey, requestedOptionId, 0,
                        assessment.policyVersion(), assessment.humanReview(), null);
                HumanCaseReceipt receipt = humanCasePort.createHumanCase(
                        String.join("; ", assessment.humanReview()), evidence);
                NotificationResult notification = notifyEscalated(assessment, receipt);
                return new ActionExecutionResult(
                        bookingId, idempotencyKey, ActionExecutionResult.Status.ESCALATED_TO_HUMAN,
                        null, requestedOptionId, 0, receipt, notification,
                        "Escalated to human specialist (" + receipt.caseId() + ")");
            }
            return new ActionExecutionResult(
                    bookingId, idempotencyKey, ActionExecutionResult.Status.NO_ACTION_TAKEN,
                    null, requestedOptionId, 0, null, null, "No automatic action required");
        }

        TripContext trip = tripContextPort.getTripContext(bookingId);
        OptionsOffer currentOffer = eligibleOptionsPort.getEligibleOptions(trip);
        Set<String> activeEntitlements = assessment.ruleOutcomes().stream()
                .filter(RuleOutcome::applies)
                .map(RuleOutcome::entitlement)
                .collect(Collectors.toSet());
        Set<String> consultedPolicies = Set.of(assessment.policyVersion());

        String requestId = null;
        ActionExecutionResult.Status finalStatus = null;
        int attempts = 0;

        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            attempts = attempt;
            if (attempt > 1 && currentOffer.isExpired(clock.instant())) {
                currentOffer = eligibleOptionsPort.getEligibleOptions(trip);
                List<OptionVerdict> refreshed = optionValidator.validate(
                        currentOffer, trip, activeEntitlements, consultedPolicies, clock.instant());
                String targetOptionId = targetOption.optionId();
                Optional<RebookOption> refreshedTarget = refreshed.stream()
                        .filter(OptionVerdict::valid)
                        .map(OptionVerdict::option)
                        .filter(o -> targetOptionId.equals(o.optionId()))
                        .findFirst();
                if (refreshedTarget.isEmpty()) {
                    List<String> reasons = new ArrayList<>(assessment.humanReview());
                    reasons.add("option " + targetOption.optionId()
                            + " no longer valid after TTL refresh following upstream timeout");
                    Evidence evidence = new Evidence(bookingId, idempotencyKey, targetOption.optionId(),
                            attempts - 1, assessment.policyVersion(), List.copyOf(reasons), null);
                    HumanCaseReceipt receipt = humanCasePort.createHumanCase(
                            "rebooking option expired during retries", evidence);
                    NotificationResult notification = notifyEscalated(assessment, receipt);
                    return new ActionExecutionResult(
                            bookingId, idempotencyKey, ActionExecutionResult.Status.ESCALATED_TO_HUMAN,
                            null, targetOption.optionId(), attempts - 1, receipt, notification,
                            "Option expired after upstream timeout; escalated to human (" + receipt.caseId() + ")");
                }
                targetOption = refreshedTarget.get();
            }

            RebookingResult result = rebookingPort.createRebookingRequest(targetOption, idempotencyKey);
            if (result instanceof RebookingResult.Created created) {
                pendingUnknownByKey.remove(idempotencyKey);
                requestId = created.requestId();
                finalStatus = ActionExecutionResult.Status.REBOOKING_REQUESTED;
                break;
            } else if (result instanceof RebookingResult.Duplicate duplicate) {
                pendingUnknownByKey.remove(idempotencyKey);
                requestId = duplicate.requestId();
                finalStatus = ActionExecutionResult.Status.ALREADY_REQUESTED;
                break;
            } else if (result instanceof RebookingResult.UpstreamTimeout) {
                pendingUnknownByKey.put(idempotencyKey, targetOption.optionId());
                if (attempt < retryPolicy.maxAttempts()) {
                    retryPolicy.sleeper().accept(retryPolicy.backoffForAttempt(attempt));
                }
            }
        }

        if (finalStatus == null) {
            // Keep pendingUnknownByKey locked until human resolution.
            List<String> reasons = new ArrayList<>(assessment.humanReview());
            reasons.add("upstream timeout after " + attempts + " attempts");
            Evidence evidence = new Evidence(bookingId, idempotencyKey, targetOption.optionId(),
                    attempts, assessment.policyVersion(), List.copyOf(reasons), null);
            HumanCaseReceipt receipt = humanCasePort.createHumanCase(
                    "rebooking upstream timeout after " + attempts + " attempts", evidence);
            NotificationResult notification = notifyEscalated(assessment, receipt);
            return new ActionExecutionResult(
                    bookingId, idempotencyKey, ActionExecutionResult.Status.ESCALATED_TO_HUMAN,
                    null, targetOption.optionId(), attempts, receipt, notification,
                    "Upstream timeout after " + attempts + " attempts; escalated to human (" + receipt.caseId() + ")");
        }

        HumanCaseReceipt humanCase = null;
        if (!assessment.humanReview().isEmpty()) {
            Evidence evidence = new Evidence(bookingId, idempotencyKey, targetOption.optionId(),
                    attempts, assessment.policyVersion(), assessment.humanReview(), requestId);
            humanCase = humanCasePort.createHumanCase(String.join("; ", assessment.humanReview()), evidence);
        }

        NotificationResult notification = notifyRebookingRequested(assessment, targetOption, requestId, humanCase);
        String summary = "Solicitamos tu cambio al vuelo " + targetOption.flightNumber()
                + " (" + requestId + ", pendiente de confirmación)";
        return new ActionExecutionResult(
                bookingId, idempotencyKey, finalStatus, requestId, targetOption.optionId(),
                attempts, humanCase, notification, summary);
    }

    private NotificationResult notifyRebookingRequested(DisruptionAssessment assessment,
                                                        RebookOption option,
                                                        String requestId,
                                                        HumanCaseReceipt humanCase) {
        String body = "Solicitamos tu cambio al vuelo " + option.flightNumber()
                + " (" + option.origin() + "->" + option.destination()
                + ", " + option.date() + " " + option.departure()
                + "). Referencia de solicitud: " + requestId + " (pendiente de confirmación).";
        PassengerMessage.Facts facts = new PassengerMessage.Facts(
                assessment.bookingId(),
                assessment.delayMinutes(),
                connectionStatusOf(assessment),
                option.optionId(),
                option.flightNumber(),
                option.date(),
                option.departure(),
                requestId,
                humanCase != null ? humanCase.caseId() : null);
        return notificationPort.notifyPassenger(
                new PassengerMessage(assessment.bookingId(), option.policyRef(), body, facts));
    }

    private NotificationResult notifyEscalated(DisruptionAssessment assessment, HumanCaseReceipt receipt) {
        String body = "Derivamos tu caso (" + receipt.caseId()
                + ") a un especialista para revisar tu cambio de vuelo.";
        PassengerMessage.Facts facts = new PassengerMessage.Facts(
                assessment.bookingId(),
                assessment.delayMinutes(),
                connectionStatusOf(assessment),
                null,
                null,
                null,
                null,
                null,
                receipt.caseId());
        return notificationPort.notifyPassenger(
                new PassengerMessage(assessment.bookingId(), assessment.policyVersion(), body, facts));
    }

    private static String computeIdempotencyKey(String bookingId, DisruptionAssessment assessment) {
        Optional<FieldResolution> delayResolution = assessment.resolutions().stream()
                .filter(r -> r.field() == EventField.SCHEDULE
                        && r.status() == FieldResolution.Status.CONFIRMED
                        && r.winner() != null
                        && "DELAY".equals(r.winner().type()))
                .findFirst();
        if (delayResolution.isPresent()) {
            var winner = delayResolution.get().winner();
            return IdempotencyKey.of(bookingId, winner.segmentId(), winner.eventId()).value();
        }
        return assessment.resolutions().stream()
                .filter(r -> r.field() == EventField.SCHEDULE
                        && r.status() == FieldResolution.Status.CONFIRMED
                        && r.winner() != null)
                .findFirst()
                .map(r -> IdempotencyKey.of(bookingId, r.winner().segmentId(), r.winner().eventId()).value())
                .orElse(null);
    }

    private static RebookOption resolveTargetOption(DisruptionAssessment assessment, String preferredOptionId) {
        if (preferredOptionId == null) {
            return assessment.recommended();
        }
        return assessment.options().stream()
                .filter(OptionVerdict::valid)
                .map(OptionVerdict::option)
                .filter(o -> preferredOptionId.equals(o.optionId()))
                .findFirst()
                .orElse(null);
    }

    private static String connectionStatusOf(DisruptionAssessment assessment) {
        return assessment.connections().isEmpty()
                ? null
                : assessment.connections().getFirst().status().name();
    }
}
