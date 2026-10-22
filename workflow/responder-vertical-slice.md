# Responder React + Spring Boot Vertical Slice

Status: **Product-owner approved; issue-specific independent
security/architecture review waived by the product owner. Implementation may
proceed against these approved criteria and decisions.**

This specification defines the first web vertical slice for Responder work in
MP3. The canonical `.agents/` policies remain authoritative. If a conflict is
found, stop and resolve it in the owning policy and this specification before
implementation.

## 1. Scope decision

This issue delivers the core Responder web journey: sign in through the
Responder portal, see authorized incidents, inspect a privacy-safe incident,
claim an eligible incident, and resolve a case currently assigned to the
Responder. It is a first vertical slice, not full Responder feature parity.

Included:

- role entry and expected-role authentication using the shared web session;
- a responsive Responder shell and authorized incident queue;
- incident detail, history, and already-authorized attachment reads;
- claim and resolution with transactionally consistent audit records;
- explicit loading, empty, unavailable, stale, denied, and recoverable failure
  states.

Excluded from this issue: handoff, weekly resolved-case counts and SLO
performance views, advanced queue filtering, and Responder AI assistance. These
remain product requirements and need their own scheduled scope and acceptance
criteria. Reporter promotion UI and behavior belong to issue #6; this slice
must not add public Responder registration or bypass the promotion workflow.

## 2. Outcomes and boundaries

An enabled account with persisted `RESPONDER` role can handle only incidents
allowed by the current authorization policy. Spring Security and application
logic are authoritative for identity, category access, lifecycle eligibility,
validation, timestamps, privacy-safe selection, and audit creation. PostgreSQL
is the shared source of truth. React holds presentation state only.

Every protected request derives its actor from the trusted request security
context. The portal selection is an expected-role check, not authority.
Mutations enforce CSRF and repeat permission and state checks within the
transaction. Browser-supplied actor, role, assignee, category, timestamp, or
audit fields are ignored. A changed or expired session clears protected
Responder data from the browser cache.

## 3. Dependencies and integration ownership

- **Issue #6 — Reporter-to-Responder promotion:** required to demonstrate the
  complete promoted-account browser journey. It is not a blocker for this
  specification, backend work, or isolated integration tests using a
  purpose-created enabled Responder fixture. The fixture must be test-only and
  must not create a public registration or production bypass.
- **Issue #5 — administrator provisioning:** no dependency for this slice.
  Responder promotion and category access remain Administrator-governed under
  the existing authorization policy.
- **Shared components:** coordinate the shared role-entry page, authentication
  and session behavior, responsive shell/navigation, API error/session-expiry
  handling, incident detail primitives, and audit/persistence adapters with
  their owners. This issue may implement shared pieces only under agreed team
  ownership; it does not authorize changing Reporter or Administrator product
  behavior.
- **Architecture decisions:** responder projection, mutation transaction
  boundaries, claim concurrency, resolution-history behavior, and conflict
  handling are recorded in `workflow/decisions/ADR-004-responder-mutations.md`.
  Remaining project-wide architecture TODOs do not block this slice unless
  implementation reaches one of those unresolved boundaries.

## 4. Actors, privacy, and authorization

- The actor is an enabled account with persisted `RESPONDER` role.
- A Responder may list and claim unassigned `SUBMITTED` incidents only in
  categories currently assigned to that Responder.
- A Responder may continue to access and act on an incident already assigned
  to them after category access is revoked, until the assignment ends or the
  incident is resolved, as specified in `.agents/authorization-matrix.md`.
- Incidents assigned to another Responder are not exposed in this queue or
  detail routes. Missing and forbidden details use the same safe unavailable
  behavior.
- Anonymous reporter identity is never returned to the Responder or exposed
  through HTML, API fields, comments, attachment metadata, URLs, errors, logs,
  or audit views. Do not infer identity from the anonymous flag or content.
- Login failures do not enumerate usernames, account states, or roles. Session
  identifiers and credentials are never logged or returned.

## 5. Functional requirements

### RSP-AUTH-01 — Responder portal authentication

