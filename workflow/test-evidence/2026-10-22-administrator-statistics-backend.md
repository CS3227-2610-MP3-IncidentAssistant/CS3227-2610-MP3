# Administrator Statistics Backend Evidence

## Scope

- Branch: `codex/admin-statistics-backend`
- Working tree based on commit: `5c72bb6458dd89274881d1adefa3d9f6139e8b32`
- Criterion: partial backend evidence for `ADM-STAT-01`
- Environment: Windows, Java 25, Gradle 9.6.1, isolated PostgreSQL 18.6 schemas
- No production database or production credentials were used.

## Traceability

| Requirement | Implementation | Evidence |
| --- | --- | --- |
| Administrator-only statistics access | Spring Security `/api/v1/admin/**` boundary plus request-time enabled-Administrator lookup | MVC denied-path tests and isolated PostgreSQL reauthorization test |
| Responder, reporter, category, and inclusive UTC period filters | Validated statistics query and parameterized PostgreSQL predicates | service validation tests and isolated PostgreSQL filter test |
| Privacy-safe anonymous reporting | Reporter predicate requires `anonymous = FALSE`; reporter options include only identities attached to non-anonymous incidents | isolated PostgreSQL anonymous-only reporter test data and option assertions |
| Persisted lifecycle calculations | Resolution-cycle queue, first-assignment, responder-at-resolution, resolution, and reopen snapshots | Flyway V9 migration, responder resolution integration tests, aggregate assertions |
| Empty and zero-denominator behavior | Nullable duration/rate DTO values represent `No data`; counts remain zero | service DTO construction and aggregate tests |
| Queue metric attribution | Time to claim is omitted for responder-filtered scopes rather than attributed to the eventual resolver | isolated PostgreSQL responder-filter assertions |

## Automated verification

### Focused unit and HTTP security tests

Command:

```text
.\gradlew.bat --no-daemon test --tests com.company.incidentdesk.web.admin.AdministratorStatisticsServiceTest --tests com.company.incidentdesk.web.admin.AdministratorStatisticsControllerTest --tests com.company.incidentdesk.web.responder.ResponderIncidentServiceTest
```

Result: passed. The final repository-wide test result below subsumed these
tests with no failures.

### Isolated PostgreSQL tests

Command:

```text
.\gradlew.bat --no-daemon registrationDatabaseTest --tests com.company.incidentdesk.web.admin.AdministratorStatisticsPostgresTest --tests com.company.incidentdesk.web.admin.AdministratorStatisticsMigrationUpgradePostgresTest --tests com.company.incidentdesk.web.responder.ResponderIncidentPostgresTest
```

Result: passed, 24 tests across 3 suites, 0 failures, 0 skipped. The task
created isolated schemas, applied all nine migrations from empty, upgraded an
existing V8 resolution row through V9 with its lifecycle snapshots preserved,
exercised statistics aggregation and responder resolution writes, and dropped
the test schemas afterward.

### Repository-wide verification

Command:

```text
.\gradlew.bat --no-daemon check
```

Result: passed in 57 seconds. Java tests reported 712 tests, 0 failures, and
117 environment-gated skips. Frontend typecheck and lint passed; all 87 React
tests passed. Cross-platform package and JavaFX checks passed.

### Diff hygiene

Command:

```text
git diff --check
```

Result: passed. No whitespace errors were reported.

## Intermediate failures resolved

- The first PostgreSQL filter run exposed a missing SQL whitespace separator
  before `GROUP BY`; the query composition was corrected and rerun.
- The inclusive-period run exposed implicit `Instant` JDBC binding; period
  bounds now bind explicitly as SQL timestamps and the suite passed.

## Remaining evidence

`ADM-STAT-01` remains open. This record covers the backend only. The React
statistics page, interaction/accessibility coverage, browser end-to-end tests,
and deployed HTTPS verification are not part of this change and have not been
claimed.
