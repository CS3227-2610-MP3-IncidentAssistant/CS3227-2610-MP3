# ADR-003: Promotion Decision Persistence and Session Invalidation

Status: **Proposed — product-owner approved on 2026-10-22; independent
security/architecture review remains pending. Existing implementation is
provisional until verified against this decision.**

- Date: 2026-10-19
- Product owner: repository user, approved in the current Codex chat on
  2026-10-22
- Independent security/architecture decider: pending

## Context

Issue #6 must persist Reporter promotion requests and decisions in PostgreSQL. Approval changes an existing account's role and category access, preserves request history, creates audit evidence, and prevents existing Reporter sessions from being used after the role change. The Administrator may approve only a non-empty subset of the categories requested. The current local promotion service approves the full request and does not provide a web PostgreSQL adapter or shared-session invalidation.

## Proposed decision

- Add forward-only Flyway schema for durable promotion request history and requested/approved category sets. Enforce one pending request per Reporter.
- Keep request creation and decision operations behind an application service and a persistence port. Do not place authorization or business transitions in controllers or SQL adapters.
- Decide against the current pending request inside a PostgreSQL transaction using a row lock or conditional `PENDING` state transition. Validate the selected category set against the persisted requested set inside that transaction.
- Commit request status, the existing account's role and exact approved categories, request history, and required audit record as one logical transaction. A rollback leaves none of these changes.
- Use the shared Spring Session JDBC store to invalidate every session for the promoted account. Verify that revocation participates safely in the promotion transaction; if the session repository cannot join that transaction, add a fail-closed current-role/version check so an old session cannot access Reporter or Responder protected routes.
- A fresh login reloads the account's persisted role and category access. The Reporter portal cannot silently become a Responder session.
- Rejection changes neither role nor access and therefore does not invalidate
  the requester's sessions. Approval and rejection both retain immutable
  request history.
- Bound and deterministically order Reporter history and Administrator pending
  lists. Reject invalid pagination values instead of silently selecting a
  different page.
- Return promotion representations with `Cache-Control: no-store`; never place
  request comments, session identifiers, or authorization state in URLs,
  browser persistence, logs, or audit details.

## Security and privacy effects

The server derives the Reporter and Administrator from the trusted request security context. The decision reads requested categories from persisted request state and rejects unrequested grants. Audit data records the actor, target, outcome, and exact category change but excludes credentials, session identifiers, and unnecessary request comments. Request history is visible only to its owner and authorized Administrators.

## Consequences

This adds persistent schema and integration tests for migration, rollback, concurrent decisions, audit atomicity, category boundaries, and multi-session revocation. It reuses the shared account and session stores; it does not create a second identity system. The web workflow cannot be considered complete until old sessions are proven unusable and a fresh Responder login receives exactly the approved category set.

## Verification required

- Empty-database and forward-migration tests using an isolated PostgreSQL database/schema.
- Concurrent Administrator decisions produce at most one winner and one success audit.
- Injected persistence/audit failure rolls back account, request, and category changes.
- Multiple active sessions are invalidated or fail closed across application instances after approval.
- A newly authenticated Responder receives only the approved categories; a wrong-role portal remains denied.
- Independent security/architecture reviewer and named human approval are
  recorded before the existing provisional implementation is accepted as the
  approved design or extended further.
