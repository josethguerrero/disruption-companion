package com.disruption.companion.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceReconcilerTest {

    private static final Instant RETRIEVED_AT = Instant.parse("2026-09-15T11:02:33Z");
    private final SourceReconciler reconciler = SourceReconciler.withDefaults();

    @Test
    void degradedSourceIsDiscardedInBaseCase() {
        OperationalEvent delay = new OperationalEvent("E-1001", "S1", "DELAY", LocalTime.of(17, 10),
                160, null, null, "OPS_CORE", Instant.parse("2026-09-15T10:48:00Z"));
        OperationalEvent onTime = new OperationalEvent("E-1012", "S1", "STATUS", null,
                null, null, "ON_TIME", "LEGACY_MIRROR", Instant.parse("2026-09-15T09:30:00Z"));
        EventFeed feed = new EventFeed(List.of(delay, onTime), RETRIEVED_AT,
                List.of("OPS_CORE", "AIRPORT_FEED", "LEGACY_MIRROR"), List.of("LEGACY_MIRROR"));

        List<FieldResolution> results = reconciler.reconcile(feed);

        assertThat(results).hasSize(1);
        FieldResolution r = results.getFirst();
        assertThat(r.segmentId()).isEqualTo("S1");
        assertThat(r.field()).isEqualTo(EventField.SCHEDULE);
        assertThat(r.status()).isEqualTo(FieldResolution.Status.CONFIRMED);
        assertThat(r.winner()).isEqualTo(delay);
        assertThat(r.stale()).isFalse();
        assertThat(r.discarded()).hasSize(1);
        assertThat(r.discarded().getFirst().event()).isEqualTo(onTime);
        assertThat(r.discarded().getFirst().reason()).contains("degraded");
    }

    @Test
    void degradedSourceLosesEvenWhenMoreRecent() {
        OperationalEvent opsCore = new OperationalEvent("E-1001", "S1", "DELAY", LocalTime.of(17, 10),
                160, null, null, "OPS_CORE", Instant.parse("2026-09-15T10:48:00Z"));
        OperationalEvent newerDegraded = new OperationalEvent("E-1099", "S1", "STATUS", null,
                null, null, "ON_TIME", "LEGACY_MIRROR", Instant.parse("2026-09-15T11:01:00Z"));
        EventFeed feed = new EventFeed(List.of(opsCore, newerDegraded), RETRIEVED_AT,
                List.of("OPS_CORE", "LEGACY_MIRROR"), List.of("LEGACY_MIRROR"));

        FieldResolution r = reconciler.reconcile(feed).getFirst();

        assertThat(r.status()).isEqualTo(FieldResolution.Status.CONFIRMED);
        assertThat(r.winner()).isEqualTo(opsCore);
        assertThat(r.discarded()).hasSize(1);
        assertThat(r.discarded().getFirst().event()).isEqualTo(newerDegraded);
    }

    @Test
    void higherAuthorityWinsOverOlderLowerAuthoritySource() {
        OperationalEvent opsCore = new OperationalEvent("E-1001", "S1", "DELAY", LocalTime.of(17, 10),
                160, null, null, "OPS_CORE", Instant.parse("2026-09-15T10:50:00Z"));
        OperationalEvent olderAirportFeed = new OperationalEvent("E-1005", "S1", "STATUS", null,
                null, null, "ON_TIME", "AIRPORT_FEED", Instant.parse("2026-09-15T10:40:00Z"));
        EventFeed feed = new EventFeed(List.of(olderAirportFeed, opsCore), RETRIEVED_AT,
                List.of("OPS_CORE", "AIRPORT_FEED"), List.of());

        FieldResolution r = reconciler.reconcile(feed).getFirst();

        assertThat(r.status()).isEqualTo(FieldResolution.Status.CONFIRMED);
        assertThat(r.winner()).isEqualTo(opsCore);
        assertThat(r.discarded()).hasSize(1);
        assertThat(r.discarded().getFirst().event()).isEqualTo(olderAirportFeed);
    }

    @Test
    void newerHealthyContradictionProducesConflict() {
        OperationalEvent opsCore = new OperationalEvent("E-1001", "S1", "DELAY", LocalTime.of(17, 10),
                160, null, null, "OPS_CORE", Instant.parse("2026-09-15T10:48:00Z"));
        OperationalEvent newerAirportFeed = new OperationalEvent("E-1010", "S1", "STATUS", null,
                null, null, "ON_TIME", "AIRPORT_FEED", Instant.parse("2026-09-15T10:58:00Z"));
        EventFeed feed = new EventFeed(List.of(opsCore, newerAirportFeed), RETRIEVED_AT,
                List.of("OPS_CORE", "AIRPORT_FEED"), List.of());

        FieldResolution r = reconciler.reconcile(feed).getFirst();

        assertThat(r.status()).isEqualTo(FieldResolution.Status.CONFLICT);
        assertThat(r.winner()).isEqualTo(opsCore);
        assertThat(r.discarded()).hasSize(1);
        assertThat(r.discarded().getFirst().event()).isEqualTo(newerAirportFeed);
    }

    @Test
    void onlyDegradedSourcesProducesUnconfirmed() {
        OperationalEvent degradedOnly = new OperationalEvent("E-1012", "S1", "STATUS", null,
                null, null, "ON_TIME", "LEGACY_MIRROR", Instant.parse("2026-09-15T10:55:00Z"));
        EventFeed feed = new EventFeed(List.of(degradedOnly), RETRIEVED_AT,
                List.of("LEGACY_MIRROR"), List.of("LEGACY_MIRROR"));

        FieldResolution r = reconciler.reconcile(feed).getFirst();

        assertThat(r.status()).isEqualTo(FieldResolution.Status.UNCONFIRMED);
        assertThat(r.winner()).isNull();
        assertThat(r.stale()).isFalse();
        assertThat(r.discarded()).hasSize(1);
    }

    @Test
    void eventOlderThanThresholdIsMarkedStale() {
        OperationalEvent oldEvent = new OperationalEvent("E-1001", "S1", "DELAY", LocalTime.of(17, 10),
                160, null, null, "OPS_CORE", Instant.parse("2026-09-15T10:20:00Z"));
        EventFeed feed = new EventFeed(List.of(oldEvent), RETRIEVED_AT,
                List.of("OPS_CORE"), List.of());

        FieldResolution r = reconciler.reconcile(feed).getFirst();

        assertThat(r.status()).isEqualTo(FieldResolution.Status.CONFIRMED);
        assertThat(r.winner()).isEqualTo(oldEvent);
        assertThat(r.stale()).isTrue();
    }

    @Test
    void gateAuthorityPrefersAirportFeedOverOpsCore() {
        OperationalEvent opsGate = new OperationalEvent("E-2001", "S1", "GATE_CHANGE", null,
                null, "12", null, "OPS_CORE", Instant.parse("2026-09-15T10:50:00Z"));
        OperationalEvent airportGate = new OperationalEvent("E-2002", "S1", "GATE_CHANGE", null,
                null, "24", null, "AIRPORT_FEED", Instant.parse("2026-09-15T10:55:00Z"));
        EventFeed feed = new EventFeed(List.of(opsGate, airportGate), RETRIEVED_AT,
                List.of("OPS_CORE", "AIRPORT_FEED"), List.of());

        FieldResolution r = reconciler.reconcile(feed).getFirst();

        assertThat(r.field()).isEqualTo(EventField.GATE);
        assertThat(r.status()).isEqualTo(FieldResolution.Status.CONFIRMED);
        assertThat(r.winner()).isEqualTo(airportGate);
    }
}
