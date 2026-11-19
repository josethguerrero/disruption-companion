package com.disruption.companion.application.port;

import com.disruption.companion.domain.OptionsOffer;
import com.disruption.companion.domain.TripContext;

public interface EligibleOptionsPort {

    OptionsOffer getEligibleOptions(TripContext tripContext);
}
