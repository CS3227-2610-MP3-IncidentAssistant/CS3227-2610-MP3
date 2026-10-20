# Acceptance Criteria Index

Acceptance criteria are owned by the individual specification files. This file
is the central index: it links to those criteria but does not duplicate them.

## Registered specifications

- [MVP exit criteria](../.agents/mvp-scope.md#mvp-exit-criteria)
- [Spring Boot health-endpoint acceptance criteria](../.agents/springboot_migration/health-endpoint.md#acceptance-criteria)
- [Superseded account-registration migration criteria](../.agents/springboot_migration/account-registration.md#historical-acceptance-criteria)
- [Administrator vertical-slice acceptance criteria and evidence](administrator-vertical-slice.md#9-acceptance-criteria-and-evidence)
- [Reporter vertical-slice acceptance criteria and evidence](reporter-vertical-slice.md#9-acceptance-criteria-and-evidence)
- [Reporter-to-Responder promotion journey acceptance criteria](promotion-journey.md#7-traceability-and-acceptance-criteria)
- [Responder vertical-slice acceptance criteria and evidence](responder-vertical-slice.md#acceptance-criteria)
- [SoCLaaS AI feature and gateway acceptance criteria](ai-features.md#7-acceptance-criteria)
- [Neon PostgreSQL and Object Storage acceptance criteria](neon-services.md#acceptance-criteria)

## Index and completion rules

- Every retained or new feature specification must contain its own observable,
  checkable acceptance criteria and be linked from this index.
- Each criterion should cover one outcome so it can be verified and ticked
  independently. Include normal, denied, validation, failure, stale, and
  concurrent outcomes when applicable.
- Tick `[x]` in the individual specification only when the exact criterion is
  fully satisfied and its reproducible automated or manual evidence is linked
  or recorded there.
- Leave `[ ]` for partial, failed, skipped, unavailable, manual-only, or
  approval-gated results, and record what evidence remains outstanding.
- Do not tick a broader release or slice criterion based only on a component
  test. Never infer or record human approval from agent work.
- Store substantial command output and release evidence under
  `workflow/test-evidence/`, then link it from the owning specification.

## New specification template

Use this structure in the individual specification, then add its link above:

```markdown
## Acceptance criteria

- [ ] Given/when/then normal outcome.
- [ ] Denied outcome without information leakage.
- [ ] Validation and limits.
- [ ] Concurrent or stale outcome, or an explicit not-applicable rationale.
- [ ] Persistence and audit outcome.
- [ ] AI data and approval behavior, or an explicit not-applicable rationale.
- [ ] Failure and recovery outcome.

## Verification evidence

- Automated: pending
- Manual/deployed: pending
- Human approver/date: pending
```
