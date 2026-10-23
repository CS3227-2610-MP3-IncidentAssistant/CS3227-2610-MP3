---
layout: default
title: Reflections
nav_order: 4
---

# Reflections

Throughout the development of Incident Desk, our team used AI coding agents to support planning, implementation, testing, documentation, and code review. We found that these tools were most useful when we gave them a narrow scope, clear product constraints, and an outcome that we could verify. They accelerated repetitive work and helped us explore solutions, but human judgement remained essential at the boundaries between components, in the running user experience, and when deciding whether a technically correct solution was maintainable.

## AI Security

### Treat every model-facing boundary as an attack surface

The AI features introduced several distinct attack surfaces. The first is
**direct prompt injection**: Reporter draft text, Responder-visible incident
text, or an Administrator question can contain instructions such as "ignore
the system prompt", request another user's data, or ask the model to perform an
unsupported action. The second is **indirect prompt injection**, where an
instruction is hidden in stored content that a later workflow might retrieve,
such as a comment, attachment name, attachment contents, or a previous model
response. The third is **output-side injection**: even if the request is safe,
the provider can return HTML, Markdown links, a `tool_calls` object, SQL, a URL,
fabricated identifiers, or an oversized response intended to exploit the
browser or the server-side parser. We also considered **cross-user and
cross-feature leakage**, where an overly broad entity serialization could send
anonymous identity, unrelated incidents, audit data, or internal identifiers;
**control-plane injection**, where a user tries to select a host, model, tool,
handler, file path, or redirect; and **availability attacks**, where repeated,
slow, or deliberately large requests consume the shared provider budget.

This classification mattered because each surface needed a different control.
A sentence in the system prompt can help distinguish instructions from data,
but it cannot prevent a compromised or confused model from producing hostile
output. The stronger controls sit outside the model. Each feature reauthorizes
the current actor immediately before selecting canonical data and constructs a
small feature-specific request record rather than serializing a domain entity.
Reporter assistance sends only the current title, category, and description.
Responder assistance sends one currently authorized incident's nine approved
operational fields and excludes identity, comments, attachments, and links.
The Administrator planner receives only the validated question, an
application-generated instant, and a closed query vocabulary; responder
selections and all statistics stay in trusted Java code. Previous AI output is
never chained into another request, so a successful injection cannot persist
itself into a later model call.

The provider also receives no application tool, database connection, callback,
repository, or mutation capability. In the Administrator insight feature, the
model does not answer the question directly. It can only propose a JSON plan
matching a closed schema. Java code validates the query type, metric, grouping,
period, direction, and row limit, maps the plan to one typed read-only handler,
calculates the result locally, and renders the factual answer
deterministically. Model output is never interpreted as SQL, a class name, a
URL, a template, a file path, or an authorization decision. This is a useful
security principle we learned from the work: the system should remain safe even
when the model follows the attacker's instruction perfectly.

### Security features and how we tested them

We implemented defence in depth across authorization, data minimization,
transport, parsing, rendering, observability, and failure handling. Actor and
role are derived from the server-side security context, resource access is
rechecked at execution time, and only allowlisted fields can cross the provider
boundary. Trusted instructions and untrusted data use separate structured
messages. Requests and responses are size-bounded; responses must have the
expected shape; generated text is labelled and escaped as plain text; prompts
and responses are excluded from logs, metrics, and audit records; and every AI
failure leaves the ordinary incident workflow available. These controls avoid
depending on any single defence, especially on prompt wording or a browser-only
check.

The test strategy deliberately treats the provider as hostile. Capture tests
assert the exact outbound field set and verify the absence of identities,
comments, attachments, tools, and unrelated records. Authorization tests cover
wrong roles, inaccessible incidents, revoked category access, forged actor
data, and access that changes while a provider request is in flight. Parser and
dispatch tests attempt SQL in enum fields, reflection-style class names,
traversal paths, URLs, OpenAI-style tool calls, duplicate JSON fields, trailing
prose, excessive limits, truncated JSON, arrays, `null`, and unsupported field
combinations. Browser tests exercise script tags, event-handler markup,
Markdown links, and late responses after navigation or actor changes. Database
tests confirm that rejected or failed requests create no incident mutation or
success audit, while load tests check one provider call per user action and
continued availability of non-AI endpoints. This combination tests both the
security boundary and the absence of side effects, rather than merely checking
that an error message appears.

