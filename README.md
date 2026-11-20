# Disruption Companion — Engineering README

**Track:** Backend / Integration (Java 21, Spring Boot 4.1.2, Maven)  
**Supporting docs:** [Assumptions & Development Plan](docs/ASSUMPTIONS_AND_PLAN.md) · [AI Usage & Decision Note](docs/AI_USAGE_AND_DECISIONS.md)

---

## 1. Problem & Expected Outcome

Passengers facing a flight disruption need to know what actually happened, what their policy entitlements are, and what safe next step is available—without receiving unverified information, leaking PII, or triggering high-impact actions without controls.

**Expected v1 outcome:** reduce avoidable call-center contacts while guaranteeing accuracy, policy traceability, and idempotent write safety.

In the base case (`ABC123`: `SCL -> LIM -> MEX`, adult + 7-year-old minor):
- `OPS_CORE` reports a 160-minute delay on `S1` (`LA500`, new departure `17:10` Santiago / `20:10Z`), while degraded `LEGACY_MIRROR` reports `ON_TIME`.
- Applying the 160-minute delay to `S1`'s arrival yields `20:35` Lima (`-05:00`) against `S2`'s `19:05` departure (`-90` min slack) -> **missed connection**.
- Policy `POL-DELAY-2026-03` triggers `R3` (`missed_connection == true` -> `REBOOK_NO_FEE`, automatic) and `R5` (`group_includes_minor == true` -> `PRIORITY_HANDLING`, requires human approval). `R1`/`R2` do not apply (`160 < 180`).
- Option `O1` (`LA2412`, next morning `08:15`, 4 seats, 0 fare difference) is valid; `O2` fails seats (`1 < 2`) and fare difference (`180,000 CLP`); `O3` fails non-refundable fare and unconsulted policy (`POL-REFUND-2026-01`).
- Result: `O1` is recommended (`autoRebook = true`), while `R5` (minor) and the uncovered overnight stay in `LIM` are routed to human review.

---

## 2. Architecture & Implemented Scope

Lightweight hexagonal architecture with dependencies pointing strictly inward (`adapters -> application -> domain`):

```text
src/main/java/com/disruption/companion/
├── domain/           Pure Java logic (no Spring, no Jackson, no ports)
│                     SourceReconciler, ConnectionAnalyzer, RuleEvaluator,
│                     OptionValidator, IdempotencyKey, typed Evidence & PassengerMessage
├── application/      EvaluateDisruption, ExecuteDisruptionAction, RetryPolicy
│   └── port/         Read ports (4) + Write ports (RebookingPort, HumanCasePort, NotificationPort)
├── adapters/
│   ├── fixture/      JSON fixture reader + configurable write simulators (201/409/503/422)
│   └── web/          AssessmentController (thin HTTP translation only)
└── config/           CompanionConfig (Spring wiring, Clock, airport/MCT constants)
```

### How to Run & Validate

```bash
# Run full test suite (40 unit and Spring WebMVC tests)
./mvnw test

# Start application (uses companion.now=2026-09-15T11:03:00Z so the 120s fixture TTL is active)
./mvnw spring-boot:run

# Evaluate disruption (read-only assessment, no PII)
curl "http://localhost:8080/api/bookings/ABC123/assessment?compensationRequested=false"

# Execute controlled rebooking + parallel human escalation + passenger notification
curl -X POST "http://localhost:8080/api/bookings/ABC123/actions/rebook?compensationRequested=false"
```

---

## 3. Key Decisions & Rejected Alternatives

| Decision | Chosen Approach | Rejected Alternative & Why |
|---|---|---|
| **Runtime AI vs. deterministic core** | 100% deterministic evaluation, rule engine, option validation, and write guardrails. | LLM/agent deciding eligibility or calling write tools: unacceptable cost, latency, and policy-hallucination risk on financial/safety actions. |
| **Policy evaluation** | Parse versioned condition strings (`RuleEvaluator`) against a fact map; unknown syntax or missing facts fail closed to `CANNOT_EVALUATE`. | Hardcoded `if` thresholds: requires code deploys whenever policy versions change and risks silent mis-evaluation. |
| **Source reconciliation** | Drop `sources_degraded`, rank by field-specific authority (`SCHEDULE`: `OPS_CORE > AIRPORT_FEED > LEGACY_MIRROR`; `GATE`: `AIRPORT_FEED > OPS_CORE > LEGACY_MIRROR`), flag newer healthy contradictions as `CONFLICT`, flag age `> 30 min` as `stale`. | Last-write-wins by timestamp: lets degraded or lower-authority feeds override `OPS_CORE`. |
| **Idempotency & `503` handling** | Backend computes key `bookingId:segmentId:eventId` (scoped to the disruption, not the option). `503` retries with exponential backoff and same key, re-querying options if TTL expired. `409` = success. Lock stays held even after escalation to human case until resolved. | Releasing the lock on escalation or keying by `optionId`: could create a duplicate booking when an upstream `503` actually succeeded. |
| **PII protection** | Typed records (`DisruptionAssessment`, `Evidence`, `PassengerMessage.Facts`) omit email, phone, document ID, and names by construction; `Passenger.toString()` strips PII. | Filtering a generic `Map<String, Object>` at serialization time: error-prone and easy to regress. |
| **Parallel rebooking + human case** | On `ABC123`, request `O1` (`PENDING_CONFIRMATION`) first, then open `create_human_case` for `R5` + overnight stay in `LIM` referencing the `requestId`. | Blocking `O1` completely until a human responds: risks losing seat inventory while still respecting `PENDING_CONFIRMATION` semantics. |