The shared role-entry page provides a clearly labelled Responder portal. Only
an enabled account whose persisted role is `RESPONDER` can authenticate there.
Invalid credentials, disabled or unknown accounts, and valid accounts used at
the wrong portal receive the same generic failure. Successful login rotates
the session identifier and uses the approved shared server-side session,
CSRF, cookie, and cache controls. Direct navigation while signed out or under
another role exposes no protected data.

### RSP-QUEUE-01 — Authorized incident queue

The queue includes unassigned `SUBMITTED` incidents in categories currently
accessible to the actor, plus incidents currently assigned to the actor. It
excludes incidents assigned to someone else, incidents outside the actor's
permitted categories unless already assigned to the actor, and lifecycle
states not eligible for the queue. Ordering is stable and deterministic, with
incident identifier as a tie-breaker. Server-side paging and existing
centralized field limits apply; the UI must not fetch a broader dataset and
filter it locally. Search trims surrounding whitespace and matches title,
description, or incident identifier case-insensitively. Category and status
selections use OR within each selection group and combine with AND across
groups; empty selections mean all authorized values. Status choices are
limited to `SUBMITTED` and `ASSIGNED`, and assignment choices are `ALL`,
`ASSIGNED`, and `UNASSIGNED`. Search is limited to 200 characters after
trimming. Filtering and counts are applied only after the authorization
predicate, before server-side paging. Submit queue criteria in a CSRF-protected
JSON request body; never put search text in a URL or browser location history.

The queue distinguishes loading, no eligible incidents, no categories
configured, expired/changed session, denied access, and service failure. A
late response cannot restore protected data after logout or replace a newer
query. The page-level “no categories configured” state must not reveal
categories or incident existence that the actor is not authorized to see;
request-level missing and forbidden resources remain indistinguishable.

### RSP-DETAIL-01 — Incident detail and authorized content

Opening a queue item returns only content the current actor may access:
incident fields, lifecycle history, resolution history, privacy-safe comments,
and attachment metadata/content. Authorization is rechecked for every detail
and attachment request. Attachment responses use safe download headers and
generated identifiers, not client paths or identity-bearing names. Missing and
forbidden incidents have the same presentation-safe response. For anonymous
incidents, every incident-facing response uses the neutral `Anonymous reporter`
label and generic attachment display names; comments and other authors remain
visible according to the anonymity policy, without exposing reporter identity.
Detail content and user-supplied text are rendered as text, never executable
HTML.

The product owner approved backend streaming from the private Neon bucket for
this slice on 2026-10-20. The browser receives neither storage credentials nor
signed URLs. The backend resolves attachment IDs through PostgreSQL and checks
the current Responder's incident access before each object read. Download names
are generic; responses use allowlisted PNG/JPEG media types, attachment
disposition, `nosniff`, and `no-store`. This is a read contract only: Reporter
uploads, object-write compensation, and production population of attachment
metadata remain outside this issue and are not enabled by this migration.

### RSP-CLAIM-01 — Claim an eligible incident

The actor may claim only an unassigned `SUBMITTED` incident in a category
currently permitted to them. The server derives assignee identity and
application-generated UTC timestamps. Claim is an atomic mutation with its
success audit record in the same database transaction. Authorization and
eligibility are rechecked at mutation time, including category revocation and
the incident becoming assigned or otherwise ineligible after the queue was
loaded. Direct attempts to claim ineligible, out-of-category, or already
assigned incidents fail safely without changing state or writing a success
audit.

### RSP-CLAIM-02 — Concurrent claim conflict

When two eligible Responders claim the same incident concurrently, exactly
one mutation succeeds, exactly one assignee is persisted, and exactly one
success audit event is committed. The losing request receives a safe
recoverable stale/conflict response and cannot create a success audit. The
behavior is proven against isolated PostgreSQL using the selected locking or
conditional-update strategy.

### RSP-RESOLVE-01 — Resolve an assigned incident