### Resource limits and safe failure are security controls

SoCLaaS is a shared and rate-limited resource, so availability and cost abuse
are part of the threat model. The shared gateway admits at most one simultaneous
request per authenticated account, 60 application requests per minute across
instances, and eight simultaneous provider requests. A feature-data record is
limited to 16 KiB, estimated input to 8,000 tokens, requested output to 1,200
tokens, and accepted decoded output to both 16 KiB and 1,200 tokens. Connection
setup is bounded to three seconds and the total provider deadline, including
the single permitted retry, is 30 seconds. Limits can be configured downward;
raising them requires a reviewed specification and production-configuration
decision.

The limits are shared through atomic PostgreSQL admission and budget records so
horizontal scaling cannot multiply the allowance. Daily, monthly, lifetime,
per-account, per-feature, and concurrency budgets are checked before a provider
connection is opened. At most one retry is allowed for an idempotent transient
failure, while validation failures, authentication failures, oversized
requests, and rate limits are not retried. Shared circuit breakers fail closed
when credentials, quotas, or the provider are unhealthy, but they disable only
AI features. Conservative accounting remains charged when usage is ambiguous,
which avoids turning a network failure into a way to bypass cost controls. Load,
deterministic-clock, and multi-instance database tests exercise reset
boundaries, races, slow requests, provider failures, and circuit behavior. This
made us view quotas as mutable external ceilings rather than capacity the
application can assume it owns.

### Human oversight must be outside the model's control

Every AI result is advisory. Applying a Reporter suggestion changes only the
browser draft; submitting the incident is a separate validated,
CSRF-protected, server-authorized action. Responder advice cannot prefill
resolution remarks or claim, comment on, hand off, or resolve an incident.
Administrator insight results cannot change incidents, accounts, access, SLOs,
or audit data. Generated results are visibly labelled, shown in a review area,
and separated from mutation controls. The user must deliberately continue
through the ordinary workflow, where all existing validation, authorization,
transaction, and audit rules run again.

We also used approval gates around development itself. Product approval fixes
the scope and acceptance criteria; security/architecture approval checks the
provider data and authority boundary; automated evidence verifies the
implementation; independent review checks the diff, tests, secrets, and unsafe
shortcuts; and a named human must approve release. An agent-generated statement
cannot satisfy any of these gates, and one agent's message is treated as
handoff data rather than human authority. Approval records are stored
separately from implementation and test evidence, tied to a specific scope,
and must be revisited when that scope or its recorded specification hash
changes. The runtime model cannot compromise these gates by printing
"approved" because it has no tools, release credentials, deployment action, or
domain mutations. A coding agent may propose or edit a review document, but
that diff is not human approval: the named person must make and record the
decision, and version-control history leaves the change reviewable. A user can
still accept poor advice or approve an incorrect design, so human over-trust
remains a residual social risk; separation of duties and independent evidence
reduce that risk but cannot eliminate it.

## Specification-Driven Development

### What a buildable specification must make explicit

A useful specification names the authorized actor, trusted source of identity,
preconditions, accepted inputs, size limits, persisted effects, audit effects,
normal and denied outcomes, stale and concurrent behavior, failure recovery,
and required evidence. It should also state explicit exclusions and
non-goals—what the feature must not do—because an agent can otherwise fill a
gap with a plausible but unauthorized behavior. For an AI feature the
specification must additionally name every field allowed to reach the provider,
excluded data, the provider's permitted role, output schema and validation,
resource limits, failure behavior, and the point where a human reviews the
result. Security, privacy, concurrency, transaction, and audit boundaries must
be observable requirements rather than assumptions hidden in an architecture
diagram.

