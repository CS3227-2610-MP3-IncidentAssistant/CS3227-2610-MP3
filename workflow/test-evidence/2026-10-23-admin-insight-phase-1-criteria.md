# Administrator AI insight criteria — automated evidence — 2026-10-23

## Scope

Automated evidence for the `AI-ADM-*` criteria that required only test coverage,
with no new tooling and no deployed environment:

- `AI-ADM-AUTH-01` — role, authentication, CSRF, and stale-Administrator denial
  with no provider or analytics call.
- `AI-ADM-QUERY-03` — ranking attribution, direction, ties, stable ordering,
  sample counts, response ceiling, and no-qualifying-data behaviour.
- `AI-ADM-QUERY-04` — category trend, period comparison, backlog snapshot, and
  operational overview metric definitions, bounds, row limits, and empty results.
- `AI-ADM-QUERY-06` — read-consistent snapshot, application-generated instant,
  non-blocking concurrent incident mutation, and fail-closed stale responder.
- `AI-ADM-SAFE-01` — anonymous incidents in permitted aggregates only, with no
  reporter identity reachable through grouping, filtering, or result labels.
- `AI-ADM-SAFE-02` — comparative responder output discloses sample sizes and
  ties, states the metric and exact period, applies no hidden minimum-sample
  threshold, and carries the operational-indicator notice.
- `AI-ADM-SAFE-04` — content-free logs, a bounded response envelope, and one
  content-free audit outcome plus a bounded ledger row.
- `AI-ADM-SAFE-05` — the full provider, database, cancellation, and timeout
  failure matrix with the non-AI statistics workflow preserved.

Commit: working tree based on `b48e2a8` (`main`).
Environment: Windows 11, Gradle 9.6.1, repository-local `GRADLE_USER_HOME=.gradle`,
isolated Neon PostgreSQL test branch supplied through `.env.test.local`.

## Tests added or extended

| File | Criteria | Purpose |
| --- | --- | --- |
| `src/test/java/.../web/admin/AdministratorInsightSecurityTest.java` (new) | `AI-ADM-AUTH-01` | MVC denial sweep: unauthenticated, Reporter, Responder, missing CSRF, and database-disabled Administrator each denied with zero provider and zero analytics calls; an enabled Administrator reaches both exactly once. |
| `src/test/java/.../web/admin/JdbcAdministratorInsightAnalyticsTest.java` (extended) | `AI-ADM-QUERY-03` | Ranking direction both ways, case-insensitive stable secondary ordering, preserved sample sizes, first-place ties inside and outside the limit, the ten-row ceiling under a fourteen-way first-place tie, and an empty ranking for no qualifying data. |
| `src/test/java/.../web/admin/AdministratorInsightPostgresTest.java` (extended) | `AI-ADM-QUERY-04`, `AI-ADM-QUERY-06`, `AI-ADM-SAFE-01` | Adjacent calendar period comparison, fixed bounded overview composition, category ranking without fabricated zero rows, empty-population backlog, year-long trend within the bounded series ceiling, concurrent-insert visibility and non-blocking behaviour, anonymous-incident aggregation without identity exposure, rejected `REPORTER` grouping, and fail-closed stale responder. |
| `src/test/java/.../web/admin/AdministratorInsightResilienceTest.java` (new) | `AI-ADM-SAFE-05` | Parameterized mapping of all seven provider failure categories plus malformed output, unsupported plan, circuit-open, database failure before and after provider disclosure, query timeout with no provider retry, cancellation, and an empty result as a successful `no qualifying data` answer. |
| `src/test/java/.../web/admin/AdministratorInsightObservabilityTest.java` (new) | `AI-ADM-SAFE-04` | Logback capture proving an admitted request, a rejected question, and a failed request write no question, plan, metric, display name, or provider body; the gateway request object withholds its content from string conversion; the response envelope carries only bounded application-owned fields. |
| `src/test/java/.../infrastructure/soclaas/SoclaasFailures.java` (new) | supporting | Test-only factory for the safe provider outcome categories, so resilience tests simulate each failure without widening production constructor visibility. |
| `src/test/java/.../infrastructure/soclaas/AdministratorInsightAuditPostgresTest.java` (new) | `AI-ADM-SAFE-04` | Isolated PostgreSQL: one admission audit and one outcome audit per admitted insight request, naming only feature and outcome category with no question, plan, result, responder, metric, or period text; `ADMINISTRATOR` actor role and `AI_REQUEST` target type; one bounded ledger row; and no ledger column capable of holding insight content. A failed outcome is audited exactly once with the failure category and no provider body. |
| `frontend/src/admin/AdministratorStatistics.test.tsx` (extended) | `AI-ADM-SAFE-02` | Both first-place ties displayed together under a one-row request, per-row sample sizes including a one-cycle and an explicit zero-cycle responder, `Not available` for a zero denominator, the metric label, the exact inclusive/exclusive period in Singapore time, the operational-indicator notice, and the absence of any exclusion or minimum-sample message. |
| `src/test/java/.../infrastructure/soclaas/AiGatewayPostgresFixture.java` (repaired) | supporting | `TRUNCATE TABLE accounts CASCADE` in place of `DELETE FROM accounts`, plus real schema isolation (see **Isolation defect**). |
| `build.gradle` (changed) | supporting | Adds `AiGatewayControlPostgresTest` and `AdministratorInsightAuditPostgresTest` to the `registrationDatabaseTest` filter. |