Only the current assigned Responder may resolve an `ASSIGNED` incident. The
request requires non-blank resolution remarks within the centrally approved
field limit. After non-blank validation, the stored remarks retain entered
whitespace. The server sets the application-generated UTC resolution
timestamp and derives the actor from the request context. The current
assignment and lifecycle state are rechecked within the mutation transaction;
a stale request after reassignment or another state change fails safely. The
request includes a UUID unique to the resolution attempt, and the JSON body is
capped at 32 KiB before parsing.

### RSP-RESOLVE-02 — Atomic resolution and audit

Resolution state, remarks/history, lifecycle timestamps, and the corresponding
success audit record commit atomically. Resolution remarks are retained in
resolution history when an incident is reopened; a later resolution appends a
new cycle. The audit stores remarks or a safe reference as allowed by the
canonical audit policy, never anonymous reporter identity or unnecessary
incident content. A denied, invalid, stale, or persistence-failed request
leaves no partial resolution and no misleading success audit. A retry after a
lost response using the same request identifier, enabled Responder, incident,
and exact remarks reports the original cycle safely and does not duplicate
resolution history or audit records. Reuse of the identifier with a different
actor, incident, or remarks returns a safe conflict. The browser preserves the
request identifier for an explicit retry after an uncertain outcome.

### RSP-UI-01 — Responsive and accessible interaction

Queue, detail, claim, and resolve workflows are usable by keyboard and screen
reader with labelled controls, visible focus, and clear validation. Every
mutation has pending, success, and safe failure feedback. Narrow layouts retain
named navigation controls rather than icon-only actions.

### RSP-E2E-01 — Promoted-account browser journey

After issue #6 provides a promoted Responder account, a human can sign in,
open an eligible incident, claim it, and resolve it with remarks in the
deployed or agreed integration environment. Capture manual evidence without
recording credentials, session identifiers, or anonymous reporter identity.

## 6. Traceability and planned evidence

| Requirement | Acceptance criteria | Planned implementation/evidence |
| --- | --- | --- |
| `.agents/authorization-matrix.md`, `.agents/security.md` | RSP-AUTH-01 | Shared web authentication; Spring Security/API integration tests for wrong role, disabled account, session rotation, CSRF, and direct routes |
| `.agents/requirements.md`, `.agents/authorization-matrix.md` | RSP-QUEUE-01, RSP-DETAIL-01 | Responder query/controller/UI; isolated PostgreSQL authorization and privacy tests; React interaction tests |
| `.agents/incident-lifecycle.md`, `.agents/persistence.md`, `.agents/audit-policy.md` | RSP-CLAIM-01, RSP-CLAIM-02 | Claim use case and PostgreSQL adapter; competing-transaction integration test asserting one state change and one audit |
| `.agents/incident-lifecycle.md`, `.agents/persistence.md`, `.agents/audit-policy.md` | RSP-RESOLVE-01, RSP-RESOLVE-02 | Resolution use case and migration/persistence as approved; validation, denial, rollback, idempotent retry, and atomicity tests |
| `.agents/STYLE_GUIDE.md`, `.agents/testing.md` | RSP-UI-01 | Accessible React components; automated interaction/accessibility checks and responsive manual review |
| Issue #6 and `.agents/testing.md` | RSP-E2E-01 | Human-run browser evidence after promotion journey is available |

### Detail API progress (does not complete RSP-DETAIL-01)

The incremental detail API and Responder-local browser view use only the
incident's persisted web fields. A repeatable-read transaction checks the
current enabled Responder identity and allows either a currently permitted,
unassigned `SUBMITTED` incident or an incident assigned to that Responder.
Raw reporter IDs and the anonymous flag are not returned; the display label is
`Anonymous reporter` for anonymous reports and the authorized login label for
non-anonymous reports. Previous resolution cycles and their remarks, privacy-
safe comments, and generic attachment metadata are returned to an authorized
current Responder as text/data. V8 defines the shared comments and attachment
metadata contracts. Attachment bytes are streamed through the backend from the
private Neon bucket after fresh per-request authorization. Reporter comment
and upload writers, upload compensation, and production population of these
tables remain outside this issue. Missing and forbidden detail requests use
the same safe unavailable response. The API contribution remains partial
until those write flows are integrated and privacy evidence is complete.

