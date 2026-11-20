package com.disruption.companion.application.port;

import com.disruption.companion.domain.RebookOption;
import com.disruption.companion.domain.RebookingResult;

public interface RebookingPort {

    RebookingResult createRebookingRequest(RebookOption option, String idempotencyKey);
}
