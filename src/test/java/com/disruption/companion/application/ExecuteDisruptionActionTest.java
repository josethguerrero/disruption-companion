package com.disruption.companion.application;

import com.disruption.companion.adapters.fixture.ConfigurableRebookingAdapter;
import com.disruption.companion.adapters.fixture.FixtureReadAdapter;
import com.disruption.companion.adapters.fixture.InMemoryHumanCaseAdapter;
import com.disruption.companion.adapters.fixture.InMemoryNotificationAdapter;
import com.disruption.companion.application.port.EligibleOptionsPort;
import com.disruption.companion.domain.Airport;
import com.disruption.companion.domain.ConnectionAnalyzer;
import com.disruption.companion.domain.Evidence;
import com.disruption.companion.domain.NotificationResult;
import com.disruption.companion.domain.OptionsOffer;
import com.disruption.companion.domain.PassengerMessage;
import com.disruption.companion.domain.RebookingResult;
import com.disruption.companion.domain.SourceReconciler;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ExecuteDisruptionActionTest {

    private static final Instant BASE_NOW = Instant.parse("2026-09-15T11:03:00Z");

    private static final Map<String, Airport> AIRPORTS = Map.of(
            "SCL", new Airport("SCL", ZoneId.of("America/Santiago"), "CL"),
            "LIM", new Airport("LIM", ZoneId.of("America/Lima"), "PE"),
            "MEX", new Airport("MEX", ZoneId.of("America/Mexico_City"), "MX")
    );

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            current.updateAndGet(t -> t.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    private static ExecuteDisruptionAction buildAction(ConfigurableRebookingAdapter rebooking,
                                                       InMemoryHumanCaseAdapter humanCases,
                                                       InMemoryNotificationAdapter notifications,
                                                       EligibleOptionsPort optionsPort,
                                                       Clock clock,
                                                       RetryPolicy retryPolicy) {
        FixtureReadAdapter fixtures = new FixtureReadAdapter();
        ConnectionAnalyzer analyzer = new ConnectionAnalyzer(
                AIRPORTS, Duration.ofMinutes(60), Duration.ofMinutes(30));
        EvaluateDisruption evaluate = new EvaluateDisruption(
                fixtures, fixtures, optionsPort, fixtures,
                SourceReconciler.withDefaults(), analyzer, clock);
        return new ExecuteDisruptionAction(
                evaluate, fixtures, optionsPort,
                rebooking, humanCases, notifications,
                clock, retryPolicy);
    }

    @Test
    void baseCaseRequestsRebookingOpensHumanCaseWithRequestIdAndNotifiesWithoutConfirming() {
        FixtureReadAdapter fixtures = new FixtureReadAdapter();
        ConfigurableRebookingAdapter rebooking = new ConfigurableRebookingAdapter();
        InMemoryHumanCaseAdapter humanCases = new InMemoryHumanCaseAdapter();
        InMemoryNotificationAdapter notifications = new InMemoryNotificationAdapter();
        MutableClock clock = new MutableClock(BASE_NOW);
        RetryPolicy retry = new RetryPolicy(3, Duration.ofSeconds(1), clock::advance);

        ExecuteDisruptionAction action = buildAction(rebooking, humanCases, notifications, fixtures, clock, retry);
        ActionExecutionResult result = action.execute("ABC123", false);

        assertThat(result.status()).isEqualTo(ActionExecutionResult.Status.REBOOKING_REQUESTED);
        assertThat(result.idempotencyKey()).isEqualTo("ABC123:S1:E-1001");
        assertThat(result.requestId()).isEqualTo("RBK-88231");
        assertThat(result.optionId()).isEqualTo("O1");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.humanCase()).isNotNull();
        assertThat(result.notification()).isInstanceOf(NotificationResult.Queued.class);

        assertThat(humanCases.recordedCases()).hasSize(1);
        Evidence evidence = humanCases.recordedCases().getFirst().evidence();
        assertThat(evidence.requestId()).isEqualTo("RBK-88231");
        assertThat(evidence.idempotencyKey()).isEqualTo("ABC123:S1:E-1001");
        assertThat(evidence.reasons()).anyMatch(r -> r.contains("R5"));
        assertThat(evidence.reasons()).anyMatch(r -> r.contains("overnight"));

        assertThat(notifications.sentMessages()).hasSize(1);
        PassengerMessage msg = notifications.sentMessages().getFirst();
        assertThat(msg.policyRef()).isEqualTo("POL-DELAY-2026-03");
        assertThat(msg.body()).containsIgnoringCase("Solicitamos tu cambio");
        assertThat(msg.body()).doesNotContainIgnoringCase("confirmado");

        JsonMapper mapper = JsonMapper.builder().build();
        String combinedJson = mapper.writeValueAsString(evidence) + mapper.writeValueAsString(msg);
        assertThat(combinedJson)
                .doesNotContain("maria.silva@example.com")
                .doesNotContain("+56 9 8765 4321")
                .doesNotContain("12.345.678-9")
                .doesNotContain("María")
                .doesNotContain("Silva")
                .doesNotContain("Tomás");
    }

    @Test
    void duplicate409IsTreatedAsSuccess() {
        FixtureReadAdapter fixtures = new FixtureReadAdapter();
        ConfigurableRebookingAdapter rebooking = new ConfigurableRebookingAdapter(
                List.of(new RebookingResult.Duplicate("RBK-88231")));
        InMemoryHumanCaseAdapter humanCases = new InMemoryHumanCaseAdapter();
        InMemoryNotificationAdapter notifications = new InMemoryNotificationAdapter();
        MutableClock clock = new MutableClock(BASE_NOW);
        RetryPolicy retry = new RetryPolicy(3, Duration.ofSeconds(1), clock::advance);

        ExecuteDisruptionAction action = buildAction(rebooking, humanCases, notifications, fixtures, clock, retry);
        ActionExecutionResult result = action.execute("ABC123", false);

        assertThat(result.status()).isEqualTo(ActionExecutionResult.Status.ALREADY_REQUESTED);
        assertThat(result.requestId()).isEqualTo("RBK-88231");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(humanCases.recordedCases().getFirst().evidence().requestId()).isEqualTo("RBK-88231");
    }

    @Test
    void timeout503RetriesWithSameKeyAndIncreasingBackoffUntilSuccess() {
        FixtureReadAdapter fixtures = new FixtureReadAdapter();
        ConfigurableRebookingAdapter rebooking = new ConfigurableRebookingAdapter(List.of(
                new RebookingResult.UpstreamTimeout("UPSTREAM_TIMEOUT"),
                new RebookingResult.UpstreamTimeout("UPSTREAM_TIMEOUT"),
                new RebookingResult.Created("RBK-88231", "PENDING_CONFIRMATION",
                        Instant.parse("2026-09-15T11:12:00Z"))
        ));
        InMemoryHumanCaseAdapter humanCases = new InMemoryHumanCaseAdapter();
        InMemoryNotificationAdapter notifications = new InMemoryNotificationAdapter();
        MutableClock clock = new MutableClock(BASE_NOW);
        List<Duration> recordedBackoffs = new ArrayList<>();
        RetryPolicy retry = new RetryPolicy(3, Duration.ofSeconds(1), d -> {
            recordedBackoffs.add(d);
            clock.advance(d);
        });

        ExecuteDisruptionAction action = buildAction(rebooking, humanCases, notifications, fixtures, clock, retry);
        ActionExecutionResult result = action.execute("ABC123", false);

        assertThat(result.status()).isEqualTo(ActionExecutionResult.Status.REBOOKING_REQUESTED);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(recordedBackoffs).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(rebooking.recordedRequests()).hasSize(3);
        assertThat(rebooking.recordedRequests())
                .extracting(ConfigurableRebookingAdapter.RecordedRequest::idempotencyKey)
                .containsOnly("ABC123:S1:E-1001");
    }

    @Test
    void timeout503ThreeTimesEscalatesToHumanAndBlocksOptionO2() {
        FixtureReadAdapter fixtures = new FixtureReadAdapter();
        ConfigurableRebookingAdapter rebooking = new ConfigurableRebookingAdapter(List.of(
                new RebookingResult.UpstreamTimeout("UPSTREAM_TIMEOUT"),
                new RebookingResult.UpstreamTimeout("UPSTREAM_TIMEOUT"),
                new RebookingResult.UpstreamTimeout("UPSTREAM_TIMEOUT")
        ));
        InMemoryHumanCaseAdapter humanCases = new InMemoryHumanCaseAdapter();
        InMemoryNotificationAdapter notifications = new InMemoryNotificationAdapter();
        MutableClock clock = new MutableClock(BASE_NOW);
        RetryPolicy retry = new RetryPolicy(3, Duration.ofSeconds(1), clock::advance);

        ExecuteDisruptionAction action = buildAction(rebooking, humanCases, notifications, fixtures, clock, retry);

        ActionExecutionResult escalated = action.execute("ABC123", false);
        assertThat(escalated.status()).isEqualTo(ActionExecutionResult.Status.ESCALATED_TO_HUMAN);
        assertThat(escalated.attempts()).isEqualTo(3);
        assertThat(escalated.requestId()).isNull();
        assertThat(escalated.humanCase()).isNotNull();
        assertThat(humanCases.recordedCases()).hasSize(1);
        assertThat(humanCases.recordedCases().getFirst().evidence().attempts()).isEqualTo(3);
        assertThat(humanCases.recordedCases().getFirst().evidence().requestId()).isNull();

        ActionExecutionResult secondAttemptDifferentOption = action.execute("ABC123", false, "O2");
        assertThat(secondAttemptDifferentOption.status())
                .isEqualTo(ActionExecutionResult.Status.BLOCKED_PENDING_RESOLUTION);
        assertThat(rebooking.recordedRequests()).hasSize(3);
    }

    @Test
    void expiredTtlBetweenRetriesRequeriesOptionsBeforeRetrying() {
        FixtureReadAdapter fixtures = new FixtureReadAdapter();
        MutableClock clock = new MutableClock(BASE_NOW);
        AtomicInteger optionsQueries = new AtomicInteger();

        EligibleOptionsPort refreshingOptionsPort = trip -> {
            int call = optionsQueries.incrementAndGet();
            OptionsOffer base = fixtures.getEligibleOptions(trip);
            if (call <= 2) {
                return base;
            }
            return new OptionsOffer(base.options(), clock.instant(), base.ttlSeconds());
        };

        ConfigurableRebookingAdapter rebooking = new ConfigurableRebookingAdapter(List.of(
                new RebookingResult.UpstreamTimeout("UPSTREAM_TIMEOUT"),
                new RebookingResult.Created("RBK-88231", "PENDING_CONFIRMATION",
                        Instant.parse("2026-09-15T11:15:00Z"))
        ));
        InMemoryHumanCaseAdapter humanCases = new InMemoryHumanCaseAdapter();
        InMemoryNotificationAdapter notifications = new InMemoryNotificationAdapter();
        // Backoff advances clock by 120s, pushing past the 11:04:35Z TTL
        RetryPolicy retry = new RetryPolicy(3, Duration.ofSeconds(120), clock::advance);

        ExecuteDisruptionAction action = buildAction(
                rebooking, humanCases, notifications, refreshingOptionsPort, clock, retry);
        ActionExecutionResult result = action.execute("ABC123", false);

        assertThat(result.status()).isEqualTo(ActionExecutionResult.Status.REBOOKING_REQUESTED);
        assertThat(result.attempts()).isEqualTo(2);
        // 1 in evaluate + 1 initial offer + 1 refresh after TTL expiry
        assertThat(optionsQueries.get()).isEqualTo(3);
    }

    @Test
    void notificationWithoutPolicyRefIsRejectedWith422() {
        InMemoryNotificationAdapter notifications = new InMemoryNotificationAdapter();
        PassengerMessage.Facts facts = new PassengerMessage.Facts(
                "ABC123", 160, "MISSED", "O1", "LA2412", null, null, null, null);

        NotificationResult result = notifications.notifyPassenger(
                new PassengerMessage("ABC123", null, "Solicitamos tu cambio", facts));

        assertThat(result).isEqualTo(new NotificationResult.Rejected("UNVALIDATED_CONTENT", "falta policy_ref"));
        assertThat(notifications.sentMessages()).isEmpty();
    }
}
