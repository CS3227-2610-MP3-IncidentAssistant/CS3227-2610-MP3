# Administrator Insight Sample-Threshold Verification — 2026-10-23

## Scope

- Approved specification commit: `8549228`
- Implementation commit: the commit containing this evidence, based on
  specification commit `8549228`
- Criteria partially evidenced: `AI-ADM-QUERY-03`, `AI-ADM-SAFE-02`, and
  `AI-ADM-SAFE-07`
- Environment: local Windows development workspace and the explicitly isolated
  PostgreSQL test database configured by `.env.test.local`

The change removes the five-cycle responder eligibility gate. A responder with
one qualifying completed cycle remains in rankings. A valid selected responder
with no qualifying completed cycles remains in the comparison with sample size
zero and an unavailable duration/rate value. The HTTP/React response no longer
contains or renders eligible/excluded counters, and the UI states the period,
row-limit, selection-cardinality, sample, and zero-denominator rules.

## Automated evidence

1. Focused React interaction test:

   ```powershell
   cd frontend
   npm test -- src/admin/AdministratorStatistics.test.tsx
   ```

   Result: passed, 1 file and 7 tests, with no failures.

2. Focused Java service and controller tests:

   ```powershell
   .\gradlew.bat --gradle-user-home C:\Users\seani\.gradle test `
     --tests "com.company.incidentdesk.web.admin.AdministratorInsightServiceTest" `
     --tests "com.company.incidentdesk.web.admin.AdministratorInsightControllerTest" `
     --no-daemon
   ```

   Result: passed. The renderer produces `Not available` and a zero sample for
   a selected responder with no qualifying cycles, while the controller returns
   the revised deterministic response shape.

3. Focused isolated PostgreSQL integration test:

   ```powershell
   .\gradlew.bat --gradle-user-home C:\Users\seani\.gradle `
     registrationDatabaseTest `
     --tests "com.company.incidentdesk.web.admin.AdministratorInsightPostgresTest" `
     --no-daemon -PfrontendSkipBuild=true
   ```

   Result: passed, 3 tests with no failures, errors, or skips. The suite proves
   inclusion of a one-cycle responder and retention of a selected zero-cycle
   responder using persisted lifecycle data.

4. Repository-wide verification:

   ```powershell
   .\gradlew.bat --gradle-user-home C:\Users\seani\.gradle check --no-daemon
   ```

   Result: passed. Java reported 800 tests, 0 failures, 0 errors, and 133
   environment-dependent skips. Frontend type checking and linting passed, and
   all 135 frontend tests passed across 14 files. Packaging and JavaFX platform
   verification also passed.

5. Diff hygiene:

   ```powershell
   git diff --check
   ```

   Result: passed.

## Development-time corrections

Initial focused invocations did not provide acceptance evidence: one used an
unwritable default Gradle cache, one supplied Vitest a path relative to the
wrong directory, and later compilation exposed stale constructor fixtures and
required explicit numeric boxing. These invocation and compilation issues were
corrected before the successful commands above. The first PostgreSQL run also
rejected an invalid comparison-plan fixture containing ranking-only fields; the
fixture was corrected to the closed plan contract before the passing run.

## Remaining evidence

The amendment-specific behavior is automatically verified, but the broader
unchecked `AI-ADM-QUERY-03`, `AI-ADM-SAFE-02`, and `AI-ADM-SAFE-07` statements
still require their complete metric, ordering/tie, division-by-zero,
constraint-disclosure, manual/deployed, or independent-review evidence before
they may be checked. No criterion is marked complete by this focused change.
