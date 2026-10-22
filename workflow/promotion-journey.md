# Reporter-to-Responder Promotion Journey

Status: **Product-owner, security/architecture, and implementation review
approved by the repository user on 2026-10-22. Verification evidence and the
remaining acceptance criteria are still pending.**

This specification governs the complete Reporter-to-Responder promotion
journey across Reporter request and history, Administrator review and decision,
session invalidation, and fresh Responder authentication. It connects the
role-specific specifications and supersedes no canonical policy in `.agents/`.

## 1. Scope, actors, and boundaries

- An enabled authenticated Reporter may request promotion for their own account
  only.
- An enabled authenticated Administrator may read and decide a still-pending
  request.
- Promotion changes the existing account from `REPORTER` to `RESPONDER`; it
  never creates another account or changes the account identifier.
- The server derives every actor, role, account state, request state, audit
  identity, and timestamp from trusted application state. Browser input cannot
  supply or override them.
- The workflow does not allow a Reporter to edit, withdraw, approve, or reject
  a submitted request. Cancellation is not part of this version.
- Promotion is not AI-assisted. No promotion data reaches the SoC LLM, and no
  generated output or AI-specific human approval point exists for this feature.
- No new production dependency or external service is required.

## 2. Reporter request and history behavior

An enabled Reporter with no pending request may select one or more distinct,
valid incident categories and enter optional comments of at most 2,000
characters. Whitespace-only comments are normalized to absent; otherwise the
trimmed comment is retained as untrusted text. The server rejects an empty
category set, unknown or duplicate categories, an over-limit comment, a stale
or disabled actor, and a second pending request without creating another
request or success audit.

Request submission creates a server-generated opaque request identifier and an
application-generated UTC submission time. The request begins in `PENDING`.
The request and its redacted audit event commit in one database transaction.
Concurrent duplicate submissions for the same Reporter have at most one
winner.

The Reporter can read only their own retained request history. History is
ordered by submission time descending and then request identifier ascending,
and is returned through bounded server-side pagination. Each record exposes
only:

- request identifier;
- requested categories;
- optional comments;
- submission time;
- `PENDING`, `APPROVED`, or `REJECTED` status;
- decision time when decided; and
- the exact approved category subset for an approved request, an empty set for
  a rejected request, or no approved-category value while pending.

Once a request is decided, an account that remains a Reporter may submit a new
request. Earlier requests and decisions are never overwritten. After approval,
the account is no longer eligible for the Reporter history endpoint; retained
history remains available to authorized Administrators.

## 3. Administrator review and decision behavior

The Administrator can page through pending requests in oldest-first order,
with request identifier ascending as the stable tie-breaker, and can open an
authorized request detail. The representation contains the requester login
label, requested categories, optional comments, submission time, status,
decision time, and approved categories where applicable. Tombstoned or
otherwise unavailable historical requesters use a neutral label and remain
non-actionable.

Approval requires an explicit non-empty subset of the request's persisted
requested categories. The server rejects an empty set, duplicate or unknown
categories, and any category not present in the persisted request. It does not
trust a browser-supplied copy of the requested categories. Approval:

1. preserves the existing account identifier and historical references;
2. changes the enabled requester from `REPORTER` to `RESPONDER`;
3. replaces category access with exactly the selected subset;
4. records the approved subset and decision time on the request;
5. invalidates every existing session for the promoted account; and
6. writes one redacted approval audit event.

Those effects commit atomically. A persistence, audit, or session-invalidation
failure leaves the request pending, the account a Reporter, category access
unchanged, existing sessions unaffected by a partial role change, and no
success audit.

Rejection records `REJECTED`, an empty approved-category set, decision time,
and one redacted rejection audit event atomically. It changes neither the
requester's role nor category access and does not invalidate the requester's
sessions.

Only a pending request whose requester is still an enabled Reporter can be
decided. A stale, duplicate, or concurrent losing decision returns a stable
conflict/unavailable result and produces no second mutation or success audit.

## 4. Authentication and session behavior

Approval invalidates all existing sessions for the promoted account through
the shared Spring Session JDBC store in the promotion transaction. Every
protected request also reloads or rechecks current persisted account authority,
so an old session fails closed if revocation cannot be observed immediately.

The promoted user must authenticate again through the Responder portal. The
Reporter portal and any other mismatched portal return the same generic
authentication failure used for invalid credentials. A fresh Responder session
contains only the currently persisted approved categories. Session identifiers
are never returned in promotion DTOs, stored in audit details, or written to
logs or browser persistence.

Rejection does not change authentication authority. Administrator sessions are
not rotated or invalidated by deciding another account's request.

## 5. HTTP and representation contract

The same-origin API is:

