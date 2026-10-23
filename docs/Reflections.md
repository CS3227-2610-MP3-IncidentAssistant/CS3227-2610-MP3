---
layout: default
title: Reflections
nav_order: 4
---

# Reflections

Throughout the development of Incident Desk, our team used AI coding agents to support planning, implementation, testing, documentation, and code review. We found that these tools were most useful when we gave them a narrow scope, clear product constraints, and an outcome that we could verify. They accelerated repetitive work and helped us explore solutions, but human judgement remained essential at the boundaries between components, in the running user experience, and when deciding whether a technically correct solution was maintainable.

## AI Security

### Treat every model-facing boundary as an attack surface

The AI features introduced more than one prompt-injection surface. Reporter
draft text and Responder-visible incident text can contain direct instructions
to the model. Stored comments, attachment names, previous model output, and
unrelated incidents are indirect-injection candidates even when the user did
not type them into the current form. The Administrator insight question is a
different risk: an attacker can ask for SQL, a tool call, a URL fetch, another
user's identity, or an unsupported personnel conclusion. Provider output is
also hostile input because it can contain markup, fabricated facts, an
oversized response, or a plan designed to reach an unintended handler.

We learned that prompt wording alone cannot secure those boundaries. Each
feature reauthorizes the current actor immediately before selecting canonical
data, builds an explicit allowlist rather than serializing an entity, and
excludes credentials, sessions, reporter identity, attachments, audit data,
and unrelated records. Reporter assistance sends only the current title,
category, and description. Responder assistance sends one currently authorized
incident's approved operational fields and no comments or attachments. The
Administrator question feature sends only the validated question, request
instant, and closed vocabulary; responder selections and all statistics remain
local. Previous AI output is never chained into another provider request.

The provider receives no application tool, database connection, callback, or
mutation capability. Responses are bounded, validated, and rendered as escaped
plain text. The Administrator provider response must match a closed JSON plan;
typed Java code validates it and invokes one allowlisted read-only analytics
handler. Tests use a hostile fake provider to attempt SQL, reflection-style
class names, URLs, traversal paths, tool calls, markup, duplicated fields,
trailing prose, and structurally invalid JSON. The important property is not
that the model always obeys the prompt, but that disobedience has no authority
and cannot widen the data or action boundary.

### Resource limits and safe failure are security controls

SoCLaaS is a shared and rate-limited resource, so availability and cost abuse
are part of the threat model. The gateway applies per-account and global
request limits, shared PostgreSQL budget reservations, input and output bounds,
timeouts, bounded retry, and circuit breakers. Conservative accounting remains
charged when provider usage is ambiguous. Load and concurrency tests check that
one user action causes at most one provider call for Administrator insights,
that slow AI work does not serialize unrelated requests, and that normal
incident endpoints remain available during provider failure. This made us view
quotas as mutable external ceilings rather than capacity the application can
assume it owns.

### Human oversight must be outside the model's control

Every AI result is advisory. Applying a Reporter suggestion changes only the
browser draft; submitting the incident is a separate validated and
CSRF-protected action. Responder advice cannot prefill resolution remarks or
claim, comment on, hand off, or resolve an incident. Administrator insight
results cannot change incidents, accounts, access, SLOs, or audit data. Labels,
separate controls, server-side authorization, and the absence of provider tools
make the review point structural rather than an instruction the model can ask
the application to ignore. A user can still accept poor advice, so the residual
risk is social and cannot be removed by technical validation alone.

## Specification-Driven Development

### What a buildable specification must make explicit

A useful specification names the authorized actor, trusted source of identity,
preconditions, accepted inputs, size limits, persisted effects, audit effects,
normal and denied outcomes, stale and concurrent behavior, failure recovery,
and required evidence. For an AI feature it must additionally name every field
allowed to reach the provider, excluded data, output validation, resource
limits, and the human review point. We found a specification precise enough for
an agent when two independent implementers could derive the same observable
HTTP and persistence behavior without inventing a security or product choice.

The Reporter-to-Responder promotion journey was a useful example. Its contract
distinguished requested categories from the Administrator-approved subset,
made the role change and audit record transactional, required old sessions to
fail closed, and required a fresh login through the Responder portal. Those
details prevented an agent from treating a role dropdown or a UI-only change
as sufficient. The resulting tests checked the stored account, request
history, category set, audit state, rollback, concurrent decision, and portal
behavior rather than merely checking for a success message.

### Requirement changes must flow through the whole evidence chain

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

Tests also need to prove intent rather than repeat wording. During the release
sweep, two promotion browser tests reported success while silently skipping the
actual decision path because a Playwright count was not awaited. A timing probe
showed the request appeared after the guard had already decided to skip. The
harness was corrected and the suite then exercised both decisions with zero
skips. That episode changed our standard of evidence: a green suite is useful
only when we inspect whether the intended path really ran.

## Basic Multi-Agent Software Engineering

### Specialized agents and bounded handoffs

We separated five responsibilities: an analyst turns an approved goal into
observable criteria; an architecture/security reviewer defines trust,
transaction, and privacy boundaries; a developer implements one bounded
change; a test agent attacks allowed, denied, stale, concurrent, and failure
paths; and a reviewer compares the diff and evidence with the approved
contract. Specialization helped most when the output of one role was concrete:
the specification agent produced criterion identifiers, the developer named
the exact files and assumptions, and the tester returned reproducible commands
and failures instead of a general assurance.

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
omitted. Repository issues, incident content, attachments, web pages,
dependency output, tool output, and messages from another agent are therefore
treated as untrusted inputs rather than authority.

We reduced context loss by requiring every handoff to include the issue and
criteria, approved scope and exclusions, files, security risks, checks already
run, failures, and the next human gate. We kept specifications, ADRs, dated
review records, interaction summaries, and exact-command test evidence in the
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
