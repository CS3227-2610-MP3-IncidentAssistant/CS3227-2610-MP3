# Issue #7 Responder queue completion

- Date: 2026-10-23 (Asia/Singapore)
- Branch: `codex/issue-7-responder-api`; evidence recorded before this step's commit.
- Scope: RSP-QUEUE-01 and the authenticated-account change boundary.

The queue query selects eligible unassigned incidents only when the current
enabled Responder has category access, plus incidents still assigned to that
Responder. PostgreSQL performs authorization before filter/count/page, orders
by queue entry time and incident ID, and returns a privacy-minimized row.
Category revocation removes unassigned incidents while retaining the actor's
assigned work. Search and filters remain in a CSRF-protected request body.

The React queue distinguishes loading, empty/no-category, expired, denied,
and service-failure states. It discards older query responses. The shared App
now remounts the Responder portal after a trusted session refresh, so cached
rows disappear before the fresh queue request completes, including when the
login name stays the same.

| Check | Result |
| --- | --- |
| `./gradlew registrationDatabaseTest --tests '*ResponderIncidentPostgresTest'` | Passed against the dedicated isolated PostgreSQL test container: 21 tests, 0 failures/errors/skips. |
| `npm run test -- ResponderPortal.test.tsx` | Passed: 28 tests, including filter request, queue states, and stale-response behavior. |
| `npm run test -- ResponderSessionChange.test.tsx` | Passed: 2 same- and changed-account cache-clearing regressions. |
| `./gradlew check` | Passed: 16 frontend test files and 143 tests, typecheck, ESLint, production build, Java tests, packaged JAR, and platform checks. |
| `git diff --check` | Passed after final review. |

The database tests use random isolated schemas and do not read or modify the
development or production database. No incident mutation or schema change is
part of this step. RSP-DETAIL-01, RSP-RESOLVE-02, RSP-UI-01, and RSP-E2E-01
remain open under the current approved specification.
