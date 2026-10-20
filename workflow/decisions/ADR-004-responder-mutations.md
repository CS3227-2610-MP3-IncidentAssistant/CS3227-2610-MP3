# ADR-004: Responder mutation and history boundaries

- Status: Accepted for issue #7
- Date: 2026-10-20
- Decision owner: Product owner `lqr1019`
- Security/architecture cross-review: Waived for issue #7 by explicit
  product-owner decision; see
  [`workflow/reviews/2026-10-20-responder-slice-specification.md`](../reviews/2026-10-20-responder-slice-specification.md).
- Scope: Responder queue/detail projection, incident claims, resolution, and
  their audit and resolution-history persistence.

## Context

Issue #7 needs concurrent-safe responder claims and resolution with category
authorization, anonymous-reporter privacy, and transactionally consistent
audit records. The product owner approved the responder vertical-slice
criteria and waived independent cross-role review because the Responder
contributor owns this role-specific work.

## Decisions

1. Responder APIs use a dedicated privacy-minimized projection. They do not
   expose a broader shared incident DTO or anonymous reporter identity.
2. A claim uses a conditional PostgreSQL update in a transaction. The mutation
   rechecks current account, category, assignment, and lifecycle eligibility.
   The winning incident mutation and success audit record commit atomically;
   a losing concurrent claim returns a recoverable conflict and writes no
   success audit.
3. Resolution uses the same atomic transaction boundary and rechecks the
   current assignee and lifecycle state. Resolution remarks are nonblank after
   validation, limited to 2,000 characters, and otherwise retain entered
   whitespace.
4. Successful resolutions are retained as forward-only history, one entry per
   resolution cycle. Reopening does not erase prior remarks; a later resolution
   appends a new cycle. History, lifecycle state, and success audit commit
   atomically. Any schema change is made with a new forward-only migration.
5. A stale or repeated resolution request returns a safe conflict/current
   state response and does not duplicate history or success audit records.
6. Shared components may change only with their current owner's agreement and
   a documented integration contract. Issue #7 does not authorize changing
   Reporter or Administrator behavior.

## Consequences

- Database operations and isolated PostgreSQL tests must prove the conditional
  claim race, execution-time authorization rechecks, and mutation/audit
  atomicity, including rollback and stale paths.
- Browser validation, application validation, and persisted limits must agree
  on the 2,000-character remarks maximum.
- Implementations must use the authenticated request actor and server time;
  client-supplied identity, role, assignment, category, timestamps, or audit
  fields are never authoritative.
- The waiver removes only the separate cross-role human security/architecture
  approval gate for this issue. It does not waive any security, privacy,
  authorization, testing, or implementation-verification requirements.
