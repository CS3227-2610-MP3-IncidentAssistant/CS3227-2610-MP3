# Issue #5 administrator bootstrap verification

Status: provisional local evidence; not release evidence because the changes
remain uncommitted. Worktree base: `5a7a9216a5a1275948d40c7b9776ee87f1e5daed`.

## Automated checks

- Passed:
  `./gradlew test --tests com.company.incidentdesk.web.security.AdministratorBootstrapTest --tests com.company.incidentdesk.web.security.ReporterAccountRegistrationHttpSecurityTest --tests com.company.incidentdesk.web.security.AdministratorBootstrapPostgresTest --console=plain`
  completed successfully. The bootstrap unit suite ran 6 tests and the
  Reporter-registration HTTP suite ran 4 tests; both had zero failures.
- Skipped: all 5 `AdministratorBootstrapPostgresTest` methods were skipped
  because `INCIDENT_DESK_TEST_DATABASE_URL` was not configured in this local
  environment. The test harness additionally requires
  `INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED=true` before creating its
  isolated schema.
- Unavailable: `./gradlew registrationDatabaseTest --console=plain` stopped
  before connecting because the required isolated database URL and explicit
  isolation confirmation were not configured. No database schema was touched.
- Passed: `git diff --check`.

## Acceptance status

- `ADM-ACC-06-A`: HTTP tests prove forged role/status values persist as
  Reporter and privileged registration paths are denied before persistence.
- `ADM-ACC-06-B`: unit tests cover absent, both partial-config directions,
  blank/oversized login, short/oversized password, and redaction from errors
  and captured logs.
- `ADM-ACC-06-C` through `ADM-ACC-06-E`: PostgreSQL success, replay,
  pre-existing-admin, concurrent-startup, and rollback tests are written but
  remain unverified until run against the explicitly isolated PostgreSQL test
  database. The success and rejection tests assert the `SYSTEM` actor with a
  null account ID; success targets the created account and rejection targets
  the bootstrap application operation.
- `ADM-ACC-06-F`: the runbook matches the checked-in deployment configuration,
  explains first run, secret removal, and startup failure recovery, and states
  that there is no self-service or break-glass account recovery flow. It tells
  operators to preserve database state and obtain an independently reviewed
  procedure. The repository owner accepted this documented limitation in the
  current Codex conversation on 2026-10-19. Final criterion evidence remains
  provisional until this worktree is reviewed and committed.
- `ADM-ACC-06-G`: remains gated on independent security/architecture approval.

The acceptance checkboxes remain unticked until all required evidence and
approvals are available.
