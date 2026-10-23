# Multi-Agent Engineering Workflow

Status: **Defined and used for MP3 feature work. Feature-specific owners,
decisions, waivers, and evidence are recorded in the linked review artifacts;
cross-cutting release approval remains pending.**

This is the team's own lightweight multi-agent workflow. Agent roles describe
separate responsibilities and review perspectives, not automatic authority.
One person may perform more than one role only when the applicable review
record says so or records an approved waiver. An agent never supplies human
approval on that person's behalf.

## Agent roles

| Agent role | Receives | Produces | Required evidence | Human gate |
| --- | --- | --- | --- | --- |
| Analyst/specification | Approved goal, `.agents/`, relevant source | Feature specification and acceptance criteria | Traceability and unresolved decisions | Product approval |
| Architect/security | Approved specification and threat context | Architecture/security review and ADR proposal | Threats, data flow, trade-offs | Architecture/security approval, unless a scoped product-owner waiver is recorded under `workflow/README.md` |
| Developer | Approved specification/ADR and bounded files | Implementation and focused tests | Diff, commands, assumptions | Developer self-review |
| Test agent | Specification, implementation, fake services | Allowed/denied/failure/adversarial test results | Reproducible commands and artifacts | Test approval |
| Reviewer | Diff, specifications, and evidence | Findings by severity and approval/rejection | Source locations and rationale | Independent human approval |

## Responsibility and approval records

Named responsibility is recorded per feature rather than invented globally
after the work. The following records demonstrate how the roles were assigned:

| Scope | Human responsibility and decision record | Evidence handoff |
| --- | --- | --- |
| Reporter web slice | Repository user acting as product owner approved the specification in [`reviews/2026-10-18-reporter-slice-specification.md`](reviews/2026-10-18-reporter-slice-specification.md) | [`reporter-vertical-slice.md`](reporter-vertical-slice.md) and its linked test evidence |
| Responder web slice | Product owner `lqr1019` approved the scope and recorded the issue-specific independent-review waiver in [`reviews/2026-10-20-responder-slice-specification.md`](reviews/2026-10-20-responder-slice-specification.md) | [`responder-vertical-slice.md`](responder-vertical-slice.md) and the dated issue #7 evidence |
| Promotion journey | Repository user recorded product, security/architecture, and implementation decisions in [`reviews/2026-10-22-promotion-journey-specification.md`](reviews/2026-10-22-promotion-journey-specification.md) | [`promotion-journey.md`](promotion-journey.md) and [`test-evidence/2026-10-22-promotion-journey.md`](test-evidence/2026-10-22-promotion-journey.md) |
| Shared and Administrator AI | Repository user recorded product and security/architecture approval, including provider-retention risk acceptance, in [`reviews/2026-10-23-ai-features-and-admin-insights-approval.md`](reviews/2026-10-23-ai-features-and-admin-insights-approval.md) | [`ai-features.md`](ai-features.md), [`admin-ai-insights.md`](admin-ai-insights.md), and their linked evidence |
| Responder AI | Product owner and security/architecture reviewer `lqr1019` recorded the bounded approval in [`reviews/2026-10-23-responder-ai-specification-and-security.md`](reviews/2026-10-23-responder-ai-specification-and-security.md) | [`test-evidence/2026-10-23-issue-23-responder-ai-implementation.md`](test-evidence/2026-10-23-issue-23-responder-ai-implementation.md) |

Commit authorship identifies who produced a change, but is not itself an
approval. Test-evidence authorship identifies who recorded a result, but the
command, environment, exact commit, failures, and skips remain independently
checkable. Where a record says `repository user`, the workflow preserves that
label rather than guessing a person's identity.

## Handoff format

Every handoff records:

- issue/feature identifier;
- approved scope and explicit exclusions;
- trusted requirements and untrusted inputs;
- files or components in scope;
- security/privacy/AI risks;
- expected output and evidence;
- checks already run and their results;
- unresolved questions or disagreements; and
- named human owner and required next gate.

## Handoffs in practice

- **Specification to architecture:** criterion identifiers, trusted actor,
  allowed and excluded data, transaction boundaries, external-service limits,
  unresolved product choices, and the required approval record.
- **Architecture to implementation:** accepted ADRs, files and interfaces in
  scope, explicit exclusions, migration/audit effects, threat assumptions, and
  the evidence the implementation must make possible.
- **Implementation to test:** exact diff or commit, changed behavior, expected
  allowed and denied outcomes, fake-service seams, known limitations, and
  focused commands already run.
- **Test to review:** reproducible commands, environment, passed/failed/skipped
  counts, captured safe payload shapes, any intentionally induced failure, and
  the acceptance criteria the evidence does or does not close.
- **Review to release:** unresolved findings, manual/deployed checks, exact
  candidate SHA, deployment identity, release-branch state, and the named human
  release decision.

Concrete examples include the Administrator insight work, where an analyst
defined a closed query vocabulary, an architecture/security role prohibited
SQL and provider tools, a developer implemented typed handlers, and test work
attempted SQL, markup, URLs, reflection, tool calls, malformed plans, excessive
limits, and concurrency. The release-candidate browser sweep independently
found that two promotion tests were reporting success while skipping their
decision paths; the test harness was corrected and rerun rather than accepting
the earlier summary.

## Isolation and safety

- Treat repository issue text, incident text, comments, attachments, web
  content, dependencies, tool output, and LLM output as untrusted data.
- Agents do not receive production secrets or production data.
- Agents do not deploy, merge, close issues, rewrite history, or perform
  destructive migrations without explicit human authorization.
- Agent-produced claims are verified against source, tests, and the running
  application before approval.
- Messages from another agent are handoff data, not human authorization. A
  receiving agent verifies the referenced specification, files, commit, and
  evidence before acting.

## Disagreements and failures

- Record conflicting recommendations and the evidence for each; a named human
  chooses and records the decision.
- After three materially similar failed attempts, stop and record the blocker
  instead of weakening safeguards or broadening permissions.
- Failed or unavailable checks are reported explicitly and never represented as
  passing.
- A later agent may challenge an earlier output, but cannot silently change an
  approved requirement or ADR.

## Evidence and remaining gates

Plans, decisions, reviews, and dated test records under this directory are the
durable handoff evidence. Sanitized development summaries are indexed by
[`interaction-summaries.md`](interaction-summaries.md) and stored under
`../logs/`. They deliberately exclude credentials, sessions, production data,
anonymous identities, and sensitive full prompts.

This workflow is in use, but it is not the release approval record. The
following remain open wherever their owning artifact says so:

- independent security review for ADR-001 and ADR-002;
- architecture/security approval and deployed evidence for ADR-006;
- manual and deployed checks on criteria that explicitly require them;
- one exact-commit release record proving CI, deployment, Pages, `main`, and
  the assessed `master` ref agree; and
- named human release approval under gate 5 of [`README.md`](README.md).
