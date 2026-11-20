# AI Usage & Decision Note

## 1. Tools Used & Purpose

An AI coding assistant was used as an engineering accelerator under strict step-by-step direction:
- Scaffolding Java records, port interfaces, and JUnit 5 / AssertJ test suites for each hexagonal layer.
- Verifying timezone arithmetic (`America/Santiago` UTC-3 in September vs. `America/Lima` UTC-5) and edge-case behavior.
- Drafting documentation structure from explicit architectural decisions and test results.

All architecture boundaries, phase specifications, domain assumptions, and acceptance criteria were defined before code generation and verified incrementally via `./mvnw test` and live HTTP calls.

---

## 2. Outputs Accepted, Modified, or Rejected

| Area | AI Output | Action Taken | Validation |
|---|---|---|---|
| **`503` lock lifecycle** | Initial design proposed releasing the `PENDING_UNKNOWN` lock after exhausting retries and escalating to a human case. | **Rejected & redesigned:** releasing the lock would allow a second booking with another option while the upstream `503` outcome is still indeterminate. Lock is now released only on `201`/`409` and remains held until human resolution. | Added explicit test: `503×3 -> human case -> attempt option O2 -> BLOCKED_PENDING_RESOLUTION` with zero extra rebooking calls. |
| **Evidence & message facts typing** | Initial design used untyped `Map<String, Object>` for `create_human_case` evidence and `PassengerMessage` facts. | **Rejected & tightened:** replaced with typed records (`Evidence`, `PassengerMessage.Facts`) that omit email, phone, document ID, and names by construction. | Verified via Jackson 3 serialization tests asserting zero PII in `DisruptionAssessment`, `Evidence`, and `PassengerMessage`. |
| **Fixture adapter bugs** | Early scaffolding referenced `operational_events.json` instead of `get_operational_events.json` and had a typo (`effectiveDFrom`). | **Caught & fixed** during code inspection and smoke testing before building downstream layers. | Verified all 4 JSON fixtures load cleanly in `EvaluateDisruptionTest` and live `curl` run. |
| **TTL expiration during retries** | Standard retry loop with `Thread.sleep`. | **Modified:** injected `Clock` and `Consumer<Duration> sleeper` in `RetryPolicy` so tests advance a `MutableClock` past the 120s TTL without real sleeps. | Verified in `expiredTtlBetweenRetriesRequeriesOptionsBeforeRetrying` (confirms options re-query and re-validation). |
| **Domain layers (Parts 3–5)** | `SourceReconciler`, `ConnectionAnalyzer`, `OptionValidator` implementations. | **Accepted** after reviewing edge cases (overnight rollover, `CONFLICT` vs. degraded, ignoring undefined `confidence`). | 24 unit tests in `domain/` passing (`./mvnw test`). |

---

## 3. Decisions Made Personally

- **Hexagonal layering:** strict inward dependency direction (`adapters -> application -> domain`) with zero Spring/Jackson imports in `domain/` and a single wiring class in `config/`.
- **Deterministic-first product design:** kept all eligibility, reconciliation, timezone, TTL, and idempotency logic 100% deterministic; scoped any future LLM usage strictly to intent parsing and phrasing grounded on validated facts.
- **Parallel rebooking + human review for `ABC123`:** request `O1` (`PENDING_CONFIRMATION`) first, then open `create_human_case` for `R5` (minor) and the uncovered overnight stay in `LIM` with the `requestId` attached.
- **Fail-closed safety defaults:** `CONFLICT`/`UNCONFIRMED` -> `UNKNOWN` (never `MISSED`), missing rule facts -> `CANNOT_EVALUATE` -> human review, missing `policy_ref` -> `422`, and never claiming "confirmed" on `201 PENDING_CONFIRMATION`.

---

## 4. Information Never Shared with a Model

- Real passenger or customer PII (emails, phone numbers, national IDs, passport numbers, PNR records from production).
- Credentials, API keys, SSH private keys, OAuth tokens, or internal network endpoints.
- Proprietary company source code, confidential client specifications, or internal security/compliance policies.
- Only synthetic challenge fixtures (`María Silva`, `ABC123`) and public technical specifications were used in this workspace.
