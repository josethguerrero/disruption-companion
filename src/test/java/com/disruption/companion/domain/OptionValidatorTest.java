package com.disruption.companion.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OptionValidatorTest {

    private static final Instant RETRIEVED_AT = Instant.parse("2026-09-15T11:02:35Z");
    private static final Instant VALID_NOW = Instant.parse("2026-09-15T11:03:00Z");

    private final OptionValidator validator = new OptionValidator();

    private static TripContext baseTrip(boolean refundable) {
        return new TripContext(
                "ABC123",
                List.of(
                        new Passenger("P1", "Maria Silva", "ADULT", null, null, null, null, "GOLD"),
                        new Passenger("P2", "Tomas Silva", "CHILD", 7, null, null, null, null)
                ),
                List.of(),
                new FareConditions(true, refundable, "LIGHT"),
                RETRIEVED_AT, "PARTIAL", List.of("fare_conditions.change_penalty_amount")
        );
    }

    private static RebookOption o1() {
        return new RebookOption("O1", "REBOOK", "LA2412", "LIM", "MEX",
                LocalDate.of(2026, 9, 16), LocalTime.of(8, 15), BigDecimal.ZERO, null,
                4, "POL-DELAY-2026-03", "HIGH");
    }

    private static RebookOption o2() {
        return new RebookOption("O2", "REBOOK", "LA2450", "LIM", "MEX",
                LocalDate.of(2026, 9, 15), LocalTime.of(23, 40), BigDecimal.valueOf(180000), "CLP",
                1, "POL-DELAY-2026-03", "MEDIUM");
    }

    private static RebookOption o3() {
        return new RebookOption("O3", "REFUND", null, null, null,
                null, null, null, null, null, "POL-REFUND-2026-01", "LOW");
    }

    @Test
    void baseCaseValidatesO1AndRejectsO2AndO3() {
        OptionsOffer offer = new OptionsOffer(List.of(o1(), o2(), o3()), RETRIEVED_AT, 120);

        List<OptionVerdict> verdicts = validator.validate(
                offer, baseTrip(false), Set.of("REBOOK_NO_FEE"), Set.of("POL-DELAY-2026-03"), VALID_NOW);

        assertThat(verdicts).hasSize(3);

        assertThat(verdicts.get(0).valid()).isTrue();
        assertThat(verdicts.get(0).reasons()).isEmpty();

        assertThat(verdicts.get(1).valid()).isFalse();
        assertThat(verdicts.get(1).reasons()).hasSize(2);
        assertThat(verdicts.get(1).reasons()).anyMatch(r -> r.contains("seats"));
        assertThat(verdicts.get(1).reasons()).anyMatch(r -> r.contains("180000"));

        assertThat(verdicts.get(2).valid()).isFalse();
        assertThat(verdicts.get(2).reasons()).hasSize(2);
        assertThat(verdicts.get(2).reasons()).anyMatch(r -> r.contains("POL-REFUND-2026-01"));
        assertThat(verdicts.get(2).reasons()).anyMatch(r -> r.contains("not refundable"));
    }

    @Test
    void expiredOfferInvalidatesAllOptions() {
        OptionsOffer offer = new OptionsOffer(List.of(o1()), RETRIEVED_AT, 120);
        Instant exactExpiry = RETRIEVED_AT.plusSeconds(120);

        List<OptionVerdict> verdicts = validator.validate(
                offer, baseTrip(false), Set.of("REBOOK_NO_FEE"), Set.of("POL-DELAY-2026-03"), exactExpiry);

        assertThat(verdicts.getFirst().valid()).isFalse();
        assertThat(verdicts.getFirst().reasons()).anyMatch(r -> r.contains("expired"));
    }

    @Test
    void rebookWithoutEntitlementIsInvalid() {
        OptionsOffer offer = new OptionsOffer(List.of(o1()), RETRIEVED_AT, 120);

        List<OptionVerdict> verdicts = validator.validate(
                offer, baseTrip(false), Set.of(), Set.of("POL-DELAY-2026-03"), VALID_NOW);

        assertThat(verdicts.getFirst().valid()).isFalse();
        assertThat(verdicts.getFirst().reasons()).anyMatch(r -> r.contains("REBOOK_NO_FEE"));
    }

    @Test
    void refundIsValidWhenFareIsRefundableAndPolicyConsulted() {
        OptionsOffer offer = new OptionsOffer(List.of(o3()), RETRIEVED_AT, 120);

        List<OptionVerdict> verdicts = validator.validate(
                offer, baseTrip(true), Set.of(), Set.of("POL-REFUND-2026-01"), VALID_NOW);

        assertThat(verdicts.getFirst().valid()).isTrue();
    }

    @Test
    void unknownOptionTypeIsInvalid() {
        RebookOption voucher = new RebookOption("O4", "VOUCHER", null, null, null,
                null, null, null, null, null, "POL-DELAY-2026-03", "HIGH");
        OptionsOffer offer = new OptionsOffer(List.of(voucher), RETRIEVED_AT, 120);

        List<OptionVerdict> verdicts = validator.validate(
                offer, baseTrip(false), Set.of("REBOOK_NO_FEE"), Set.of("POL-DELAY-2026-03"), VALID_NOW);

        assertThat(verdicts.getFirst().valid()).isFalse();
        assertThat(verdicts.getFirst().reasons()).anyMatch(r -> r.contains("unknown option type"));
    }
}
