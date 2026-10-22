# Reporter-to-Responder Promotion Journey Verification

- Date: 2026-10-22 (Asia/Singapore)
- Branch: `codex/reporter-promotion-spec`
- Tested implementation commit: `f184a1cef769eab177601a2633be5e48d1470881`
- Tested state: the exact committed promotion implementation and automated
  verification changes listed below
- Environment: Windows, Java 25.0.4.1, Gradle 9.6.1, Node/Vitest, and the
  explicitly isolated PostgreSQL database configured by `.env.test.local`

## Traceability and results

| Criteria | Evidence |
| --- | --- |
| `PROM-AC-01`, `PROM-AC-02` | Application/controller tests cover required, duplicate, unknown, and oversized input; wrong-role and CSRF tests cover denied HTTP access; isolated PostgreSQL tests cover concurrent duplicate submission, transaction-time Reporter disable, and audit-failure rollback. |
| `PROM-AC-03`, `PROM-AC-04` | Controller and PostgreSQL tests cover bounded pagination, deterministic owned history, Administrator-only reads, no-store responses, malformed identifiers, and the neutral unavailable-requester label. React tests render retained untrusted labels/comments as inert text. |
| `PROM-AC-05`, `PROM-AC-06` | Application/controller tests cover exact requested subsets, empty/duplicate/unknown/unrequested grants, and rejection validation. PostgreSQL verifies that rejection retains role, access, and sessions and writes exactly one rejection audit. |
| `PROM-AC-07`, `PROM-AC-08` | Isolated PostgreSQL tests verify exact subset persistence, identity preservation, all-session invalidation, audit atomicity, injected rollback, concurrent single-winner decisions, and transaction-time Administrator loss. The conditional account update prevents transaction-time requester ineligibility from committing. |
| `PROM-AC-09` | Partial: PostgreSQL proves persisted exact access and session deletion; security tests cover generic wrong-portal denial. A fresh-login, category-scoped browser journey remains required. |
| `PROM-AC-10` | Partial: 14 focused React tests cover validation, loading, empty, pending, success, conflict, session expiry, retry, confirmation, duplicate submission prevention, focus return, and inert malicious text. Manual keyboard, 200% zoom, and narrow-reflow evidence remains required. |
| `PROM-AC-11` | Open: no browser end-to-end, restart/redeploy, or deployed HTTPS smoke evidence was produced in this run. |

## Commands and exact results

1. Focused React verification:

   ```text
   cd frontend
   npm.cmd test -- --run src/admin/PromotionRequests.test.tsx src/reporter/PromotionPanel.test.tsx
   ```

   Result: passed, 2 files and 14 tests.

2. Full frontend verification, also executed by the final Gradle check:

   ```text
   cd frontend
   npm.cmd run check
   ```

   Result: typecheck passed, ESLint passed with zero warnings, and 14 files / 133 tests passed.

3. Focused application, controller, and web-security verification:

   ```text
   gradlew.bat --no-daemon test --tests '*PromotionRequestServiceTest' \
     --tests '*PromotionRequestControllerTest' --tests '*ReporterWebSecurityTest'
   ```

   Result: passed.

4. Promotion PostgreSQL verification:

   ```text
   gradlew.bat --no-daemon registrationDatabaseTest \
     --tests '*PromotionWorkflowPostgresTest'
   ```

   Result: passed, including concurrent submission/decision, reauthorization,
   session invalidation, and rollback cases.

5. Full repository verification:

   ```text
   gradlew.bat --no-daemon check
   ```

   Result: `BUILD SUCCESSFUL` in 1m 20s. The generated JUnit XML contains 148
   suites, 762 tests, zero failures/errors, and 129 environment-conditional
   skips. Frontend check, production frontend build, Java tests, packaged JAR
   verification, and JavaFX platform verification passed. Gradle emitted only
   existing Shadow plugin duplicate-metadata and Java native-access warnings.

6. Full isolated PostgreSQL verification:

   ```text
   gradlew.bat --no-daemon registrationDatabaseTest
   ```

   Result: `BUILD SUCCESSFUL` in 1m 14s; 9 suites / 59 tests passed with zero
   failures, errors, or skips.

7. Diff hygiene:

   ```text
   git diff --check
   ```

   Result: passed. Git reported only the configured future LF-to-CRLF working-
   tree conversion notice for `PromotionRequestControllerTest.java`.

## Intermediate failures

The first expanded focused React run had two failed assertions caused by test
queries: one expected a hidden dialog element to remain in the accessibility
tree, and one used a singular role query when the UI intentionally exposes two
related alerts. The assertions were corrected without changing product
behavior. A later inert-text assertion required a flexible text matcher because
React combines adjacent text nodes. The final focused and full runs above pass.

## Changed behavior and remaining gates

`JdbcPromotionWorkflowStore` now locks and rechecks the submitting account as
an enabled persisted Reporter inside the request/audit transaction. This closes
the race where a concurrent disable or role change could otherwise occur after
the application-layer check but before insertion.

No schema, production dependency, AI, attachment, or SLO behavior changed.
Manual accessibility/reflow, browser end-to-end, restart/redeploy, deployed
HTTPS, and independent human review of the post-`c146284` transaction fix
remain pending.
