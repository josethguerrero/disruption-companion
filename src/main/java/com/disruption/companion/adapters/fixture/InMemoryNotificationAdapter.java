package com.disruption.companion.adapters.fixture;

import com.disruption.companion.application.port.NotificationPort;
import com.disruption.companion.domain.NotificationResult;
import com.disruption.companion.domain.PassengerMessage;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryNotificationAdapter implements NotificationPort {

    private final List<PassengerMessage> sent = new ArrayList<>();

    @Override
    public synchronized NotificationResult notifyPassenger(PassengerMessage message) {
        if (message.policyRef() == null || message.policyRef().isBlank()) {
            return new NotificationResult.Rejected("UNVALIDATED_CONTENT", "missing policy_ref");
        }
        sent.add(message);
        String id = sent.size() == 1 ? "NT-9912" : "NT-" + (9911 + sent.size());
        return new NotificationResult.Queued(id, "PUSH", "QUEUED");
    }

    public synchronized List<PassengerMessage> sentMessages() {
        return List.copyOf(sent);
    }
}
