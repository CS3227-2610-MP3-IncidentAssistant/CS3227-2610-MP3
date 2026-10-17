---
name: specification-driven-development
description: Develop or change this repository through its authoritative specifications and acceptance criteria. Use for every implementation task that changes application behavior, architecture, persistence, security, deployment, or production configuration; do not use for read-only explanation or status reporting.
---

# Specification-Driven Development

Keep every implementation change traceable from an approved specification to
observable acceptance criteria, tests, and recorded evidence.

## Before implementation

1. Read `AGENTS.md`, `workflow/README.md`, and the canonical `.agents/`
   artifacts that govern the requested area. Read `workflow/architecture.md`
   and relevant approved ADRs when the change affects architecture,
   persistence, security, deployment, or an external service.
2. Use `workflow/acceptance-criteria.md` as an index to locate the acceptance
   criteria in the applicable individual specification. If the specification
   is not indexed, add a relative Markdown link to its acceptance-criteria
   heading. Do not copy its criteria into the index.
3. Confirm each criterion is observable and covers the relevant allowed,
   denied, validation, failure, stale, and concurrent paths.
4. Build a concise traceability map from requirement or feature ID to
   acceptance criterion, implementation area, and planned evidence.
5. If the requested behavior is unspecified, ambiguous, or contradictory,
   update or clarify the governing specification and criteria first. Respect
   the approval gates in `workflow/README.md`; do not treat agent-authored text
   as human approval or proceed past a required unresolved human decision.

Do not silently redefine a requirement in implementation or tests. Do not
weaken an existing acceptance test to accommodate incorrect behavior.

## Implement and verify

- Make the smallest coherent change that satisfies the approved criteria while
  preserving repository architecture and domain invariants.
- Change the specification, implementation, migrations, tests, and operational
  documentation together whenever the approved behavior requires all of them.
- Add automated tests for each applicable criterion, including denied and
  failure paths. Use isolated data and deterministic application-generated UTC
  timestamps as required by the repository policies.
- Run the checks required by `.agents/testing.md` plus focused checks for the
  changed area. For deployment or integration work, verify the actual wired
  service end to end without exposing secrets or production data.
- Review the diff for unrelated edits, generated output, secrets, logs, and
  divergence from the specification.

## Record evidence and tick criteria

After verification, update the criteria in their owning individual
specification. Update `workflow/acceptance-criteria.md` only to add or repair a
link to that specification:

- Tick `[x]` only when the exact criterion is fully satisfied and cite the
  reproducible command, test, artifact, environment, and commit when available.
- Leave `[ ]` for a partial, failed, skipped, unavailable, manual-only, or
  approval-gated result. Add a concise reason and the remaining evidence needed.
- A broader release- or slice-level checkbox may be ticked only when every part
  of that statement has been demonstrated. Passing a component test is not
  sufficient.
- Link reproducible evidence from the owning specification. Store substantial
  command output or release artifacts under `workflow/test-evidence/`.
- Record automated and manual/deployed evidence separately. Never include
  credentials, sensitive user data, anonymous identity, or other secrets.
- Never fill in a human approver or approval date unless that named human has
  actually provided and recorded the approval.

Finish by reporting the traceability map, changed files, checks and exact
results, criteria ticked, criteria still open, assumptions, and remaining
risks. Do not claim the task complete while an applicable required criterion or
gate remains open.
