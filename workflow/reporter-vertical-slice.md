# Reporter React + Spring Boot Vertical Slice

Status: **Product-owner approved on 2026-10-18. The first Reporter walking
slice is implemented locally and its required development code-quality findings
are resolved. Broader feature implementation, independent security/architecture
review, and release verification remain in progress.**

This document specifies the Reporter experience to be migrated from the JavaFX
application to a React browser interface backed by Spring Boot and PostgreSQL.
It reconciles the current desktop surface with the canonical MP3 contracts and
defines observable acceptance criteria for implementation and verification.

The canonical policies under `.agents/` remain authoritative. If this draft and
a canonical policy disagree, the canonical policy wins until a human approves
and records a coordinated policy change.

### Current implementation checkpoint

The first walking slice currently provides:

- role entry with generic wrong-portal authentication failure;
- public registration only in the Reporter portal, with server-controlled
  `REPORTER` creation, adaptive password hashing, and atomic registration audit;
- an authenticated Reporter shell sharing the Administrator shell's responsive
  labelled mobile navigation behavior;
- a direct-submission form with required title, description, category,
  explicit unchecked anonymity, CSRF protection, and duplicate-submission key;
- a bounded, newest-first owned-incident list with basic text search and paging;
- application-generated UTC submission timestamps and atomic PostgreSQL
  incident/audit persistence; and
- safe denied and disabled-Reporter list behavior that returns the same
  non-enumerating unavailable response.

This checkpoint does **not** yet provide drafts, incident detail, advanced
filters and sorting, edit, withdrawal, reopen, attachments, promotion, AI
assistance, own-password change, or the complete failure/concurrency and
deployed evidence required by this specification.

## 1. Scope decision

This specification defines **Reporter feature parity**, not only the minimum Reporter
MVP. In addition to direct submission, own-incident tracking, promotion, and AI
description assistance, it schedules these current post-MVP capabilities:

- incomplete drafts;
- editing and withdrawing an eligible incident;
- reopening a resolved incident with a required explanatory comment; and
- the advanced own-incident search, filter, and sort controls shared with the
  desktop application.

Approval of this specification schedules those features earlier. It does not
weaken lifecycle, ownership, privacy, attachment, audit, or transaction rules.

The proposed anonymous-submission default is **unchecked**. The Reporter must
make an explicit, visible choice before an anonymous report is submitted. This
matches the current desktop behavior and does not change the rule that
anonymous status cannot be removed after submission.

### Current desktop inventory and disposition

| Current JavaFX surface | Behavior found | Web disposition |
| --- | --- | --- |
| Reporter workspace | Submit incident, anonymity control, own-incident table, search/filter/sort, refresh, open detail | Preserve with server-side paging and trusted request identity |
| Incident detail | View lifecycle, resolution history, comments, and images; edit, withdraw, or reopen when eligible | Preserve with transaction-safe HTTP operations and stale-version checks |
| Draft support | Domain/application support exists but is not exposed as a complete web workflow | Include save, resume, edit, attach, and submit |
| Promotion workflow | Select requested categories, add optional comments, and track the latest request | Preserve with PostgreSQL persistence and session-safe role change |
| AI assistance | Required by MP3 but not a completed desktop production integration | Add the approved advisory description-improvement workflow |
| Authenticated shell | Own password change, logout, and notification affordance | Preserve password/logout; persistent notifications remain deferred |

JavaFX controls, virtual-thread UI tasks, process-global sessions, local files,
and in-process refresh events are implementation details to replace. Every
pointer interaction must also have a keyboard-operable named control.

## 2. Goals and outcomes

The slice is complete when an authenticated Reporter can register and sign in,
create and track only their own incidents, use drafts and eligible lifecycle
actions, upload supported images, request and track Responder promotion, and
review an advisory AI description suggestion without allowing AI to submit or
mutate an incident.

Spring Boot is authoritative for identity, role, ownership, lifecycle state,
validation, timestamps, privacy-safe presentation, transactions, and audit
creation. PostgreSQL is the shared source of truth. React owns interaction and
presentation state only.

## 3. Actors and global preconditions

