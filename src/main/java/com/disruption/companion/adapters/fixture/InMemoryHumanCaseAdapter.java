package com.disruption.companion.adapters.fixture;

import com.disruption.companion.application.port.HumanCasePort;
import com.disruption.companion.domain.Evidence;
import com.disruption.companion.domain.HumanCaseReceipt;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryHumanCaseAdapter implements HumanCasePort {

    public record RecordedCase(String reason, Evidence evidence, HumanCaseReceipt receipt) {
    }

    private final List<RecordedCase> recorded = new ArrayList<>();

    @Override
    public synchronized HumanCaseReceipt createHumanCase(String reason, Evidence evidence) {
        String caseId = recorded.isEmpty() ? "HC-4471" : "HC-" + (4471 + recorded.size());
        HumanCaseReceipt receipt = new HumanCaseReceipt(caseId, "DISRUPTION_L2", 45);
        recorded.add(new RecordedCase(reason, evidence, receipt));
        return receipt;
    }

    public synchronized List<RecordedCase> recordedCases() {
        return List.copyOf(recorded);
    }
}
