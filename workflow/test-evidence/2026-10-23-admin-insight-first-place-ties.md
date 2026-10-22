# Administrator Insight First-Place Tie Evidence

- Date: 2026-10-23 (Asia/Singapore)
- Base commit: `1da960c7667d12a68485ff0283ca57687b7d0954`
- Scope: uncommitted working-tree change preserving and displaying all
  first-place ranking ties within the approved ten-row response ceiling

## Automated verification

1. Focused backend verification:

   `./gradlew.bat test --tests com.company.incidentdesk.web.admin.JdbcAdministratorInsightAnalyticsTest --tests com.company.incidentdesk.web.admin.AdministratorInsightServiceTest -PfrontendSkipBuild=true --no-daemon`

   Result: passed. The ranking test proves a one-row request retains both
   equal first-place values in deterministic label order and does not expand a
   cutoff for a tie below first place.

2. Focused React verification:

   `npm.cmd test -- AdministratorStatistics.test.tsx`

   Result: 7 tests passed. The insight table rendered both equal first-place
   rows, escaped untrusted labels, retained sample sizes, and disclosed that
   first-place ties are shown together.

3. Repository verification:

   `./gradlew.bat check --no-daemon`

   Result: passed with 802 Java tests (0 failures, 0 errors, 133
   environment-dependent skips) and 135 frontend tests. Type checking, lint,
   production frontend build, packaged JAR checks, and JavaFX platform checks
   also passed.

4. Isolated PostgreSQL verification:

   `./gradlew.bat registrationDatabaseTest --no-daemon -PfrontendSkipBuild=true`

   Result: 64 tests passed with 0 failures, 0 errors, and 0 skips. The suite
   used the repository's explicitly isolated test database configuration and
   did not access production data.

5. Diff validation:

   `git diff --check`

   Result: passed; Git emitted only line-ending conversion warnings.

## Criterion status

- `AI-ADM-QUERY-03` remains open. This evidence proves first-place tie
  preservation, stable ordering, and browser display, but the criterion also
  requires complete evidence for every ranking metric, division-by-zero,
  sample counts, and no-qualifying-data behavior.
- No migration, persistence format, authorization rule, audit contract, SLO
  calculation, AI provider payload, or concurrency behavior changed.
- Manual/deployed verification remains pending.