- The actor is an enabled account whose persisted role is `REPORTER`.
- The actor authenticated through the Reporter portal. Portal choice is an
  expected-role check, not authority by itself.
- Every protected request derives the actor from Spring Security's trusted
  request context. Browser-supplied actor, role, owner, timestamp, audit, or
  anonymous-identity values are ignored.
- Every state-changing request passes CSRF validation and repeats authorization
  against current persisted state in the mutation transaction.
- Missing and forbidden protected incidents have the same presentation-safe
  response so another Reporter's incident cannot be enumerated.
- Logout, disablement, role change, or session expiry clears Reporter data and
  drafts from React memory and returns to the appropriate authentication flow.

## 4. Information architecture

The public and authenticated Reporter experience provides:

| Route | Purpose |
| --- | --- |
| Role entry | Choose the Reporter, Responder, or Administrator portal |
| Reporter sign in | Authenticate an existing Reporter |
| Reporter registration | Create a Reporter account only |
| My incidents | Search, filter, sort, and page through owned incidents |
| New incident | Save a draft or submit directly, with explicit anonymity |
| Incident detail | View an owned incident and perform currently allowed actions |
| Promotion | Request Responder access and track request status |

The authenticated shell also exposes the current login name and role, own
password change, and logout. Browser history and deep links work for owned
incident routes. Incident content, session data, and protected identity never
appear in URLs.

## 5. Functional requirements

### REP-AUTH-01 — Role entry, registration, and authentication

The public role-entry page offers clearly labelled Reporter, Responder, and
Administrator portals backed by one identity store. Only the Reporter portal
offers registration. Registration always creates an enabled Reporter and has
no role selector, hidden privileged role, or client-controlled authority.

Login names are case-sensitive. Registration validates the approved login and
password rules, confirmation, and duplicates on the server and stores only an
adaptive salted password hash. Invalid credentials, unknown or disabled
accounts, and a valid account used at the wrong portal receive the same generic
authentication failure.

Successful login rotates the session identifier. Direct access to a Reporter
route while signed out or under another role exposes no protected data.

A missing or invalid CSRF token returns a stable, non-sensitive
`SECURITY_SESSION_EXPIRED` response rather than the protected-resource
unavailable response. During Reporter registration or login, React refreshes
the anonymous security session and retries that pre-handler rejection at most
once with the new token. Registration retains all entered values if refresh or
retry fails. Login retains the login name but clears the password after a failed
attempt, while the automatic retry reuses the credentials only in memory.

### REP-INC-01 — My incidents

The list contains only incidents whose protected reporter identifier matches
the current actor, including that Reporter's anonymous incidents and drafts.
Each row shows the incident identifier, title or `Untitled draft`, category,
status, creation time, current assignee label where applicable, and evaluated
SLO state where applicable.

The server provides bounded pagination, trimmed case-insensitive search across
title, description, and incident identifier, multi-select category and status
filters, assignment state, evaluated SLO state, inclusive creation-date bounds,
and the approved sort fields. Selections within one group use OR; different
groups use AND; empty selections mean all. The default order is newest creation
time first with incident identifier ascending as the stable tie-breaker.

Reporter and Responder identity filters are absent. Loading, empty, denied or
expired-session, invalid-filter, failure, and retry states are explicit. A late
response cannot replace a newer query or restore protected data after logout.

### REP-INC-02 — Save and resume a draft

A Reporter may save an incomplete incident as `DRAFT`. Category and anonymity
must be explicit valid values; title and description may be empty. The server
sets the owner, identifier, version, and application-generated UTC creation and
draft timestamps and atomically appends the required audit event.

Only the owner may view, edit, attach images to, or submit the draft. Saving an
unchanged draft is idempotent and does not create misleading audit evidence.
Concurrent or stale saves cannot overwrite a newer version.

### REP-INC-03 — Submit an incident

A Reporter may submit a new incident directly or submit an owned draft. The
form requires a non-blank title, non-blank description, and category. The title
is trimmed before storage; description whitespace is retained after non-blank
validation. The centrally configured field limits approved with this slice are
enforced consistently by React guidance, Spring validation, and database
constraints.

