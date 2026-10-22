# Administrator React + Spring Boot Vertical Slice

Status: **Product-owner approved on 2026-10-18 and amended on 2026-10-22 to
keep audit records internal-only; implementation and independent
security/architecture verification remain in progress.**

This document specifies the administrator experience to be migrated from the
JavaFX application to a React browser interface backed by Spring Boot and
PostgreSQL. It inventories every capability exposed by the current desktop
administrator view, reconciles that behavior with the canonical MP3 contracts,
and defines observable acceptance criteria before implementation begins.

The canonical policies under `.agents/` remain authoritative. If this draft and
a canonical policy disagree, the canonical policy wins until a human approves
and records a coordinated policy change.

## 1. Scope decision

The requested slice targets **administrator feature parity**, not only the
minimum administrator MVP. It therefore includes current desktop features that
`.agents/mvp-scope.md` presently labels post-MVP:

- administrator assignment, reassignment, unassignment, and resolution;
- account deletion and temporary-password reset;
- detailed operational statistics.

Approval of this specification schedules those features earlier; it does not
weaken any lifecycle, privacy, authorization, audit, or transaction rule. The
team must record product-owner approval before implementation starts.

### Current desktop inventory and disposition

| Current JavaFX surface | Behavior found | Web disposition |
| --- | --- | --- |
| Authenticated shell | Dashboard, Accounts, SLO configuration, Statistics, notification tray, own-password change, logout | Preserve as a responsive authenticated shell; notifications remain a decision |
| Administrator dashboard | Per-category SLO target overview; company incident table; search, filters, sort, refresh, open detail | Preserve and make queries paged/server-side |
| Incident detail | Lifecycle facts, resolution history, SLO, comments, image attachments, assign/reassign, unassign, resolve | Preserve with transaction-safe HTTP mutations; omit the non-functional audit placeholder |
| Accounts | Directory, pending-only toggle, promotion request detail and decision, Responder category configuration, password reset, deletion | Preserve; add MP3-required controlled Administrator provisioning |
| SLO configuration | Per-category current target, create new version, read-only history | Preserve with PostgreSQL version history and atomic audit |
| Statistics | Category/date filters, company metrics, Responder breakdown | Preserve and add the required Responder/Reporter filters and AI trend summary |
| Audit log | Newest-first activity table and event-detail dialog | Do not migrate; canonical audit writes remain an internal backend integrity control with no HTTP read endpoint or React surface |

JavaFX controls, virtual-thread UI tasks, double-click-only shortcuts,
process-global sessions, local aggregate files, and in-process event refreshes
are implementation details to replace, not behaviors to preserve. Web controls
must always expose a keyboard-operable named link or button even when pointer
shortcuts are also offered.

## 2. Goals and outcomes

The slice is complete when an authenticated Administrator can use a responsive
React application to monitor all company incidents, perform authorized incident
and account operations, govern responder access and SLO targets, inspect
privacy-safe statistics, and request an advisory operational
trend summary. Spring Boot is authoritative for identity, authorization,
validation, transactions, timestamps, privacy-safe data selection, and audit
creation. PostgreSQL is the shared source of truth.

The migration must preserve reusable domain and application rules while
replacing JavaFX views, process-global session assumptions, local-file
persistence, and in-process UI refresh mechanics.

## 3. Actors and global preconditions

- The actor is an enabled account whose persisted role is `ADMINISTRATOR`.
- The actor authenticated through the Administrator portal. Portal selection
  is an expected-role check, not authority by itself.
- Every protected HTTP request derives the actor from Spring Security's trusted
  request context. Browser-supplied actor, role, timestamp, ownership, or audit
  values are ignored.
- Every state-changing request passes CSRF validation and repeats authorization
  at execution time.
- Missing and forbidden protected resources use the same presentation-safe
  response wherever distinguishing them could leak information.
- A changed, disabled, or signed-out session clears administrator data from the
  React query/cache state and redirects to the appropriate authentication flow.

## 4. Information architecture

The authenticated administrator shell provides these destinations:

| Route | Purpose |
| --- | --- |
| Dashboard | SLO overview and company-wide incident list |
| Accounts | Accounts, promotion decisions, category access, reset, deletion, and administrator provisioning |
| SLO configuration | Current per-category targets and version history |
| Statistics | Company and responder operational measures plus advisory AI trend summary |

The shell also exposes the current login name and role, password change,
notifications if retained for the release, and logout. On narrow screens the
labelled sidebar becomes an accessible menu; it must not collapse into
unlabelled icons.

Browser history and deep links must work for list and detail routes. Protected
incident content, reporter identity, reset credentials, and session identifiers
must not appear in URLs.

## 5. Functional requirements

### ADM-AUTH-01 — Administrator portal authentication

The public role-entry page links to a clearly labelled Administrator login
portal backed by the same identity store and authentication service as the
other roles. The portal accepts only an enabled account whose persisted role is
`ADMINISTRATOR`. Invalid credentials, unknown or disabled accounts, and a
valid account presented to the wrong role portal receive the same generic
failure without username or role enumeration.

Successful authentication rotates the session identifier and establishes a
server-side session with production cookie controls. The Administrator portal
offers no public registration, role selector, or self-service privileged
account creation. Direct navigation to an administrator route while signed out
or under another role exposes no protected page data.

