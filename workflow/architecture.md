# MP3 Architecture

Status: **Implementation in progress — deployment direction and web persistence
foundation selected; independent architecture/security review pending.**

## Proposed shape

The current plan is a React browser application backed by a modular Java web
API with server-side security, PostgreSQL persistence, a shared SoC LLM
gateway, and a containerized independent deployment. Existing domain and
application services should be reused where they remain valid. An ADR must
decide whether React assets are served by the Java application or deployed as
a separate static frontend.

## Required sections

- Context and component diagrams: TODO
- Request/authentication/session flow: TODO
- Application authorization boundary: TODO
- PostgreSQL schema, migrations, and transaction boundary: TODO
- Concurrent incident-claim strategy: TODO
- Attachment storage choice: Neon Object Storage private branch-scoped bucket;
  metadata remains in PostgreSQL. Responder reads stream through the backend
  after per-request authorization; no signed URLs or browser credentials.
  Reporter uploads and write compensation remain TODO. See `neon-services.md`.
- Application event replacement for JavaFX-thread events: TODO
- AI gateway and provider trust boundary: the Spring backend is the only
  SoCLaaS client. It reauthorizes the actor, constructs a feature-specific
  allowlisted record, acquires atomic shared rate/budget capacity, and calls the
  fixed HTTPS SoCLaaS origin through a narrow non-streaming chat-completions
  adapter. React receives only a validated plain-text suggestion, a
  deterministic application-rendered Administrator analytics result, or a
  stable safe error. Administrator questions may produce one closed JSON query
  plan, which the application validates before dispatching one read-only typed
  analytics handler; responder names are joined locally after the provider
  call. The provider has no application credentials, database access,
  application or MCP tools, callback, result visibility, or mutation path.
  Exact limits, key handling, circuit behavior, and acceptance criteria are
  specified in `ai-features.md` and `admin-ai-insights.md`; the repository user
  approved this AI trust boundary for implementation as product owner and
  security/architecture reviewer on 2026-10-23, including the documented
  provider-retention risk. Verification and release approval remain pending.
- Development/test/production configuration: TODO
- Health checks, logging, monitoring, backup, and restore: TODO
- Deployment topology and rollback/forward-fix strategy: TODO
- Reused MP2 components and replaced components: TODO

## Architecture decisions

- `decisions/ADR-001-web-persistence-and-session-foundation.md`: accepted web,
  migration, authentication, shared-session, and administrator-bootstrap
  foundation.
- `decisions/ADR-002-spa-csrf-token-flow.md`: accepted cookie-backed SPA CSRF
  flow with request-time token reads and bounded stale-token recovery.
- `decisions/ADR-003-promotion-decision-and-session-invalidation.md`:
  approval-ready proposed transaction, category-subset, bounded-history, and
  shared-session contract for issue #6; existing implementation remains
  provisional and approval remains pending.
- `decisions/ADR-004-responder-mutations.md`: approved issue #7 responder
  projection, transactional claim/resolution, audit atomicity, and history
  behavior. Separate cross-role human security/architecture review waived by
  the product owner for issue #7; automated and implementation verification
  requirements remain mandatory.
- `decisions/ADR-005-responder-attachment-and-comment-reads.md`: approved
  issue #7 backend-only attachment streaming and privacy-safe comment read
  projection. Reporter upload, comment writes, and cleanup compensation remain
  outside the decision.
- `decisions/ADR-006-cloud-run-deployment-identity.md`: proposed keyless GitHub
  Actions deployment identity and least-privilege Cloud Run service identities;
  independent architecture/security approval remains pending.

Create one record in `decisions/` for each material choice. At minimum cover:

- web framework and rendering approach;
- authentication/session model;
- PostgreSQL access/mapping and migrations;
- attachment storage;
- AI provider adapter and safety limits; and
- host, container, CI/CD, and release branch.

## Approval

- Architect/reviewer: TODO
- Security reviewer: TODO
- Date: TODO

The repository user approved the Neon Object Storage provider amendment on
2026-10-17. Independent architecture and security review remain TODO.
