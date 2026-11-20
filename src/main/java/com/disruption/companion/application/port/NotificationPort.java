package com.disruption.companion.application.port;

import com.disruption.companion.domain.NotificationResult;
import com.disruption.companion.domain.PassengerMessage;

public interface NotificationPort {

    NotificationResult notifyPassenger(PassengerMessage message);
}
