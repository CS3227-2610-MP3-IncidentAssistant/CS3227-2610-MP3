# Responder AI specification and security review

Status: **Product specification and security/architecture approved for issue #23.**

- Tracking issue: [#23 — Responder AI incident summary and investigation suggestions](https://github.com/CS3227-2610-MP3-IncidentAssistant/CS3227-2610-MP3/issues/23)
- Specification: [`workflow/ai-features.md`](../ai-features.md), section 4.2 and criteria `AI-RES-02` through `AI-RES-08`
- Specification SHA-256 for this review: `c0f15be532786f2123a743c2be6352bc36293888080b229862c166b8d8be200c`
- Base code revision: `92a5dde7ea5947e4857d7657ed5c95e89e623915` (`main` before this uncommitted specification draft)
- Review date: 2026-10-23 (Asia/Singapore)
- Product owner: `lqr1019`
- Security/architecture reviewer: `lqr1019`

## Proposed scope for review

An enabled Responder may explicitly request an advisory summary and suggested
investigation steps for one incident they can currently view. The detail-view
authorization rule applies to both an unassigned `SUBMITTED` incident in a
permitted category and an `ASSIGNED` incident assigned to that Responder, even
after category access is revoked. The output is escaped plain text for human
review; it does not prefill remarks or perform an application action.

The provider receives only the section 4.2 operational allowlist. Its
current-stage duration is queue age from the current cycle's queue-entry time
for `SUBMITTED`, or time in progress from the current cycle's first assignment
for `ASSIGNED`. The SLO state is evaluated against the applicable versioned
target chosen at the current cycle's queue entry and uses `NOT_APPLICABLE` when
no target applies. A handed-off `SUBMITTED` incident retains queue age but has
`NOT_APPLICABLE` time-to-claim SLO state because it was already claimed. A
reopened incident starts a new target selection. The stage timestamp,
authorization, and selected state must come from a consistent canonical read.
The application rechecks access before returning the provider result and
discards the text if access was lost during processing.
No identity, comments, attachments, history, audit content, or other incidents
are sent. The request is bounded and uses the existing secured SoCLaaS gateway.

## Security and architecture evidence to examine

- Governing contracts: `.agents/ai-policy.md`, `.agents/authorization-matrix.md`,
  `.agents/anonymity-policy.md`, `.agents/audit-policy.md`, `.agents/slo.md`,
  `workflow/ai-features.md`, and `workflow/threat-model.md`.
- Existing gateway boundary: backend-only fixed provider origin and model,
  shared admission/budget controls, bounded output, no provider tools, and
  content-free audit and metrics. The responder selector and route are not yet
  implemented; the existing selector defaults to denial.
- Remaining provider risk: the reviewed SoCLaaS documentation does not state a
  prompt/response retention period or deletion guarantee. A provider request
  discloses the allowlisted incident text to a separate service. The security
  reviewer must explicitly accept or reject this residual risk for the stated
  minimized payload before implementation. Recheck provider terms before
  production enablement.
- Planned verification: direct role/access and CSRF denial, stale/concurrent
  selection, exact fake-provider payload, anonymous identity exclusion,
  deterministic duration/SLO cases including handoff, mid-cycle target changes,
  and reopening, post-provider access recheck, adversarial output, failure
  isolation, redacted audit, and no incident mutation. All criteria remain
  unticked.

## Human decisions on the exact specification

- Product specification decision: **Approved**. In this Codex project
  conversation, `lqr1019` reviewed and approved the specification revision
  identified by the SHA-256 above, including section 4.2 and criteria
  `AI-RES-02` through `AI-RES-08` with the stage-specific duration and SLO rules.
- Security/architecture decision: **Approved for this issue**. `lqr1019`
  identified themself as the reviewer and confirmed their security/architecture
  approval for the same bounded, read-only Responder feature. The required
  authorization, minimization, failure, audit, and test controls remain in
  force; no waiver is claimed.
- Provider-retention risk decision: **Accepted for the approved minimized
  payload**. When asked directly, `lqr1019` explicitly accepted sending only
  the approved incident fields to SoCLaaS despite its undocumented prompt and
  response retention period and deletion guarantee. Provider terms must be
  rechecked before production enablement; material new terms require review.
- Implementation gate: **Satisfied for issue #23 at this specification hash**.
  Implementation and verification may proceed against these criteria. This
  record does not approve production enablement, deployment, or release.

The human decisions above, rather than agent analysis, satisfy the specification
and security/architecture gates for this issue. If the specification changes
after review, record the new hash and obtain approval of the changed scope
before implementing it.
