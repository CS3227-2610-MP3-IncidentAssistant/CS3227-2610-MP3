# Reporter-to-Responder Promotion Journey

Status: **Draft — implementation is gated on product-owner confirmation of
this revised cross-role contract and independent security/architecture
approval.**

This specification governs issue #6 across Reporter request and history,
Administrator review and decision, and fresh Responder authentication. It
connects the role-specific specifications; it does not replace the canonical
policies in `.agents/`.

## 1. Actors and boundaries

- An enabled Reporter may request promotion for their own account only.
- An enabled Administrator may decide a still-pending request.
- A Responder account is created by changing the existing account's role;
  promotion never creates another account or changes its identifier.
- The server derives each actor from the authenticated request. Browser fields
  cannot set an actor, role, request status, audit identity, or timestamp.
- The promotion request and decision are not AI-assisted. AI policy is not
  applicable to this feature.

## 2. Request and history behavior

A Reporter may request one or more distinct, valid incident categories and
optional comments up to the centrally approved 2,000-character limit. At most
one request may be pending for that account. Once a request is decided, the
Reporter may submit another; prior request and decision history remains
available and is not overwritten. Request creation and its audit record are
one transaction.

While the account remains a Reporter, the Reporter can read only their own
request history. Each record shows its requested categories, submission time,
status, and privacy-safe outcome. An approved record identifies the categories
granted. A Reporter cannot alter, approve, or reject a submitted request. After
approval the account is no longer eligible for the Reporter history endpoint;
the retained decision remains available to authorized Administrators, and the
fresh Responder session is authorized only for the currently granted
categories.

## 3. Administrator decision behavior

An Administrator can review a pending request's requester, requested
categories, request time, and optional comments. Approval requires selecting a
non-empty subset of the categories requested. The server rejects an empty set,
unknown or duplicate categories, and any category not in the original request.
The decision uses the current persisted request, not a browser-supplied copy of
its requested categories.

Approval changes that same account to `RESPONDER`, grants exactly the selected
categories, preserves its identifier and historical references, records the
selected categories in request history and audit evidence, and commits the
role, access, request decision, and audit changes atomically. Rejection changes
neither role nor category access and retains the decision and required audit
record. Only an enabled Reporter with a pending request is eligible. A stale,
duplicate, or concurrent losing decision makes no second change or success
audit.

## 4. Authentication and session behavior

Approval invalidates every existing session for the promoted account through
the shared Spring Session JDBC store. The promoted user must sign in again
through the Responder portal. The Reporter portal and any mismatched role
portal must not issue a usable session. A fresh Responder session reflects
only the categories selected in the approved decision. Authentication and
authorization are checked on the server; hiding routes or controls is not a
security boundary.

## 5. HTTP contract

The Reporter endpoints follow the paths already listed in
`workflow/reporter-vertical-slice.md`:

```text
GET  /api/v1/reporter/promotion-request
POST /api/v1/reporter/promotion-request
```

The Administrator API provides a bounded pending-request list, an
Administrator-authorized detail endpoint for pending or decided requests, and
a CSRF-protected decision operation under `/api/v1/admin/promotion-requests`:

```text
GET  /api/v1/admin/promotion-requests?page=0&size=25
GET  /api/v1/admin/promotion-requests/{requestId}
POST /api/v1/admin/promotion-requests/{requestId}/decision
```

The pending list defaults to 25 rows and caps `size` at 100. Results sort by
request time ascending, then request identifier ascending, so paging is
deterministic. Approval submits only the selected category identifiers;
rejection submits no category grant. Request and response DTOs omit
caller-supplied actor, role, audit fields, and timestamps. Responses use the
established stable validation, denial, stale/conflict, and persistence-failure
error contract and do not reveal another Reporter's request.

The fresh Responder session also needs an observable incident-access check. A
Responder may retrieve a bounded queue from
`GET /api/v1/responder/incidents?page=0&size=25`. The server derives the
enabled Responder account and its currently persisted categories from the
authenticated principal; category or account identifiers are not accepted as
authorization input. The result contains unassigned submitted incidents only
from those categories, plus incidents currently assigned to that Responder
regardless of current category access. It excludes incidents assigned to
another account and all other unassigned categories. Rows omit anonymous
reporter identity. Results use queue-entry time ascending and incident ID as a
stable tie-breaker. Queue-entry time follows `.agents/incident-lifecycle.md`:
handoff preserves the original submission queue position, while reopening uses
the current reopen-cycle timestamp. The endpoint returns `{ incidents,
page, size, hasMore }`; each row contains only `incidentId`, `title`,
`category`, `status`, and `queueEnteredAt`. It returns no identity, comments,
description, attachment, or total-count field. `size` defaults to 25 when
omitted, must be positive when supplied, and is capped at 100; negative `page`
values are rejected, and a page beyond the end returns an empty list with
`hasMore: false`. Every request rechecks the account role, enabled state,
category grants, and assignment against current persisted state. This read-only
queue contract does not add claim, detail, or lifecycle mutation endpoints;
those remain governed by their own approved feature specification.