A missing or stale login CSRF token returns the stable, non-sensitive
`SECURITY_SESSION_EXPIRED` response. React refreshes the anonymous security
session and retries login at most once with the new token. The automatic retry
reuses credentials only in memory; a failed attempt retains the login name and
clears the password.

The session endpoint establishes the current SPA CSRF cookie. React reads the
cookie immediately before unsafe requests rather than retaining a token in
component state. Recovery is limited to the exact stale-token response and one
retry. An authenticated mutation is not retried if refresh reports an anonymous
session; protected client state is cleared and reauthentication is required.

### ADM-INC-01 — Company incident dashboard

The Dashboard shows one current SLO summary for every incident category and a
company-wide incident list. Each row shows:

- incident identifier and title;
- category and lifecycle status;
- creation/submission time;
- privacy-safe reporter label;
- current assignee or `Unassigned`; and
- evaluated SLO label/progress state.

The list supports:

- trimmed, case-insensitive search across title, description, and incident ID;
- multi-select category, status, and evaluated-SLO filters;
- assignment state;
- non-anonymous reporter and eligible responder identity filters;
- inclusive creation-date bounds interpreted in the user's display timezone;
- sorting by creation time, submission time, queue-entry time, title, status,
  or category in ascending or descending order; and
- reset to newest creation time first, with incident ID ascending as the stable
  tie-breaker for every ordering.

Selections within category, status, or SLO groups use OR. Different filter
groups use AND. Empty selections mean all. Anonymous incidents remain in
results when otherwise matched, but their internal reporter identity is absent
from row data and reporter-filter options. Search/filter/sort executes on the
server with bounded page size and pagination; React must not load the entire
company dataset to filter locally.

Loading, empty, denied/session-expired, invalid-filter, server-failure, and
retry states are explicit. A late response from an earlier query must not
replace a newer result or restore data after logout.

### ADM-INC-02 — Incident detail and collaboration

An Administrator can open any incident and view the latest authorized,
privacy-safe representation:

- identifier, title, description, category, status, reporter and assignee;
- created, submitted, withdrawn, queue-entry, first-assigned, and latest-
  assignment timestamps when applicable;
- complete resolution history;
- SLO state and progress;
- comment thread and comment composer; and
- authorized PNG/JPEG attachment metadata and images.

Anonymous reporters and their comments use `Anonymous reporter`. The normal
administrator interface offers no deanonymization path. Attachments use generic
display names for anonymous reports and are read through an authorization-
checked endpoint with safe content headers. Administrators may view but not add
attachments.

The current JavaFX `Audit timeline not available yet` placeholder is not a
feature to reproduce. The React page omits the section. Canonical audit writes
remain internal and are not exposed through an incident or global read API.

An Administrator may add a non-blank comment. Server-configured text limits
apply consistently in React, Spring validation, and the database. The UI keeps
unsent text after a recoverable failure and prevents duplicate submissions.

### ADM-INC-03 — Assign or reassign

For an unassigned `SUBMITTED` incident the action is labelled `Assign`; for an
`ASSIGNED` incident it is labelled `Reassign`. The selection contains only
enabled Responders whose current category access includes the incident's
category.

On confirmation the server rechecks the incident version/state, administrator
role, target account status/role, and category eligibility in the mutation
transaction. It sets or replaces the single assignee and required assignment
timestamps, then commits the incident change and one audit event atomically.
Concurrent or stale requests cannot overwrite a newer lifecycle or access
change. An ineligible or stale target returns a stable conflict/unavailable
result and leaves state and audit unchanged.

If no eligible Responders exist, the page explains that category access must be
configured first and offers a safe route to Accounts without changing the
incident.

### ADM-INC-04 — Unassign

An Administrator may unassign only an `ASSIGNED` incident. A confirmation names
the consequence before submission. Success clears the assignee, transitions
the incident to `SUBMITTED`, preserves its original queue position for the
current queue cycle, and commits one audit event in the same transaction.

Cancel changes nothing. Duplicate clicks produce at most one mutation. A stale
state returns a safe conflict result and refreshes the detail.

### ADM-INC-05 — Resolve

An Administrator may resolve only an `ASSIGNED` incident. Resolution remarks
must be non-blank and within the configured text limit; entered whitespace is
retained after non-blank validation. Success transitions the incident to
`RESOLVED`, appends rather than replaces resolution history, records the
application-generated UTC timestamp, and commits one resolution audit event in
the same transaction.

Invalid, stale, denied, or persistence-failed requests leave the incident and
audit unchanged. Recoverable failures preserve entered remarks. This slice does
not permit direct resolution of an unassigned `SUBMITTED` incident.

### ADM-ACC-01 — Account directory

Accounts are listed in deterministic login-name order with login name, current
role, status where relevant, responder categories, pending-promotion indicator,
and available actions. The directory supports a `pending promotion requests
only` filter. Deleted/tombstoned accounts are not actionable directory rows,
but their stable identifiers remain in historical incident and audit records.

Account data is server-paginated for web scale. Empty, loading, denied,
session-expired, and failure states reveal no privileged data.

### ADM-ACC-02 — Promotion review

