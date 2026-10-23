# Architecture Decision Records

Status: **Active index. Decision status and approval are authoritative only in
each ADR; inclusion here does not upgrade a proposed or pending decision.**

| ADR | Scope | Current recorded status |
| --- | --- | --- |
| [ADR-001](ADR-001-web-persistence-and-session-foundation.md) | React/Spring artifact, PostgreSQL, JDBC sessions, and Administrator bootstrap | Accepted for implementation; independent security review pending |
| [ADR-002](ADR-002-spa-csrf-token-flow.md) | Same-origin SPA CSRF token creation, request-time reads, and bounded recovery | Accepted for implementation; independent security review pending |
| [ADR-003](ADR-003-promotion-decision-and-session-invalidation.md) | Promotion persistence, category subset, transaction, and session invalidation | Product and security/architecture approval recorded; implementation evidence remains governed by the journey criteria |
| [ADR-004](ADR-004-responder-mutations.md) | Responder claims, resolutions, history, and audit boundaries | Accepted for issue #7 with a recorded role-owner review waiver |
| [ADR-005](ADR-005-responder-attachment-and-comment-reads.md) | Authorized attachment streaming and privacy-safe comment reads | Accepted for issue #7 read scope; Reporter writes remain excluded |
| [ADR-006](ADR-006-cloud-run-deployment-identity.md) | GitHub OIDC/WIF deployer and separate Cloud Run runtime identity | Proposed; independent architecture/security and release approvals pending |

New material decisions receive the next sequential ADR. Each record identifies
status, date, deciders, context, options, security/privacy effects, decision,
consequences, verification, and superseded decisions. A decision is not
accepted until the required human decision is recorded.
