package com.disruption.companion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class OptionValidator {

    private static final String REBOOK_NO_FEE = "REBOOK_NO_FEE";

    public List<OptionVerdict> validate(OptionsOffer offer, TripContext trip,
                                        Set<String> activeEntitlements,
                                        Set<String> consultedPolicies, Instant now) {
        boolean expired = offer.isExpired(now);
        int partySize = trip.passengers().size();
        boolean hasRebookNoFee = activeEntitlements.contains(REBOOK_NO_FEE);

        List<OptionVerdict> verdicts = new ArrayList<>(offer.options().size());
        for (RebookOption option : offer.options()) {
            List<String> reasons = new ArrayList<>();

            if (expired) {
                reasons.add("offer expired at " + offer.expiresAt());
            }
            if (option.policyRef() == null || !consultedPolicies.contains(option.policyRef())) {
                reasons.add("policy not consulted: " + option.policyRef());
            }

            String type = option.type();
            if ("REBOOK".equals(type)) {
                if (option.seatsAvailable() == null || option.seatsAvailable() < partySize) {
                    reasons.add("insufficient seats: " + option.seatsAvailable() + " for " + partySize + " passengers");
                }
                if (!hasRebookNoFee) {
                    reasons.add("missing active " + REBOOK_NO_FEE + " entitlement");
                } else if (option.fareDifference() != null
                        && option.fareDifference().compareTo(BigDecimal.ZERO) > 0) {
                    String amount = option.currency() != null
                            ? option.fareDifference() + " " + option.currency()
                            : option.fareDifference().toString();
                    reasons.add("fare difference " + amount + " not covered by " + REBOOK_NO_FEE);
                }
            } else if ("REFUND".equals(type)) {
                if (trip.fare() == null || !Boolean.TRUE.equals(trip.fare().refundable())) {
                    reasons.add("fare is not refundable");
                }
            } else {
                reasons.add("unknown option type: " + type);
            }

            verdicts.add(new OptionVerdict(option, reasons.isEmpty(), List.copyOf(reasons)));
        }
        return List.copyOf(verdicts);
    }
}