We found a specification precise enough for an agent when two independent
implementers could derive the same externally visible HTTP, persistence,
authorization, and audit behavior without inventing a security or product
choice. Each criterion should have an unambiguous actor, trigger, initial
state, outcome, and evidence method. Terms such as "secure", "fast", or
"appropriate access" are not buildable until they are converted into concrete
rules such as a field allowlist, a deadline, or a denied-path response. A useful
pre-implementation review therefore asks: can we derive a positive test, a
denied test, and any necessary failure or concurrency test from every
criterion? If not, the ambiguity belongs in the specification review, not in
an implementer's private assumption.

The Reporter-to-Responder promotion journey was a useful example. Its contract
distinguished requested categories from the Administrator-approved subset,
made the role change and audit record transactional, required old sessions to
fail closed, and required a fresh login through the Responder portal. Those
details prevented an agent from treating a role dropdown or a UI-only change
as sufficient. The resulting tests checked the stored account, request
history, category set, audit state, rollback, concurrent decision, and portal
behavior rather than merely checking for a success message.

### Requirement changes must flow through the whole evidence chain

When a requirement changes, the owning canonical artifact changes first and
the decision is reviewed at the same level as the original requirement. The
acceptance criterion and traceability index are then updated, followed by the
architecture or ADR if the boundary changed, the smallest coherent
implementation change, and tests for both the new permitted behavior and the
behavior that must remain denied. User documentation, threat models, and dated
evidence are updated last, after the relevant checks actually run. Old evidence
is retained as historical evidence rather than silently rewritten, and
criteria are ticked only when the new behavior has been proved. This order
prevents code from becoming an accidental specification and prevents a passing
test written for the old rule from being presented as proof of the new one.

The Administrator insight sample-size rule showed why the specification cannot
be repaired only in code. The initial design suppressed small responder
samples. After product review removed that threshold, the team first amended
the owning specification and review record, then changed deterministic
analytics, rendering, UI disclosure, tests, and evidence together. The revised
tests prove that a one-cycle responder remains ranked, a selected responder
with zero qualifying cycles remains visible with an explicit unavailable
metric, and every result shows its sample size. Keeping the old text in the
threat model or User Guide would have recreated ambiguity even if the code was
correct.

Tests also need to prove intent rather than repeat wording. We use several
techniques to avoid a tautology in which the test merely restates the same
mistake as the implementation: check externally visible state and audit
records, test both allowed and denied actors, exercise boundary values and
state transitions, inject persistence/provider failures, test stale and
concurrent actions, and verify the exact data crossing a trust boundary. A
reviewer should be able to explain which risk each test detects and see it fail
when the relevant safeguard is deliberately removed. End-to-end tests then
show that individually correct components are actually connected through the
real route.

During the release sweep, two promotion browser tests reported success while
silently skipping the actual decision path because a Playwright count was not
awaited. A timing probe showed the request appeared after the guard had already
decided to skip. The harness was corrected and the suite then exercised both
decisions with zero skips. That episode changed our standard of evidence: a
green suite is useful only when we inspect its assertions, skips, fixtures, and
side effects closely enough to know that the intended path really ran.

## Basic Multi-Agent Software Engineering

### Specialized agents and bounded handoffs

We separated five responsibilities because they require different questions.
An analyst turns an approved goal into observable criteria and exposes missing
product decisions. An architecture/security reviewer maps data flows, trust
boundaries, transactions, and failure modes. A developer implements one
bounded change while preserving repository conventions. A test agent attacks
allowed, denied, boundary, stale, concurrent, and failure paths. A reviewer
compares the diff and evidence with the approved contract and looks for unsafe
shortcuts or maintainability problems. Specialized agents are most useful when
the task contains enough independent reasoning to justify a separate
perspective; splitting a tiny mechanical edit across several agents would add
handoff risk without useful independence.

We learned that a specialized agent should be built and tested like a small
software component. Its role needs a narrow objective, authoritative inputs,
explicit non-goals, allowed tools and files, expected output format, stop
conditions, and a definition of done. It should be evaluated with representative
tasks and adversarial fixtures: ambiguous requirements for the analyst,
cross-role data flows for the architect, a deliberately unsafe shortcut for the
reviewer, and known failing or flaky cases for the tester. The evaluation must
measure whether the agent cites source evidence, preserves exclusions, reports
uncertainty and skipped checks, and refuses actions outside its authority—not
only whether it produces fluent text or a patch that compiles.

