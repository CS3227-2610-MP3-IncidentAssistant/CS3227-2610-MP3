# Product Requirements

## Product goal

Provide a production-style web application in which reporters can raise and
track incidents, authorized responders can handle them, and administrators can
govern access and review operational performance. Each retained role has one
narrow, advisory SoC LLM feature with explicit human review.

The application is a concurrent Java web application deployed on an
independently managed public host. It uses server-side authentication and
authorization, shared persistent storage, environment-based secrets, and
separate development and production configuration. JavaFX and local files are
MP2 implementation details, not MP3 production architecture.

## Shared capabilities

- A public role-entry page with separate Reporter, Responder, and Administrator
  login portals. The portals use one backend identity store and authentication
  service; they are not separate account databases or authorization boundaries.
- Login, logout, and multiple isolated concurrent web sessions. Each portal
  accepts only an account currently authorized for that role and returns a
  generic failure for invalid credentials or a role mismatch.
- Login names are case-sensitive. Names that differ only by letter case identify
  distinct accounts.
- Public registration is available only from the Reporter portal and always
  creates a Reporter. The Responder and Administrator portals provide no public
  registration or client-controlled role selection.
- Authenticated users can replace their own password after confirming their current password.
  Replacement passwords contain 12 through 128 characters; browser helper text
  describes the minimum while the backend remains authoritative for both bounds.
- Role- and category-based access control.
- Reusable table/list views with searching, filtering, and sorting where
  required.
- Incident list search trims input and matches title, description, or incident
  identifier case-insensitively. Category/status selections use OR within each
  group and all filter groups combine with AND. Empty selections mean "all".
- Incident filters support assignment state, inclusive creation-date bounds,
  evaluated SLO state, and authorized reporter/responder identities. Reporter
  identity filtering excludes anonymous incidents. The default ordering is
  newest creation time first; every ordering uses incident identifier ascending
  as its stable tie-breaker.
- A shared incident-detail view.
- Incident comment threads.
- Image-only attachment upload and rendering; video and audio are unsupported.
- Attachments support PNG/JPEG up to 10 MiB. An incident permits at most 5 attachments
  and 100 MiB total. Limits are centrally configurable; image decoding is
  limited to 40 million pixels. Unsupported media fails safely in the viewer.
- Only the owning reporter can add attachments to a persisted `DRAFT` or
  unassigned `SUBMITTED` incident. Viewing follows incident-detail permissions.
- A React browser interface that works on current desktop and mobile-width
  browsers without relying on JavaFX. The Java backend remains the authority
  for validation, authorization, persistence, audit, and AI data selection.
- All state-changing HTTP requests use CSRF protection and server-side
  authorization. Sessions use secure production cookie settings.
- Application events must not expose one user's protected data to another
  session. Persistent notification history remains optional unless scheduled.
- SLO countdown or status badges.
- A shared AI gateway providing provider configuration, authorization-aware
  prompt construction, rate limiting, timeouts, bounded retries, input/output
  limits, safe errors, redacted operational logging, and a test double.

## Reporter requirements

A reporter can:

- Submit an incident report with a clearly labelled anonymous-report checkbox
  or switch. The control defaults to the approved product value, explains the
  application's anonymity boundary, and sends its value through the normal
  validated submission operation.
- Submitted incident reports require a non-blank title, description, and
  category; incomplete drafts may omit title or description. Titles are
  stripped of leading and trailing whitespace before storage. Descriptions and
  resolution remarks retain entered whitespace after non-blank validation.
  Centrally configured server-side length limits must be documented in
  `workflow/product-requirements.md` and applied consistently in browser,
  application, database, and AI-selection boundaries.
- Save an incomplete report as a draft and submit it later.
- Upload supported images and view them in the application.
- View, search, and filter their own incidents and their statuses.
- View incident details and resolution remarks for their own incidents.
- Edit or withdraw an incident while it is submitted and unassigned.
- Reopen their own resolved incident by adding a non-blank follow-up comment
  explaining why the incident remains unresolved or why the resolution was
  insufficient.
- Request promotion to responder by selecting requested incident categories and
  providing optional additional comments.
- Track promotion-request history and outcomes. An Administrator may approve
  a non-empty subset of the requested valid categories, but cannot grant a
  category that was not requested. Approval changes the existing account to
  Responder, grants exactly the approved subset, preserves the account
  identifier and historical references, records an audit event, and requires
  reauthentication before Responder access. Rejection leaves the role and
  category access unchanged and records the decision.
- Request an AI-assisted rewrite or structure of the reporter's current draft
  description. Generated text remains an untrusted suggestion until the
  reporter explicitly applies or copies it, and incident submission remains a
  separate normal application action.

## Responder requirements

A responder can:

- View and search/filter unassigned incidents in categories assigned to them.
- View and search/filter incidents assigned to them.
- Claim an eligible unassigned incident.
- Resolve an assigned incident with resolution remarks.
- Hand an assigned incident back to its category queue at its original queue
  position.
- View weekly resolved-case counts and the responder SLO measures defined in
  `.agents/slo.md`.
- Request an AI summary and suggested investigation steps for an incident the
  responder is currently authorized to view. The feature cannot claim, assign,
  comment on, hand off, or resolve an incident.

## Administrator requirements

The MP3 web application must migrate every functional capability exposed by
the MP2 JavaFX Administrator experience. The authoritative inventory and web
disposition for those capabilities is
`workflow/administrator-vertical-slice.md`; desktop implementation details and
explicitly non-functional placeholders are replaced or omitted as specified
there, rather than treated as product features.

An administrator can:

- View, search, filter, and sort incidents across the company.
- Resolve an incident with resolution remarks.
- Reassign an incident to an eligible responder.
- Review responder-promotion requests and approve a non-empty subset of their
  requested categories or reject them. An approval cannot grant unrequested
  categories.
- Update a responder's permitted categories.
- Delete an account subject to the retention rules below.
- Initiate a secure password reset without learning the existing password. The application generates a
  high-entropy one-time temporary password, displays it only after durable commit, and expires it after
  24 hours. A user authenticated with it must replace it before accessing any role workflow.
- Configure SLO targets for each incident category.
- View operational statistics filtered by responder, reporter, category, and
  time period, subject to anonymous-report privacy rules.
- Provision the first Administrator through the one-time controlled bootstrap
  specified in `.agents/mp2-grade-improvements.md`. Later Administrators can be
  created only through an authenticated, authorized Administrator operation;
  no public form or request parameter may select the Administrator role.

Statistics include incidents resolved, resolution duration, reopened-incident
counts/rates, and configured SLO performance.

An administrator can also request an AI operational-trend summary built from
privacy-safe aggregate statistics. The feature must not send raw incident text,
comments, credentials, or reporter identities to the provider and cannot
change accounts, access, incidents, SLOs, or audit records.

An administrator can ask one bounded natural-language operational-statistics
question as specified by `workflow/admin-ai-insights.md`. The provider may
translate the question only into a closed query plan. Deterministic application
code authorizes and executes one read-only analytics capability and renders the
factual answer. Responder names may appear in the local Administrator answer but
must not be sent to the provider; named comparisons use structured selections.
Responder insight results have no hidden minimum-sample eligibility threshold:
rankings include every responder with qualifying data, selected comparisons do
not silently omit a responder with no qualifying data, and every responder
result displays its sample size.
The feature has no general chat memory, SQL, row-level incident retrieval,
provider-executed application tools, or mutation path.

## AI-wide requirements

- Authorization is checked immediately before selecting any data for an AI
  request; a prior page view is not sufficient.
- Only feature-specific allowlisted data may be sent to the SoC LLM.
- Passwords, password hashes, reset credentials, session identifiers,
  anonymous-reporter identities, unrelated incidents, audit secrets, and
  attachment bytes are never sent.
- Incident text, comments, attachment names, provider responses, and all other
  external text are untrusted data, never instructions to the application.
- AI output is advisory plain text, escaped for browser display, and cannot
  invoke application operations.
- Every AI result has a visible human review point. Failure, timeout, malformed
  output, or rate limiting leaves the non-AI workflow available.
- Provider prompts and responses are not written to application logs. Store
  only safe metadata required for diagnostics and evidence.

## Data-retention expectations

- Deleting an account must not delete incident or audit history required for
  integrity. Historical references become a non-login tombstoned identity.
- Withdrawing an incident is a lifecycle state, not physical deletion.
- Audit entries are append-only and retained with the application data.
- Audit entries are an internal integrity control. The web application exposes
  no audit-history endpoint, page, filter, detail view, or downloadable audit
  representation to Reporters, Responders, or Administrators.
- Attachments belonging only to drafts may be removed when the draft is deleted
  under a future explicit requirement; no such deletion feature is currently
  specified.

## Decisions requiring product confirmation

The following are deliberately not invented by this document:

- Additional incident form fields beyond title, description, and category.
- Whether registration is open or requires admin activation.
- Business-hours, weekend, holiday, and pause rules for SLOs.
- Whether an admin can recover the identity of an anonymous reporter through a
  separate exceptional process.
- Notification presentation and retention.
- Password policy and the exact delivery mechanism for reset credentials.
- The SoCLaaS endpoint and published quota defaults are recorded in
  `workflow/ai-features.md`. Before coding, approve its application limits,
  select a model visible to the production key, verify the actual key budget,
  and explicitly accept or resolve the undocumented provider-retention terms.
- Production host and managed PostgreSQL provider.