---

## 4. Step Classification (Deterministic / AI / Human) & Complex Queries

For ambiguous or free-text passenger inquiries (e.g. *"Will I make my connection with my son, and do I get a hotel?"*), the system decomposes the workflow so an LLM only handles intent extraction and empathetic phrasing—never policy truth or writes:

| Step | Classification | Reason | Risk | Control / Fallback |
|---|---|---|---|---|
| Intent & question parsing (future) | **AI-assisted** | Free-text queries are ambiguous | Misclassified intent (e.g. compensation) | Structured schema output; low-confidence falls back to standard assessment |
| Fetch trip, events, policy, options | **Deterministic** | Exact tool contracts, strict timeouts | Partial data or degraded feeds | `completeness`/`missing_fields` recorded; degraded sources discarded |
| Source reconciliation & timezone math | **Deterministic** | Safety-critical schedule truth | Conflicting healthy feeds | `CONFLICT`/`UNCONFIRMED` -> `UNKNOWN` -> human review |
| Policy rule evaluation & option filtering | **Deterministic** | Auditable, versioned entitlements | Missing facts or expired TTL | `CANNOT_EVALUATE` -> human; expired TTL invalidates options |
| Rebooking request (`create_rebooking_request`) | **Deterministic** | High-impact write, idempotency required | `503` indeterminate state / double booking | Disruption-scoped key, backoff + TTL refresh, persistent `PENDING_UNKNOWN` lock |
| Minor priority, overnight stay, cash comp | **Human** | Policy requires approval or has no rule | Uncovered cost or duty-of-care gap | `create_human_case` with PII-free `Evidence` and `requestId` |
| Passenger explanation phrasing (future) | **AI-assisted** | Clear, empathetic communication | Hallucinated confirmation or missing policy | Grounded strictly on `PassengerMessage.Facts`; `notify_passenger` enforces `policy_ref` (`422`) and forbids "confirmed" on `201` |

---

## 5. Complementary Layer Specification (Frontend / Passenger Experience)

The frontend consumes `GET /api/bookings/{bookingId}/assessment` and triggers `POST /api/bookings/{bookingId}/actions/rebook`:

```text
+-------------------------------------------------------------------+
| Flight LA500 (SCL -> LIM) delayed 160 min (New dep: 17:10 local)  |
| Alert: Connection LA2410 (LIM -> MEX, 19:05) will be missed       |
+-------------------------------------------------------------------+
| Recommended Option (No fee — Policy POL-DELAY-2026-03)            |
| LA2412  LIM -> MEX  |  2026-09-16 08:15  |  4 seats  |  $0 diff   |
| [ Request Rebooking ]                                             |
+-------------------------------------------------------------------+
| Notice: Priority agent review opened (minor in booking +          |
| overnight stay in LIM). Case ref: HC-4471 (SLA: 45 min).          |
+-------------------------------------------------------------------+
```

- **Never displayed to passenger:** internal source names (`LEGACY_MIRROR`), raw rule expressions, `confidence` labels, or unmasked PII.
- **Notification contract (`notify_passenger`):** requires non-blank `policyRef` (adapter returns `422 UNVALIDATED_CONTENT` otherwise). On `201 PENDING_CONFIRMATION`, copy states *"Solicitamos tu cambio..."* and never *"confirmado"*.

---

## 6. Known Limitations & Next Steps

- **In-memory `PENDING_UNKNOWN` lock:** currently stored in `ConcurrentHashMap` inside `ExecuteDisruptionAction`; in production this must be persisted in a transactional store (DB/Redis with fencing) to survive restarts and horizontal scaling.
- **Fixture adapters:** read/write ports use local JSON fixtures and configurable in-memory simulators; production requires HTTP clients with circuit breakers and structured telemetry.
- **Reference data:** airports (`SCL`, `LIM`, `MEX`) and MCT (`60 min`) are configured in `CompanionConfig`; production should source per-airport/international MCTs from an ops reference service.
- **Additional policies:** `POL-REFUND-*` and hotel/overnight policies are not yet queried when `REFUND` or overnight stays arise; both currently fail safe to rejection or human review.