```text
GET  /api/v1/reporter/promotion-request?page=0&size=25
POST /api/v1/reporter/promotion-request

GET  /api/v1/admin/promotion-requests?page=0&size=25
GET  /api/v1/admin/promotion-requests/{requestId}
POST /api/v1/admin/promotion-requests/{requestId}/decision
```

List endpoints default to 25 rows and cap `size` at 100. `page` must be zero or
greater and `size` must be positive when supplied; malformed or out-of-range
values return the stable validation response. A page beyond the end returns an
empty list rather than falling back to another page. Reporter history uses the
newest-first ordering in section 2. The Administrator pending queue uses the
oldest-first ordering in section 3.

Reporter submission accepts only `requestedCategories` and `comments`.
Administrator decisions accept only `decision` (`APPROVE` or `REJECT`) and
`approvedCategories`; rejection must submit no category grant. DTOs contain no
caller-supplied actor, role, status, audit fields, account authority, or trusted
timestamps. Opaque identifiers are validated before repository access.

Every response containing promotion data uses `Cache-Control: no-store`.
State-changing requests require JSON and a valid CSRF token. Missing or stale
CSRF, signed-out, wrong-role, disabled/demoted actor, missing resource, invalid
input, conflict, and persistence failure use stable privacy-safe error codes.
Missing and denied protected resources are indistinguishable where a
distinction could reveal another account or request. The client may perform the
shared bounded stale-CSRF recovery, but it never automatically retries a
promotion submission or decision after the refreshed session is anonymous.

The fresh Responder result is observed through the approved Responder queue and
incident-access contracts, not through browser-supplied category or account
identifiers. Promotion does not add or weaken Responder claim, detail, comment,
attachment, handoff, or resolution behavior.

## 6. React interaction and accessibility contract

The Reporter surface provides labelled category checkboxes, a labelled
optional-comments field with the 2,000-character limit, submission status, and
a paginated semantic history table or equivalent accessible list. It prevents
duplicate UI submission, preserves entered values after recoverable failure,
and distinguishes loading, empty, pending-request, validation, session-expired,
conflict, and server-failure states. Client-side checks improve feedback but do
not replace server validation.

The Administrator surface provides a paginated pending queue and an explicit
review surface showing the request details and only the requested category
choices. Approve and reject controls are keyboard operable, prevent duplicate
UI submission, preserve selected categories after recoverable failure, and
surface stale/conflict results with a refresh action. An approval confirmation
identifies the requester and exact categories to grant; a rejection
confirmation states that the request will close without changing role or
access.

Both surfaces use semantic headings, labelled controls, visible focus, status
or alert live regions as appropriate, safe text rendering, keyboard-only
operation, 200% zoom, and narrow-screen reflow. Request comments and labels are
rendered as text, never through `dangerouslySetInnerHTML`. Promotion data,
comments, and protected identifiers are not written to `localStorage`, URLs,
analytics, or client logs. Role-specific state is cleared on logout or session
change.

## 7. Persistence, audit, concurrency, and recovery

- Schema changes use new forward-only Flyway migrations; an applied migration
  is never edited.
- Every request and decision is retained. A database constraint enforces at
  most one pending request per account.
- Requested and approved category sets are persisted separately. Approved
  categories are absent while pending, empty after rejection, and the exact
  non-empty requested subset after approval.
- Request creation and each decision commit their domain/account/session
  effects and required audit event in one transaction.
- Decision races use a locked or conditional transition from `PENDING`, with
  transaction-time reauthorization of the Administrator and requester. Exactly
  one concurrent decision can win.
- Audit events identify the trusted actor, request/account target, action,
  outcome, status change, and exact category grant where applicable. They omit
  comments, credentials, session identifiers, and other unnecessary content.
- Application-generated timestamps are UTC. Browser and database-server clocks
  are not business-time authorities.
- Restart or redeploy preserves request history, decisions, account identity,
  category grants, audit records, and shared-session invalidation state.
- The feature has no external provider, rate limit, timeout, or retry contract.
  Ordinary request-size, authentication, CSRF, database availability, and
  bounded client-recovery policies still apply.

## 8. Traceability and acceptance criteria

| Requirement | Governing source | Primary implementation area | Planned evidence |
| --- | --- | --- | --- |
| `PROM-REQ-01` | requirements, MVP scope, authorization matrix, audit policy, `REP-PRO-01` | Reporter API/UI and promotion persistence | validation, denied, uniqueness, ownership, pagination, and audit-atomicity tests |
| `PROM-DEC-01` | requirements, authorization matrix, audit policy, `ADM-ACC-02` | Administrator API/UI and locked decision transaction | subset, tampering, rejection, stale/concurrent, rollback, and reauthorization tests |
| `PROM-SESSION-01` | security policy, persistence policy, ADR-003 | shared session invalidation and role-specific authentication | multi-session, cross-instance, old-session, fresh-login, and wrong-portal tests |
| `PROM-UX-01` | React style guide | Reporter and Administrator promotion surfaces | interaction, focus, semantic, malicious-text, error-state, reflow, and accessibility evidence |
| `PROM-E2E-01` | MP2 improvements and testing policy | deployed Reporter → Administrator → Responder journey | isolated PostgreSQL, browser, restart, and HTTPS smoke evidence |

