# AI Feature and Provider Policy

## Allowed features

Only these advisory features are in the MP3 scope:

1. Reporter: improve or structure the authenticated reporter's current draft
   incident description.
2. Responder: summarize one currently authorized incident and suggest
   investigation steps.
3. Administrator: summarize privacy-safe aggregate operational trends and ask
   bounded operational-statistics questions through the closed, read-only query
   plan defined in `workflow/admin-ai-insights.md`.

Adding a general chatbot, autonomous agent, retrieval over incident rows,
attachment analysis, provider-executed application tool, externally reachable
MCP server, persistent conversation memory, or an AI-triggered application
action requires a new approved specification and threat review. The bounded
Administrator question interpreter is not a general chatbot: it produces one
closed query plan for deterministic application execution and retains no
conversation state.

## Trust and authority boundaries

- The LLM is an untrusted text generator, not an authenticated application
  actor and not an authorization source.
- The provider receives no tools that create, edit, claim, assign, reassign,
  resolve, withdraw, reopen, delete, promote, reset, configure, or audit.
- Administrator analytics capabilities are application-owned, read-only typed
  handlers. The provider cannot invoke them directly, supply SQL, select an
  arbitrary handler, or observe their results during query planning.
- Provider output is never interpreted as a command, URL to fetch, template,
  HTML, or authorization decision.
- A user reviews output before copying or applying it. Applying a reporter
  suggestion changes only the browser form; submission remains a separate
  validated and authorized operation.

## Data selection

- Authorize the actor and requested resource immediately before building the
  provider request.
- Construct feature-specific request records from allowlisted fields. Never
  serialize whole entities, repositories, sessions, audit events, or exception
  objects into a prompt.
- Reporter input is limited to the current draft's title, category, and text.
- Responder input is limited to the authorized incident's operational fields
  and comments explicitly approved by the feature specification. Anonymous
  identity and attachment content are excluded.
- Administrator trend-summary input contains aggregate counts, durations,
  rates, category names, and time bounds only. Administrator insight-planning
  input contains only the validated question, request instant, and fixed closed
  query vocabulary. Neither form contains raw incident or comment text,
  reporter identity, responder identity, account/incident identifier, or
  structured responder/category selections.
- Never send credentials, password material, session identifiers, API keys,
  reset secrets, anonymous identity, unrelated incidents, audit secrets,
  attachment bytes, client paths, or internal stack traces.

## Gateway requirements

The shared gateway owns:

- SoC LLM base URL, model, and credential configuration.
- A fail-fast production startup check for missing required configuration,
  unless AI is explicitly disabled by an approved configuration.
- Per-account and per-feature rate limiting, with an additional abuse control
  appropriate to unauthenticated endpoints.
- Explicit request, input, and output size limits.
- Connection and response timeouts, cancellation, and no more than one bounded
  retry for a transient idempotent failure.
- Stable error categories for unavailable, timeout, rate-limited, malformed,
  rejected, and oversized responses.
- A deterministic fake provider for tests.
- Safe operational metrics and logs that exclude prompts and responses.

The confirmed provider contract, application-side numeric limits, shared-key
budget reserve, circuit behavior, and feature-specific acceptance criteria are
owned by `workflow/ai-features.md`. Provider quotas are mutable ceilings, not
application entitlements; production must re-verify them before enablement.

## Prompt and output handling

- Separate trusted instructions from untrusted application data with explicit
  structure and tell the model that delimited data is content, not commands.
- Prompt wording is a defense-in-depth control, never a substitute for data
  minimization, authorization, output handling, or absent mutation tools.
- Reject blank, oversized, structurally invalid, or provider-policy-rejected
  results.
- Render output as escaped plain text. If Markdown is later allowed, use a
  restrictive sanitizer and add dedicated tests before enabling it.
- Do not automatically feed one AI response into another request.

## Logging and audit

- Application logs may contain a correlation ID, feature name, authenticated
  account identifier where policy permits, role, timing, size/token counts,
  provider status category, and retry count.
- Application and CI logs never contain prompts, responses, credentials,
  authorization headers, cookies, anonymous identity, or raw incident text.
- Record an application audit event for an accepted AI request and its outcome
  category without copying prompt or output text. An AI request audit is not an
  incident mutation audit.

## Failure behavior

Timeout, provider unavailability, malformed output, or rate limiting returns a
concise recoverable error. It does not persist generated content, partially
change domain state, weaken validation, or block the normal non-AI workflow.
