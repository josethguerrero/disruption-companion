package com.disruption.companion.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record RebookOption(String optionId, String type, String flightNumber, String origin,
                            String destination, LocalDate date, LocalTime departure,
                            BigDecimal fareDifference, String currency, Integer seatsAvailable,
                            String policyRef, String confidence) {

}