No production source file was changed. No authorization, validation, audit,
anonymity, or persistence safeguard was modified or weakened.

### Incidental repair: 16 AI gateway tests were never executing

`AiGatewayControlPostgresTest` was absent from the `registrationDatabaseTest`
filter and is gated on `INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED`, which the
plain `test` task does not set. It therefore reported 16 of 16 tests **skipped**
in `./gradlew.bat check` and ran in no other task, so its assertions had not
executed for some time.

Adding it to the filter revealed that its `@BeforeEach` cleanup still used
`DELETE FROM accounts`, which a later migration broke by adding the
`responder_promotion_requests.decided_by` foreign key. Every test in the class
failed on cleanup. The fixture now uses `TRUNCATE TABLE accounts CASCADE`, so it
does not rot again the next time a migration adds a dependant table.

Effect: `registrationDatabaseTest` went from 74 to 94 tests with 0 skipped, and
16 previously dormant gateway tests — including the content-free AI audit
assertion relied on below — now actually run and pass.

## Commands and results

All commands ran from the repository root with `GRADLE_USER_HOME=.gradle`.

- `./gradlew.bat --no-daemon test --tests
  "com.company.incidentdesk.web.admin.AdministratorInsightSecurityTest" --tests
  "com.company.incidentdesk.web.admin.JdbcAdministratorInsightAnalyticsTest"
  -PfrontendSkipBuild=true`: **passed**.
- `./gradlew.bat --no-daemon test --tests
  "com.company.incidentdesk.web.admin.AdministratorInsightResilienceTest"
  -PfrontendSkipBuild=true`: **passed**.
- `./gradlew.bat --no-daemon test --tests
  "com.company.incidentdesk.web.admin.AdministratorInsightObservabilityTest"
  -PfrontendSkipBuild=true`: **passed**.
- `./gradlew.bat --no-daemon registrationDatabaseTest --tests
  "com.company.incidentdesk.web.admin.AdministratorInsightPostgresTest"
  -PfrontendSkipBuild=true`: **passed**, 14 tests, 0 skipped, 0 failures,
  0 errors.
- `./gradlew.bat --no-daemon registrationDatabaseTest --tests
  "com.company.incidentdesk.infrastructure.soclaas.AdministratorInsightAuditPostgresTest"
  -PfrontendSkipBuild=true`: **passed**, 2 tests.
- `npm test -- --run AdministratorStatistics.test.tsx` from `frontend/`:
  **passed**, 1 file, 8 tests.
- `./gradlew.bat --no-daemon registrationDatabaseTest -PfrontendSkipBuild=true`:
  **passed**, 94 isolated PostgreSQL tests, 0 skipped, 0 failures, 0 errors
  (74 before the gateway-fixture work described above).
- `./gradlew.bat --no-daemon check`: **passed**. 854 Java tests, 0 failures,
  0 errors, 149 environment-dependent skips (live-provider and
  database-confirmation suites), plus frontend typecheck, lint, and 150 frontend
  tests across 17 files.
- `git diff --check`: **passed** (one expected LF/CRLF advisory only).

## Intermediate failures and their resolution

Recorded because they affected test correctness rather than implementation:

1. `periodComparisonReportsBothAdjacentIntervals` first used two identical
   `ROLLING_7_DAYS` windows. The resolver correctly rejected them as
   non-adjacent, because equal rolling windows overlap exactly. The test now
   compares `THIS_UTC_MONTH` with `PREVIOUS_UTC_MONTH`. The implementation was
   right; the test expectation was wrong.