- [ ] `PROM-AC-01` — An enabled Reporter can create one pending request with a
  non-empty set of distinct valid categories and an optional comment within
  2,000 characters; actor, identifier, and UTC time are server-derived, and the
  request plus redacted audit event commit atomically.
- [ ] `PROM-AC-02` — Empty, duplicate, unknown, or tampered categories,
  over-limit comments, signed-out/wrong-role/disabled actors, persistence or
  audit failure, and concurrent duplicate submissions create neither a second
  pending request nor a misleading success audit.
- [ ] `PROM-AC-03` — Bounded history returns only the authenticated Reporter's
  retained requests in deterministic order with the specified pending,
  approved, and rejected outcomes; cross-user reads and missing resources do
  not disclose whether another request exists.
- [ ] `PROM-AC-04` — Only an enabled Administrator can page through pending
  requests or open request detail, and representations are bounded,
  non-cacheable, safely rendered, and neutral for unavailable historical
  requesters.
- [ ] `PROM-AC-05` — Approval accepts exactly a non-empty subset of the
  persisted requested categories; empty, duplicate, unknown, browser-tampered,
  or unrequested grants are rejected without mutation or success audit.
- [ ] `PROM-AC-06` — Rejection leaves role, category access, and sessions
  unchanged while atomically retaining the rejected decision and one redacted
  rejection audit event.
- [ ] `PROM-AC-07` — Approval atomically preserves account identity/history,
  changes the requester to `RESPONDER`, replaces access with exactly the
  approved subset, records the decision, invalidates all existing requester
  sessions, and writes one redacted approval audit; an injected failure leaves
  every effect uncommitted.
- [ ] `PROM-AC-08` — A stale, duplicate, or concurrent decision has at most one
  winner. Transaction-time loss of Administrator authority or requester
  eligibility prevents the decision, and every loser creates no second
  mutation or success audit.
- [ ] `PROM-AC-09` — Old Reporter sessions fail across application instances;
  wrong-role portals fail generically; fresh Responder authentication exposes
  only current approved categories and authorizes only the corresponding
  Responder queue and assigned-incident access.
- [ ] `PROM-AC-10` — React tests and accessibility evidence cover Reporter and
  Administrator loading, empty, validation, pending, success, conflict,
  session-expired, and retry states; confirmations, duplicate-submit
  prevention, focus return, malicious/long text, keyboard operation, 200% zoom,
  narrow reflow, and absence of promotion data from browser persistence/logs.
- [ ] `PROM-AC-11` — An isolated browser end-to-end test completes Reporter
  request, Administrator partial approval, old-session denial, fresh Responder
  login, and category-scoped queue access, then verifies persisted request,
  account, category, session, and audit state. Restart/redeploy and deployed
  HTTPS smoke evidence use the exact release commit and non-production data.

## 9. Verification and approval record

Required automated evidence:

1. Domain/application tests for request and decision validation,
   authorization, state transitions, and redacted audit contents.
2. Spring MVC/security tests for route roles, CSRF, content type, request
   limits, stable safe errors, no-store responses, pagination, and denied versus
   missing behavior.
3. Isolated PostgreSQL tests for empty migration, retained history,
   uniqueness, transaction rollback, transaction-time reauthorization,
   concurrent submissions/decisions, audit atomicity, and shared-session
   invalidation.
4. React component tests for the interaction and accessibility states in
   `PROM-AC-10`.
5. Browser end-to-end and deployed HTTPS tests for `PROM-AC-11`, including
   restart/redeploy persistence.

Current record:

- Existing implementation: merged on `main` through issue #6; treated as
  provisional and not as specification approval or completion evidence.
- Automated evidence linked to this revision: pending.
- Manual/accessibility/deployed evidence: pending.
- Product-owner approval of this revision: repository user, confirmed in the
  current Codex chat on 2026-10-22 and recorded in
  [`reviews/2026-10-22-promotion-journey-specification.md`](reviews/2026-10-22-promotion-journey-specification.md).
- Independent security/architecture approval of this revision and ADR-003:
  repository user, approved in the current Codex chat on 2026-10-22 and
  recorded in
  [`reviews/2026-10-22-promotion-journey-specification.md`](reviews/2026-10-22-promotion-journey-specification.md).
- Independent implementation review of commit `c146284`: repository user,
  approved in the current Codex chat on 2026-10-22 and recorded in the same
  review record.

No acceptance criterion is checked until reproducible evidence is recorded and
linked here. Agent-authored specification text does not satisfy a human
approval gate.
