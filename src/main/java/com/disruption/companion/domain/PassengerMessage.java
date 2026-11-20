package com.disruption.companion.domain;

import java.time.LocalDate;
import java.time.LocalTime;

public record PassengerMessage(String bookingId, String policyRef, String body, Facts facts) {

    public record Facts(
            String bookingId,
            Integer delayMinutes,
            String connectionStatus,
            String recommendedOptionId,
            String recommendedFlight,
            LocalDate recommendedDate,
            LocalTime recommendedDeparture,
            String rebookingRequestId,
            String humanCaseId) {
    }
}
