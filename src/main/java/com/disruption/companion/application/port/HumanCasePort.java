package com.disruption.companion.application.port;

import com.disruption.companion.domain.Evidence;
import com.disruption.companion.domain.HumanCaseReceipt;

public interface HumanCasePort {

    HumanCaseReceipt createHumanCase(String reason, Evidence evidence);
}
