package com.disruption.companion.application.port;

import java.util.List;

import com.disruption.companion.domain.EventFeed;

public interface OperationalEventsPort {

    EventFeed getOperationalEvents(List<String> segmentIds);
}
