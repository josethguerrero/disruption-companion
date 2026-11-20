package com.disruption.companion.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ConnectionAnalyzer {

    private final Map<String, Airport> airports;
    private final Duration minimumConnection;
    private final Duration atRiskMargin;

    public ConnectionAnalyzer(Map<String, Airport> airports, Duration minimumConnection, Duration atRiskMargin) {
        this.airports = Map.copyOf(airports);
        this.minimumConnection = minimumConnection;
        this.atRiskMargin = atRiskMargin;
    }

    public Optional<DepartureEstimate> departure(Segment segment, Optional<FieldResolution> scheduleResolution) {
        Airport origin = airports.get(segment.origin());
        if (origin == null) {
            return Optional.empty();
        }

        ZonedDateTime scheduled = ZonedDateTime.of(segment.date(), segment.scheduledDeparture(), origin.zone());
        if (scheduleResolution.isEmpty()) {
            return Optional.of(new DepartureEstimate(scheduled, Duration.ZERO));
        }

        FieldResolution resolution = scheduleResolution.get();
        if (resolution.status() != FieldResolution.Status.CONFIRMED || resolution.winner() == null) {
            return Optional.empty();
        }

        OperationalEvent event = resolution.winner();
        return switch (event.type()) {
            case "DELAY" -> delayDeparture(segment, origin, scheduled, event);
            case "STATUS" -> ("ON_TIME".equals(event.status()) || "SCHEDULED".equals(event.status()))
                    ? Optional.of(new DepartureEstimate(scheduled, Duration.ZERO))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    public ConnectionAssessment assess(Segment from, Optional<FieldResolution> fromSchedule,
                                       Segment to, Optional<FieldResolution> toSchedule) {
        List<String> notes = new ArrayList<>();
        Optional<DepartureEstimate> fromDeparture = departure(from, fromSchedule);
        Optional<DepartureEstimate> toDeparture = departure(to, toSchedule);

        ZonedDateTime estimatedArrival = estimateArrival(from, fromDeparture, notes);
        ZonedDateTime onwardDeparture = toDeparture.map(DepartureEstimate::departure).orElse(null);

        if (fromDeparture.isEmpty()) {
            notes.add("unusable schedule for segment " + from.segmentId());
        }
        if (toDeparture.isEmpty()) {
            notes.add("unusable schedule for segment " + to.segmentId());
        }

        if (estimatedArrival == null || onwardDeparture == null) {
            return new ConnectionAssessment(ConnectionAssessment.Status.UNKNOWN,
                    estimatedArrival, onwardDeparture, null, List.copyOf(notes));
        }

        long slackMinutes = Duration.between(estimatedArrival, onwardDeparture).toMinutes();
        long minMinutes = minimumConnection.toMinutes();
        long atRiskLimit = minMinutes + atRiskMargin.toMinutes();

        ConnectionAssessment.Status status;
        if (slackMinutes < minMinutes) {
            status = ConnectionAssessment.Status.MISSED;
        } else if (slackMinutes < atRiskLimit) {
            status = ConnectionAssessment.Status.AT_RISK;
        } else {
            status = ConnectionAssessment.Status.KEPT;
        }

        return new ConnectionAssessment(status, estimatedArrival, onwardDeparture, slackMinutes, List.copyOf(notes));
    }

    public boolean isInternational(List<Segment> segments) {
        Set<String> countries = new HashSet<>();
        for (Segment segment : segments) {
            Airport origin = airports.get(segment.origin());
            Airport destination = airports.get(segment.destination());
            if (origin != null) {
                countries.add(origin.country());
            }
            if (destination != null) {
                countries.add(destination.country());
            }
        }
        return countries.size() > 1;
    }

    private Optional<DepartureEstimate> delayDeparture(Segment segment, Airport origin,
                                                       ZonedDateTime scheduled, OperationalEvent event) {
        if (event.newDeparture() != null) {
            LocalDate date = event.newDeparture().isBefore(segment.scheduledDeparture())
                    ? segment.date().plusDays(1)
                    : segment.date();
            ZonedDateTime actual = ZonedDateTime.of(date, event.newDeparture(), origin.zone());
            return Optional.of(new DepartureEstimate(actual, Duration.between(scheduled, actual)));
        }
        if (event.delayMinutes() != null) {
            Duration delay = Duration.ofMinutes(event.delayMinutes());
            return Optional.of(new DepartureEstimate(scheduled.plus(delay), delay));
        }
        return Optional.empty();
    }

    private ZonedDateTime estimateArrival(Segment from, Optional<DepartureEstimate> fromDeparture, List<String> notes) {
        if (fromDeparture.isEmpty()) {
            return null;
        }
        Airport origin = airports.get(from.origin());
        Airport destination = airports.get(from.destination());
        if (origin == null || destination == null) {
            notes.add("unknown airport for segment " + from.segmentId());
            return null;
        }

        ZonedDateTime scheduledDeparture = ZonedDateTime.of(from.date(), from.scheduledDeparture(), origin.zone());
        ZonedDateTime scheduledArrival = ZonedDateTime.of(from.date(), from.scheduledArrival(), destination.zone());
        if (scheduledArrival.toInstant().isBefore(scheduledDeparture.toInstant())) {
            scheduledArrival = scheduledArrival.plusDays(1);
        }

        Duration delay = fromDeparture.get().delay();
        if (!delay.isZero()) {
            notes.add("arrival estimated by applying departure delay of " + delay.toMinutes() + " min");
        }
        return scheduledArrival.plus(delay);
    }
}