For each pending Reporter-to-Responder request, an Administrator can review the
requester, requested categories, request time, and optional comments, then
approve or reject it. Approval requires selecting at least one category from
the request's requested categories. The decision cannot grant categories the
Reporter did not request. Cancellation is not part of this promotion workflow.

Approval changes the existing account to `RESPONDER`, grants exactly the
selected non-empty subset, preserves the account identifier and historical
references, records the selected categories and decision atomically, and
invalidates existing sessions so the user must reauthenticate through the
Responder portal. Rejection changes neither role nor category access. Both
decisions retain request history and create the required audit event.

Only a still-pending request for an enabled Reporter can be decided. Duplicate
or stale decisions fail without a second mutation or success audit.

### ADM-ACC-03 — Responder category access

An Administrator can replace an enabled Responder's permitted category set,
including clearing it to an empty set. The server ignores any browser-supplied
role and rechecks that the target is still an enabled Responder.

Success persists the new set and its audit event atomically and invalidates or
refreshes affected sessions as required by `.agents/security.md`. Revocation
immediately removes access to unassigned incidents and future claims in that
category. Incidents already assigned to the Responder remain viewable and
actionable until resolution, handoff, or reassignment. Submitting an unchanged
set is idempotent and must not create misleading change evidence.

### ADM-ACC-04 — Temporary-password reset

After explicit confirmation, an Administrator can initiate a reset for an
enabled account other than their own currently authenticated account. The server
generates a high-entropy one-time temporary password, stores only its salted
hash, invalidates/supersedes the old credential and active sessions, and sets a
24-hour expiry. The credential change and secret-free audit event commit
atomically.

The plaintext temporary password is returned only after durable commit, shown
once in a non-cacheable response/dialog, never logged or stored in browser
persistence, and cleared from client state when the dialog closes or the page
unmounts. The user must replace it before accessing any role workflow. A failed
reset never displays a secret.

The delivery mechanism outside the application remains a product decision; UI
copy instructs the Administrator to use an approved secure channel.

### ADM-ACC-04 acceptance criteria — temporary-password reset

- [ ] `ADM-ACC-04-A`: A reset succeeds only after the trusted acting account
  row is locked and rechecked as an enabled persisted Administrator. A
  self-reset, a missing/disabled/demoted actor, a non-existent or
  already-disabled target, a signed-out caller, a wrong-role caller, and a
  missing CSRF token each leave the target credential unchanged, return the
  stable generic response for that class, and create no success audit record.
- [ ] `ADM-ACC-04-B`: A successful reset replaces the target credential with a
  salted bcrypt hash of a freshly generated high-entropy one-time password,
  marks it temporary with an expiry 24 hours after the
  application-generated UTC instant, deletes the target's persisted sessions,
  and commits the credential change together with a secret-free
  `PASSWORD_RESET_INITIATED` audit record in one transaction.
- [ ] `ADM-ACC-04-C`: The plaintext temporary password is returned exactly once
  in a `Cache-Control: no-store` response body after durable commit. No
  plaintext, hash, or audit detail field contains the secret, and an injected
  persistence or audit failure returns a safe error carrying no secret and
  leaves neither a changed credential nor a success audit.
- [ ] `ADM-ACC-04-D`: Authentication is refused for a temporary credential whose
  expiry has passed, using the same generic sign-in failure as any other
  rejected login, and is accepted for an unexpired temporary credential.
- [ ] `ADM-ACC-04-E`: While a credential is marked temporary, every role
  workflow endpoint is refused and only the own-password replacement and logout
  endpoints remain available; replacing the password clears the temporary
  marker and expiry and restores normal access.
- [ ] `ADM-ACC-04-F`: The React account directory exposes the reset action,
  its confirmation dialog, single-reveal display, and cleared client state as
  specified in ADM-ACC-04. Not started; backend-only scope was approved for
  the current change.

Backend implementation and its partial automated evidence are recorded in the
[2026-10-22 temporary-password reset verification](test-evidence/2026-10-22-administrator-temporary-password-reset.md).
Criteria `ADM-ACC-04-A` through `ADM-ACC-04-C` remain unticked pending isolated
PostgreSQL evidence, which was skipped for want of a configured test database.
Criteria `ADM-ACC-04-D` and `ADM-ACC-04-E` have passing automated evidence but
remain unticked pending the product owner's approval of this criteria text.

### ADM-ACC-05 — Account deletion

After a destructive confirmation, an Administrator may disable/tombstone an
eligible account. The operation does not physically delete incidents, comments,
resolutions, promotion history, or audit evidence. Historical references use a
neutral deleted-account label. The target cannot authenticate, and existing
sessions are invalidated or rejected on their next use.

The account directory presents the action as a compact outlined danger button
labelled `Delete`, matching the original desktop page. Activating it opens a
modal confirmation dialog that identifies the account, states that the action
cannot be undone, explains that login access is disabled while incident and
audit history remains, and requires an explicit destructive confirmation.
Cancel or Escape closes the dialog without a request and returns focus to the
trigger.

An Administrator cannot delete their own currently authenticated account. The
server rechecks eligibility and commits the tombstone and audit event in one
transaction. This operation is irreversible in this slice.

