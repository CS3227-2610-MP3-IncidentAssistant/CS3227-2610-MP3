# Administrator account tombstone verification

Status: automated local verification passed for the uncommitted working tree
based on commit `06904be9bf54e199c63abfc27e5bd2cd4f64f889`.
Independent review remains outstanding.

## Traceability

| Criterion | Implementation | Evidence |
| --- | --- | --- |
| `ADM-ACC-05-A` | trusted Spring Security principal; transaction-time actor row lock and enabled-Administrator recheck; self and ineligible-target denial; credential, access, session, target, and audit mutation in one transaction | `AdministratorAccountControllerTest`; passing `AdministratorAccountPostgresTest` |
| `ADM-ACC-05-B` | reciprocal actor-first row locking; deadlock/serialization mapping to stable `CONFLICT`; no automatic retry; every explicit call repeats actor authorization | passing `AdministratorAccountPostgresTest` reciprocal concurrency and retry test |

## Automated checks

- Passed:
  `$env:GRADLE_USER_HOME='C:\Users\seani\.gradle'; .\gradlew.bat test --tests com.company.incidentdesk.web.admin.AdministratorAccountControllerTest --tests com.company.incidentdesk.application.account.AccountDeletionServiceTest --console=plain`.
  The focused Java application, HTTP authorization, CSRF, trusted-principal,
  self-denial, success, and conflict-mapping tests completed successfully.
- Passed: `npm.cmd run check` in `frontend/`. TypeScript compilation and ESLint
  passed. The account component tests cover the outlined row action, modal
  destructive confirmation, button and Escape cancellation without a request,
  self-action suppression, retained popup/error state after a conflict,
  successful refresh, and retained-history messaging.
- Passed: `git diff --check`.
- Passed:
  `$env:GRADLE_USER_HOME='C:\Users\seani\.gradle'; .\gradlew.bat registrationDatabaseTest --console=plain`.
  The complete `registrationDatabaseTest` task ran 15 isolated PostgreSQL
  tests with zero failures and zero skips; this included durable tombstoning,
  rollback, reciprocal concurrency, stable conflict mapping, and explicit
  retry reauthorization.
- Passed: `$env:GRADLE_USER_HOME='C:\Users\seani\.gradle'; npm.cmd run build`.
  Frontend type checking and linting passed, Vitest passed 71 tests in 7 files,
  and Gradle completed `build` successfully.

## Open evidence

- Record independent review and deployed/browser verification separately.
