# Administrator AI Insights Verification — 2026-10-23

## Scope

Working tree on branch `codex/admin-ai-insights-spec`, based on approved
specification commit `d403ee5`. Verification covers the closed provider plan,
trusted orchestration, deterministic typed analytics, migration V11, escaped
React output, and the four code-quality findings approved by the repository
user. No production provider credential or production data was used.

## Automated evidence

- `npm.cmd run check` from `frontend/`: passed. TypeScript typecheck and ESLint
  reported no errors; Vitest reported 14 files and 135 tests passed.
- `.\gradlew.bat check --no-daemon`: passed in 2m 3s. The Java suite reported
  796 tests, 0 failures, 0 errors, and 132 skipped environment-dependent tests;
  frontend checks, production frontend build, JavaFX platform checks, and
  packaged-JAR verification also passed.
- `.\gradlew.bat registrationDatabaseTest --no-daemon
  -PfrontendSkipBuild=true`: passed in 1m 23s against the configured explicitly
  isolated PostgreSQL test database. It reported 63 tests, 0 failures, 0
  errors, and 0 skipped tests.
- Focused provider-payload, controller, service, parser, period, and gateway
  suites passed through Gradle. The isolated PostgreSQL evidence includes
  V4-to-V11 migration upgrade, empty-schema V11 migration, persisted responder
  ranking, category trend, backlog snapshot, version-separated SLO comparison,
  and stale-administrator reauthorization.
- After correcting the disabled-provider path, `AdministratorInsightServiceTest`
  and `AdministratorInsightControllerTest` passed through a focused Gradle run,
  proving that a missing AI gateway maps to `503 AI_UNAVAILABLE` rather than the
  non-enumerating authorization `404`. A subsequent `\.\gradlew.bat check
  --no-daemon` passed in 1m 4s, including 135 frontend tests and the full Java,
  packaging, and JavaFX-platform verification tasks.

## Security and privacy observations

- Provider planning input is constructed from exactly the validated question
  and application-generated request instant. Structured categories and
  responder identifiers remain application-side.
- The provider response is accepted only through the strict closed plan parser.
  An exhaustive typed registry dispatches the plan; no provider-authored SQL,
  URL, class, repository method, name, or factual value is executed or shown.
- Analytics execute in a read-only, repeatable-read transaction with a bounded
  timeout. Responder comparisons suppress samples below five.
- React tests prove result labels are rendered as escaped text. Shared CSRF and
  stale-session retry behavior is used for the insight POST.
- V11 expands the existing AI usage feature constraint without editing a
  released migration. Existing content-free gateway audit and usage controls
  apply under `ADMINISTRATOR_INSIGHTS`.

## Remaining evidence

- `AI-ADM-AUTH-01`, `AI-ADM-QUERY-03`, `AI-ADM-QUERY-04`,
  `AI-ADM-QUERY-06`, `AI-ADM-SAFE-01`, `AI-ADM-SAFE-02`, and
  `AI-ADM-SAFE-04..06` remain open for their full matrices: every role/stale
  HTTP path, ranking ties and zero denominators, every metric/empty bound,
  concurrent snapshot behavior, anonymous/log/audit capture, provider failure
  matrix, and load testing.
- Manual accessibility/responsive review, a production-configured provider
  smoke test, sanitized production observability review, and release approval
  remain pending.