The API returns incident text as data; any browser view must render it as text,
not executable HTML. The current core-fields UI uses React text rendering;
later comment/history/attachment surfaces still require their own rendering
and privacy verification.

The Responder timeline now also includes Administrator assignment and
reassignment transitions, with neutral labels and status changes only. The
PostgreSQL read test and shared React rendering test are recorded in
[`test-evidence/2026-10-23-issue-7-timeline-completion.md`](test-evidence/2026-10-23-issue-7-timeline-completion.md).
RSP-DETAIL-01 remains open for the broader content and privacy verification
described above.

### Partial mutation UI progress (does not complete RSP-UI-01)

The Responder detail view offers Claim for `SUBMITTED` incidents and resolve
remarks for `ASSIGNED` incidents. Both use same-origin CSRF-protected requests,
pending-state duplicate prevention, safe stale/error feedback, and no
optimistic state change. Resolution retries after uncertain outcomes reuse a
persisted request identifier and exact remarks; confirmed success clears the
protected incident detail. `ResponderPortal.test.tsx` covers success,
validation, conflict, uncertain replay, and cache clearing. RSP-UI-01 remains
unchecked until complete keyboard/screen-reader and narrow-layout verification
and keyboard access to the detail comments and attachment downloads is
verified.

The shared role shell now removes closed narrow-screen navigation from the
accessibility and keyboard trees and restores focus to the Menu control after a
navigation choice. The focused React regression and full project check are
recorded in
[`test-evidence/2026-10-23-issue-7-mobile-navigation.md`](test-evidence/2026-10-23-issue-7-mobile-navigation.md).
RSP-UI-01 remains unchecked pending actual narrow-browser, keyboard, and
screen-reader verification of the complete Responder journey.

## 7. Approved implementation decisions

The product owner approved the following decisions for this slice:

1. Use a privacy-minimized Responder-specific projection; do not expose a
   broader shared incident DTO to responder APIs.
2. Claims use a conditional database update inside a transaction, with
   authorization and eligibility rechecked at mutation time. The incident
   change and success audit commit atomically. Resolution uses the same
   transaction boundary and rechecks assignment and lifecycle state.
3. Preserve each resolution as forward-only history. Any schema change uses a
   new migration and never edits an applied migration. Retain history according
   to the existing incident retention policy; if no policy covers it, keep the
   history for the lifetime of the incident.
4. Limit resolution remarks to 2,000 characters, enforced consistently in the
   browser, application, and database boundary where supported.
5. Responder work may change Responder-specific components. Shared components
   may be changed only with their existing owner’s agreement and a documented
   integration contract; this issue does not authorize changing Reporter or
   Administrator behavior.
6. A stale or repeated resolution request returns a safe conflict/current-state
   response and must not duplicate history or success audit records.

The product owner waived a separate independent human security/architecture
approval for this issue because the Responder feature is owned within its own
role boundary. This is a one-issue process exception; it does not waive any
security, privacy, authorization, transaction, test, or audit requirements
above, nor the implementation-verification requirements in `workflow/README.md`.

## 8. Out of scope and explicit non-applicability

- AI assistance is not part of issue #7; no AI data flow or agent authority is
  introduced by this slice. AI requirements remain governed by `.agents/ai-policy.md`
  for a future Responder AI feature.
- Handoff and weekly responder performance/SLO metrics remain required product
  features but are not accepted by this slice.
- No new production dependency, public Responder registration, or change to
  promotion/category governance is authorized here.

## Acceptance criteria

- [x] RSP-AUTH-01: expected-role authentication, generic failures, session
  rotation, CSRF, and direct-route denial are demonstrated by
  `ReporterWebSecurityTest.roleSpecificLoginPathsAcceptOnlyTheirPersistedRole`,
  `ReporterWebSecurityTest.responderLoginChangesThePreAuthenticationSessionId`,
  and the Responder queue/detail/attachment role-denial tests.
- [ ] RSP-QUEUE-01: only the authorized queue and stable ordering are returned;
  empty, no-category, expired, denied, and failure states are distinct without
  leaking protected categories or incident existence.