`Submit anonymously` is an accessible unchecked checkbox with explanatory copy
that accurately describes application-level confidentiality and media-metadata
risk. Its explicit boolean value is persisted. After submission, anonymity
cannot be removed. Success sets submission and queue timestamps, transitions
to `SUBMITTED`, and commits the incident and audit event atomically.

Duplicate clicks do not create duplicate incidents. Invalid, denied, stale, or
persistence-failed requests retain recoverable form input and leave no partial
incident or success audit.

### REP-INC-04 — Incident detail and collaboration

An owner may open the latest authorized representation of their incident and
view its identifier, title, description, category, anonymous flag, status,
lifecycle timestamps, assignee where applicable, SLO state, complete resolution
history, privacy-safe comments, and authorized image attachments.

The owner continues to see their own incident when it was submitted
anonymously. This does not create an identity field in incident-facing transfer
objects used by other roles. The detail response exposes only actions currently
available under lifecycle and ownership rules.

Loading, unavailable, stale, and failure states do not retain another incident's
content. The browser never renders incident text, comments, filenames, or AI
output as raw HTML.

### REP-INC-05 — Edit an eligible incident

The owner may edit an owned `DRAFT` or an owned, unassigned `SUBMITTED`
incident. The same content validation applies as for the target state. Editing
a submitted incident preserves its original submission and queue timestamps.
Anonymity may be changed while a draft remains unsubmitted; it cannot be
removed or changed after submission.

The server rechecks ownership, current state, assignment, and version inside
the transaction. A stale claim or other conflicting transition wins over the
edit; the edit fails safely and does not create an audit event.

### REP-INC-06 — Withdraw an eligible incident

After explicit confirmation, the owner may withdraw only an owned, unassigned
`SUBMITTED` incident. Success records the application-generated UTC withdrawal
time, transitions to `WITHDRAWN`, and commits one audit event atomically.

Cancel changes nothing. A concurrent claim, duplicate request, denial, or
persistence failure leaves the winning/current state intact and creates no
misleading success audit.

### REP-INC-07 — Reopen with required follow-up

The owner may reopen only an owned `RESOLVED` incident by submitting a
non-blank explanatory comment within the approved comment limit. Success
appends the comment, preserves resolution history, increments the reopen count,
clears the assignee, transitions to `SUBMITTED`, and sets a new queue-cycle
timestamp in one transaction with one incident audit event.

For an anonymous incident, other roles see the comment author as
`Anonymous reporter`. A blank, stale, denied, duplicate, or persistence-failed
request commits neither comment, transition, nor success audit.

### REP-ATT-01 — Image attachments

The owner may add a PNG or JPEG attachment only to a persisted `DRAFT` or owned
unassigned `SUBMITTED` incident. The service detects content type rather than
trusting the extension and enforces 10 MiB per file, 5 attachments per incident,
25 MiB aggregate size, and 40 million decoded pixels. Video and audio are
rejected.

Stored objects use generated identifiers outside executable resources. Reads
reauthorize against the incident and use safe content headers. Anonymous
incidents display generic image names to every role; protected original names
and internal reporter identity never appear in keys or URLs. A failed metadata
or object write is compensated so no inaccessible orphan or partial metadata
is presented as success.

### REP-PRO-01 — Request and track Responder promotion

An enabled Reporter with no pending request may select one or more valid
incident categories, add optional comments within the approved limit, and
submit a promotion request. The server derives the requester from the session,
persists `PENDING`, and commits the request and audit event atomically.

The Reporter can view the current request's requested categories, submitted
time, status, and privacy-safe decision outcome. They cannot approve, reject,
or alter a submitted request. A duplicate pending request, empty categories,
tampered actor or role, denial, or persistence failure creates no second request
or misleading audit.

Approval changes the same account to `RESPONDER`, grants only approved
categories, preserves identity/history, invalidates existing sessions, and
requires sign-in through the Responder portal. Rejection leaves role and access
unchanged.

### REP-AI-01 — Advisory description improvement

