# MP2 Feedback Improvements for MP3

This file converts the actionable findings from the MP2 grade report into a
small MP3 improvement backlog. It summarizes evidence; it does not copy grader
comments verbatim and does not override the product, security, authorization,
or assignment requirements in the other canonical artifacts.

When an item is implemented, link its specification, tests, review record, and
exact release evidence. Do not mark an item complete based only on an agent's
claim or a UI screenshot.

## P0 - Privileged account provisioning

MP2 allowed a signed-out user to select the Administrator role during public
registration. That let an unauthenticated user create a privileged account and
then control existing accounts, including resetting another user's password.

MP3 must:

- prohibit public self-registration as Administrator or Responder;
- permit public registration only for the approved least-privileged role;
- create the first Administrator through a controlled, documented bootstrap
  mechanism that cannot be reused after successful initialization;
- create or promote later privileged accounts only through an authenticated,
  authorized Administrator application operation;
- enforce this rule in application/backend logic rather than only removing a
  role selector from React;
- record successful privileged provisioning and rejected attempts according to
  the audit policy without recording credentials;
- add denied-path regression tests proving that unauthenticated or
  insufficiently privileged requests cannot create an Administrator, change a
  role, or reset another account, and that rejected attempts leave account and
  success-audit state unchanged; and
- document first-run, recovery, credential rotation, and production deployment
  procedures without placing bootstrap secrets in source control.

This is a release blocker.

### Required role-separated entry flow

The MP3 team also requires a public role-entry page and separate Reporter,
Responder, and Administrator login portals. This is an MP3 product decision,
not a claim that the graders required three screens.

- All three portals authenticate against one backend account/credential store.
- Reporter public registration is available only from the Reporter portal and
  can create only a Reporter account.
- The Responder portal accepts accounts whose persisted role is Responder after
  an approved promotion.
- The Administrator portal accepts only controlled, provisioned Administrator
  accounts and exposes no public registration.
- Portal selection and any browser-supplied role value are untrusted; backend
  authentication and current persisted role decide access.
- Invalid credentials and portal-role mismatches use a generic failure that
  does not enumerate usernames or roles.
- Automated tests cover successful entry for each role, wrong-portal attempts,
  direct route access, public-registration tampering, and session isolation.

### Required Reporter-to-Responder promotion journey

The complete interface-supported journey is:

1. A signed-in Reporter requests promotion, selects requested incident
   categories, and may provide comments.
2. The Reporter can see the pending or decided request status.
3. A signed-in Administrator reviews the request and explicitly approves a
   non-empty subset of its requested categories or rejects it. The
   Administrator cannot grant an unrequested category.
4. Approval updates the same account to Responder, assigns exactly the
   approved subset, preserves its identifier/history, creates an audit event,
   and invalidates existing sessions so the user must reauthenticate.
5. Rejection preserves the Reporter role and category state and records the
   decision without creating a success audit for promotion.
6. The promoted user signs in through the Responder portal; the Reporter portal
   must not silently grant a mismatched role session.

Tests must cover pending, approval, rejection, duplicate/stale decisions,
unauthorized decisions, persistence failure rollback, audit state, session
invalidation, and the resulting Responder queue access.

## P0 - Verify complete user journeys through the real interface

MP2 documentation asked testers to promote an account through a flow that the
Reporter interface could not initiate. MP3 documentation and acceptance tests
must describe only routes that exist in the assessed release.

For each Reporter, Responder, and Administrator journey:

- begin from a documented account and permission state;
- exercise the workflow through the React interface and deployed backend;
- verify the resulting domain state, authorization decision, audit event, and
  persisted database state;
- cover prerequisites such as promotion requests and category access before a
  dependent queue or action;
- distinguish UI-supported operations from service-only or deferred behavior;
  and
- update the User Guide, Developer Guide, acceptance criteria, and deployed
  smoke evidence together when the journey changes.

The Reporter journey must visibly include the anonymous-report checkbox or
switch. Tests must prove that the chosen value reaches persisted incident state
and that every unauthorized role sees only the neutral anonymous label.

## P0 - Preserve authorization, privacy, and audit strengths

The report recognized the existing incident lifecycle, anonymous reporting,
denied-access tests, recovery safeguards, and atomic mutation/audit behavior as
strengths. The web migration must retain these properties rather than treating
them as desktop-only behavior.

In particular:

- keep anonymous labels consistent across every role, response model, audit
  presentation, export, log, metric, and AI request;
- keep authorization in application/domain logic and recheck it at execution;
- commit each domain mutation and its success audit in one transaction;
- preserve restart/redeploy coverage using PostgreSQL; and
- test cross-user, cross-role, cross-category, and cross-incident denial paths.

## P1 - Make access and empty states explainable

An empty Responder queue must distinguish, without leaking incidents, between:

- a legitimate empty eligible queue;
- an account that has not been granted responder category access;
- expired or changed authorization; and
- a recoverable loading or backend failure.

Long forms, especially attachment and incident forms, must preserve visible
focus, adjacent validation, accessible summaries, keyboard operation, and safe
narrow-screen layout. Anonymous labels must remain visually and semantically
consistent.

