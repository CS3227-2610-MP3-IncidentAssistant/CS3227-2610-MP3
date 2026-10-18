# MP3 Product Requirements

Status: **TODO — derive the approved release specification from `.agents/` and
record team approval.**

## Product goal

TODO: Summarize the deployed Incident Desk problem, users, and measurable MP3
outcome.

## Role requirements

### Reporter

The approved Reporter vertical slice preserves the complete current desktop
Reporter surface, exposes the existing draft workflow, and adds the MP3
advisory description-improvement feature. An authenticated Reporter can:

- create, save, submit, search, filter, sort, and inspect only their own
  incidents, including incidents submitted anonymously;
- edit or withdraw an eligible incident, reopen a resolved incident with a
  required explanatory follow-up, and view lifecycle and resolution history;
- upload and view authorized PNG/JPEG attachments within the approved limits;
- request Responder promotion and track the request status; and
- request an advisory AI description improvement that changes only local form
  state until the Reporter separately saves or submits it.

Detailed behavior, denied/stale/failure paths, data boundaries, and required
evidence are specified in `reporter-vertical-slice.md`. Product-owner approval
schedules its full feature-parity scope and field limits for implementation,
including capabilities classified as post-MVP in `.agents/mvp-scope.md`.

### Responder

TODO: List retained web workflows and the advisory incident-summary/next-step
AI feature.

### Administrator

The draft administrator vertical slice preserves the complete current desktop
administrator surface and adds the MP3 controls required by the canonical
contracts. An authenticated Administrator can:

- monitor, search, filter, sort, and inspect incidents across the company;
- comment on incidents; view privacy-safe comments and image attachments; and
  assign, reassign, unassign, or resolve incidents within lifecycle rules;
- review promotion requests, update Responder category access, reset or
  tombstone eligible accounts, and use controlled Administrator provisioning;
- configure prospective, versioned SLO targets by category;
- view privacy-safe operational statistics and filterable append-only audit
  history; and
- request an advisory AI summary built only from privacy-safe aggregate
  statistics, with no mutation authority.

Detailed behavior, denied/stale/failure paths, data boundaries, and required
evidence are specified in `administrator-vertical-slice.md`. Its expanded
feature-parity scope requires product-owner approval because some retained
desktop capabilities are currently classified as post-MVP in
`.agents/mvp-scope.md`.

## Shared non-functional requirements

TODO: Record security, accessibility, concurrency, persistence, availability,
observability, deployment, privacy, and performance requirements with
measurable targets.

## Scope exclusions

TODO: Record deferred MP2 features and prohibited autonomous AI behavior.

## Traceability

TODO: Map each approved requirement to `.agents/`, its implementation issue or
pull request, acceptance criteria, and test evidence.

## Approval

- Approved by: TODO
- Date: TODO
- Evidence/review link: TODO
