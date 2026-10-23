# Threat Model

Status: **Cross-cutting threat baseline updated on 2026-10-23. Feature-specific
security approvals and evidence are linked below; independent review and
release approval remain open where their owning records say so.**

This model summarizes the current React, Spring Boot, PostgreSQL, private object
storage, SoCLaaS, and Cloud Run system. It does not grant approval or close an
acceptance criterion. A behavior change must update its owning specification,
this model when the boundary changes, implementation, tests, and evidence.

## System and trust boundaries

1. **Public browser -> Spring Security:** every request, identifier, role hint,
   form field, upload name, and CSRF value is untrusted. Spring Security owns
   authentication, server-side JDBC sessions, session fixation protection,
   role routes, CSRF enforcement, and response headers.
2. **Spring controller -> application/domain operation:** controllers parse and
   bound transport input. Application and domain logic derive the actor from
   the security context and enforce ownership, category, assignment, lifecycle,
   and account-state rules. A hidden React control is never authorization.
3. **Application operation -> PostgreSQL:** JDBC adapters use versioned Flyway
   schemas and transactions. Security-sensitive mutations recheck current
   state, use row locks or conditional/versioned updates where conflicts are
   possible, and commit the mutation and success audit together.
4. **Application -> private object storage:** attachment metadata and incident
   relationships are resolved in PostgreSQL before the backend reads the
   private bucket. Browsers receive neither storage credentials nor object
   keys. The current web release exposes authorized PNG/JPEG reads; a future
   Reporter upload/write path requires its separately approved validation and
   compensation contract.
5. **Spring backend -> SoCLaaS:** the backend reauthorizes the actor, selects a
   feature-specific allowlisted record, obtains shared PostgreSQL-backed
   rate/budget capacity, and calls one fixed HTTPS provider origin. The
   provider receives no session, database credential, application tool,
   callback, or mutation authority. Output returns through strict validation
   and escaped-text rendering.
6. **GitHub Actions -> Google Cloud:** CI has no production credentials. The
   deployment job uses GitHub OIDC and Workload Identity Federation to
   impersonate a dedicated deployer; Cloud Run uses a separate runtime service
   account. Runtime secrets come from Cloud Run/Secret Manager, not the image
   or GitHub variables.
7. **Operators and observability:** hosting, database, storage, and provider
   operators remain privileged infrastructure parties. Application logs,
   audits, metrics, workflow evidence, and CI output must contain bounded
   metadata rather than credentials, prompts, responses, anonymous identities,
   or raw incident text.

## Protected assets

- Passwords, password hashes, temporary reset credentials, bootstrap values,
  session identifiers, CSRF state, provider keys, database/storage credentials,
  and deployment identities.
- Incident titles, descriptions, comments, lifecycle and resolution history,
  attachments, reporter ownership, and anonymous reporter identity.
- Persisted roles, account status, responder-category access, assignments,
  SLO targets, statistics, promotion decisions, and session invalidation state.
- Append-only audit integrity, migration history, transaction atomicity, and
  the availability of non-AI incident workflows.
- AI prompts and responses, provider budget and circuit state, and the closed
  Administrator query-plan/handler boundary.
- Repository history, CI evidence, container provenance, release refs, and the
  exact deployed revision.

## Threat actors and assumptions

- An unauthenticated remote user may enumerate accounts, forge portal roles,
  submit malformed or oversized requests, attempt CSRF, and exhaust public
  endpoints.
- A malicious or compromised Reporter, Responder, or Administrator may call
  hidden APIs directly, forge identifiers or versions, race mutations, submit
  prompt injection, and try to access another actor's data.
- Incident text, comments, attachment metadata/content, provider output,
  repository issues, web content, dependencies, tool output, and agent handoffs
  may contain malicious instructions or malformed data.
- SoCLaaS and networks may be slow, unavailable, quota-limited, return hostile
  output, change limits, or retain disclosed content under undocumented terms.
- Concurrent browser sessions and application instances are normal, so
  process-local state is not an authorization or locking boundary.
- Infrastructure operators can access more metadata than application users.
  Least privilege, external platform controls, audit, and documented residual
  risk are required; the application cannot technically eliminate operator
  access.

## Threat analysis