## P1 - Clarify architecture and responsibility boundaries

MP2 contained a dependency-boundary shortcut where the session service detected
a concrete credential service with `instanceof`. MP3 must use explicit
interfaces and dependency injection instead of discovering collaborator
capabilities from concrete runtime types.

During the migration:

- keep authentication/session concerns separate from registration and
  credential persistence;
- adapt the authenticated web principal explicitly to application-layer actor
  access;
- keep React, controllers, SQL adapters, and the LLM provider outside the
  domain model;
- split stores and services when they accumulate unrelated responsibilities;
- remove unused compatibility or reserved-section methods rather than carrying
  dead MP2 code into the new persistence design; and
- record material design choices and rejected alternatives in
  `workflow/decisions/`.

## P1 - Improve code hygiene

Reviews should explicitly check for the issues identified in MP2:

- inconsistent indentation in complex service methods;
- compressed control flow in security-sensitive account actions;
- unused methods and compatibility scaffolding;
- large services or stores with multiple reasons to change; and
- UI-specific logic leaking into shared application services.

Prefer guard clauses, named operations, small reviewable methods, explicit
interfaces, and deletion of unused code. Passing tests do not excuse unclear
security-sensitive control flow.

## P1 - Make verification commands unambiguous

MP2's root instructions required verification but did not name the commands,
leaving agents to guess. Before implementation work is considered ready, update
the root instructions and Developer Guide with the exact supported commands for:

- frontend install, lint, unit tests, production build, and accessibility or
  browser tests;
- backend formatting or Checkstyle, unit tests, PostgreSQL integration tests,
  security tests, and production build;
- container build and local startup;
- migration validation;
- deployed health and role smoke tests; and
- any full verification command used by CI.

The commands must match the checked-in build files and CI workflows. Do not add
placeholder commands before the React and Java web build structure is chosen.

## P1 - Keep canonical instructions coherent

MP2 had an unreconciled schema-version rule and distributed too much essential
context across separate files. MP3 must:

- use the migration rule in `.agents/persistence.md` as the single database
  schema authority;
- remove superseded rules instead of leaving contradictory compatibility text;
- keep the root `AGENTS.md` concise but sufficiently self-contained to expose
  critical authorization, security, verification, and completion rules;
- link detailed contracts from the root index; and
- run a broken-reference and contradiction scan when canonical files change.

## P2 - Strengthen agentic evidence and reflection

The MP2 reflection described useful integration failures but gave less detail
about how custom skills were designed, iterated, and verified. MP3 evidence
should:

- explain why each retained or new project skill exists and what risk it
  controls;
- record meaningful changes made after a skill or agent produced an inadequate
  result;
- preserve the original sanitized command or CI output when a summary discusses
  a failed attempt;
- connect each lesson to the specification, correction, tests, and review; and
- identify human verification rather than presenting agent output as proof.

Do not commit raw prompts, responses, secrets, session identifiers, anonymous
identity, or sensitive production data.

## P2 - Use smaller, outcome-focused Git changes

MP2 generally used descriptive branches and pull requests, but some changes
combined validation, persistence, UI, CI, and documentation into one review
unit. For MP3:

- split shared infrastructure, feature implementation, tests, and documentation
  when each can be reviewed and verified independently;
- keep security-critical provisioning changes especially small;
- avoid mixing unrelated cleanup with feature behavior;
- name commits after the user-visible or architectural outcome, not only the
  file edited;
- use a consistent imperative commit-message format; and
- keep every intermediate commit buildable when practical.

## Traceability checklist

| Improvement | Specification/ADR | Automated evidence | Manual/deployed evidence | Review | Status |
| --- | --- | --- | --- | --- | --- |
| Controlled Administrator bootstrap | TODO | TODO | TODO | TODO | Not started |
| Role-separated login portals | TODO | TODO | TODO | TODO | Not started |
| Anonymous submission control and persisted value | TODO | TODO | TODO | TODO | Not started |
| Reporter-to-Responder promotion journey | `workflow/promotion-journey.md`; `workflow/decisions/ADR-003-promotion-decision-and-session-invalidation.md` | Existing component, security, application, and PostgreSQL tests require verification and a dated evidence record against the approved revision | Pending browser, restart/redeploy, and deployed HTTPS evidence | Product-owner approved 2026-10-22; independent security/architecture review pending | Specification approved by product owner; implementation provisional |
| Denied privileged registration and reset | TODO | TODO | TODO | TODO | Not started |
| Complete three-role journeys | TODO | TODO | TODO | TODO | Not started |
| Anonymous/privacy regression coverage | TODO | TODO | TODO | TODO | Not started |
| Explainable queue/access states | TODO | TODO | TODO | TODO | Not started |
| Explicit session/credential boundaries | TODO | TODO | TODO | TODO | Not started |
| Exact verification commands | TODO | TODO | TODO | TODO | Not started |
| Canonical-contract consistency check | TODO | TODO | TODO | TODO | Not started |
| Agent/skill evidence improvements | TODO | TODO | TODO | TODO | Not started |
| Small reviewable Git changes | TODO | TODO | TODO | TODO | Ongoing |
