package com.disruption.companion.adapters.web;

import com.disruption.companion.adapters.fixture.ConfigurableRebookingAdapter;
import com.disruption.companion.adapters.fixture.FixtureReadAdapter;
import com.disruption.companion.adapters.fixture.InMemoryHumanCaseAdapter;
import com.disruption.companion.adapters.fixture.InMemoryNotificationAdapter;
import com.disruption.companion.application.ActionExecutionResult;
import com.disruption.companion.application.DisruptionAssessment;
import com.disruption.companion.application.EvaluateDisruption;
import com.disruption.companion.application.ExecuteDisruptionAction;
import com.disruption.companion.application.RetryPolicy;
import com.disruption.companion.config.CompanionConfig;
import com.disruption.companion.domain.ConnectionAnalyzer;
import com.disruption.companion.domain.NotificationResult;
import com.disruption.companion.domain.PassengerMessage;
import com.disruption.companion.domain.RebookingResult;
import com.disruption.companion.domain.SourceReconciler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo/scenarios")
public class DemoScenarioController {

    private static final Instant BASE_NOW = Instant.parse("2026-09-15T11:03:00Z");
    private static final Instant EXPIRED_NOW = Instant.parse("2026-09-15T11:08:00Z");

    private final FixtureReadAdapter fixtures;

    public DemoScenarioController(FixtureReadAdapter fixtures) {
        this.fixtures = fixtures;
    }

    @GetMapping("/expired-ttl")
    public DisruptionAssessment expiredTtl() {
        return evaluateAt(EXPIRED_NOW).evaluate("ABC123", false);
    }

    @PostMapping("/duplicate-409")
    public ActionExecutionResult duplicate409() {
        ConfigurableRebookingAdapter rebooking = new ConfigurableRebookingAdapter(
                List.of(new RebookingResult.Duplicate("RBK-88231")));
        return actionWith(rebooking, new RetryPolicy(3, Duration.ofSeconds(1), d -> {}))
                .execute("ABC123", false);
    }

    @PostMapping("/retry-503-then-201")
    public Map<String, Object> retry503Then201() {
        ConfigurableRebookingAdapter rebooking = new ConfigurableRebookingAdapter(List.of(
                new RebookingResult.UpstreamTimeout("UPSTREAM_TIMEOUT"),
                new RebookingResult.UpstreamTimeout("UPSTREAM_TIMEOUT"),
                new RebookingResult.Created("RBK-88231", "PENDING_CONFIRMATION",
                        Instant.parse("2026-09-15T11:12:00Z"))
        ));
        List<Long> backoffsSeconds = new ArrayList<>();
        RetryPolicy retry = new RetryPolicy(3, Duration.ofSeconds(1), d -> backoffsSeconds.add(d.toSeconds()));
        ActionExecutionResult result = actionWith(rebooking, retry).execute("ABC123", false);
        List<String> keys = rebooking.recordedRequests().stream()
                .map(ConfigurableRebookingAdapter.RecordedRequest::idempotencyKey)
                .toList();
        return Map.of(
                "result", result,
                "backoffsSeconds", backoffsSeconds,
                "idempotencyKeysUsed", keys);
    }

    @PostMapping("/timeout-503-blocks-o2")
    public Map<String, Object> timeout503BlocksO2() {
        ConfigurableRebookingAdapter rebooking = new ConfigurableRebookingAdapter(List.of(
                new RebookingResult.UpstreamTimeout("UPSTREAM_TIMEOUT"),
                new RebookingResult.UpstreamTimeout("UPSTREAM_TIMEOUT"),
                new RebookingResult.UpstreamTimeout("UPSTREAM_TIMEOUT")
        ));
        ExecuteDisruptionAction action = actionWith(rebooking, new RetryPolicy(3, Duration.ofSeconds(1), d -> {}));
        ActionExecutionResult escalated = action.execute("ABC123", false);
        ActionExecutionResult blockedO2 = action.execute("ABC123", false, "O2");
        return Map.of(
                "firstAttemptO1", escalated,
                "secondAttemptO2", blockedO2,
                "totalRebookingCalls", rebooking.recordedRequests().size());
    }

    @PostMapping("/missing-policy-ref-422")
    public NotificationResult missingPolicyRef422() {
        InMemoryNotificationAdapter notifications = new InMemoryNotificationAdapter();
        PassengerMessage.Facts facts = new PassengerMessage.Facts(
                "ABC123", 160, "MISSED", "O1", "LA2412", null, null, null, null);
        return notifications.notifyPassenger(
                new PassengerMessage("ABC123", null, "We have requested your rebooking to flight LA2412", facts));
    }

    private EvaluateDisruption evaluateAt(Instant now) {
        ConnectionAnalyzer analyzer = new ConnectionAnalyzer(
                CompanionConfig.AIRPORTS, CompanionConfig.MINIMUM_CONNECTION, CompanionConfig.AT_RISK_MARGIN);
        return new EvaluateDisruption(
                fixtures, fixtures, fixtures, fixtures,
                SourceReconciler.withDefaults(), analyzer, Clock.fixed(now, ZoneOffset.UTC));
    }

    private ExecuteDisruptionAction actionWith(ConfigurableRebookingAdapter rebooking, RetryPolicy retryPolicy) {
        Clock clock = Clock.fixed(BASE_NOW, ZoneOffset.UTC);
        return new ExecuteDisruptionAction(
                evaluateAt(BASE_NOW), fixtures, fixtures,
                rebooking, new InMemoryHumanCaseAdapter(), new InMemoryNotificationAdapter(),
                clock, retryPolicy);
    }
}
