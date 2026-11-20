# Problem Brief, Open Questions, Assumptions & Development Plan

## 1. Problem Brief

A passenger's itinerary has experienced an operational disruption. The backend service must combine trip context, operational events, versioned policy rules, and candidate rebooking options to:

1. Determine what actually happened despite stale, degraded, or conflicting operational feeds.
2. Calculate downstream connection impact across airport timezones.
3. Evaluate versioned policy rules without hardcoding business thresholds.
4. Filter candidate options against TTL, party size, fare rules, and active entitlements.
5. Execute high-impact writes safely (idempotent rebooking, PII-free human escalation, policy-backed passenger notification).

### Base Case (`ABC123`) Summary

- **Passengers:** 2 (`P1` adult GOLD, `P2` child age 7 -> `group_includes_minor = true`).
- **Segments (2026-09-15):**
  - `S1`: `LA500` `SCL` (`America/Santiago`, UTC-3) `14:30` -> `LIM` (`America/Lima`, UTC-5) `17:55`.
  - `S2`: `LA2410` `LIM` `19:05` -> `MEX` (`America/Mexico_City`) `23:40`.
- **Events (`retrieved_at = 11:02:33Z`):**
  - `E-1001` (`OPS_CORE`, `10:48Z`): `S1` `DELAY`, `new_departure = 17:10` (`160 min` -> `20:10Z`).
  - `E-1007` (`AIRPORT_FEED`, `10:55Z`): `S1` `GATE_CHANGE` to gate `24`.
  - `E-1012` (`LEGACY_MIRROR`, `09:30Z`, **degraded**): `S1` `ON_TIME`.
  - `E-2003` (`OPS_CORE`, `10:48Z`): `S2` `SCHEDULED`.
- **Impact:** `S1` arrives ~`20:35` Lima -> `-90 min` slack vs. `S2` (`19:05`) -> **`MISSED`**.
- **Rules (`POL-DELAY-2026-03`):** `R3` (`missed_connection == true` -> `REBOOK_NO_FEE`, auto) and `R5` (`group_includes_minor == true` -> `PRIORITY_HANDLING`, human approval).
- **Options (`ttl = 120s`, expires `11:04:35Z`):** `O1` (`LA2412`, next day `08:15`, 4 seats, 0 diff) valid; `O2` invalid (1 seat for 2 pax, `180,000 CLP` diff); `O3` invalid (`refundable = false`, `POL-REFUND-2026-01` not consulted).

---

## 2. Open Questions & Documented Assumptions

The challenge intentionally leaves several operational and product rules undefined. Below are the questions to raise with stakeholders and the concrete assumptions adopted for v1:

| # | Ambiguity / Question | Who to Ask | v1 Assumption Implemented |
|---|---|---|---|
| 1 | **What counts as a relevant disruption?** Is a gate change or a small delay disruptive? | Product Manager / Airline Ops | `GATE_CHANGE` is informational only. `DELAY` is operationally relevant when it breaks a connection (`slack < 60 min`, `AT_RISK` at `< 90 min`) or meets policy delay thresholds (`>= 180 min`). |
| 2 | **How should conflicting operational feeds be resolved?** | Airline Ops / Platform | Events from `sources_degraded` are always discarded regardless of timestamp. Among healthy feeds, field authority wins (`SCHEDULE`: `OPS_CORE > AIRPORT_FEED > LEGACY_MIRROR`; `GATE`: `AIRPORT_FEED > OPS_CORE > LEGACY_MIRROR`). A newer contradicting healthy feed triggers `CONFLICT` -> human review. |
| 3 | **How old is too old for operational events?** | Airline Ops | Winner events older than `30 min` relative to `retrieved_at` are flagged `stale` and surfaced in `notes`. |
| 4 | **How do we estimate arrival time when only `new_departure` is provided?** | Airline Ops | Assume departure delay propagates 1:1 to arrival (`scheduledArrival + departureDelay`). If `new_departure < scheduled_departure`, roll to next day. Documented in `ConnectionAssessment.notes`. |
| 5 | **What Minimum Connection Time (MCT) applies at `LIM`?** | Airline Ops | Default `60 min` minimum connection and `30 min` at-risk margin (`60–89 min` = `AT_RISK`), injected via `CompanionConfig`. |
| 6 | **What does `confidence` (`HIGH`/`MEDIUM`/`LOW`) on options mean?** | Product / Pricing | Not defined in spec -> intentionally ignored in `OptionValidator` rather than inventing arbitrary cutoffs. |
| 7 | **Should `R5` (minor) block automatic rebooking or run in parallel?** | Product / Customer Care | Run in parallel: request `O1` (`PENDING_CONFIRMATION`) to hold seats, then immediately open `create_human_case` for `R5` + uncovered overnight stay in `LIM`, including `requestId` in `Evidence`. |
| 8 | **What about missing `fare_conditions.change_penalty_amount`?** | Product / Pricing | Logged in `notes` (`partial trip context`). Does not block `O1` because `R3` grants `REBOOK_NO_FEE` and `fare_difference = 0`; paid changes (`O2`) are rejected. |
| 9 | **What about the overnight stay in `LIM` (`09-15 -> 09-16`)?** | Product / Policy | No hotel/accommodation rule exists in `POL-DELAY-2026-03` -> explicitly added to `humanReview` so an agent handles duty-of-care. |
| 10 | **When can a `503` idempotency lock be released?** | Backend / Ops | Only upon `201` or `409`. Escalating to a human case after exhausting retries keeps the key locked (`PENDING_UNKNOWN`) so another option cannot trigger a duplicate booking. |

