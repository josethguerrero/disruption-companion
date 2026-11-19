package com.disruption.companion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record TripContext(String bookingId, List<Passenger> passengers, List<Segment> segments,
                            FareConditions fare, Instant retrievedAt, String completeness,
                            List<String> missingFields) {
    
    public Optional<Segment> segment(String segmentId) {
        return segments.stream().filter(s -> s.segmentId().equals(segmentId)).findFirst();
    }

    public boolean groupIncludesMinor() {
        return passengers.stream().anyMatch(Passenger::isMinor);
    }
}