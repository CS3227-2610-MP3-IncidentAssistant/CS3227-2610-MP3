# Shared AI usage controls and secured gateway

Contributor account: `haowern98`. Work period: 18-19 October 2026.

These entries record request summaries, implementation decisions, corrections,
and verification from the shared gateway work. Request summaries describe the
intent of the exchanges rather than serving as verbatim chat quotations.

## Establish the specification and divide the work

**Request summary.** Read the supplied SoCLaaS documents, establish the agreed AI
scope, and divide the shared integration into reviewable issues before starting
implementation. Use the existing `codex/issue-<number>-<description>` branch format.

**Agent response and decision.** The integration was divided into provider adapter
#2, shared usage controls #3, and secured gateway #4. Provider transport was kept
separate from application authorization, accounting, and role features. The Spring
AI discussion clarified that a library for model calls would not replace these
application safeguards. The implementation used Java HTTP and Jackson.

**Course connection.** This follows the specification-first approach: define the
allowed operations and provider contract before building the integration. The
issue boundaries provide smaller handoffs and review scopes rather than treating
all LLM work as a single unrestricted task.

## Implement shared resource admission

**Request summary.** Continue with issue #3, using the agreed resource limits and
shared persistence so concurrent application instances cannot independently spend
the same quota. Keep credentials private and preserve normal non-AI operation.

**Agent response and output.** PostgreSQL-backed admission and accounting were
implemented with reservations, per-account limits, global concurrency controls,
and conservative handling of unknown provider usage. Isolated database tests
covered shared accounting and competing requests. Representative history includes
`16d991c`, `2d282e3`, `951bb18`, and `b6d0544`.

**Human corrections.** I checked that the private environment was not committed.
I also stopped documentation commits when they were being included before I
wanted them published. I limited removal to issue #3's four documentation commits;
the agent preserved that documentation locally instead of removing unrelated
implementation work or the older issue #2 documentation.

**Verification boundary.** Database and fake-provider results concern the tested
implementation and isolated environment. They do not establish production quota,
provider availability, or deployment approval. No credentials are included here.

## Secure the provider boundary

**Request summary.** Complete issue #4 against the agreed AI contracts: authorize
the actor, select only approved data, separate trusted instructions from untrusted
content, enforce bounded provider access, and return safe errors on failure.

**Agent response and output.** The gateway combined backend actor checks,
allowlisted data selection, bounded requests/responses, shared usage admission,
metadata verification, circuit handling, and content-free audit outcomes. No
mutation tools or provider-output command interpreter were exposed. Representative
commits include `e35e6b9`, `3c3d9f3`, and `5302f09`.

**Human correction and environment evidence.** Provider metadata access initially
timed out. I asked what the budget response meant and showed the portal usage
screen. The screenshot was useful context but did not supply the API's JSON
schema. I later connected the NUS VPN so endpoint checks could continue. Network
failure was not treated as proof that the gateway itself was working or broken.

**Course connection.** Lecture 6, pages 24-25, discusses adversarial tests,
instruction/data separation, input limits, post-generation checks, and human
oversight. The gateway applies these ideas through bounded structured data,
response validation and absent mutation authority. It does not claim a universal
prompt-injection detector or automatic filtering of every malicious phrase.

## Integrate without losing the verification context

**Request summary.** Fetch teammates' changes, check whether the branch is behind,
retest the combined implementation, and keep a reviewable path toward merging.

**Agent response and output.** Integration incorporated promotion and SPA CSRF
changes. The canonical migration order placed promotion at V4, AI usage at V5,
and AI gateway controls at V6. Upgrade tests checked migration from the canonical
promotion schema; old experimental preview data was preserved separately.

**Verification and handoff.** The integrated historical check reported 644 Java
tests, with 642 passing and two opt-in live cases skipped, plus 47 frontend tests.
These are recorded historical results, not tests rerun while writing this log.
Subsequent changes on `main` may alter both behavior and test totals. Scope, schema
ordering, CSRF behavior, provider limits and outstanding live checks were context
that had to survive the handoff to Reporter integration.