## 6. Persistence, audit, and concurrency

- Add schema only through forward Flyway migrations; never edit an applied
  migration.
- Preserve every request and decision as history. Enforce at most one pending
  request per Reporter.
- Persist `approvedCategories` separately from `requestedCategories`: it is
  absent while pending, empty after rejection, and the exact non-empty subset
  after approval. Preserve this outcome after later requests are submitted.
- Commit request/decision state and its required audit event in one database
  transaction. A failed write leaves neither a partial role/access change nor
  a success audit.
- Protect decision races with a database transaction and conditional pending
  state transition or equivalent row lock. Exactly one concurrent decision can
  win.
- Persist application-generated UTC times. The browser cannot supply them.
- Audit the acting Administrator, decision, target request/account, and exact
  category access change. Do not record credentials, session identifiers, or
  unnecessary request comment text.
- Invalidate sessions across application instances using the shared session
  store. Failure to invalidate must not leave a promoted account with a usable
  old session.

## 7. Traceability and acceptance criteria

| Requirement | Governing source | Primary implementation | Evidence |
| --- | --- | --- | --- |
| `PROM-REQ-01` | `.agents/requirements.md`, `authorization-matrix.md`, `audit-policy.md`, REP-PRO-01 | Reporter request/history API and UI; promotion persistence | allowed, validation, duplicate, owner-denial, and atomic-audit tests |
| `PROM-DEC-01` | `.agents/requirements.md`, `authorization-matrix.md`, `audit-policy.md`, ADM-ACC-02 | Administrator pending review and decision API/UI | subset, unrequested-category denial, stale/concurrent, rejection, and rollback tests |
| `PROM-SESSION-01` | `.agents/security.md`, ADR-001 | Spring Session JDBC invalidation and role-specific login | multiple-session and wrong-portal integration tests |
| `PROM-E2E-01` | `.agents/mp2-grade-improvements.md`, `.agents/testing.md` | Reporter → Administrator → Responder journey | isolated PostgreSQL and browser evidence |

- [ ] `PROM-AC-01` — An enabled Reporter creates one pending request for a
  non-empty set of distinct valid categories and optional comments within the
  2,000-character limit; actor and time are server-derived, and request plus
  audit persist atomically. Concurrent duplicate submissions leave exactly one
  pending request and one success audit.
- [ ] `PROM-AC-02` — A Reporter sees only their own request history, including
  approved categories, and cannot alter or decide a request; direct cross-user
  reads do not disclose whether another request exists. After promotion, the
  Reporter history endpoint denies the Responder role while the decision
  remains available to authorized Administrators and the Responder session is
  restricted to the currently granted categories.
- [ ] `PROM-AC-03` — Only an enabled Administrator can decide a pending request;
  approval grants exactly a non-empty selected subset of requested categories,
  while an empty, duplicate, unknown, tampered, or unrequested category
  selection is denied without mutation or success audit.
- [ ] `PROM-AC-04` — Rejection leaves the account role and category access
  unchanged, retains request history, and records one decision audit event.
- [ ] `PROM-AC-05` — Approval atomically preserves the account ID/history,
  changes role, persists exactly the selected category subset, records the
  decision and audit, and leaves no partial mutation or success audit after an
  injected persistence failure.
- [ ] `PROM-AC-06` — Stale, duplicate, and concurrent decisions have at most
  one winner; losing decisions create no second mutation or success audit.
- [ ] `PROM-AC-07` — Approval invalidates all existing account sessions across
  application instances; the old Reporter session cannot access protected
  routes, and fresh Responder-portal authentication receives only approved
  categories. Wrong-role portals fail generically.
- [ ] `PROM-AC-08` — An isolated end-to-end test completes Reporter request,
  Administrator partial approval, old-session denial, fresh Responder login,
  and a category-scoped Responder queue read. It verifies that only unassigned
  incidents in approved categories plus incidents assigned to that Responder
  are returned, anonymous identity is absent, and persisted promotion, access,
  session, and audit state match the outcome.

## 8. Verification and approval record

- Automated evidence: pending.
- Manual/deployed evidence: pending.
- Product-owner approval of this revised contract: pending review of this
  specification revision.
- Independent security/architecture review: pending.
