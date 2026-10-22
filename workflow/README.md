# MP3 Specification and Evidence Workflow

Status: **scaffold created; sections marked TODO require team approval or
verified evidence before release.**

This directory records the team's own specification-driven, multi-agent
workflow for MP3. It is not an off-the-shelf SDD toolkit. Product behavior is
defined by `.agents/`; this directory connects those contracts to architecture
decisions, feature acceptance criteria, agent handoffs, reviews, and test
evidence.

## Files

- `product-requirements.md`: MP3 goals, roles, feature inventory, and scope.
- `administrator-vertical-slice.md`: draft React + Spring Boot administrator
  feature-parity specification and acceptance boundaries.
- `ai-features.md`: feature-specific provider data and human-review contracts.
- `admin-ai-insights.md`: bounded Administrator question planning, read-only
  analytics capability catalog, privacy rules, and acceptance criteria.
- `threat-model.md`: assets, trust boundaries, threats, and mitigations.
- `acceptance-criteria.md`: index linking to acceptance criteria stored in each
  individual specification.
- `architecture.md`: selected web, persistence, AI, and deployment design.
- `agent-workflow.md`: agent responsibilities, handoffs, isolation, and gates.
- `decisions/`: approved architecture decision records.
- `reviews/`: dated human review and approval records.
- `test-evidence/`: evidence tied to an exact commit and environment.
- `interaction-summaries.md`: index or summary pointing to sanitized `logs/`.

## Required feature specification fields

Every implemented feature must identify:

1. authorized actors and preconditions;
2. input, output, validation, and size limits;
3. normal, denied, stale, concurrent, and failure paths as applicable;
4. data persisted and audit behavior;
5. data allowed to reach the SoC LLM, if any;
6. the human approval point for generated output;
7. rate-limit, timeout, retry, and unavailable-provider behavior; and
8. acceptance tests and required evidence.

## Approval gates

1. **Specification approved:** product owner confirms scope and acceptance
   criteria before implementation.
2. **Security/architecture approved:** reviewer confirms authorization, privacy,
   data, concurrency, AI, and deployment boundaries. For a feature wholly
   owned within one role, the product owner may explicitly waive a separate
   cross-role human review when cross-review is not part of the team's agreed
   ownership model. Record the reason and scope in the feature specification,
   a dated review record, and its tracking issue. A waiver never waives the
   underlying security, privacy, authorization, testing, or verification
   requirements.
3. **Implementation verified:** automated allowed/denied/failure tests pass and
   the developer records the exact commands and commit.
4. **Independent review approved:** reviewer checks code, tests, unsafe
   shortcuts, secrets, and documentation.
5. **Release approved:** a human verifies the deployed workflows for all roles,
   confirms `master` and the release tag, and records the result.

Agent output never satisfies an approval gate by itself. A named human must
verify the evidence and record approval under `reviews/`.
