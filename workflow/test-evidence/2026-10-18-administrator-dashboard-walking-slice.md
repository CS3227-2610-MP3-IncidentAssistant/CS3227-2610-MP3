# Administrator Dashboard Walking-Slice Evidence

- Date: 2026-10-18 (Asia/Singapore)
- Revision: uncommitted working tree based on `bb47c73`
- Environment: Windows development workspace, Java 25, Node/Vitest/jsdom
- Scope: reviewed administrator authentication/dashboard foundation only; not
  the complete administrator feature-parity slice

## Passed

- `npm run check` from `frontend/`
  - TypeScript build: passed
  - ESLint with zero warnings: passed
  - Vitest: 1 file, 4 tests passed
- `.\gradlew.bat -g build\gradle-user-home --no-daemon compileJava compileTestJava`
  - Java main and test compilation: passed
- `.\gradlew.bat -g build\gradle-user-home --no-daemon test --tests com.company.incidentdesk.HealthEndpointIntegrationTest`
  - Spring Boot health, static React artifact, hidden actuator route, and
    deny-by-default unknown API checks: 4 tests passed
- `git diff --check`
  - Passed; Git reported only expected LF-to-CRLF working-copy notices.
- `\.\gradlew.bat test --tests com.company.incidentdesk.web.security.AdministratorBootstrapTest --tests com.company.incidentdesk.infrastructure.neon.NeonConfigurationTest`
  - Passed: 6 focused configuration/bootstrap tests.
- `\.\gradlew.bat bootRun` with the git-ignored local Neon configuration and
  an alternate local port
  - Loaded `.env.local`, selected the database-backed authentication service,
    connected to Neon, rejected the invalid bootstrap password with its
    variable name and allowed length, stated that no account was created, and
    exited non-zero without printing the configured value.
- `\.\gradlew.bat bootRun` against the configured Neon branch after correcting
  the bootstrap input and disabling the blocking VPN
  - Flyway validated and applied V1 and V2 to an empty schema before bootstrap.
  - The first failed bootstrap write rolled back atomically when PostgreSQL
    rejected an untyped Java `Instant`; after the explicit JDBC timestamp fix,
    bootstrap succeeded without logging credentials.
  - A second startup validated schema version 2 and required no migration.
- Sanitized HTTP smoke test against the local backend on port 18080
  - Anonymous session lookup: HTTP 200.
  - Administrator login with CSRF and a private cookie session: HTTP 204.
  - Authenticated session: role `ADMINISTRATOR`.
  - Protected administrator dashboard: HTTP 200, zero incident rows and three
    SLO category rows.

## Unavailable or not yet run

- PostgreSQL migration, round-trip, transaction, and concurrency suites:
  unavailable because no Docker executable/engine is installed in this
  environment and no production or shared development database may be used for
  tests.
- Full Java test suite, browser end-to-end, automated accessibility, deployed
  HTTPS smoke, and independent security review: not run for this partial slice.

## Acceptance status

No administrator slice acceptance checkbox is ticked by this evidence. The
tests prove only the initial protected walking slice and the review fixes; the
remaining destinations, mutations, provider integration, database evidence,
and deployment gates remain open.