Specialization helped most when each output was concrete. The specification
agent produced criterion identifiers and unresolved decisions; the developer
named the exact files, assumptions, and commands; and the tester returned
reproducible commands, environment details, counts, and failures instead of a
general assurance. Independence also mattered: if the same generated summary
is the only input to implementation and review, three nominal agents can still
share one blind spot.

For Responder AI, the handoff carried the approved incident fields, excluded
identity/comments/attachments, current-access rule, stale-result discard rule,
provider limits, and the required fake-provider evidence. For Administrator
insights, the architecture handoff constrained the model to a closed plan and
the test handoff attempted to escape that plan through malformed JSON, SQL,
tool calls, URLs, and fabricated labels. For deployment, the handoff separated
the GitHub OIDC deployer identity, Cloud Run runtime identity, Neon credentials,
and the human-only release gate. These boundaries made it possible for one
agent to challenge another's output without silently broadening the task.

### Where errors or malicious instructions can enter

An error can enter before code is written if an analyst treats issue text or a
model suggestion as an approved requirement. It can enter at architecture if a
reviewer overlooks a cross-role data flow, at implementation if an agent trusts
browser-supplied identity, at testing if generated assertions merely encode the
same mistake, and at handoff if skipped checks or unresolved assumptions are
omitted. A malicious instruction can use the same route: issue text can ask the
analyst to ignore a canonical policy; a diagram or web page can tell the
architect to expose data; a source comment or test fixture can tell the
developer to leak a secret; command output can ask the tester to run an
unrelated script; and a forged handoff can claim that a human already approved
the change. Repository issues, incident content, attachments, web pages,
dependencies, tool output, generated code, and messages from another agent are
therefore treated as untrusted inputs rather than authority. Each receiving
agent reopens the cited specification and evidence instead of trusting the
sender's summary.

We reduced context loss by requiring every handoff to include the issue and
criterion identifiers; the approved scope and explicit exclusions; trusted
requirements and untrusted inputs; relevant files, interfaces, data shapes,
and transaction boundaries; security and privacy risks; assumptions and open
questions; checks already run with their environment and results; known
failures or skips; and the named owner of the next human gate. Exact commit or
diff identity is important because otherwise the next agent may test a
different state from the one that was reviewed.

Each role also leaves evidence suited to independent verification. The analyst
leaves traceability from requirement to acceptance criteria and records
unresolved choices. The architect leaves a data-flow/threat analysis and ADR
with rejected alternatives. The developer leaves a focused diff, tests,
assumptions, and exact commands. The tester leaves fixtures, environment,
passed/failed/skipped counts, adversarial cases, and raw sanitized output. The
reviewer leaves findings tied to source locations, severity, rationale, and a
clear approval or rejection. We kept these specifications, ADRs, dated review
records, interaction summaries, and command-level test evidence in the
repository. Agents were not allowed to infer human approval, deploy, merge,
rewrite history, or weaken a test to obtain a pass. A later reviewer could
challenge an earlier result, but had to leave the disagreement and supporting
evidence visible for a person to decide.

### How we would improve the workflow

The strongest multi-agent runs used narrow tasks and independent evidence; the
weakest allowed one agent's summary to become the next agent's unquestioned
premise. In future work we would assign named human owners at the start of each
feature, require a compact machine-checkable handoff alongside the prose, and
make the test agent demonstrate at least one intentionally failing or
adversarial case before accepting the happy path. We would also preserve the
original sanitized command or CI output whenever a summary records a failure,
so a reviewer can distinguish an observed result from a persuasive narrative.

## Cross-cutting case studies

## Working from product requirements rather than plausible output

One recurring lesson was that convincing output is not the same as a working product. For example, a web mockup helped us agree on a visual direction, but it did not prove that the JavaFX application could submit or track incidents. When the Reporter form was first connected, the actual authenticated route still constructed the page without an `IncidentService`, leaving users with a **Submission unavailable** message. Tracing the route through `DefaultViewFactory` revealed the missing dependency and ensured that submission used the existing validation, authorization, persistence, and audit path.

