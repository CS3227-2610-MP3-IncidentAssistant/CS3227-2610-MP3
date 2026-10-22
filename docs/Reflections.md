---
layout: default
title: Reflections
nav_order: 4
---

# Reflections

Throughout the development of Incident Desk, our team used AI coding agents to support planning, implementation, testing, documentation, and code review. We found that these tools were most useful when we gave them a narrow scope, clear product constraints, and an outcome that we could verify. They accelerated repetitive work and helped us explore solutions, but human judgement remained essential at the boundaries between components, in the running user experience, and when deciding whether a technically correct solution was maintainable.

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

## Overall reflection

AI agents made the team faster at drafting code, tests, interfaces, diagrams, and prose, especially when a task was repetitive or well bounded. The most expensive mistakes occurred at boundaries: a page created without its service, a comment that did not perform the required lifecycle transition, a checkbox whose value never reached storage, a cached view that did not react to another feature, and documentation that lagged behind the product.

The strongest results came from combining precise prompts with small changes, source inspection, automated tests, manual use of the JavaFX application, and careful review before merging. In future work, we would define the end-to-end evidence for a task before implementation and verify each boundary explicitly.
