# Issue #5 administrator bootstrap verification

Status: local verification evidence for commit
`5a704c63355690b9763553792dc32e800bb4ec23`. This does not include deployed
smoke-test evidence or approval for later-Administrator provisioning.

## Automated checks

- Passed:
  `./gradlew test --tests com.company.incidentdesk.web.security.AdministratorBootstrapTest --tests com.company.incidentdesk.web.security.ReporterAccountRegistrationHttpSecurityTest --tests com.company.incidentdesk.web.security.AdministratorBootstrapPostgresTest --console=plain`
  completed successfully. The bootstrap unit suite ran 6 tests and the
  Reporter-registration HTTP suite ran 4 tests; both had zero failures.
- Passed: `./gradlew registrationDatabaseTest --console=plain` ran against the
  local, loopback-only PostgreSQL 18 test container. All 3
  `ReporterAccountRegistrationPostgresTest` tests and all 5
  `AdministratorBootstrapPostgresTest` tests passed with zero skips, failures,
  or errors. Each test created and dropped its own random schema after applying
  the checked-in migrations.
- Passed: `git diff --check`.

## Acceptance status

- `ADM-ACC-06-A`: HTTP tests prove forged role/status values persist as
  Reporter and privileged registration paths are denied before persistence.
- `ADM-ACC-06-B`: unit tests cover absent, both partial-config directions,
  blank/oversized login, short/oversized password, and redaction from errors
  and captured logs.
- `ADM-ACC-06-C` through `ADM-ACC-06-E`: PostgreSQL success, replay,
  pre-existing-admin, concurrent-startup, and rollback tests were verified
  against the explicitly isolated PostgreSQL test database. The
  success and rejection tests assert the `SYSTEM` actor with a null account
  ID; success targets the created account and rejection targets the bootstrap
  application operation.
- `ADM-ACC-06-F`: the runbook matches the checked-in deployment configuration,
  explains first run, secret removal, and startup failure recovery, and states
  that there is no self-service or break-glass account recovery flow. It tells
  operators to preserve database state and obtain an independently reviewed
  procedure. The repository owner accepted this documented limitation in the
  current Codex conversation on 2026-10-19.
- `ADM-ACC-06-G`: remains gated on independent security/architecture approval.

Criteria `ADM-ACC-06-A` through `ADM-ACC-06-F` are checked in the owning
specification. `ADM-ACC-06-G` remains unchecked until its independent approval
gate is satisfied.