The combined implementation was published in [PR #11](https://github.com/CS3227-2610-MP3-IncidentAssistant/CS3227-2610-MP3/pull/11),
which merged on 19 October and closed issues #2, #3, #4 and #8. The PR also included
the Reporter feature described in the separate interaction summary.

## Security-focused planning and review exchanges

### Read the provider contract before granting access

**Request summary.** Read documents 1-9 first, then explain the next implementation
step against the agreed AI specification.

**Work and security decision.** We treated the provider documents as an interface
contract rather than granting the model general access to the application. The
implementation distinguished provider authentication, model selection and metadata
from the authorization of an Incident Desk user. The backend kept the provider
credential; the browser received only the feature result or a safe error.

### Separate framework support from application security

**Request summary.** Explain whether Spring AI is needed, whether the shared
gateway already handles SoCLaaS calls, and which security requirements the library
would leave to our application.

**Work and security decision.** We separated request formatting from the security
policy surrounding a request. Our gateway owned actor checks, selected fields,
limits, accounting, circuit state and safe diagnostics. Choosing direct Java HTTP
kept that policy in the existing backend rather than introducing another library
as a substitute for the controls. The model remained a text generator rather
than an identity, authorization decision-maker or incident service.

### Define a small credential and network boundary

**Request summary.** Confirm that the private environment has not been committed,
and keep real provider checks modest while allowing enough quota to diagnose the
integration.

**Work and security decision.** We kept the private provider environment outside
the tracked configuration and used placeholders in `.env.example`. The adapter
restricted its production base URL to the approved SoCLaaS API instead of accepting
a browser-selected host. Provider keys, cookies and authorization headers were
excluded from error messages and recorded evidence. Small live checks addressed
connectivity and provider response questions; routine regression cases used a
fake provider and synthetic data.

### Keep developer permissions narrower than the task's ambition

**Request summary.** Create issues and branches first, keep the gateway and
Reporter work reviewable before merging, and follow the requested branch names.

**Work and security decision.** We used issue boundaries to control what each
implementation step could change. Starting a provider adapter did not authorize
a chatbot, incident-wide retrieval, an arbitrary-prompt endpoint or autonomous
mutations. Separate fixture and review work returned findings to the main agent;
the main agent integrated the changes and retained the user's publication gate.
This gave the agent handoffs a concrete scope rather than an open instruction to
make the AI do everything.

### Turn the shared gateway requirement into explicit trust boundaries

**Request summary.** Explain the shared gateway's place in the specification and
start implementation of the approved gateway issues.

**Work and security decision.** We separated three boundaries: the authenticated
user requesting assistance, the backend selecting permissible data, and the
provider returning untrusted text. `AiPromptBuilder` placed trusted instructions
in the system message and serialized selected data separately. Reporter drafts
were limited to title, category and description; entire account, session and
incident objects were not serialized into the prompt.

The contract tests used instruction-like content inside a draft and checked that
it remained in the data message rather than replacing system instructions. The
same checks protected object stringification from exposing that content. The
backend validated request shape and limits before dispatch, so rejection did not
require asking the model whether an input was acceptable.

### Protect the shared key from concurrent spending

**Request summary.** Proceed with shared usage controls and then complete the
secured gateway while keeping normal incident reporting usable.

**Work and security decision.** We made rate, concurrency and budget decisions in
shared PostgreSQL state. Per-account limits prevented one account from creating
unbounded work; global limits protected the key across instances. Reservations
were taken before provider dispatch. Ambiguous timeouts and malformed usage did
not automatically refund resources that the provider might already have consumed.

`AiUsageLedgerPostgresTest` covered competing reservations, exact time boundaries,
shared lower settings, UTC day rollover, lock contention and repeated settlement.
`AiGatewayControlPostgresTest` covered content-free audit writes, rollback on audit
failure, shared circuit behavior and stale workers. These tests addressed attacker
objectives such as exhausting the key or racing two application instances for the
same remaining budget.

### Bound failures instead of retrying generation blindly

**Request summary.** Investigate endpoint access and budget timeouts, then continue
the gateway work when the NUS VPN connection is available.

**Work and security decision.** We separated network availability from request
validation. Connection and response deadlines bounded worker use. Generation was
not automatically retried because a lost response did not establish that the
provider had rejected the request. Shared circuits stopped repeated dispatch after
provider/key failures, and metadata checks controlled admission. The normal
non-AI workflow stayed available when the AI path returned a safe failure.

### Preserve integrity during schema and teammate integration

**Request summary.** Fetch the newest commits, check the effect of pulling, and
run the tests again before committing and publishing.

**Work and security decision.** We integrated the current promotion and CSRF work
rather than preserving stale branch assumptions. Migration ordering was checked
against the canonical database chain. Upgrade coverage preserved existing account
and promotion rows while adding AI tables. Existing experimental preview data was
kept separate instead of rewriting an applied shared migration to make startup
succeed. The same review checked that the authenticated actor still came from the
current session and database, not a browser-supplied role or account field.

## Security evidence in the integrated gateway snapshot

The following test areas were present at `268b8ef`, the integrated Reporter/gateway
snapshot used for the first publication. They identify the concrete security work
behind the summaries rather than treating model output as the only test oracle.

| Attacker objective or failure | Boundary checked | Test evidence |
| --- | --- | --- |
| Replace trusted instructions through incident text | Serialized data stays separate from system instructions | `AiGatewayContractTest` |
| Retrieve an unrelated resource | Selectors deny missing access and mismatched incident selection | `AiGatewayContractTest` |
| Spend the same credit concurrently | Shared reservation and concurrency state | `AiUsageLedgerPostgresTest` |
| Keep dispatching after key/rate failures | Shared circuit and revision fencing | `AiGatewayControlPostgresTest` |
| Hide sensitive content in diagnostics | Fixed rejection reasons and content-free audit records | `SoclaasClientTest`, `AiGatewayControlPostgresTest` |
| Return tools or malformed completion structures | Strict provider response validation | `SoclaasClientTest` |
| Consume memory or exceed output budgets | Input/body/text/token bounds | `SoclaasClientTest`, `AiGatewayContractTest` |
| Reach the provider with a stale or disabled actor | Current database actor check before admission | `AiGatewayControlPostgresTest`, `ReporterAiHttpPostgresTest` |

## Reflection

Separating transport, admission and authorized feature selection made the work
reviewable. The useful security boundary was enforced by application code and
transactions, rather than relying only on a model obeying the system prompt.
Missing network access, skipped live tests and local-only documentation required
explicit handoff notes so another agent would not assume they were complete.