An authenticated Reporter may request help for the title, category, and current
description held in the incident form. Immediately before prompt construction,
the server reauthorizes the current actor. The feature sends only normalized
category, title, and current description within the approved 16 KiB feature
record limit.

It sends no account or identity data, anonymity value, ownership metadata,
credentials, sessions, attachment data or names, audit data, other incidents,
hidden metadata, or server errors. Output is escaped plain text labelled
`AI-generated suggestion`, limited to 16 KiB and 1,200 tokens, and visually
separate from saved content.

Copying or applying a suggestion changes only the local editable description.
Saving or submitting remains a separate CSRF-protected normal operation. Rate
limits, timeout, provider errors, malformed output, cancellation, and an open
circuit leave the form and all non-AI incident workflows usable. Accepted
requests and outcome categories are audited without prompt or response text.

Production implementation of this requirement remains blocked until
`workflow/ai-features.md` receives the required product and independent
security/architecture approvals.

### REP-SELF-01 — Own password and logout

The Reporter can change their password only after confirming the current
password and satisfying the approved credential rules. Success stores only a
new salted hash, rotates or invalidates sessions as specified, and creates a
secret-free audit event atomically.

Logout invalidates the server session and clears role-specific React state.
Credentials and session identifiers are never written to browser persistence,
logs, audit records, or URLs.

## 6. HTTP and application contract

- React uses same-origin `/api/v1/**` JSON or form endpoints and sends the
  server-issued CSRF token on every state-changing request.
- Login uses the role-specific Reporter endpoint; registration exposes no role
  parameter. Protected Reporter endpoints require persisted `REPORTER` role.
- Incident collection responses are paged and bounded. Detail and mutation
  routes use opaque incident identifiers and never accept an authoritative
  owner or actor identifier.
- Mutable incident requests carry the last observed version. A stale version
  returns a stable conflict response without overwriting newer state.
- Validation errors use stable field codes. Denied and missing incidents use a
  shared presentation-safe response. Production errors expose no stack trace,
  SQL, storage key, provider payload, or internal identity.
- Successful protected responses and authentication/session responses use
  private/no-store caching.

The initial route family is:

```text
POST   /api/v1/auth/reporter/login
POST   /api/v1/auth/reporter/register
GET    /api/v1/reporter/incidents
POST   /api/v1/reporter/incidents/drafts
POST   /api/v1/reporter/incidents
GET    /api/v1/reporter/incidents/{incidentId}
PUT    /api/v1/reporter/incidents/{incidentId}
POST   /api/v1/reporter/incidents/{incidentId}/submit
POST   /api/v1/reporter/incidents/{incidentId}/withdraw
POST   /api/v1/reporter/incidents/{incidentId}/reopen
POST   /api/v1/reporter/incidents/{incidentId}/attachments
GET    /api/v1/reporter/incidents/{incidentId}/attachments/{attachmentId}
GET    /api/v1/reporter/promotion-request
POST   /api/v1/reporter/promotion-request
POST   /api/v1/reporter/ai/description-suggestion
```

Endpoint names may be refined without changing observable behavior, but the
authorization and privacy boundaries may not be weakened.

## 7. Transaction, persistence, and concurrency rules

- Flyway introduces forward-only schema changes; already-applied migrations are
  never edited.
- Each incident, attachment-metadata, promotion, password, or lifecycle
  mutation commits with its audit event in one database transaction.
- PostgreSQL constraints enforce ownership references, lifecycle-compatible
  nullability, attachment limits where feasible, and a single pending promotion
  request per Reporter.
- Optimistic versioning or equivalent locked compare-and-set semantics prevents
  stale edit, submit, withdraw, reopen, and duplicate mutations.
- Application-generated timestamps are UTC. Browser time is display-only.
- Tests use isolated PostgreSQL databases or rolled-back transactions and never
  access production data.
- Object-storage compensation and retry behavior follow `workflow/neon-services.md`;
  an object-store failure never produces successful attachment metadata.

## 8. React interaction and accessibility contract

- Use the shared Calm Blue Operations tokens and components in
  `.agents/STYLE_GUIDE.md`; do not create an unrelated Reporter theme.