Every tombstone request derives the acting Administrator from the trusted
security context, then locks that actor's account row with `SELECT ... FOR
UPDATE` and rechecks that the actor is still enabled with persisted role
`ADMINISTRATOR` before changing the target. The actor and target must differ.
Every path that disables, tombstones, or demotes an Administrator must follow
the same locking protocol. The target mutation and its audit event commit in
the transaction that holds the actor lock.

Reciprocal concurrent attempts by two Administrators to tombstone each other
cannot both commit: each target update conflicts with the other transaction's
actor-row lock, so PostgreSQL serialization or deadlock resolution aborts at
least one attempt. The application maps the losing attempt to a stable safe
conflict, writes no success audit for it, and does not retry it automatically.
Any explicit later attempt starts a new transaction and reauthorizes and
rechecks the acting Administrator. Together with the self-deletion denial,
this preserves at least one enabled Administrator without globally serializing
unrelated Administrator actions.

### ADM-ACC-05 acceptance criteria — account tombstoning

- [x] `ADM-ACC-05-A`: A tombstone succeeds only after the trusted acting
  account row is locked and rechecked as an enabled persisted Administrator;
  self-deletion, a missing/disabled/demoted actor, an ineligible target, and an
  audit or persistence failure leave the target unchanged and create no
  misleading success audit. Automated evidence is recorded in
  [the 2026-10-21 tombstone verification](test-evidence/2026-10-21-administrator-account-tombstone.md).
- [x] `ADM-ACC-05-B`: Isolated PostgreSQL concurrency tests prove reciprocal
  Administrator tombstone attempts cannot both commit, at least one enabled
  Administrator remains, the losing deadlock/serialization path returns a
  stable conflict without automatic retry or success audit, and an explicit
  retry performs fresh authorization. See the
  [2026-10-21 verification evidence](test-evidence/2026-10-21-administrator-account-tombstone.md).

### ADM-ACC-06 — Administrator provisioning

Although the desktop Accounts page does not provide it, MP3 requires controlled
administrator provisioning. The first Administrator is created only through
the one-time environment/CLI/bootstrap mechanism approved under
`.agents/mp2-grade-improvements.md`. Reuse is rejected and audited without
exposing credentials.

The bootstrap runs only when both `INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN` and
`INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD` are configured. If neither is set,
startup continues without creating an account; if only one is set or either
value fails validation, startup fails without exposing the supplied value. The
bootstrap refuses to create a first Administrator when a prior bootstrap
marker or any Administrator account already exists. Competing startup attempts
are serialized by a database uniqueness reservation so only one can create the
account. The account, salted password hash, one-time marker, and success audit
commit atomically. A rejected replay is audited; a failed transaction leaves
no partial account, credential, marker, or success audit. Rejection records,
logs, and errors contain no password or bootstrap secret.
Bootstrap audit events identify the trusted application as actor `SYSTEM`
with no actor account ID. A successful event targets the created account; a
rejection targets the `administrator-bootstrap` application operation. This
audit-only actor does not represent an account role or grant authorization.

The public Reporter registration request cannot choose or alter a role or
account status. Forged `role`, `status`, or similar privilege fields cannot
create a Responder or Administrator, and there is no public privileged
registration route.

After bootstrap, only an authenticated Administrator may create another
Administrator through a dedicated operation. No public form, login portal,
query parameter, or client-supplied role selector can create a privileged
account. Exact credential delivery and whether this control shares the Accounts
page require an approved security ADR before implementation.

### ADM-ACC-06 acceptance criteria — bootstrap and public registration

- [x] `ADM-ACC-06-A`: Public Reporter registration creates only a Reporter.
  Forged `role` and `status` fields cannot alter the persisted role, and
  unauthenticated Administrator/Responder registration routes are denied
  without creating an account.
- [x] `ADM-ACC-06-B`: Bootstrap is inactive when both configuration values are
  absent, fails safely when configuration is partial or invalid, and never
  includes supplied values in errors or logs.
- [x] `ADM-ACC-06-C`: With valid configuration and no existing Administrator
  or bootstrap marker, bootstrap stores a salted password hash and commits the
  account, marker, and success audit together. The audit actor is `SYSTEM`
  with no account ID, and its target is the created Administrator account.
- [x] `ADM-ACC-06-D`: A replay, an existing Administrator without a marker, or
  a competing startup cannot create another Administrator; rejection is
  audited as actor `SYSTEM` with no account ID and without credentials.
  Concurrent startup is verified against isolated PostgreSQL.
- [x] `ADM-ACC-06-E`: An injected persistence/audit failure leaves no partial
  account, credential, marker, or success audit.
- [x] `ADM-ACC-06-F`: First-run, secret-removal, failed-startup recovery, and
  administrator-access recovery instructions match the deployed configuration
  and never require committing or recording bootstrap values. If no supported
  self-service or break-glass recovery flow exists, the runbook must state that
  limitation and give safe escalation guidance instead of inventing a recovery
  path.
- [ ] `ADM-ACC-06-G`: Later Administrator provisioning remains unavailable
  until its credential-delivery and security/architecture design receives the
  required independent human approval; then its denied and allowed paths are
  verified.

### ADM-SLO-01 — Versioned category targets

For every incident category, an Administrator can view the current effective
target and read-only version history, then create a new version containing:

- average time-to-claim target in non-negative whole minutes;
- average time-in-progress target in non-negative whole minutes; and
- average reopen-rate target from 0 through 100 percent.

The browser shows field-level and summary errors, but the server remains
authoritative. Saving unchanged values is disabled/no-op and does not create a
version. A valid change receives a server-generated ID and UTC effective time,
commits with an audit event, applies prospectively, and never rewrites historic
cycle evaluation. Persistence failure leaves the prior target effective.

The React page follows the desktop interaction model: a labelled category
drop-down at the top selects one category at a time, a separate summary shows
that category's current effective target, and the editable new-version fields
remain visually distinct. Version history is paginated with the shared
Previous/Page/Next control also used by the account directory and dashboard.

### ADM-SLO-01 acceptance criteria — versioned category targets

- [x] `ADM-SLO-01-A`: An enabled authenticated Administrator can read every
  category's current target and chronological append-only history; signed-out,
  wrong-role, disabled, or demoted actors receive no SLO representation. See
  [the 2026-10-21 SLO verification](test-evidence/2026-10-21-administrator-slo-configuration.md).
- [x] `ADM-SLO-01-B`: A valid change containing non-negative whole-minute
  durations and a reopen-rate percentage from 0 through 100 creates one
  server-identified, application-UTC version and one secret-free audit event
  atomically, while the prior versions remain unchanged. See
  [the 2026-10-21 SLO verification](test-evidence/2026-10-21-administrator-slo-configuration.md).
- [x] `ADM-SLO-01-C`: Invalid fields, an unchanged target, a stale expected
  version, or a persistence/audit failure creates neither a target version nor
  a misleading success audit. Concurrent updates from the same current version
  have at most one winner and a safe conflict response for the loser. See
  [the 2026-10-21 SLO verification](test-evidence/2026-10-21-administrator-slo-configuration.md).
- [x] `ADM-SLO-01-D`: The React page exposes a labelled category selector, a
  distinct current-effective-target summary, editable new-version fields, and
  paginated read-only history for every category; it keeps entered values after
  recoverable errors, prevents duplicate submission, and provides loading,
  empty, validation, success, conflict, and retry states. Its visual
  presentation was approved in
  [the 2026-10-21 UI and implementation review](reviews/2026-10-21-administrator-slo-ui-review.md).
  Automated interaction evidence is recorded in
  [the 2026-10-21 SLO verification](test-evidence/2026-10-21-administrator-slo-configuration.md).

### ADM-STAT-01 — Operational statistics

An Administrator can filter statistics by responder, non-anonymous reporter,
category, inclusive time period, and any other approved incident scope shared
with the service. Anonymous incidents contribute to aggregate measures but
never expose or become selectable by reporter identity.

The company summary displays:

- incidents resolved;
- average time to claim;
- average time in progress;
- reopened-incident count and reopen rate; and
- Administrator-resolved count.

The responder breakdown displays responder-attributable resolution count,
average time in progress, reopen count/rate, and Administrator-resolved count
where meaningful. Queue-level time to claim is not falsely attributed to a
Responder. Empty populations show `No data`; zero-denominator calculations do
not invent a percentage. Charts, if used, have a textual/table equivalent.

An inverted period is rejected without querying protected statistics. All
calculations use persisted UTC lifecycle timestamps and the definitions in
`.agents/slo.md`.

### ADM-AI-01 — Advisory operational-trend summary

From the currently selected statistics scope, an Administrator may request an
AI-generated operational-trend summary. Immediately before data selection the
server reauthorizes the actor and rebuilds the aggregates; it does not trust
statistics posted back by React.

The provider payload may contain only aggregate counts, durations, rates,
category names, and time bounds. It contains no raw incident text, comments,
attachment data or names, credentials, session data, audit records, reporter
identity, or anonymous internal identity. Output is escaped plain text labelled
`AI-generated suggestion` and remains separate from saved data. There is no
apply or mutation action.

The shared AI gateway enforces configured input/output limits, per-account and
per-feature rate limits, timeouts, cancellation, at most one bounded retry for
an idempotent transient failure, safe error categories, and secret/content-
free logs. Accepted requests and outcome categories are audited without prompt
or response text. Failure leaves normal statistics usable.

Exact provider, model, and numeric limits remain blocked on the current course
guide and `workflow/ai-features.md` approval.

### ADM-AUD-INT-01 — Internal-only audit evidence

Canonical audit events remain append-only backend integrity records and retain
the transaction, redaction, privacy, and retention rules in
`.agents/audit-policy.md`. The web application exposes no audit-history HTTP
endpoint, React route, navigation destination, filter, event-detail view, or
download to any role. Ordinary feature tests may inspect persisted audit rows
through isolated repository/test fixtures; that verification access is not a
production application interface.

### ADM-AUD-INT-01 acceptance criteria

- [x] `ADM-AUD-INT-01-A`: The production web application has no audit-history
  controller or route, and the Administrator shell and public documentation
  expose no audit-history destination, while focused mutation tests continue to
  prove required append-only audit writes and rollback atomicity. Evidence is
  recorded in
  [the 2026-10-22 internal-audit-only verification](test-evidence/2026-10-22-internal-audit-only-scope.md).

### ADM-SELF-01 — Own password, notifications, and logout

The Administrator can replace their own password only after current-password
verification and matching valid new-password confirmation. The change and
secret-free audit event are atomic; plaintext and hashes never enter logs or
client persistence.

Logout invalidates the server session, clears privileged React state, and
returns to public role entry. A stale logout CSRF token triggers one session
refresh and at most one retry; an already-anonymous refreshed session completes
logout by clearing client state. If transient notifications are retained, they
must be scoped to the authenticated account/session and never leak anonymous
identity or protected records across sessions. Persistent notification history
is not required by this slice.

## 6. HTTP and application contract

Exact URI names may follow the approved web ADR, but the implementation must
expose typed operations with these semantics:

| Operation family | Read/mutation behavior |
| --- | --- |
| Administrator session | current actor, password change, logout |
| Incidents | paged query, detail, comment, attachment read, assign/reassign, unassign, resolve |
| Accounts | paged directory, pending requests, promotion decision, category replacement, reset, tombstone, privileged provisioning |
| SLO | current targets, category history, create target version |
| Statistics/AI | aggregate query, responder breakdown, trend-summary request |

Responses use explicit DTOs rather than serialized domain entities. DTOs
contain only fields authorized for this actor and representation. Mutation
requests contain business inputs and an opaque concurrency/version token where
needed, never actor or audit identity. Stable machine-readable error codes
cover validation, unauthenticated, unavailable/denied, conflict/stale,
rate-limited, and internal/persistence failure. React does not parse exception
messages.

List endpoints use bounded pagination and allowlisted sort fields. Identifiers
are validated before repository access. State-changing endpoints reject missing
or invalid CSRF tokens and unsupported content types.

## 7. Transaction, persistence, and concurrency rules

- PostgreSQL schema changes use forward-only versioned migrations.
- Each mutation and its required audit event commit in one database
  transaction; neither is visible on rollback.
- Conflicting lifecycle and account mutations use optimistic version checks or
  equivalent row locking proven by integration tests.
- Application-generated timestamps are UTC and database/server-clock defaults
  are not the source of business time.
- An incident has at most one assignee, enforced in application logic and
  database constraints where appropriate.
- SLO version history is append-only through ordinary application APIs. Audit
  events are append-only internal records with no ordinary read endpoint.
- Tests use isolated PostgreSQL databases/schemas and never production data.

## 8. React interaction and accessibility contract

- Follow `.agents/STYLE_GUIDE.md` and target WCAG 2.2 AA.
- Use semantic headings, labelled controls, real buttons/links, table headers,
  keyboard-operable menus, visible focus, non-color state cues, and live
  regions appropriate to the urgency of feedback.
- Dialogs trap focus, close with Escape when safe, and return focus to their
  trigger. Destructive and credential-reset dialogs state consequences.
- Prevent duplicate UI submissions without relying on that prevention for
  correctness.
- Preserve layout at 200% zoom and reflow from desktop to mobile width. Dense
  tables may scroll in a labelled region or become accessible cards.
- Long titles, descriptions, comments, identifiers, and AI output wrap safely.
- Render all untrusted content as text; do not use `dangerouslySetInnerHTML`.
- Do not store credentials, reset secrets, anonymous identity, raw AI content,
  or protected incident data in `localStorage` or analytics.
- Every page defines loading, empty, success, field-error, denied/session-
  expired, stale/conflict, server-failure, and retry behavior as applicable.

## 9. Acceptance criteria and evidence

The administrator slice is acceptable when:

- [ ] Only an enabled authenticated Administrator can load an administrator
  route or API representation; denied and missing resources do not leak data.
- [ ] The Administrator portal shares the backend identity store, accepts only
  the persisted Administrator role, uses generic failures, rotates the session
  on success, and exposes no public registration or role-selection path. A
  missing or stale CSRF token refreshes the anonymous security session and
  retries login at most once without weakening CSRF protection.
- [ ] The dashboard provides server-paginated company incidents with the
  specified search, filters, sorting, stable tie-breaker, privacy-safe identity
  options, SLO overview, and stale-response protection.
- [ ] Incident detail renders privacy-safe lifecycle, resolution, SLO, comment,
  and image-attachment data without exposing an anonymous identity.
  Backend automated evidence is recorded in
  [the 2026-10-22 administrator incident detail verification](test-evidence/2026-10-22-administrator-incident-detail-backend.md)
  and React automated evidence in
  [the 2026-10-22 administrator incident detail React verification](test-evidence/2026-10-22-administrator-incident-detail-react.md).
  The criterion remains open pending interaction/accessibility, browser
  end-to-end, and deployed verification.
- [ ] Assign/reassign, unassign, resolve, and comment operations reauthorize at
  execution, reject stale or invalid state, prevent duplicate mutation, and
  commit required audit evidence atomically.
  Backend automated evidence is recorded in
  [the 2026-10-22 administrator incident detail verification](test-evidence/2026-10-22-administrator-incident-detail-backend.md)
  and React automated evidence in
  [the 2026-10-22 administrator incident detail React verification](test-evidence/2026-10-22-administrator-incident-detail-react.md).
  The criterion remains open pending interaction/accessibility, browser
  end-to-end, and deployed verification.
- [ ] The account directory supports pending-promotion review, approval and
  rejection, Responder category replacement, one-time temporary-password
  reset, eligible account tombstoning, and controlled Administrator
  provisioning with the specified session effects.
- [ ] SLO target changes validate all fields, create prospective append-only
  versions, preserve history, and commit audit evidence atomically.
- [ ] Statistics use persisted lifecycle timestamps, apply the specified
  filters and formulas, handle empty populations, and include anonymous
  incidents only in non-identifying aggregates.
  Backend-only automated evidence is recorded in
  [the 2026-10-22 administrator statistics verification](test-evidence/2026-10-22-administrator-statistics-backend.md).
  The criterion remains open pending the React page, interaction/accessibility,
  browser end-to-end, and deployed verification.
- [ ] The operational-trend AI request is reauthorized and rebuilt server-side,
  sends only allowlisted aggregate data, renders escaped advisory text, has no
  mutation path, and fails without blocking statistics.
- [x] Audit records remain internal-only with no HTTP read endpoint or React
  destination, while required mutation-plus-audit atomicity remains verified by
  [the 2026-10-22 internal-audit-only verification](test-evidence/2026-10-22-internal-audit-only-scope.md).
- [ ] Password change and logout use server-side sessions, CSRF protection,
  request-time SPA tokens, bounded exact-error recovery, secret-free logging
  and auditing, session invalidation, and client cache clearing. Recovery does
  not retry an authenticated mutation after the refreshed session is anonymous.
- [ ] All pages meet the responsive, keyboard, focus, semantic HTML, error,
  reflow, and safe-rendering requirements in `.agents/STYLE_GUIDE.md`.
- [ ] The required automated, accessibility, and deployed checks below are
  recorded against the exact release commit under `workflow/test-evidence/`.

### ADM-ACC-03 acceptance criteria — Responder category access

- [x] `ADM-ACC-03-A`: The account directory and Administrator API expose an
  enabled Responder's current category set and opaque account version; the UI
  can replace the full set, including clearing it, and preserves recoverable
  failure feedback. Automated component and MVC evidence is recorded in
  [the 2026-10-22 verification record](test-evidence/2026-10-22-administrator-responder-category-access.md).
- [x] `ADM-ACC-03-B`: Missing CSRF, signed-out, wrong-role, malformed-version,
  unknown-category, and duplicate-category requests are rejected before the
  account mutation service is invoked, using privacy-safe errors. Automated
  MVC evidence is recorded in
  [the 2026-10-22 verification record](test-evidence/2026-10-22-administrator-responder-category-access.md).
- [x] `ADM-ACC-03-C`: PostgreSQL reauthorizes the actor and enabled Responder
  under lock; a successful change atomically replaces access, increments the
  account version, invalidates affected sessions, and writes a redacted audit
  event, while unchanged input writes no misleading audit and audit failure
  rolls back every effect. Automated evidence is recorded in
  [the 2026-10-22 verification record](test-evidence/2026-10-22-administrator-responder-category-access.md).
- [x] `ADM-ACC-03-D`: Two replacements submitted from the same account version
  produce one committed winner and one recoverable `CONFLICT`, with one access
  set, version increment, and success audit. The isolated concurrency test
  evidence is recorded in
  [the 2026-10-22 verification record](test-evidence/2026-10-22-administrator-responder-category-access.md).
- [x] `ADM-ACC-03-E`: Deployed verification proves revocation immediately
  removes unassigned queue and future-claim access while preserving access to
  incidents already assigned to the Responder. The repository user confirmed
  the end-to-end check in the current Codex chat on 2026-10-22; the result is
  recorded in
  [the 2026-10-22 verification record](test-evidence/2026-10-22-administrator-responder-category-access.md).

### Required verification

The following suites are required before the slice can be called complete:

1. Domain/application tests for every allowed and denied administrator action,
   lifecycle rule, validation limit, privacy transformation, SLO formula, and
   audit change.
2. Spring MVC/security tests for authentication, expected-role portal checks,
   CSRF, resource authorization, generic missing/denied responses, content
   types, request limits, session invalidation, and safe error DTOs.
3. PostgreSQL integration tests for empty-database migrations, round trips,
   transaction rollback, audit atomicity, version conflicts, and concurrent
   assignment/reassignment/deletion/access changes.
4. React component tests for semantic structure, keyboard/focus behavior,
   validation, confirmation, loading/empty/error states, stale responses, and
   malicious/long text rendering. Purely visual composition and styling do not
   require brittle DOM-structure or CSS-class assertions when a documented human
   review approves them; this exception does not cover interactive behavior,
   accessibility semantics, or state handling.
5. Browser end-to-end tests covering each navigation destination and one
   successful plus denied/stale path for each mutation family.
6. AI gateway tests for aggregate-only payloads, prompt injection, anonymous
   identity exclusion, malicious output, rate limit, timeout, retry, malformed
   response, unavailable provider, cancellation, and absence of content in
   logs/audits.
7. Responsive and accessibility verification at wide, medium, and narrow
   widths, keyboard-only operation, 200% zoom, and automated accessibility
   scanning.
8. Deployment smoke test over HTTPS using the exact release commit and an
   isolated non-production dataset.

Partial automated evidence: on 2026-10-18, working tree based on `541825c`,
`npm.cmd test -- --run src/App.test.tsx` passed all 12 focused tests,
`npm.cmd run check` passed typecheck, lint, and all 15 React tests,
`npm.cmd run build` produced the production frontend, and
`gradlew.bat --no-daemon check` completed successfully. The parameterized React
tests cover Administrator and Reporter login, successful fresh-token recovery,
generic retry failure, bounded retry count, retained login name, and cleared
password. The criterion remains open pending its other required and deployed
evidence.

Evidence must record exact commands, commit SHA, environment, passed/failed/
skipped checks, sanitized outputs, and remaining risks under
`workflow/test-evidence/`. Agent output alone does not satisfy an approval gate.

## 10. Traceability matrix

| Requirement | Canonical source | Primary evidence |
| --- | --- | --- |
| ADM-AUTH-01 | requirements, security, authorization matrix | authentication/session/portal security tests |
| ADM-INC-01/02 | requirements, authorization matrix, anonymity, SLO | MVC + React + E2E list/detail tests |
| ADM-INC-03/04/05 | incident lifecycle, authorization matrix, audit | service + PostgreSQL conflict/atomicity + E2E tests |
| ADM-ACC-01/02/03 | requirements, authorization matrix, security | service + MVC + session invalidation + E2E tests |
| ADM-ACC-04/05/06 | requirements, security, audit, MP2 improvements | credential/tombstone/bootstrap security tests |
| ADM-ACC-05-A/B | authorization matrix, security, persistence, audit | trusted-actor/self-denial tests plus isolated PostgreSQL reciprocal-tombstone concurrency, rollback, audit, and explicit-retry tests |
| ADM-ACC-06-A/B | authorization matrix, security, issue #5 | forged-role HTTP tests, route-denial tests, bootstrap configuration unit tests; [provisional issue #5 evidence](test-evidence/2026-10-19-issue-5-admin-bootstrap.md) |
| ADM-ACC-06-C/D | persistence, audit, MP2 improvements, issue #5 | isolated PostgreSQL success, system actor/target attribution, replay, pre-existing-admin, and concurrent-startup tests; [provisional issue #5 evidence](test-evidence/2026-10-19-issue-5-admin-bootstrap.md) |
| ADM-ACC-06-E | persistence, audit, issue #5 | isolated PostgreSQL audit-write failure and transaction rollback test; [provisional issue #5 evidence](test-evidence/2026-10-19-issue-5-admin-bootstrap.md) |
| ADM-ACC-06-F | deployment, issue #5 | first-run/recovery runbook and deployment-workflow configuration review |
| ADM-ACC-06-G | security ADR gate, issue #5 | named independent approval plus protected route/mutation tests; currently blocked |
| ADM-SLO-01 | SLO, audit | calculation + versioning + PostgreSQL tests |
| ADM-SLO-01-A/B/C/D | SLO, authorization matrix, persistence, audit, style guide | MVC security and validation tests, isolated PostgreSQL authorization/atomicity/concurrency tests, and React interaction/accessibility tests |
| ADM-STAT-01 | requirements, SLO, anonymity | aggregation/privacy + React tests |
| ADM-AI-01 | AI policy, anonymity, security | fake-provider adversarial/resilience tests |
| ADM-AUD-INT-01 | requirements, audit policy, persistence | source-boundary inspection, React navigation test, and focused mutation/audit atomicity tests |
| ADM-SELF-01 | requirements, security, audit | password/session/CSRF tests |

## 11. Decisions required before implementation

1. Full desktop-parity scope is scheduled in this slice despite the current
   post-MVP labels.
2. Administrator provisioning, migration, session, credential-delivery, API,
   cookie, and asset-topology decisions are recorded in the architecture ADRs.
3. Centrally enforced text, page-size, password, and request limits must be
   recorded before the affected endpoint is enabled.
4. Persistent notification history is deferred; transient notifications are
   permitted only where they meet ADM-SELF-01.
5. Audit events remain an internal backend integrity control. The web
   application exposes no incident-scoped or global audit-history endpoint,
   navigation destination, filter, detail view, or download.
6. The SoCLaaS provider contract and published quota defaults are recorded in
   `workflow/ai-features.md`. ADM-AI-01 may be built and tested against the
   deterministic fake provider, but production model selection, the actual key
   budget, application limits, and the undocumented provider-retention risk
   require human security/architecture approval before production integration;
   its acceptance criterion remains unticked until verified.
7. The single-origin React/Spring Boot topology and Neon storage direction are
   approved in `.agents/deployment_strategy.md`; remaining implementation
   choices are recorded in the architecture ADRs.

For the bounded issue #5 work, criteria `ADM-ACC-06-A` through
`ADM-ACC-06-F` are in scope. Criterion `ADM-ACC-06-G` remains gated: no later
Administrator provisioning endpoint or UI may be added until the required
independent security/architecture approval is recorded.

## 12. Approval

- Product owner: repository user (approval provided in the current Codex chat)
- Security/architecture reviewer: approved by owner
- Approved scope/date: full administrator feature-parity scope, 2026-10-18
- Review evidence: `reviews/2026-10-18-administrator-slice-specification.md`
