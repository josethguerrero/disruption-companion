package com.disruption.companion.application.port;

import com.disruption.companion.domain.Policy;

public interface PolicyRulesPort {

    Policy getPolicyRules(String scenario);
}