- [ ] RSP-DETAIL-01: authorized detail and attachments work; forbidden/missing
  resources do not enumerate; each attachment read reauthorizes and has safe
  headers; anonymous identity is absent from all responder surfaces, including
  serialized responses, comments, and attachment display names.
- [x] RSP-CLAIM-01: eligible claim uses trusted actor and UTC timestamp and
  atomically records the success audit; direct denied/stale attempts, including
  category revocation after queue load, change neither incident nor success
  audit. Verified by `ResponderIncidentPostgresTest.claimChecksCurrentCategoryAndLifecycleAndAtomicallyWritesAudit`
  and `ResponderIncidentControllerTest.claimReturnsNoStoreSuccessOrSafeConflict`.
- [x] RSP-CLAIM-02: isolated PostgreSQL race yields one winner, one persisted
  assignee, one success audit, and a safe loser response.
- [x] RSP-RESOLVE-01: only the current enabled assignee can resolve with valid,
  non-blank remarks and application-generated UTC time; entered whitespace is
  retained after validation. Verified by `ResponderIncidentPostgresTest` resolution
  authorization/time/remarks checks and `ResponderIncidentServiceTest` code-point
  limit validation.
- [ ] RSP-RESOLVE-02: resolution/history/audit are atomic across denied,
  invalid, reassignment/state-stale, retry, and persistence-failure paths;
  repeated resolution appends a new history cycle and does not duplicate audit.
  Automated evidence covers atomicity, failure rollback, retry, concurrent
  resolution, and history persistence. The required reopen-and-reclaim journey
  is not implemented in this Responder slice, so the full approved criterion
  remains open.
- [ ] RSP-UI-01: keyboard, screen-reader labels, focus, narrow layout, and
  mutation feedback are verified.
- [ ] RSP-E2E-01: promoted-account browser journey is manually demonstrated
  after issue #6 is available.
- [x] Product-owner approval of this specification and decisions is recorded
  in `workflow/reviews/2026-10-20-responder-slice-specification.md` and issue
  #7. Independent security/architecture approval is waived for this issue by
  explicit product-owner decision.

## Verification evidence

- Automated: `./gradlew test` passed with 681 tests, 2 skipped, and no failures
  or errors. `npm run check` passed TypeScript, ESLint, and 69 frontend tests.
  PostgreSQL-backed tests used a disposable local PostgreSQL 18 container and
  random isolated schemas. Claim evidence includes
  `ResponderIncidentPostgresTest.claimChecksCurrentCategoryAndLifecycleAndAtomicallyWritesAudit`,
  `ResponderIncidentPostgresTest.concurrentClaimsHaveOneWinnerAndOneSuccessAudit`,
  `ResponderIncidentPostgresTest.auditFailureRollsBackTheClaim`,
  `ResponderIncidentControllerTest.claimReturnsNoStoreSuccessOrSafeConflict`,
  and `ReporterWebSecurityTest.responderClaimRequiresResponderRoleAndCsrf`.
  Resolution evidence includes `ResponderIncidentPostgresTest` atomicity,
  rollback, idempotent replay, request-ID collision, authorization, and history
  tests, plus `ResponderResolutionRequestLimitFilterTest` for the pre-parse
  JSON bound. The Java suite used isolated random schemas and the dedicated
  test-only PostgreSQL database. These checks apply to the implementation
  commit recorded below.
- Manual/deployed: pending; requires approved implementation and issue #6 for
  the promoted-account journey.
- Human product approver/date: `lqr1019`, 2026-10-20 (Asia/Singapore).
- Independent security/architecture reviewers/date: waived for issue #7 by
  explicit product-owner decision; see the review record and issue comment.
- Implementation commit verified: `079c0b3` (backend and UI commits).

## Review note

Independent agent reviews found and corrected the Unicode whitespace
constraint gap, bounded-request parsing gap, and concurrent request-ID reuse
race. RSP-RESOLVE-02 remains unchecked because its complete approved wording
requires an actual reopen-and-reclaim journey. Agent review is not human
approval.
