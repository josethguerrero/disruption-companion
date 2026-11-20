package com.disruption.companion.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SourceReconciler {

    private static final Map<EventField, List<String>> DEFAULT_AUTHORITY = Map.of(
            EventField.SCHEDULE, List.of("OPS_CORE", "AIRPORT_FEED", "LEGACY_MIRROR"),
            EventField.GATE, List.of("AIRPORT_FEED", "OPS_CORE", "LEGACY_MIRROR")
    );
    private static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(30);

    private final Map<EventField, List<String>> authorityByField;
    private final Duration maxAge;

    public SourceReconciler(Map<EventField, List<String>> authorityByField, Duration maxAge) {
        this.authorityByField = Map.copyOf(authorityByField);
        this.maxAge = maxAge;
    }

    public static SourceReconciler withDefaults() {
        return new SourceReconciler(DEFAULT_AUTHORITY, DEFAULT_MAX_AGE);
    }

    public List<FieldResolution> reconcile(EventFeed feed) {
        Set<String> degraded = feed.sourcesDegraded() == null
                ? Set.of()
                : Set.copyOf(feed.sourcesDegraded());

        Map<GroupKey, List<OperationalEvent>> groups = new LinkedHashMap<>();
        for (OperationalEvent event : feed.events()) {
            GroupKey key = new GroupKey(event.segmentId(), event.field());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(event);
        }

        List<FieldResolution> resolutions = new ArrayList<>(groups.size());
        for (Map.Entry<GroupKey, List<OperationalEvent>> entry : groups.entrySet()) {
            resolutions.add(reconcileGroup(entry.getKey(), entry.getValue(), degraded, feed));
        }
        return List.copyOf(resolutions);
    }

    private FieldResolution reconcileGroup(GroupKey key, List<OperationalEvent> events,
                                           Set<String> degraded, EventFeed feed) {
        List<OperationalEvent> healthy = new ArrayList<>();
        List<FieldResolution.DiscardedEvent> discarded = new ArrayList<>();

        for (OperationalEvent event : events) {
            if (degraded.contains(event.source())) {
                discarded.add(new FieldResolution.DiscardedEvent(event, "degraded source: " + event.source()));
            } else {
                healthy.add(event);
            }
        }

        if (healthy.isEmpty()) {
            return new FieldResolution(key.segmentId(), key.field(), FieldResolution.Status.UNCONFIRMED,
                    null, false, List.copyOf(discarded));
        }

        List<OperationalEvent> ranked = new ArrayList<>(healthy);
        ranked.sort(comparatorFor(key.field()));
        OperationalEvent winner = ranked.getFirst();

        boolean conflict = false;
        for (int i = 1; i < ranked.size(); i++) {
            OperationalEvent other = ranked.get(i);
            boolean newerContradiction = !other.source().equals(winner.source())
                    && !other.value().equals(winner.value())
                    && other.recordedAt().isAfter(winner.recordedAt());

            if (newerContradiction) {
                conflict = true;
                discarded.add(new FieldResolution.DiscardedEvent(
                        other, "conflicts with " + winner.source() + " (newer healthy event)"));
            } else if (other.source().equals(winner.source())) {
                discarded.add(new FieldResolution.DiscardedEvent(
                        other, "superseded by newer event from " + winner.source()));
            } else {
                discarded.add(new FieldResolution.DiscardedEvent(
                        other, "lower authority than " + winner.source()));
            }
        }

        FieldResolution.Status status = conflict
                ? FieldResolution.Status.CONFLICT
                : FieldResolution.Status.CONFIRMED;
        boolean stale = Duration.between(winner.recordedAt(), feed.retrievedAt()).compareTo(maxAge) > 0;

        return new FieldResolution(key.segmentId(), key.field(), status, winner, stale, List.copyOf(discarded));
    }

    private Comparator<OperationalEvent> comparatorFor(EventField field) {
        return Comparator
                .comparingInt((OperationalEvent e) -> rank(field, e.source()))
                .thenComparing(OperationalEvent::recordedAt, Comparator.reverseOrder());
    }

    private int rank(EventField field, String source) {
        List<String> hierarchy = authorityByField.getOrDefault(field, List.of());
        int idx = hierarchy.indexOf(source);
        return idx >= 0 ? idx : Integer.MAX_VALUE;
    }

    private record GroupKey(String segmentId, EventField field) {
    }
}