- Forms have persistent labels, field errors, an accessible summary, useful
  autocomplete, visible focus, and keyboard operation.
- The anonymity checkbox is explicitly labelled and described; its persisted
  value is shown after save or submission.
- Buttons prevent accidental duplicate submissions without treating browser
  state as the correctness boundary. Recoverable failures retain user input.
- Lists and detail layouts reflow at narrow widths, support 200% zoom, and do
  not rely on color or pointer-only rows.
- Loading placeholders never expose the previous user or incident. Logout and
  role changes clear all Reporter query, form, suggestion, and attachment state.
- AI text is rendered as plain text, clearly labelled, and has explicit Copy
  and Apply actions; Apply never submits.

## 9. Acceptance criteria and evidence

All criteria remain open until their exact automated and required manual or
deployed evidence is recorded.

- [ ] `REP-AC-01` — Public registration is available only from the Reporter
  portal, creates only a Reporter, stores no plaintext password, and rejects
  duplicate, invalid, and tampered privileged-role requests generically. A
  missing or stale CSRF token returns `SECURITY_SESSION_EXPIRED`; the browser
  refreshes its security session, retries registration at most once, and
  preserves the form if recovery fails.
- [ ] `REP-AC-02` — Reporter login rotates the session, rejects wrong-role,
  unknown, disabled, and invalid-credential attempts without enumeration, and
  protected routes expose no data to signed-out or non-Reporter sessions. A
  missing or stale CSRF token returns `SECURITY_SESSION_EXPIRED`; the browser
  refreshes its anonymous security session and retries login at most once.
- [ ] `REP-AC-03` — My incidents returns only the current Reporter's owned
  incidents, including their anonymous incidents and drafts, with bounded
  deterministic paging and the specified search/filter/sort semantics.
- [ ] `REP-AC-04` — A Reporter can save an incomplete draft, resume it after a
  restart, submit it once complete, and direct-submit a valid incident; required
  fields, normalization, limits, timestamps, anonymity, and audit evidence are
  persisted atomically.
- [ ] `REP-AC-05` — Another Reporter, a wrong-role session, and a direct HTTP
  request cannot list, view, edit, withdraw, reopen, attach to, or use AI data
  from an incident they do not own; missing and forbidden cases do not reveal
  existence or reporter identity.
- [ ] `REP-AC-06` — Checked and unchecked anonymity values reach persisted
  state; checked incidents remain visible to their owner while responder,
  administrator, audit, attachment, AI, error, log, and filter representations
  contain no anonymous identity leakage.
- [ ] `REP-AC-07` — Edit and withdraw succeed only for an owned draft or owned
  unassigned submitted incident as applicable; concurrent claim, stale version,
  duplicate, invalid, denied, and persistence-failed requests preserve the
  winning/current state and create no partial or misleading success audit.
- [ ] `REP-AC-08` — Reopening an owned resolved incident requires a non-blank
  explanation and atomically appends the comment, preserves resolution history,
  increments reopen count, clears assignment, starts a new queue cycle, and
  audits the change; all failure paths commit none of those effects.
- [ ] `REP-AC-09` — Attachment upload/read enforces ownership and lifecycle,
  detected PNG/JPEG type, byte/count/aggregate/pixel limits, generated storage
  keys, anonymous generic names, safe response headers, and failed-write
  compensation; unsupported media and spoofed extensions fail safely.
- [ ] `REP-AC-10` — A Reporter can create at most one pending promotion request
  with at least one category and track its status; tampered, duplicate, denied,
  stale, and persistence-failed requests create neither a second request nor a
  misleading audit, and an approved role change invalidates the old session.
- [ ] `REP-AC-11` — Reporter AI requests are reauthorized, send only title,
  normalized category, and current description within the approved limits,
  exclude all identity and unrelated data, render safe bounded plain text, and
  Apply changes only local form state; rate, timeout, malformed, cancellation,
  and provider failures leave ordinary incident workflows usable.
- [ ] `REP-AC-12` — Password change and logout use server-side sessions, CSRF
  protection, secret-free atomic audit, required session rotation/invalidation,
  and complete clearing of Reporter data from client memory.
