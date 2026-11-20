package com.disruption.companion.domain;

import java.time.ZonedDateTime;
import java.util.List;

public record ConnectionAssessment(Status status, ZonedDateTime estimatedArrival, ZonedDateTime onwardDeparture,
                                   Long slackMinutes, List<String> notes) {

    public enum Status { KEPT, AT_RISK, MISSED, UNKNOWN }
}
