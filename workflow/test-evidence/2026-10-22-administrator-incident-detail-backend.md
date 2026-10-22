# Administrator Incident Detail Backend Evidence

## Scope

- Branch: `codex/admin-incident-detail-backend`
- Working tree based on commit: `a90f973d906a58df646bd9b47bf6315bf757352c`
- Criteria: partial backend evidence for `ADM-INC-02`, `ADM-INC-03`,
  `ADM-INC-04`, and `ADM-INC-05`
- Environment: Windows, Java 25, Gradle 9.6.1, isolated PostgreSQL schemas
- No production database or production credentials were used.

## Traceability

| Requirement | Implementation | Evidence |
| --- | --- | --- |
| Administrator-only incident detail and mutations | Spring Security `/api/v1/admin/**` boundary plus request-time enabled-Administrator recheck inside the mutation transaction | MVC denied-path tests and isolated PostgreSQL reauthorization test |
| Privacy-safe representation of anonymous reporters | Detail projection substitutes `Anonymous reporter` for reporter and comment identities; no deanonymization path is exposed | `detailIsCompletePrivacySafeAndRechecksThePersistedAdministrator` and `detailIsPrivacySafeNoStoreAndUsesTrustedPrincipal` |
| Lifecycle, resolution history, SLO and comment thread | Detail query reads persisted lifecycle timestamps and append-only resolution history | isolated PostgreSQL detail assertions |
| Assign/reassign with eligibility and staleness rechecks | Versioned transition under lock, re-validating role, target status/role, and category eligibility in the mutation transaction | `assignmentReassignmentAndUnassignmentUseVersionedAtomicAuditedTransitions` |
| Concurrent mutation safety and audit atomicity | Single-winner locking; audit write committed with the domain mutation in one transaction | `concurrentAssignmentHasOneWinnerAndAuditFailureRollsBack` |
| Duplicate-submission prevention for comments | `request_id` column with a partial unique index (Flyway `V10`) making comment submission idempotent | `commentsAndResolutionAreIdempotentValidatedAndAudited` |
| Non-blank, length-bounded resolution remarks | Service validation plus database constraints; whitespace retained after non-blank validation | service validation assertions in the same PostgreSQL test |
| Attachment reads are authorized with safe headers | Authorization-checked download endpoint with generic display names for anonymous reports | `downloadUsesGenericNameSafeHeadersAndTrustedPrincipal` and `deniedAndStorageFailureResponsesDoNotRevealProviderOrIdentity` |

## Automated verification

### Isolated PostgreSQL tests

Command:

```text
.\gradlew.bat registrationDatabaseTest --tests com.company.incidentdesk.web.admin.AdministratorIncidentPostgresTest
```

Result: passed, 4 tests, 0 failures, 0 skipped. The suite exercised detail
projection, versioned assign/reassign/unassign transitions, concurrent
assignment with a single winner, audit-failure rollback, and comment and
resolution idempotency against isolated schemas.

### Repository-wide verification

Command:

```text
.\gradlew.bat check
```

Result: passed. Java tests reported 725 tests, 0 failures, and 121
environment-gated skips. Frontend typecheck, lint and React tests passed.
Cross-platform package and JavaFX checks passed.

### Diff hygiene

Command:

```text
git diff --check
```

Result: passed. No whitespace errors were reported.

## Startup regression resolved

`JdbcAdministratorIncidentStore` was initially declared `final`. Because
`@Repository` beans are proxied for persistence exception translation, and
Spring proxies by CGLIB subclassing, the application context failed to start
with `Cannot subclass final class`. The modifier was removed. A new
`ProxyableBeanConventionTest` now scans every Spring stereotype class and fails
the build when a proxied class is `final`, converting that startup-time crash
into a build-time failure.

## Remaining evidence

`ADM-INC-02`, `ADM-INC-03`, `ADM-INC-04`, and `ADM-INC-05` remain open. This
record covers the backend only. The React incident detail page, interaction and
accessibility coverage, browser end-to-end tests, and deployed HTTPS
verification are not part of this change and have not been claimed.