The same issue appeared in smaller forms. The anonymous-submission checkbox existed visually, but the form continued to pass `false` to `IncidentService.submit(...)`. Connecting the control to the service argument and checking the stored result showed why verification has to follow data beyond the visible interface. We also verified the privacy presentation from more than one role: the author could still find the incident under **My incidents**, while the Responder saw **Anonymous reporter**. This supported the intended privacy boundary without implying that the owner of the local data files could not inspect them.

These experiences taught us to state the required domain outcome in prompts and then trace the generated implementation through the real application path. For important workflows, we should confirm construction, authorization, persistence, audit effects, and the final screen rather than accepting a plausible UI or an isolated passing test.

## Combining automated checks with manual workflow testing

Agents were effective at generating focused tests for the behaviour they implemented, but those tests did not always expose interactions with other parts of the application. One Administrator page, for example, cached its contents and did not refresh after an SLO changed during the same session. The feature's tests passed in isolation; manual use exposed that the displayed data had become stale.

Manual testing also caught layout and navigation problems. The combined Reporter form and incident list extended below the application window without a page scrollbar, so part of the workflow was unreachable despite the underlying services working correctly. Similarly, placeholders in the first **My incidents** table conversion concealed the fact that the shared row mapping was not being used. Running the application, following the complete Reporter route, and inspecting the rendered result identified issues that service-level tests alone could not show.

Our approach therefore became layered: deterministic tests checked domain rules and denied paths, UI-flow tests checked that controls reached the correct services, and manual workflow testing covered navigation, refresh behaviour, scrolling, and visual clarity. None of these forms of verification was sufficient by itself.

## Keeping lifecycle and authorization rules out of the UI

Incident actions showed why prompts need to distinguish visible controls from real domain behaviour. The initial Reporter detail view used `IncidentDetailActions.none()`, while its comment box called `IncidentCommentService.add(...)`. On a resolved incident, that added text but did not reopen the incident. A Reporter-specific detail action was needed to call `IncidentService.reopen(...)`, which records the transition back to `SUBMITTED`, stores a `REOPEN_EXPLANATION` comment, and creates the audit event as one operation.

We applied the same reasoning to editing and withdrawal. Hiding a button was not enough: the service still had to reject attempts after assignment, including stale actions from an already-open screen. Tests therefore covered both permitted and denied operations and checked stored state, not merely control visibility. This reinforced a broader lesson: agents can wire an interface quickly, but we must ensure that role, ownership, assignment, category access, and lifecycle rules remain enforced in application or domain logic.

## Reviewing architecture and maintainability

An agent's first correct fix was not always the best long-term design. To address a stale Administrator page, the initial solution added a page-specific condition to the generic navigator so that the page would be recreated on every visit. Although this fixed the immediate bug, it coupled shared navigation code to one page and would not scale as other views gained refresh requirements. We instead introduced `NavigableView.onShown()`, allowing each page to refresh itself when displayed.

Code review also caught smaller maintainability problems, such as generated Java code using fully qualified class names like `java.util.List` inline instead of normal imports. The code compiled, but repeated fully qualified names reduced readability and did not match the project's conventions. We added this concern to our code-quality checks so future reviews would catch it consistently.

These examples changed how we assess generated code. Passing tests establish useful evidence, but we also review whether responsibilities live in the right layer, whether a solution introduces special cases into shared infrastructure, and whether the result remains readable and consistent with the existing codebase.

## Keeping documentation tied to evidence

AI helped draft and organize the User Guide and Developer Guide, but polished documentation could still be wrong. The first User Guide sequence introduced the Responder queue before explaining that an Administrator must grant category access, so a peer tester following it saw an empty queue. Reordering account and access setup before the workflow made the guide usable. Screenshots also had to be replaced when the Reporter page gained anonymous submission and **My incidents**.

Architecture diagrams required the same scrutiny. One persistence relationship initially pointed from repository interfaces toward `LocalApplicationStore`, even though the store implements those interfaces. Comparing the diagram with the source corrected the UML realization direction. The incident-submission sequence also needed updating when the form stopped hard-coding the anonymity value.