- [ ] `REP-AC-13` — The Reporter routes satisfy responsive reflow, keyboard and
  focus operation, semantic labels, error identification, screen-reader status,
  safe untrusted-text rendering, and 200% zoom requirements.
- [ ] `REP-AC-14` — Required Java, React, isolated PostgreSQL, concurrency,
  migration, privacy, security, accessibility, packaged-artifact, and deployed
  smoke checks pass for the exact release commit, and independent review and
  human release approval are recorded.

Current criterion status:

- `REP-AC-01` is partial: Reporter-only registration, hashing, CSRF, duplicate,
  validation, rollback, secret-absence, and stale-CSRF recovery paths have
  automated coverage; complete tampered-request and exact-commit evidence
  remain required.
- `REP-AC-02` is partial: role-gated login and Reporter route protection are
  covered, stale login CSRF refresh-and-single-retry behavior has React coverage,
  and a disabled Reporter's list request uses the safe denied response; complete
  session-rotation/invalidation and deployed evidence remain required.
- `REP-AC-03` is partial: ownership-scoped basic search and bounded paging are
  implemented; the approved filters, sort fields, SLO state, drafts, and
  isolated PostgreSQL query evidence remain required.
- `REP-AC-04` is partial: valid direct submission, validation, UTC timestamps,
  explicit anonymity, idempotency, and atomic incident/audit persistence are
  implemented; draft save/resume/submit and isolated PostgreSQL evidence remain
  required.
- `REP-AC-05` and `REP-AC-06` are partial for list and direct-submission paths;
  the remaining detail, mutation, attachment, and AI privacy paths are not yet
  implemented or proved.
- `REP-AC-07` through `REP-AC-11` remain open because their workflows are not
  implemented in this walking slice.
- `REP-AC-12` is partial for logout only; own-password change and complete
  client-state/session verification remain required.
- `REP-AC-13` is partial: the shared responsive shell and mobile menu behavior
  have component coverage; manual narrow-width, keyboard, screen-reader, and
  200% zoom verification remains required.
- `REP-AC-14` is partial: local build, automated tests, and packaged-artifact
  checks pass, while the exact release commit, isolated database, deployed
  smoke, independent review, and human release approval remain outstanding.

### Required verification

Automated evidence must include:

- Spring controller/security tests for role portal, registration, CSRF,
  stable CSRF-expiry recovery, generic denial, cache/security headers, and
  session rotation/invalidation;
- application/domain tests for all allowed and invalid Reporter transitions;
- repository integration tests for paging, ownership scope, atomic audit,
  optimistic concurrency, restart persistence, and promotion uniqueness;
- attachment tests for authorization, type detection, all size limits, safe
  names/headers, and object/metadata failure compensation;
- React tests for all interaction states, anonymity semantics, stale request
  suppression, input preservation, AI Apply-versus-Submit, and cache clearing;
- adversarial AI tests with a fake provider for allowlisting, injection,
  output escaping, limits, retries, timeouts, cancellation, and safe logs; and
- production artifact, migration-from-empty, accessibility, and deployed
  Reporter login/direct-submit/detail smoke checks.

Verification evidence:

- Automated (partial stale-login recovery evidence): on 2026-10-18, working
  tree based on `541825c`, `npm.cmd test -- --run src/App.test.tsx` passed all
  12 focused tests, `npm.cmd run check` passed typecheck, lint, and all 15 React
  tests, `npm.cmd run build` produced the production frontend, and
  `gradlew.bat --no-daemon check` completed successfully. The parameterized
  React tests cover Reporter and Administrator login, successful fresh-token
  recovery, generic retry failure, bounded retry count, retained login name,
  and cleared password.
