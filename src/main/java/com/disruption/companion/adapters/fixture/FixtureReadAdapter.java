package com.disruption.companion.adapters.fixture;

import java.util.List;
import java.util.Map;

import com.disruption.companion.application.port.EligibleOptionsPort;
import com.disruption.companion.application.port.OperationalEventsPort;
import com.disruption.companion.application.port.PolicyRulesPort;
import com.disruption.companion.application.port.TripContextPort;
import com.disruption.companion.domain.EventFeed;
import com.disruption.companion.domain.FareConditions;
import com.disruption.companion.domain.OperationalEvent;
import com.disruption.companion.domain.OptionsOffer;
import com.disruption.companion.domain.Passenger;
import com.disruption.companion.domain.Policy;
import com.disruption.companion.domain.PolicyRule;
import com.disruption.companion.domain.RebookOption;
import com.disruption.companion.domain.Segment;
import com.disruption.companion.domain.TripContext;

import static com.disruption.companion.adapters.fixture.FixtureReader.*;

public final class FixtureReadAdapter
                implements TripContextPort, OperationalEventsPort, EligibleOptionsPort, PolicyRulesPort {

        @Override
        public TripContext getTripContext(String bookingId) {
                Map<String, Object> root = read("get_trip_context.json");
                List<Passenger> passengers = list(root, "passengers").stream().map(p -> new Passenger(
                                str(p, "passenger_id"), str(p, "name"), str(p, "type"), integer(p, "age"),
                                str(p, "email"), str(p, "phone"), str(p, "document_id"), str(p, "loyalty_tier")))
                                .toList();
                List<Segment> segments = list(root, "segments").stream().map(s -> new Segment(
                                str(s, "segment_id"), str(s, "flight_number"), str(s, "origin"), str(s, "destination"),
                                date(s, "date"), time(s, "scheduled_departure"), time(s, "scheduled_arrival")))
                                .toList();
                Map<String, Object> fare = obj(root, "fare_conditions");
                Map<String, Object> meta = obj(root, "_meta");
                return new TripContext(str(root, "booking_id"), passengers, segments,
                                new FareConditions(bool(fare, "changeable"), bool(fare, "refundable"),
                                                str(fare, "fare_family")),
                                instant(meta, "retrieved_at"), str(meta, "completeness"),
                                strings(meta, "missing_fields"));
        }

        @Override
        public EventFeed getOperationalEvents(List<String> segmentIds) {
                Map<String, Object> root = read("get_operational_events.json");
                List<OperationalEvent> events = list(root, "events").stream()
                                .filter(e -> segmentIds.contains(str(e, "segment_id")))
                                .map(e -> new OperationalEvent(str(e, "event_id"), str(e, "segment_id"), str(e, "type"),
                                                time(e, "new_departure"), integer(e, "delay_minutes"), str(e, "gate"),
                                                str(e, "status"),
                                                str(e, "source"), instant(e, "recorded_at")))
                                .toList();
                Map<String, Object> meta = obj(root, "_meta");
                return new EventFeed(events, instant(meta, "retrieved_at"), strings(meta, "sources_available"),
                                strings(meta, "sources_degraded"));
        }

        @Override
        public OptionsOffer getEligibleOptions(TripContext tripContext) {
                Map<String, Object> root = read("get_eligible_options.json");
                List<RebookOption> options = list(root, "options").stream().map(o -> new RebookOption(
                                str(o, "option_id"), str(o, "type"), str(o, "flight_number"), str(o, "origin"),
                                str(o, "destination"), date(o, "date"), time(o, "departure"),
                                decimal(o, "fare_difference"),
                                str(o, "currency"), integer(o, "seats_available"), str(o, "policy_ref"),
                                str(o, "confidence"))).toList();
                Map<String, Object> meta = obj(root, "_meta");
                return new OptionsOffer(options, instant(meta, "retrieved_at"), integer(meta, "ttl_seconds"));
        }

        @Override
        public Policy getPolicyRules(String scenario) {
                Map<String, Object> root = read("get_policy_rules.json");
                List<PolicyRule> rules = list(root, "rules").stream().map(r -> new PolicyRule(
                                str(r, "rule_id"), str(r, "condition"), str(r, "entitlement"),
                                Boolean.TRUE.equals(bool(r, "requires_human_approval")))).toList();
                return new Policy(str(root, "scenario"), str(root, "version"), date(root, "effective_from"), rules,
                                instant(obj(root, "_meta"), "retrieved_at"));
        }
}