We learned to treat documentation claims as outputs that require evidence. For each guide change, we should check the implemented feature, the route a user actually follows, the current interface, and the source or automated check supporting the description.

## Using cross-platform evidence to control scope

The attachment work showed us that success on one development machine does not establish that a desktop feature is portable. Initial image and video support passed local tests, but CI exposed missing Linux codecs and a temporary-video file-lock problem on Windows. Instead of treating the local result as sufficient or adding increasingly fragile platform-specific workarounds, we used those failures to reassess the requirement. We narrowed attachment support to PNG and JPEG, removed the JavaFX media dependency, rejected disguised video files, and preserved existing video data without attempting to open it. This experience taught us to ask agents for verification on every supported platform and to treat unresolved environmental failures as product evidence. Reducing scope can be the more responsible engineering decision when it produces a feature whose security, packaging, and behaviour the team can genuinely support.

## Reflections on implementing MP3

Moving Incident Desk from a JavaFX desktop application to a React and Spring Boot web application forced us to revisit assumptions that had been safe in a single desktop process. A web request can come from an untrusted browser, and multiple users can act on the same incident at once. We therefore had to carry the useful incident rules forward while making authentication, ownership, category access, and state transitions enforceable on the server. The work made clear that a successful page interaction is only the beginning of verification: we also need to inspect the stored result, audit record, and response to a stale or unauthorized request.

Specification-driven work gave the migration a more reliable rhythm. Writing observable acceptance criteria before implementation helped us identify who could perform each action, what should happen on failure, and which effects must be committed together. It also made reviews more concrete: an agent's implementation or test result could be compared with an agreed contract instead of judged only by whether the new screen appeared to work. Human approval and recorded evidence remained important whenever a feature changed security, privacy, or release behaviour.

MP3 also showed us the value of testing at several boundaries. Automated checks covered domain and HTTP behaviour, while a packaged build and browser walkthrough exposed whether the React interface, Spring Boot routes, database migrations, and role workflows worked together. A health response or passing unit test could not establish that full journey. For AI-assisted features, we kept suggestions advisory and required a separate human action before changing incident data. This reinforced our broader lesson from MP2: useful agent output still needs explicit limits, end-to-end evidence, and independent review before we can trust it in a deployed application.

The Administrator insight feature pushed that lesson further and changed how we use a language model at all. Our first instinct was to let the model answer the operational question directly: give it the statistics and let it report which responder was slowest. Testing that idea made its weakness obvious. The same question asked twice could produce differently worded answers, a different rounding, or a confidently stated ranking we could not reproduce from the database. Numbers about people's work are exactly where that is least acceptable, and no amount of prompt tightening turns a probabilistic generator into a reliable calculator. Rather than accept flaky output or abandon the feature, we inverted the design and used the model's output as an input to deterministic code instead of as the result. The provider now receives only the question and must return a single JSON query plan drawn from a closed vocabulary of query types, metrics, periods, groupings, and limits. Java validates that plan against the schema, rejects anything unknown, and then one allowlisted analytics handler computes every name, count, duration, rate, ranking, date bound, and sample size from persisted timestamps. The model is reduced to a parser from natural language into an enum, which is a task where being occasionally wrong is survivable: a misread question produces a clarification prompt or a rejected plan, never a wrong number. This also made the feature far easier to test, because the analytics handlers are ordinary deterministic Java that we can assert against fixtures without calling a provider at all. We would reach for this shape again whenever a feature needs natural-language input but factual output, and we now treat "could the model state this fact directly?" as a design smell rather than a convenience.

## Overall reflection

AI agents made the team faster at drafting code, tests, interfaces, diagrams, and prose, especially when a task was repetitive or well bounded. The most expensive mistakes occurred at boundaries: a page created without its service, a comment that did not perform the required lifecycle transition, a checkbox whose value never reached storage, a cached view that did not react to another feature, and documentation that lagged behind the product.

The strongest results came from combining precise prompts with small changes, source inspection, automated tests, manual use of the JavaFX application, and careful review before merging. In future work, we would define the end-to-end evidence for a task before implementation and verify each boundary explicitly.
