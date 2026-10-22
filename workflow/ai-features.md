# SoCLaaS AI Feature and Gateway Specification

Status: **Approved for implementation on 2026-10-23 — provider documentation
was verified on 2026-10-18; verification and release approval remain pending.**

This specification refines `.agents/ai-policy.md` for the three approved
advisory AI features. The Administrator contract is further refined by
`admin-ai-insights.md`. It does not authorize an autonomous agent,
provider-side tools, attachment analysis, or any application mutation.

## 1. Provider evidence and assumptions

The following authenticated SoC DocHub pages were reviewed on 2026-10-18:

- [SoCLaaS overview](https://dochub.comp.nus.edu.sg/cf/guides/soclaas/start)
- [access and key management](https://dochub.comp.nus.edu.sg/cf/guides/soclaas/access)
- [API reference](https://dochub.comp.nus.edu.sg/cf/guides/soclaas/api)
- [models](https://dochub.comp.nus.edu.sg/cf/guides/soclaas/models)
- [quick start](https://dochub.comp.nus.edu.sg/cf/guides/soclaas/quickstart)
- [usage limits](https://dochub.comp.nus.edu.sg/cf/guides/soclaas/usage-limits)

Confirmed provider contract:

- SoCLaaS is a SoC-hosted, OpenAI-compatible gateway at
  `https://soclaas-api.comp.nus.edu.sg/v1` using a bearer API key.
- The application uses only `GET /v1/models` and non-streaming
  `POST /v1/chat/completions`. It must not use `/v1/responses`, embeddings,
  transcription, `previous_response_id`, streaming, or any client- or
  gateway-executed tool.
- Model aliases and access are operator-managed. Production configuration
  names one model, and startup validates that it appears in the authenticated
  `GET /v1/models` response. Application code and database rows do not depend
  on a particular currently documented model name.
- Accounting, quotas, model policy, and audit records are attributed to the
  authenticated SoCLaaS API key. One application key therefore represents a
  shared scarce resource across all application users and instances.
- Documented provider defaults are 90 requests per minute, 80,000,000
  microdollars ($80 accounting value) per UTC day, and 800,000,000
  microdollars ($800 accounting value) per UTC month. These are fair-use
  accounting credits, not charges, and SoC may change them.
- The daily window resets at 00:00 UTC (08:00 Singapore time); the monthly
  window resets at 00:00 UTC on the first day of the month.
- Actual key budgets are available from the portal `/api-key/budget` API and
  the current model catalog/cost basis from `GET /v1/models`. The published
  response schema for the budget call is not part of the reviewed API
  reference, so an implementation must verify and contract-test it before
  relying on automated budget discovery.
- `429` can mean a request-rate or budget limit. `401` means a missing,
  invalid, or revoked key; `403` means a model or feature is disallowed; `503`
  covers provider-side dependency or policy-service failures.
- The reviewed guide does not state a prompt/response retention period,
  deletion guarantee, privacy classification, service-level objective, or
  production availability commitment. The application therefore treats every
  provider request as disclosure to a separate service and sends only the
  minimum approved fields. Provider retention remains an approval blocker to
  be accepted explicitly by the security reviewer.

Documentation is time-sensitive. The release checklist must re-verify the
endpoint, approved model, quotas, cost metadata, error behavior, and retention
terms no more than seven days before production enablement.

## 2. Trust boundary and integration contract

```text
authenticated browser
  -> Spring application authorization + validation
  -> feature-specific allowlist and prompt builder
  -> application admission control and budget ledger
  -> SoCLaaS adapter (fixed HTTPS origin, bearer key)
  -> untrusted plain-text result
  -> size/shape validation and escaped browser rendering
```

- Only the Spring backend can reach SoCLaaS. React never receives the base URL,
  API key, provider response object, model catalog, or budget response.
- The provider origin is an exact deployment setting with HTTPS required.
  User input cannot select a host, path, model, tool, redirect destination, or
  request header. Redirects are rejected to prevent credential forwarding.
- The API key comes from the production secret store, is redacted by default,
  is never committed or placed in the image, and is unavailable to pull
  requests, frontend builds, tests, and preview environments. A suspected leak
  disables AI and triggers key rotation; incident management remains online.
- On Cloud Run, `SOCLAAS_API_KEY` is a numeric-version Google Secret Manager
  reference granted only to the runtime service account. `AI_ENABLED`,
  `SOCLAAS_MODEL`, `AI_CONFIGURATION_REVISION`, and approved downward-only
  limits are non-secret Cloud Run runtime settings. They are not GitHub Actions
  variables or secrets, and the deployment workflow does not copy or replace
  them.
- Production startup fails if AI is enabled but its key, fixed base URL,
  selected model, local limits, or provider-budget ceiling is absent or
  syntactically invalid. Provider catalog or budget unavailability opens the AI
  circuit but does not prevent the non-AI application from starting.
  `AI_ENABLED=false` is the approved fail-safe configuration.
- The adapter sends a non-streaming request with one system message and one
  structured user-data message. It sets an explicit output-token cap, connect
  timeout, total response timeout, and maximum response-body size.
- The adapter never supplies tools or `tool_choice`, never follows model output
  as a URL or instruction, and never automatically makes a second model call.
- At most one retry is allowed, with jittered backoff, only for an idempotent
  request that failed before any response body was accepted and only for a
  transient connection failure, `502`, `503`, or `504`. There is no automatic
  retry for `400`, `401`, `403`, `413`, or `429`. A timeout or cancellation is
  not retried unless the adapter can prove the provider did not accept it.

## 3. Application limits and shared-budget protection

Provider limits are ceilings, not application entitlements. The application
enforces stricter limits before opening a provider connection.

### 3.1 Required default limits

All limits are centrally configurable downward. Raising one requires a reviewed
specification change and production configuration approval.

| Limit | Default |
| --- | ---: |
| Requests per authenticated account, all AI features combined | 5/minute, 30/hour, 100/UTC day |
| Simultaneous requests per authenticated account | 1 |
| Application requests across all instances | 60/minute |
| Simultaneous provider requests across all instances | 8 |
| UTF-8 bytes in the complete feature data record | 16 KiB |
| Estimated provider input | 8,000 tokens |
| Requested provider output | 1,200 tokens |
| Accepted decoded output | 16 KiB and 1,200 tokens |
| Connect timeout | 3 seconds |
| Total provider deadline, including one allowed retry | 30 seconds |
| Provider response body before decoding | 64 KiB |

The application-wide request limit reserves one third of the documented
90-request provider default for manual recovery, health investigation, quota
drift, and other approved clients. The concurrency cap prevents a burst from
occupying every application worker while SoCLaaS is slow.

### 3.2 Budget ceilings

- Production configuration records the provider key's verified daily, monthly,
  and lifetime budgets without recording the key itself. The effective
  application ceilings are the lower of the configured ceilings and 75% of the
  verified provider allowance.
- Against the documented defaults, the application ceilings are therefore
  60,000,000 microdollars per UTC day and 600,000,000 microdollars per UTC
  month. If the actual key allowance is lower, the derived ceilings must also
  be lower. A lifetime credit, when present, receives the same 25% reserve.
- A PostgreSQL ledger shared by all instances atomically accounts for admitted
  requests by account, feature, model, UTC window, estimated input/output
  tokens, provider-reported tokens, estimated accounting cost, outcome, and a
  non-content correlation identifier. It stores no prompt or response text.
- Before dispatch, the application reserves the worst-case cost using the
  selected model's current authenticated price metadata, estimated input
  tokens, and the 1,200-token output cap. Admission fails locally if any
  account, concurrency, request-rate, daily, monthly, or lifetime limit would
  be crossed.
- After a successful response, the reservation is reconciled to provider usage
  metadata. If usage is absent, malformed, or the outcome is ambiguous, the
  conservative reservation remains charged until an authorized reconciliation
  process proves otherwise.
- Rate and budget decisions are transactional. Concurrent instances cannot
  both spend the same remaining capacity. Browser-side throttling is only a
  usability aid and is never authoritative.
- Provider pricing/catalog data is cached for at most 15 minutes. If required
  model or price metadata is missing or stale beyond that window, new AI
  requests fail closed while normal incident workflows remain available.
- A provider `429` opens a shared circuit until an operator-configured
  `Retry-After`/reset instant or, if none is trustworthy, for five minutes.
  The UI reports a generic temporary limit and never exposes key-wide budget,
  another user's activity, or cost details. Repeated clicks do not call the
  provider during the open interval.
- `401` or `403` opens the circuit indefinitely and marks AI unavailable until
  configuration/model access is repaired. Transient `5xx` failures use a
  bounded circuit breaker. None of these states affects non-AI endpoints.

## 4. Feature contracts

### 4.1 Reporter description assistance (`AI-REP-01`)

- Actor/precondition: an enabled authenticated Reporter requesting help for
  text in their current incident form. The server accepts no actor or role
  field and reauthorizes immediately before prompt construction.
- Allowlisted data: normalized category label, title, and current description
  only. The combined feature data record must fit the shared 16 KiB limit.
- Excluded data: account/identity fields, anonymity state, incident ownership,
  credentials, sessions, attachments or names, audit data, other incidents,
  hidden metadata, and server errors.
- Output: an improved description in escaped plain text, at most 1,200 tokens
  and 16 KiB. It is labelled `AI-generated suggestion`.
- Human point: applying a suggestion changes only the local form field. Saving
  or submitting remains a separate, CSRF-protected, validated operation.

### 4.2 Responder incident assistance (`AI-RES-01`)

- Actor/precondition: an enabled authenticated Responder who, at execution
  time, may view the incident under `.agents/authorization-matrix.md`.
- The detail-view rule governs eligibility: an unassigned `SUBMITTED` incident
  requires current category access; an `ASSIGNED` incident remains viewable to
  its assignee even after category access is revoked. A consistent authorized
  read selects the provider data immediately before gateway admission.
- Allowlisted data: incident identifier, category label, title, description,
  lifecycle state, creation time, current-stage kind and elapsed seconds, and
  privacy-safe current-stage SLO state. Version one sends no comments.
- For an unassigned `SUBMITTED` incident, the stage is `QUEUE` and elapsed time
  is measured from the current resolution cycle's `queue_entered_at`. For an
  `ASSIGNED` incident, the stage is `IN_PROGRESS` and elapsed time is measured
  from that cycle's first assignment, including time through handoff or
  reassignment. Use one application-generated UTC instant for selection and
  elapsed-time calculation. A missing or future stage timestamp rejects the
  request before provider dispatch; do not substitute zero.
- Select the category target version effective at the current cycle's queue-entry
  instant, following the prospective rule in `.agents/slo.md`. A later target
  change does not rewrite that cycle; reopening starts a new cycle with a new
  target selection. The provider receives only `WITHIN_TARGET`, `OVERDUE`, or
  `NOT_APPLICABLE`, never target history or SLO configuration. An absent target
  yields `NOT_APPLICABLE`. A handed-off `SUBMITTED` incident retains queue age,
  but its time-to-claim SLO state is `NOT_APPLICABLE` because that cycle was
  already claimed, matching the current live SLO calculation.
- Excluded data: reporter identity whether anonymous or not, internal ownership
  link, comments, attachments or names/bytes, audit events, credentials,
  sessions, unrelated incidents, and hidden administrator fields.
- Output: a concise summary and suggested investigation steps in escaped plain
  text. It cannot claim, assign, comment, hand off, resolve, or call a tool.
- Stale/concurrent path: authorization and state are read consistently just
  before data selection. Loss of access or a conflicting state change denies
  the request without provider disclosure. Reauthorize before returning a
  completed result; if access was lost during provider processing, discard the
  result without exposing its text. This second check cannot retract an
  already-authorized provider request.
- Human point: the Responder explicitly requests and reviews the result in the
  incident detail view. It does not prefill resolution remarks or trigger any
  claim, assignment, comment, handoff, or resolution action.

### 4.3 Administrator trend assistance (`AI-ADM-01`)

- Actor/precondition: an enabled authenticated Administrator with a valid
  statistics scope. The server rebuilds aggregates from authorized canonical
  data and ignores client-posted counts or identities.
- Allowlisted data: selected UTC time bounds, category labels, aggregate
  incident/resolution/reopen/SLO counts and rates, and aggregate durations.
  Groups smaller than five incidents are suppressed or combined into `Other`
  before provider disclosure.
- Excluded data: raw incident or comment text, incident identifiers, reporter
  or responder identities, anonymous internal identity, attachment data,
  audit rows, credentials, sessions, and row-level timestamps.
- Output: an escaped plain-text operational trend suggestion with no apply or
  mutation path and no claim that it is an authoritative statistical result.
- Bounded natural-language operational questions use the separate closed query
  plan and deterministic-answer contract in `admin-ai-insights.md`. That
  operation sends no statistics, selections, or identities to the provider,
  exposes no provider tool, makes at most one provider call, and never feeds a
  plan or answer into the trend-summary request.

## 5. Prompt, output, logging, and audit security

- Each prompt has immutable application instructions separated from a
  canonical JSON data block. The instructions state that every value in that
  block is untrusted content and cannot alter policy, request tools, disclose
  other data, or initiate actions. Delimiting is defense in depth; allowlisting
  and absent tools are the primary controls.
- Input is normalized for encoding and checked for size, but content is not
  silently rewritten to remove suspected prompt injection. Direct and indirect
  injection must remain inert because it has no authority or additional data.
- Responses must be successful JSON of the expected chat-completions shape,
  contain exactly one non-blank textual assistant result, and fit every byte and
  token limit. Unknown fields are ignored; malformed, multi-part, tool-call,
  HTML-dependent, or oversized results are rejected.
- Output is rendered with normal React text escaping. No
  `dangerouslySetInnerHTML`, Markdown renderer, URL fetch, code execution, or
  automatic persistence is permitted.
- Operational logs and traces may contain correlation ID, internal account ID,
  role, feature, model alias, timing, byte/token counts, accounting estimate,
  retry count, circuit state, and stable outcome category. They contain no
  authorization header, API key fragment, prompt, response, incident text,
  comment, attachment name, anonymous identity, or provider error body.
- Every admitted request creates the audit event required by
  `.agents/audit-policy.md`, including feature and outcome category but no
  content. Locally denied throttled attempts are counted in safe metrics;
  security-relevant abuse thresholds may create a content-free failure audit.
- Metrics use bounded labels. Incident IDs, account names, prompts, responses,
  and correlation IDs are not metric labels.

## 6. User-visible failure contract

Stable internal categories map provider and local failures to concise UI
states: `AI_RATE_LIMITED`, `AI_BUDGET_EXHAUSTED`, `AI_TIMEOUT`,
`AI_UNAVAILABLE`, `AI_REJECTED`, `AI_INVALID_RESPONSE`, and
`AI_OUTPUT_TOO_LARGE`. The browser does not receive raw provider status bodies
or distinguish failures in ways that reveal shared-key status.

Every failure keeps the user's form, incident view, or statistics view usable;
does not persist generated text; does not mutate domain state; and offers a
non-AI continuation. Automatic UI retry is prohibited. A user-initiated retry
is admitted only after the applicable local/circuit limit permits it.

## 7. Acceptance criteria

### Provider and configuration (`AI-GW`)

- [ ] `AI-GW-01` — With AI enabled, startup validates the exact HTTPS origin,
  secret presence, selected-model setting, and all local/budget ceilings;
  invalid configuration fails startup without exposing a secret, while an
  unavailable provider catalog opens only the AI circuit.
- [ ] `AI-GW-02` — The production adapter calls only authenticated
  `GET /v1/models` and non-streaming `POST /v1/chat/completions`, rejects
  redirects, and never supplies or executes tools.
- [ ] `AI-GW-03` — Frontend bundles, repository history, container layers,
  errors, logs, traces, metrics, audit events, and CI evidence contain no key,
  authorization header, prompt, response, or prohibited incident data.
- [ ] `AI-GW-04` — A revoked key, forbidden model, stale/missing catalog cost,
  provider outage, timeout, malformed response, cancellation, or oversized
  response produces the specified safe category and leaves all non-AI
  workflows available.
- [ ] `AI-GW-05` — Production exposes the provider key only through a
  numeric-version secret-store reference granted to the runtime identity;
  non-secret AI runtime settings remain outside GitHub Actions, and an ordinary
  image deployment preserves both classes of configuration.

### Usage limits (`AI-LIM`)

- [ ] `AI-LIM-01` — Deterministic-clock tests prove the per-account minute,
  hour, UTC-day, and concurrency defaults, including exact boundary/reset
  behavior and denial without a provider call.
- [ ] `AI-LIM-02` — PostgreSQL concurrency tests prove that multiple instances
  enforce 60 requests/minute, eight in flight, and atomic daily/monthly/lifetime
  cost reservations without overspend.
- [ ] `AI-LIM-03` — The effective daily/monthly/lifetime ceiling is no more than
  75% of the verified provider allowance; lower provider/configured limits win,
  and missing or stale price/budget inputs fail closed.
- [ ] `AI-LIM-04` — Successful usage reconciles reservations; missing or
  ambiguous usage remains conservatively charged; no ledger field stores
  prompt or response content.
- [ ] `AI-LIM-05` — Provider `429`, `401`, `403`, and transient `5xx` responses
  create the specified shared circuit state, do not cause retry storms, and do
  not reveal key-wide quota or other-user activity.
- [ ] `AI-LIM-06` — Load tests show the application reserve and concurrency
  controls continue to protect normal authenticated incident endpoints while
  AI requests are slow or exhausted.

### Authorization, privacy, and human control (`AI-FEAT`)

- [ ] `AI-FEAT-01` — Reporter requests send only current title, category, and
  description, and applying output changes only the local form until a separate
  authorized save/submit.
- [ ] `AI-FEAT-02` — Responder requests reauthorize immediately before
  selection, deny stale/lost access without disclosure, and send only the
  approved operational fields with no identity, comments, or attachments.
- [ ] `AI-FEAT-03` — Administrator requests rebuild aggregates server-side,
  suppress groups below five, and contain no row-level identifiers, text, or
  identities; Administrator insight questions additionally satisfy every
  `AI-ADM-*` criterion in `admin-ai-insights.md` before that capability is
  considered verified.
- [ ] `AI-FEAT-04` — Cross-role, cross-incident, anonymous-identity, excess
  field, forged actor, direct prompt-injection, and indirect prompt-injection
  tests prove that prohibited data and application actions remain unavailable.
- [ ] `AI-FEAT-05` — Malicious HTML, Markdown, URLs, tool calls, and oversized or
  structurally invalid provider outputs are rejected or displayed only as
  escaped text and cannot execute, fetch, or mutate anything.
- [ ] `AI-FEAT-06` — Each admitted request records a redacted audit outcome and
  safe operational metrics; failure never persists generated content or a
  partial domain change.

### Responder incident assistance (`AI-RES`)

- [ ] `AI-RES-02` — A currently authorized Responder can explicitly request a
  summary and suggested investigation steps for one viewable incident through
  a CSRF-protected endpoint that accepts only its identifier; the result is
  labelled advisory plain text and causes no incident mutation.
- [ ] `AI-RES-03` — A signed-out, disabled, wrong-role, or unauthorized account,
  or an incident made inaccessible by category, assignment, or state change,
  is denied before provider dispatch or usage reservation; missing and forbidden
  incidents have the same privacy-safe presentation. An incident assigned to
  the requesting Responder remains eligible after category access revocation.
- [ ] `AI-RES-04` — A deterministic fake-provider capture contains only the
  fields in section 4.2 within shared input bounds. `SUBMITTED` uses `QUEUE`
  age from current-cycle queue entry and its time-to-claim SLO when never
  claimed; a handed-off `SUBMITTED` cycle uses `NOT_APPLICABLE` SLO state.
  `ASSIGNED` uses `IN_PROGRESS` age from current-cycle first assignment and
  its in-progress SLO. The target is the version effective at cycle queue
  entry, including after a mid-cycle configuration edit or a reopening.
  Missing targets produce `NOT_APPLICABLE`; invalid timestamps fail before
  dispatch. Identity, comments, attachments, history, audit content, and other
  incidents are absent.
- [ ] `AI-RES-05` — A concurrent access or state change yields either one
  consistently authorized selection or denial before provider dispatch. A
  second authorization check denies a result if access was lost during provider
  processing. The UI ignores a late result after incident navigation, actor
  change, an access-denied detail refresh, or detail-state reconciliation.
- [ ] `AI-RES-06` — Valid output is displayed only as escaped, bounded,
  labelled text. Malformed or oversized output, markup, URLs, and tool calls
  cannot execute, fetch, prefill remarks, or change application state.
- [ ] `AI-RES-07` — Provider failure, timeout, cancellation, and rate or budget
  limits leave the normal incident workflow usable and persist no generated
  text or partial domain change. Admitted requests record only the required
  content-free audit outcome and safe metrics.
- [ ] `AI-RES-08` — Both eligible detail states offer an explicit request,
  pending/cancel, error, and user-initiated retry path; keyboard, focus, and
  narrow-layout checks verify that the advice can be reviewed without an
  automatic apply or retry action.

## 8. Verification evidence and approval

- Automated evidence: pending implementation; use the deterministic fake
  provider and isolated PostgreSQL required by `.agents/testing.md`.
- Manual/deployed evidence: pending. Re-verify the authenticated provider guide,
  production model visibility, actual budgets, response metadata, TLS, secret
  injection, circuit behavior, and sanitized observability.
- Product approver/date: pending.
- Security/architecture approver/date: pending.
- Provider-retention risk acceptance: pending.

No broad gateway criterion is checked yet. Issue #2 implements and verifies the
adapter portion only; remaining integration and release evidence is described
below.

## Issue #2 adapter progress

The repository user confirmed agreement with this specification and authorized
issue #2 implementation in the working session on 2026-10-18. This is not an
independent security review or release approval.

On 2026-10-23 the repository user explicitly approved this specification and
`admin-ai-insights.md` as both product owner and security/architecture reviewer,
including acceptance of the provider's undocumented prompt/response-retention
risk. This authorizes implementation but does not satisfy implementation
verification, independent code review, deployed verification, or release
approval. See
`reviews/2026-10-23-ai-features-and-admin-insights-approval.md`.

The provider adapter in `infrastructure.soclaas` uses Java's built-in HTTP client
and existing Jackson support; Spring AI is not required. It has no public route
and cannot mutate application state. Production AI remains disabled by default.
The developer guide documents the conservative byte-based token estimate and
explicit caller responsibility for authorized, allowlisted input.

`AI-GW-01` is partial: provider configuration/model checks are implemented; shared
budget/limit validation and circuit integration belong to issues #3 and #4.
`AI-GW-02` has adapter contract coverage for the two allowed paths, redirect
rejection, non-streaming messages, and absence of tools. Broader release evidence
is still required. `AI-GW-03` and `AI-GW-04` have adapter-level redaction and
failure tests, not a full history/image/observability or integrated release audit.
All broad acceptance checkboxes therefore remain open.

The initial provider probe found lower key policy than the published defaults:
30 requests/minute, 50,000,000 daily and 500,000,000 monthly microdollars. Issue #3
must reconcile its configuration and examples with verified key policy: 20
application requests/minute and 37,500,000 daily/375,000,000 monthly microdollars
preserve the intended reserves. These observations do not replace production-key
verification or authorize increased limits.

See [adapter plan](plans/issue-2-soclaas-provider-adapter.md) and
[verification evidence](test-evidence/2026-10-18-soclaas-provider-adapter.md).

## 9. Traceability

| Specification | Governing sources | Implementation area | Planned evidence |
| --- | --- | --- | --- |
| `AI-GW-*` | `.agents/ai-policy.md`, `.agents/security.md`, `.agents/deployment.md` | Spring SoCLaaS adapter, secret/config validation, circuit breaker | Adapter contract, configuration, secret-scan, resilience, and deployed TLS tests |
| `AI-LIM-*` | `.agents/ai-policy.md`, `.agents/persistence.md`, `.agents/testing.md` | PostgreSQL admission/budget ledger and provider-usage reconciliation | Deterministic-clock, multi-instance concurrency, reset-boundary, and load tests |
| `AI-FEAT-01` | `.agents/requirements.md`, `.agents/authorization-matrix.md` | Reporter prompt builder and review UI | Payload allowlist, CSRF, apply-versus-submit, and malicious-output tests |
| `AI-FEAT-02` | `.agents/authorization-matrix.md`, `.agents/anonymity-policy.md` | Responder authorization and prompt builder | Allowed/denied/stale, anonymous-identity, excess-field, and injection tests |
| `AI-FEAT-03` | `.agents/requirements.md`, `.agents/anonymity-policy.md`, `.agents/slo.md`, `admin-ai-insights.md` | Administrator aggregate builder and closed insight-query planner | Server-rebuild, small-group suppression, payload, plan-validation, deterministic-answer, and privacy tests |
| `AI-FEAT-04..06` | `.agents/ai-policy.md`, `.agents/audit-policy.md`, `threat-model.md` | Shared prompt/output, audit, logging, and metrics controls | Adversarial fake-provider plus log/trace/metric/audit capture tests |
