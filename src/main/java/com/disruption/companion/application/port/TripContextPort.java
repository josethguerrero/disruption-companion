package com.disruption.companion.application.port;

import com.disruption.companion.domain.TripContext;

/** Port for the get_trip_context read tool defined in the challenge spec. */
public interface TripContextPort {
    
    TripContext getTripContext(String bookingId);
}
