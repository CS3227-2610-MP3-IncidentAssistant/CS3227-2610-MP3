# Administrator SLO configuration verification

- Date: 2026-10-21
- Branch: `complete-administrator-migration`
- Baseline commit: `7c127a2`
- Backend implementation commit: `cb080e3`
- React implementation commit: `d82cb28`
- Scope: `ADM-SLO-01-A` through `ADM-SLO-01-D`
- Evidence state: `ADM-SLO-01-A` through `ADM-SLO-01-D` verified in the working
  tree

## Traceability

| Criterion | Implementation | Evidence state |
| --- | --- | --- |
| `ADM-SLO-01-A` | trusted Spring Security principal plus transaction-time enabled-Administrator recheck; current and chronological history projection | MVC/service and isolated PostgreSQL enabled/disabled/demoted tests pass; API test proves internal `changedBy` is omitted |
| `ADM-SLO-01-B` | append-only target version and secret-free audit insert in one `TransactionTemplate` transaction using application UTC and generated identifiers | unit/security and isolated PostgreSQL atomicity tests pass |
| `ADM-SLO-01-C` | field validation, unchanged no-op, expected-version conflict, per-category PostgreSQL advisory transaction lock, rollback mapping | unit/security and isolated PostgreSQL rollback/concurrency tests pass |
| `ADM-SLO-01-D` | labelled category selector, distinct effective-target card, editable form, paginated history, preserved recoverable-error input, and loading, empty, validation, success, conflict, duplicate-submit, and retry behavior | nine focused React tests and the full React suite pass; product owner approved the visual presentation and implementation |

## Passing automated checks

- `$env:GRADLE_USER_HOME='C:\Users\seani\.gradle'; .\gradlew.bat test --tests com.company.incidentdesk.web.admin.AdministratorSloServiceTest --tests com.company.incidentdesk.web.admin.AdministratorSloControllerTest -PfrontendSkipBuild=true --console=plain`
  - Passed 6 tests with zero failures.
- `$env:GRADLE_USER_HOME='C:\Users\seani\.gradle'; .\gradlew.bat test --tests com.company.incidentdesk.web.admin.AdministratorSloSpringWiringTest --tests com.company.incidentdesk.web.admin.AdministratorSloServiceTest --tests com.company.incidentdesk.web.admin.AdministratorSloControllerTest -PfrontendSkipBuild=true --console=plain`
  - `BUILD SUCCESSFUL`; the Spring wiring regression test proves repository
    exception translation can create its AOP proxy, alongside the service and
    controller tests.
- `$env:GRADLE_USER_HOME='C:\Users\seani\.gradle'; .\gradlew.bat bootRun --console=plain`
  - Started the complete Spring application successfully on port 8080, validated
    all eight Flyway migrations, and confirmed schema version 8 was current. The
    process was then stopped intentionally after the startup probe.
- `$env:GRADLE_USER_HOME='C:\Users\seani\.gradle'; .\gradlew.bat test -PfrontendSkipBuild=true --console=plain`
  - `BUILD SUCCESSFUL`; complete Java unit/security task was up to date after the focused passing run.
- `npm.cmd test -- --run src/admin/AdministratorSloConfiguration.test.tsx`
  - Passed 9 tests with zero failures, including explicit loading, empty,
    validation, conflict, duplicate-submission, retry, category-selection,
    effective-target, and history-pagination coverage.
- `npm.cmd run check`
  - Typecheck, ESLint, and all 83 React tests passed across 9 files. This includes
    category selection, distinct effective-target presentation, unchanged-save
    disabling, newest-first history pagination, and the shared pagination paths
    used by the Administrator dashboard and account directory.

The first full check after adding the pending-save test failed TypeScript
compilation because its resolver placeholder inferred an `undefined` return
type. The resolver was explicitly typed as returning `void`; the full check was
then rerun successfully as recorded above. No production behavior changed in
that correction.
- `npm.cmd run build`
  - Production TypeScript/Vite build passed.
- `git diff --check`
  - Passed; only the existing Git line-ending notice for `AdministratorDashboardQuery.java` was printed.
- `$env:GRADLE_USER_HOME='C:\Users\seani\.gradle'; .\gradlew.bat registrationDatabaseTest --tests com.company.incidentdesk.web.admin.AdministratorSloPostgresTest -PfrontendSkipBuild=true --console=plain`
  - Passed 3 isolated PostgreSQL tests with zero failures after applying all
    eight checked-in Flyway migrations to a random test schema.

## PostgreSQL correction history

The isolated `AdministratorSloPostgresTest` applies all eight checked-in Flyway
migrations to a random test schema and covers reauthorization, append/audit
atomicity, unchanged and stale requests, injected audit failure rollback, and
two concurrent changes from the same expected version.

Two executions of
`$env:GRADLE_USER_HOME='C:\Users\seani\.gradle'; .\gradlew.bat registrationDatabaseTest --tests com.company.incidentdesk.web.admin.AdministratorSloPostgresTest -PfrontendSkipBuild=true --console=plain`
failed because the new store mapped PostgreSQL SQL-binding errors to the
expected safe `PERSISTENCE_FAILURE` result. Inspection identified and corrected
both causes: PostgreSQL's `void` advisory-lock result is now executed through a
prepared statement rather than converted to `Long`, and the nullable prior
version audit parameter is explicitly cast to `text` inside `jsonb_build_object`.

The corrected suite was rerun successfully. It proves enabled-Administrator
reauthorization, disabled/demoted denial, append-only target and audit writes,
rollback after an injected duplicate-audit failure, unchanged and stale
handling, and exactly one winner for concurrent changes from one expected
version. No production data was read or modified; each execution used and
removed its random isolated schema.

## Spring wiring correction history

The first `bootRun` attempt failed while Spring created the repository exception-
translation proxy because `JdbcAdministratorSloStore` was declared `final`.
Removing that modifier made the package-private repository subclassable, matching
the existing persistence convention. `AdministratorSloSpringWiringTest` now
guards the proxy requirement, and the subsequent full `bootRun` startup passed.

## Manual and release evidence

- Visual presentation and independent implementation review: approved by the repository user acting as product
  owner in the 2026-10-21 Codex conversation. The review covers the selected-
  category layout and the current-effective-target card containing its header
  and three metric cards, as well as the SLO implementation, tests, unsafe-
  shortcut review, secret handling, and documentation. See
  [the dated UI review record](../reviews/2026-10-21-administrator-slo-ui-review.md).
- Exact implementation commits are recorded above; this evidence and its review
  record are committed immediately afterward as the documentation closeout.
- Deployed HTTPS smoke verification remains a later slice/release gate; it is
  not part of `ADM-SLO-01-D`.

## Code-quality follow-up

- `CQ-1` (`R11`, required) resolved: the HTTP projection now uses
  `SloVersionView`, which omits the persistence-only `changedBy` account UUID;
  the MVC test asserts that field is absent from serialized JSON.
- `CQ-2` (`R1`/`R11`, recommended) resolved: the transaction method now
  coordinates focused category-lock, current-version, comparison, append, and
  audit helpers without changing the transaction boundary.
- Post-change review found no remaining code-quality findings in the modified
  SLO service/store boundary.