| Threat | Entry point | Asset/impact | Preventive controls | Detection/evidence | Residual risk |
| --- | --- | --- | --- | --- | --- |
| Public privileged-account creation | Reporter registration body or guessed privileged route | Administrator/Responder takeover | Reporter-only registration DTO and service; no public privileged endpoints; one-time Administrator bootstrap; Administrator-controlled promotion/category changes | Forged-role, direct-route, bootstrap reuse/concurrency, unchanged-state, and audit tests | Compromise of an existing Administrator or published grading credential still grants privileged access |
| Account enumeration and credential abuse | Three login portals, registration, password reset | Account identity and access | One identity store; generic login failure; salted password hashes; show-once temporary reset credential; forced replacement and session invalidation | Wrong-password/unknown/wrong-portal parity, password-storage, reset, and session tests | The application does not yet enforce login throttling; the public edge and operators must monitor and constrain online guessing before release |
| CSRF, session fixation, or session theft | Unsafe browser request or stolen cookie | Unauthorized mutation or account use | Spring SPA CSRF cookie/header flow; every unsafe route protected; session ID rotation on login; Secure/HttpOnly/SameSite production cookie; bounded session; logout and privilege-change invalidation; CSP and no-store responses | Missing/invalid/stale CSRF, login rotation, logout, wrong-role, multi-session, and production-cookie tests | XSS or endpoint compromise can act with the victim's active session; readable CSRF token is not an XSS defense |
| Broken object- or role-level authorization | Forged incident/account/attachment ID, browser-supplied actor, or stale page | Cross-role, cross-owner, cross-category, or cross-assignment disclosure/mutation | Actor derived from security context; application/domain policy; execution-time recheck; generic missing/forbidden presentation; privacy-minimized DTOs | Authorization-matrix, direct-HTTP, stale-access, disabled-account, and denied/missing parity tests | A defect in a new endpoint or projection can bypass an intended check |
| Anonymous identity leakage | Entity serialization, filters, statistics, errors, logs, AI payloads | Reporter identity exposed to Responder, Administrator, provider, or another Reporter | Explicit DTO and AI allowlists; ownership retained only for authorized Reporter access; aggregate trend suppression before provider disclosure; Administrator insight questions send no identities/statistics and locally render every responder sample with its size | Anonymous submission, cross-role projection, payload capture, log/audit, filter, and statistics tests | Free text can self-identify; aggregate operational data can still support inference |
| Concurrent double claim or stale mutation | Simultaneous claim, resolution, assignment, promotion, category, account, or SLO request | Lost update, duplicate assignee/history/audit, or invalid authorization | PostgreSQL transactions; conditional updates, row locks, expected versions, request IDs, and execution-time authorization; mutation and success audit commit together | Two-claim single-winner, stale-version, replay, concurrent decision, rollback, and audit-count tests | Database/network failure may yield an ambiguous client outcome; safe retry is operation-specific |
| Partial mutation or audit omission | Persistence or audit failure inside a domain change | State without its required audit or false success evidence | One transaction for mutation and success audit; exceptions roll back; failure paths do not write success audits | Injected persistence/audit failure and post-failure state assertions | External object storage cannot join a PostgreSQL transaction; any future upload needs compensation and reconciliation |
| Attachment spoofing or active content | Attachment identifier or stored object returned through backend | Cross-incident disclosure, executable content, memory exhaustion | Generated identifiers; metadata relationship and authorization before storage read; private bucket; PNG/JPEG allowlist; bounded size; attachment disposition; generic filename; `nosniff` and no-store | Denied-path zero-read, MIME/header, mismatch, absent-object, and storage-failure tests | Authorized users can view permitted incident images; current web release does not yet expose the separately specified Reporter write path |
| Direct prompt injection | Reporter draft, authorized incident fields, or Administrator question | Model follows attacker text or fabricates an unsafe response/plan | Structured trusted instruction plus untrusted data; minimal feature records; no provider tools or authority; strict result validation; human review | Fake-provider injection, captured-request, plan-parser, malicious-output, and mutation-absence tests | Misleading advisory text can influence a person who reviews it carelessly |
| Indirect prompt injection or response chaining | Comments, attachment names/bytes, unrelated incidents, prior AI output, provider plan | Hidden instruction reaches another request or widens data/action scope | Reporter/Responder selectors exclude comments, attachments, identities, and unrelated incidents; Administrator planner receives no analytics; previous output is never chained; one call per action | Negative-selection payload tests, one-call/load tests, and adversarial-provider suite | Future field additions can reopen the surface and require a new data-selection review |
| Cross-role/cross-incident AI leakage | Forged resource ID, stale access, or client-posted statistics/identity | Protected data disclosed to provider or caller | Immediate authorization before canonical selection; Responder access rechecked before returning; Administrator aggregates rebuilt server-side; structured selections remain outside provider payload | Allowed/denied/stale access, captured-provider, selector isolation, and discarded-result tests | A request authorized before a mid-call change cannot be retracted from the provider; the result is discarded locally |
| Administrator query-plan injection | Free-text question or hostile provider plan | SQL/code execution, arbitrary handler, row retrieval, mutation, or fabricated facts | Closed JSON schema; strict enums, combinations, periods, and row bounds; typed handler registry; no SQL/reflection/URL/tool interpretation; application-owned deterministic renderer | SQL, reflection, URL, traversal, tool-call, malformed/duplicate-field, excessive-limit, handler-allowlist, and mutation-absence tests | A defect shared by validator and tests could expose an unintended aggregate |
| Misleading responder ranking | Ambiguous metric, ties, zero denominator, or model-fabricated result | Unfair personnel conclusion or incorrect operational action | Deterministic application metrics; exact metric/period/filter/sample disclosure; every qualifying responder included; explicit zero-sample comparison row; first-place ties preserved; operational-indicator notice | Boundary, attribution, tie, zero-sample, no-threshold, and provider-fabrication tests | Administrators may over-interpret a valid low-sample operational indicator |
| Malicious provider markup or oversized/malformed output | SoCLaaS response | Browser script/navigation, resource exhaustion, or misleading UI | Bounded HTTP subscriber and token/byte caps; expected JSON shape; one text result or closed plan; escaped plain text; no Markdown/HTML/URL fetch | HTML/Markdown/script, tool-call, malformed shape, streaming oversize, and browser rendering tests | Plain text can still contain social-engineering instructions |
| AI quota, cost, or availability exhaustion | Repeated/concurrent requests or changed provider limits | Shared key depleted or normal app degraded | Per-account/feature/global limits; atomic PostgreSQL reservation; daily/monthly/lifetime caps; concurrency cap; provider reserve; timeout; bounded retry; shared circuit; AI-disabled fallback | Deterministic-clock, PostgreSQL concurrency, 429/401/403/5xx, timeout, load, and normal-endpoint availability tests | Provider quotas and pricing can change; production limits require re-verification |
| Prompt, response, or identity leakage through observability | Client tracing, exceptions, audit, metrics, CI evidence | Sensitive content retained outside the application | Content-free audit/metrics; bounded labels; no provider-body logging; redacted errors; no secrets or production data in CI/evidence | Log, trace, metric, audit, repository, image/layer, and workflow-output scans | Infrastructure operators can still observe connection metadata; provider retention remains an accepted, documented risk for approved payloads |
| Database, storage, or deployment credential exposure | Repository, image layer, GitHub workflow, environment, log, or over-privileged identity | Production data and deployment compromise | Ignored local env files and safe examples; Cloud Run/Secret Manager ownership; GitHub OIDC/WIF; separate deployer/runtime identities; no long-lived Google key; private storage; least-privilege documented roles | Secret scans, tracked-file/history/image checks, workflow-permission review, and deployed configuration evidence | External IAM/configuration can drift; ADR-006 independent review and periodic operator verification remain open |
| Malicious or mistaken agent instruction | Issue, document, web content, tool output, dependency message, or agent handoff | Requirement drift, weakened safeguards, secret disclosure, or unauthorized release | Canonical specs outrank untrusted text; bounded agent roles; explicit approval gates; no inferred human approval; source/test verification; stop after repeated failures | Review records, diff review, exact-command evidence, and disagreement/blocker records | A human can still approve an incorrect interpretation; independent review quality remains important |

## Approval and residual risk

- Feature-specific approval records live under [`reviews/`](reviews/) and in
  the owning specifications. The shared AI boundary and provider-retention
  risk were approved on 2026-10-23; the Responder AI approval is recorded
  separately with `lqr1019`.
- This cross-cutting update does not invent a new security reviewer or approval
  date. Independent review remains pending for ADR-001, ADR-002, and ADR-006,
  and release approval remains pending under [`README.md`](README.md).
- Accepted or unavoidable residual risks include human over-trust of advisory
  text, operator access, provider retention/availability uncertainty,
  statistical inference, configuration drift, and ambiguous outcomes after a
  network failure. Each is bounded by the controls above and must be revisited
  if its feature or trust boundary changes.
