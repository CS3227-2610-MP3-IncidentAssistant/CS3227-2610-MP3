# Testing Requirements

## General approach

- Keep domain and authorization logic testable without a browser or provider.
- Return stable application error codes and field validation results so the UI
  never parses exception messages.
- Use deterministic clocks and identifier generators.
- Use isolated databases, fake AI providers, and fake external services.
- Test successful, denied, stale, concurrent, malformed, and unavailable paths.
- Verify persisted state and audit evidence rather than trusting HTTP status or
  visible text alone.
- Purely visual presentation changes may be accepted through documented human
  review instead of dedicated DOM-structure or CSS-class assertions. Interactive
  behavior, state transitions, validation, accessibility semantics, data flow,
  authorization, and security controls still require the applicable automated
  verification below.

## Required suites

### Authentication and web security

- Registration validation, duplicate identities, login success/failure,
  logout, session fixation protection, and session invalidation.
- Successful Reporter, Responder, and Administrator portal login; generic
  rejection for wrong-portal role, invalid credentials, disabled account, and
  direct protected-route access without role enumeration.
- Reporter-only public registration and tampered requests attempting to create
  Responder or Administrator accounts.
- Concurrent users retain distinct identities, roles, data, and CSRF tokens.
- State-changing requests reject missing or invalid CSRF tokens.
- Production cookies are Secure, HttpOnly, and use an explicit SameSite policy.
- Protected routes require authentication and enforce role authorization in
  backend logic.
- Security headers, safe error pages, request-size limits, and open-redirect
  prevention are verified.
- Password hashes are salted and plaintext is absent from the database, logs,
  HTTP responses, and test evidence.
- Promotion, category-access, password-change/reset, and account-disable paths
  include allowed and denied tests.
- Promotion tests cover request status, approval, rejection, duplicate or stale
  decisions, preserved account identity/history, approved category assignment,
  rollback, audit effects, session invalidation, and subsequent Responder portal
  and queue access.
- Administrator provisioning tests cover one-time bootstrap, reuse rejection,
  authorized later provisioning, public endpoint denial, unchanged account
  state on failure, and absence of credentials from logs and audits.

### Authorization

- Each row of `.agents/authorization-matrix.md` has allowed and denied tests.
- Denied and missing resources use the same presentation-safe response where
  distinguishing them would leak information.
- Reporters cannot enumerate another reporter's incidents.
- Responders cannot access unauthorized categories or another responder's
  assigned incident.
- Authorization is rechecked after role, ownership, assignment, and category
  changes and immediately before every AI request.
- UI-hidden actions fail when invoked directly over HTTP or at the application
  boundary.

### Incident lifecycle and concurrency

- Cover every transition and invalid transition in
  `.agents/incident-lifecycle.md`.
- Validate required fields, edit/withdraw timing, resolution remarks, handoff
  queue order, reassignment eligibility, reopening explanation, and preservation
  of resolution history.
- Reopening commits comment, state transition, and audit together.
- Two simultaneous claims have exactly one success.
- Stale updates do not overwrite a newer assignment or lifecycle state.

### Privacy and attachments

- Anonymous identity is absent from responder and normal administrator views,
  filters, statistics, exports, notifications, AI payloads,
  provider errors, and logs.
- The owning reporter can still access an anonymous incident.
- The anonymous checkbox/switch value reaches persisted state; unchecked and
  checked submissions produce the expected privacy-safe representations.
- Attachment tests cover authorization, detected type, spoofed extensions,
  image dimensions, byte/count/aggregate limits, safe headers, unsafe names,
  failed-write cleanup, and unsupported video/audio.

### Persistence and audit

- No role can retrieve audit history through an application HTTP endpoint,
  page, navigation item, filter, detail view, or download.
- Apply all migrations to an empty PostgreSQL database.
- Round-trip all retained entity and lifecycle variants.
- Restart/redeploy persistence preserves data and relationships.
- Database constraints reject invalid references and duplicate unique values.
- Failed validation, authorization, persistence, or audit insertion rolls back
  the entire logical operation.
- Every required operation produces the correct redacted append-only audit
  event, including deterministic ordering and tombstoned actors.
- Cover every SLO formula and edge case in `.agents/slo.md`.

### AI security and resilience

- Use a fake provider for deterministic automated tests; CI must not require a
  real SoC LLM key.
- Cover direct prompt injection and indirect injection in incident text,
  comments, attachment names, and previous AI output.
- Cover cross-role and cross-incident leakage, anonymous identity requests,
  excessive data selection, and unrelated-incident identifiers.
- Cover malicious HTML/Markdown/script output and attempts to make AI perform
  unauthorized application actions.
- Cover per-user rate-limit exhaustion, oversized input/output, timeout,
  malformed response, unavailable provider, bounded retry, and cancellation.
- Prove prompts and responses do not appear in application or CI logs.
- Prove AI output is not persisted as a domain change without a separate,
  authorized human action through the normal workflow.

### Deployment and acceptance

- CI runs build, unit tests, database integration tests, formatting/style
  checks, security-related tests, and production artifact creation.
- The container starts from a clean environment and exposes a working health
  endpoint.
- A deployed smoke test covers login and one end-to-end path for each role.
- Production and development use separate configuration and credentials.

## Completion evidence

Before declaring a change complete, record:

- Test/build commands run and the exact commit tested.
- Passed, failed, skipped, and unavailable checks.
- Relevant manual and deployed verification.
- Migration, concurrency, audit, privacy, and AI-data effects.
- Sanitized evidence location under `workflow/test-evidence/`.
- Remaining assumptions and risks.