- Automated (partial walking-slice evidence): `gradlew.bat --no-daemon check
  bootJar` passed on 2026-10-18,
  including Java tests, frontend typecheck/lint and 12 component tests,
  production frontend build, Boot JAR creation, and packaged-artifact checks.
  Focused Reporter and renamed registration tests also passed with
  `gradlew.bat --no-daemon test --tests
  "com.company.incidentdesk.web.security.ReporterAccountRegistration*"
  --tests
  "com.company.incidentdesk.web.security.JdbcReporterAccountRegistrationStoreTest"
  --tests "com.company.incidentdesk.web.reporter.*"
  -PfrontendSkipBuild=true`. This evidence does not prove the full criteria
  above; isolated PostgreSQL Reporter integration checks remain pending.
- Local runtime: `gradlew.bat --no-daemon bootRun
  --args='--server.port=18080'` started the real Spring application on
  2026-10-18 after validating all three Flyway migrations against the configured
  development database. Non-mutating probes returned `UP` from `/health`, an
  unauthenticated session from `/api/v1/auth/session`, HTTP 200 from `/`, and
  the expected HTTP 400 from a CSRF-protected invalid Reporter registration.
  A Vite development server on port 5173 was then proxied to that isolated
  backend: `/api/v1/auth/session` returned HTTP 200, a CSRF-protected `POST` to
  `/api/v1/auth/reporter/register` reached server validation and returned the
  expected HTTP 400, and invalid credentials sent to the role-specific `POST
  /api/v1/auth/reporter/login` reached authentication and returned the expected
  generic HTTP 401.
  A subsequent isolated runtime check proved the stale-CSRF recovery contract:
  registration without a valid token returned HTTP 403 with
  `SECURITY_SESSION_EXPIRED`; `/api/v1/auth/session` then returned a fresh
  token, and repeating the same non-mutating invalid registration reached
  controller validation and returned the expected HTTP 400.
- Manual/deployed: pending.
- Independent security/architecture review: pending.
- Human release approver/date: pending.

## 10. Traceability matrix

| Requirement | Governing sources | Planned implementation | Planned evidence |
| --- | --- | --- | --- |
| `REP-AUTH-01` | `.agents/requirements.md`, `.agents/authorization-matrix.md`, `.agents/security.md` | Spring Security, registration service, React role entry/auth | Security/controller, credential, session, and portal tests |
| `REP-INC-01..07` | `.agents/requirements.md`, `.agents/incident-lifecycle.md`, `.agents/anonymity-policy.md`, `.agents/audit-policy.md` | Reporter controllers, incident application services, JDBC repositories, React routes | Allowed/denied/validation/stale/concurrent/rollback and UI tests |
| `REP-ATT-01` | `.agents/requirements.md`, `.agents/persistence.md`, `.agents/anonymity-policy.md` | Private object storage plus transactional metadata adapter | Type/limit/auth/privacy/compensation integration tests |
| `REP-PRO-01` | `.agents/requirements.md`, `.agents/authorization-matrix.md`, `.agents/audit-policy.md` | Promotion request controller and PostgreSQL store | Allowed/duplicate/denied/decision/session tests |
| `REP-AI-01` | `.agents/ai-policy.md`, `workflow/ai-features.md` | Shared SoCLaaS gateway, Reporter prompt builder, suggestion panel | Allowlist, human-control, resilience, log, and audit tests |
| `REP-SELF-01` | `.agents/security.md`, `.agents/audit-policy.md` | Spring credential/session workflow and shell controls | Password, CSRF, secret absence, session, and logout tests |

## 11. Approved implementation decisions

Product-owner approval confirms:

1. the full feature-parity scope, including post-MVP draft, edit, withdraw,
   reopen, and advanced-list features;
2. anonymity defaults to unchecked;
3. the user-facing anonymity explanation described above; and
4. centrally enforced limits proposed for the first implementation: title 200
   characters, description 10,000 characters, comments and reopen explanation
   4,000 characters, and promotion comments 2,000 characters.

Independent security/architecture approval remains required for the complete
slice. Production `REP-AI-01` also remains blocked on approval of
`workflow/ai-features.md` and the provider-retention risk recorded there.

## 12. Approval

- Product owner: repository user through the current Codex chat
- Specification approval date: 2026-10-18 (Asia/Singapore)
- Approval evidence:
  `workflow/reviews/2026-10-18-reporter-slice-specification.md`
- Security/architecture reviewer: pending
