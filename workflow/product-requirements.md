# MP3 Product Requirements

Status: **Current release summary derived from the canonical `.agents/`
contracts. Approval remains owned by the linked feature specifications and
review records; this summary does not create new scope or release approval.**

## Product goal

Incident Desk provides a publicly deployed, production-style web application
for internal company incident reporting. Reporters submit and track incidents,
Responders handle incidents in their assigned categories, and Administrators
govern incidents, access, SLOs, accounts, and operational statistics. Each role
has one bounded advisory SoCLaaS feature with authorization-aware data
selection, explicit human review, resource limits, safe failure, and no
provider mutation authority.

The release replaces production JavaFX and local-file assumptions with React,
Spring Boot/Security, shared PostgreSQL, private object storage, environment
configuration, CI/CD, and independently managed Cloud Run deployment while
retaining reusable domain logic.

## Current web release scope

### Shared

- Separate Reporter, Responder, and Administrator portals backed by one
  identity store and server-side session implementation.
- Reporter-only public registration, controlled one-time Administrator
  bootstrap, and Administrator-reviewed Reporter promotion.
- CSRF-protected mutations, secure production cookies, generic authentication
  failure, role/resource authorization, UTC application timestamps, and
  transactional audit writes.
- Shared PostgreSQL persistence with forward-only Flyway migrations and
  concurrency controls for conflicting mutations.
- Responsive React role workspaces, bounded lists/details, health endpoint,
  public product site, CI, container build, and Cloud Run deployment.
- One shared secured SoCLaaS gateway with allowlisted feature records, atomic
  rate/budget admission, timeouts, bounded retry, circuits, safe errors,
  redacted observability, and deterministic fake-provider tests.

### Reporter

The current web release lets an authenticated Reporter:

- submit a non-draft incident, including the explicit anonymous option;
- view and search their own submitted incidents and status;
- request Responder promotion for selected categories and track the decision;
- change their own password and sign out; and
- request an advisory rewrite of the current title/category/description, then
  explicitly apply or discard it before a separate normal submission.

Draft persistence, web incident detail, edit, withdrawal, reopening, and
Reporter attachment upload are approved future-facing contracts but are not
advertised as current web controls. Their absence is stated in the User Guide
and they remain open in `reporter-vertical-slice.md`.

### Responder

An authenticated enabled Responder with current category access can:

- search and filter authorized queue incidents and assigned incidents;
- open privacy-minimized incident details, comments, history, SLO state, and
  authorized PNG/JPEG attachment reads;
- claim one eligible unassigned incident with a single-winner transaction;
- resolve an assigned incident with nonblank remarks and retained history;
- change their own password and sign out; and
- explicitly request an advisory summary and investigation steps for one
  currently authorized incident without granting the provider or result any
  claim, assignment, comment, handoff, or resolution authority.

Responder handoff and personal statistics remain outside the completed MVP
unless their owning criteria and current User Guide say otherwise.

### Administrator

An authenticated enabled Administrator can:

- view, search, filter, sort, and inspect company incidents; view privacy-safe
  comments and attachments; and perform the implemented authorized incident
  mutations with version and transaction checks;
- review promotion requests, update Responder categories, issue a show-once
  temporary password, tombstone eligible accounts, and use the controlled
  first-Administrator bootstrap;
- configure prospective versioned SLO targets and inspect their history;
- view privacy-safe operational statistics; and
- request advisory aggregate trend text or ask one bounded operational
  question whose provider output is only a closed plan for deterministic,
  read-only application analytics.

Later Administrator creation/recovery through the web remains unavailable and
is documented as an open criterion rather than implied by the account page.
Audit history remains an internal integrity control with no user-facing route.

## Shared non-functional requirements

- **Security and privacy:** deny by default; derive actors from the trusted
  request; reauthorize at execution time; minimize role DTOs and AI payloads;
  protect anonymous identity; escape untrusted text; apply CSP, safe headers,
  body/field/upload bounds, and generic errors.
- **Persistence and concurrency:** use PostgreSQL, versioned migrations,
  isolated tests, row locks/conditional updates/versions as applicable, atomic
  mutation and audit transactions, and single-winner conflicting operations.
- **AI safety and availability:** no provider tools or domain authority;
  feature allowlists, human review, input/output/token/body limits, shared
  budget reservation, circuits, timeouts, bounded retry, content-free logs and
  audits, and non-AI workflow availability during provider failure.
- **Accessibility and interaction:** desktop and mobile-width reflow,
  keyboard-visible focus, semantic controls, labelled feedback, and no hidden
  UI-only authorization assumption.
- **Deployment and operations:** reproducible container, separate development/
  test/production configuration, secret-store ownership, keyless deployment,
  health checks, structured redacted logs, migration/rollback planning, and
  exact-commit release evidence.
- **Engineering process:** observable criteria before implementation, bounded
  multi-agent handoffs, recorded human decisions, reviewable changes, automated
  allowed/denied/failure/concurrency tests, and explicit skipped/unavailable
  checks.

## Scope exclusions

- General chatbot, autonomous incident/account actions, provider tools,
  arbitrary SQL, row-level AI retrieval, persistent AI memory, attachment AI,
  and externally reachable application MCP servers.
- Public Responder or Administrator registration, client-controlled roles,
  client-supplied audit identity/timestamps, and user-facing audit history.
- Video/audio attachments, executable storage, and production runtime data in
  the container or repository.
- Persistent notification history and post-MVP web controls whose owning
  acceptance criteria remain open.
- Claims of backup restore, independent review, deployed role journey, or
  release approval that have not been performed and recorded by a human.

## Traceability

| Requirement area | Canonical source | Observable criteria and evidence |
| --- | --- | --- |
| Product roles and scope | `../.agents/requirements.md`, `../.agents/mvp-scope.md` | Reporter, Responder, and Administrator vertical slices |
| Authorization and privacy | `../.agents/authorization-matrix.md`, `../.agents/anonymity-policy.md`, `../.agents/security.md` | Role slice denied/stale criteria and security tests |
| Lifecycle, audit, SLO, persistence | `../.agents/incident-lifecycle.md`, `../.agents/audit-policy.md`, `../.agents/slo.md`, `../.agents/persistence.md` | Slice criteria plus isolated PostgreSQL evidence |
| AI features and provider | `../.agents/ai-policy.md` | `ai-features.md`, `admin-ai-insights.md`, and linked fake/live-provider evidence |
| Deployment and release | `../.agents/deployment.md` | `cloud-run-deployment.md` and dated pipeline/release-candidate evidence |
| Verification | `../.agents/testing.md` | `acceptance-criteria.md` index and `test-evidence/` records |
| Process and handoffs | `../AGENTS.md` | `agent-workflow.md`, `decisions/`, `reviews/`, plans, and interaction summaries |

## Approval

Product, security/architecture, implementation, and waiver decisions are
recorded per feature under [`reviews/`](reviews/) and
[`decisions/`](decisions/). This consolidated summary was updated from those
approved and implemented contracts; it does not replace them. Human release
approval and exact `master` finalization remain pending under
[`README.md`](README.md).