---

## 3. Incremental Development Plan

Each phase is self-contained, verified with `./mvnw test`, and committed atomically:

| Phase | Layer | Deliverable | Verification |
|---|---|---|---|
| **1** | `domain`, `application/port`, `adapters/fixture` | Core domain records (`TripContext`, `Segment`, `OperationalEvent`, `Policy`, `RebookOption`, `Airport`), 4 read ports, `FixtureReadAdapter` + PDF JSON fixtures. | Compilation + fixture smoke check |
| **2** | `domain` | `RuleOutcome` (`APPLIES` / `DOES_NOT_APPLY` / `CANNOT_EVALUATE`) and `RuleEvaluator` parsing policy condition strings. | `RuleEvaluatorTest` (6 tests) |
| **3** | `domain` | `FieldResolution` and `SourceReconciler` (degraded filtering, field authority, newer healthy contradiction -> `CONFLICT`, staleness). | `SourceReconcilerTest` (7 tests) |
| **4** | `domain` | `DepartureEstimate`, `ConnectionAssessment`, `ConnectionAnalyzer` (IANA timezones, overnight rollover, MCT/at-risk thresholds, international check) + `TestReferenceData`. | `ConnectionAnalyzerTest` (6 tests) |
| **5** | `domain` | `OptionVerdict` and `OptionValidator` (TTL expiry, consulted policy check, seat count, `REBOOK_NO_FEE` & fare difference, refundability). | `OptionValidatorTest` (5 tests) |
| **6** | `application` | `DisruptionAssessment` (PII-free) and `EvaluateDisruption` fixed orchestration flow. | `EvaluateDisruptionTest` (7 tests, incl. PII serialization check) |
| **7** | `config`, `adapters/web` | `CompanionConfig` (configurable `Clock`, constants), `application.properties`, `AssessmentController` (`GET /api/bookings/{bookingId}/assessment`). | `AssessmentControllerTest` + live `curl` HTTP 200 check |
| **8** | `domain`, `application`, `adapters/fixture`, `adapters/web`, `config` | Write ports (`RebookingPort`, `HumanCasePort`, `NotificationPort`), typed `Evidence` & `PassengerMessage.Facts`, `ExecuteDisruptionAction` (idempotency key, exponential backoff, TTL refresh, persistent `503` lock), configurable simulators, `POST .../actions/rebook`. | `ExecuteDisruptionActionTest` (6 tests) + `AssessmentControllerTest` (2 tests) — **40/40 passing** |

### Post-MVP Roadmap
1. Persist `PENDING_UNKNOWN` idempotency state in a relational/transactional store with an operator resolution endpoint.
2. Replace fixture adapters with real HTTP clients (timeouts, circuit breakers, correlation IDs, structured metrics).
3. Externalize airport/MCT tables and query additional policy scenarios (`REFUND`, `ACCOMMODATION`).
4. Add an optional AI-assisted presentation layer strictly downstream of `PassengerMessage.Facts` for natural-language passenger queries.