2. `categoryRankingOmits...` expected a lower-cased `it` label. `categoryLabel`
   deliberately preserves `IT` as an initialism. The expectation was corrected.
3. An `ADMINISTRATOR_INSIGHTS` admission failed with `AI_UNAVAILABLE` despite
   healthy observed control state. The cause was **not** in the application: the
   shared `AiGatewayPostgresFixture` sets its isolated schema only through a
   `currentSchema=` JDBC URL parameter, which was being ignored, so the suite ran
   against the shared `public` schema. That schema is migrated only to V8, so the
   V11 widening of the `ai_usage_requests_feature_check` constraint was absent and
   the insert violated the old three-value constraint. `AiUsageLedger` deliberately
   converts any JDBC `RuntimeException` into `AI_UNAVAILABLE` so bound values and
   server diagnostics never escape, which is correct behaviour but hid the cause.
   The fixture now pins the schema with `HikariDataSource.setSchema` and asserts
   `current_schema()` matches before migrating, so an isolation failure stops the
   run instead of mutating shared tables. See **Isolation defect** below.
4. `AdministratorInsightObservabilityTest` initially forced the root logger to
   `TRACE` without restoring it, which made
   `ReporterAccountRegistrationHttpSecurityTest` observe a plaintext password in
   captured output and fail. The suite now captures at `DEBUG` and restores the
   previous level in `@AfterEach`. This was a defect in the new test, not in the
   application; it is recorded here because it briefly produced a misleading
   secret-disclosure failure in an unrelated security suite.

## Findings

- The insight request path contains no logging statement at all, so
  `AI-ADM-SAFE-04` holds by construction as well as by capture.
- AI audit rows are written by the shared gateway control using feature
  `ADMINISTRATOR_INSIGHTS` (the `AiUsageLedger.Feature` value) rather than the
  `ADMIN_AI_INSIGHTS` identifier named illustratively in
  `admin-ai-insights.md` section 9. The row carries only feature, outcome
  category, actor, role, and request correlation identifier, with no question,
  plan, result, or provider body, which satisfies the criterion. Section 9 says
  the audit "may include" that identifier, so no specification change is
  required; the naming difference is noted for reviewers.
- Metric labels are bounded enums only (`feature`, `outcome`) on the single
  `incidentdesk.ai.requests` counter.

## Isolation defect found in the shared AI gateway fixture

`AiGatewayPostgresFixture` created an isolated schema and migrated it, but its
pooled connections were not actually using it: the schema was requested only via
a `currentSchema=` URL parameter that the driver did not honour here, so
`current_schema()` returned `public`. Every test in the four suites built on this
fixture therefore read and wrote the shared `public` tables, and
`flyway_schema_history` in that schema stops at version 8.

Two consequences:

- The suites were not isolated, contrary to the repository rule that tests must
  use isolated databases. They were mutating shared tables (`accounts`,
  `audit_events`, `ai_usage_requests`, `ai_usage_control`).
- Behaviour that depends on a migration after V8 could not work there, which is
  why an `ADMINISTRATOR_INSIGHTS` admission failed until the schema was fixed.

The fixture now calls `HikariDataSource.setSchema(SCHEMA)` and verifies
`current_schema()` before Flyway runs, failing fast with the observed schema
name. The repository already had a hardened equivalent in
`PostgresTestDataSource.isolated`, which performs the same verification; the
gateway fixture had diverged from it.

Mutation check: injecting a `'question'` field into the audit `details` JSON made
`administratorInsightAuditAndLedgerRowsAreContentFreeAndBounded` fail, and
removing the operational-indicator sentence from `AdministratorStatistics.tsx`
made the new React test fail. Both assertions are therefore load-bearing rather
than vacuous. The production files were restored immediately afterwards.

## Remaining evidence for this feature

Not covered by this record and still outstanding:

- `AI-ADM-SAFE-07` needs UI and response review confirming that *every*
  result-affecting product constraint is disclosed. The constraints currently
  shown are asserted by the React tests, but confirming the list is complete is a
  human judgement over the product surface and is not claimed here.
- `AI-ADM-SAFE-06` needs load and concurrency testing for one provider call per
  user action and continued availability of normal authenticated endpoints.
- Browser end-to-end, accessibility scanning, responsive verification, and
  deployed HTTPS verification remain pending; they require the Playwright and
  axe tooling and the isolated deployed environment that are not yet approved.
- Independent human review and approval of this record remain pending. No
  approval is claimed or recorded on any person's behalf.
