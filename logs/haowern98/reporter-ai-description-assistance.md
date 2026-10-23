# Reporter AI description assistance

Contributor account: `haowern98`. Work period: 19 October 2026.

## Implement the approved advisory feature

**Request summary.** Extend the shared gateway with Reporter description
assistance so the team can try the complete feature before merging. Follow the
agreed role and data boundaries and keep submission under the user's control.

**Agent response and output.** The Reporter endpoint accepted the current draft's
title, category and description. The gateway rechecked the authenticated actor
before provider access. The React interface offered Improve description,
Cancel, Apply suggestion and Discard. Applying changed only the local form;
incident submission remained a separate authorized operation. Editing or leaving
the form invalidated stale AI results.

**Specification connection.** This turns the advisory-only requirement into
observable outcomes: no generated text is persisted without a separate submission,
no provider tools mutate incidents, and failure leaves the user's draft intact.
Representative changes include `b014682`, `10b1ac0` and `ec74eda`.

## Diagnose the invalid-suggestion failure

**Request summary.** Investigate why description assistance returns an invalid
suggestion and why clicking Improve description sometimes appears to do nothing.
Use the actual UI state and provider contract to distinguish the causes.

**Agent response and correction.** A provider response containing `tool_calls: []`
was being rejected as an action-bearing response. An exactly empty array carries
no tool action, so the parser was corrected to accept that shape while retaining
rejection of nonempty or malformed tool-call data. Focused regression coverage
preserved rejection cases rather than broadly relaxing validation. The relevant
fix was `cb6fea7`.

A separate screenshot showed a blank title. The Improve action required a
nonblank title, category and description, so that state did not dispatch an AI
request. This was distinct from the provider response problem. The control's
visual feedback could still be confusing; the screenshot alone did not establish
that a provider request had occurred.

**Course connection.** This resembles a generator-verifier loop: inspect the
produced result, identify the exact rejected structure, and verify a narrow
correction with positive and negative cases. Provider output remained untrusted;
accepting an empty tool list did not authorize actual tool execution.

## Exercise prompt-injection and off-topic behavior

**Request summary.** Explain how to test the AI security boundaries, including
prompt injection and an unrelated coding request embedded in a plausible incident.
Compare the result with the intended description-rewriting scope.

**Observed interaction.** I tried a fictional broken-calculator incident that
asked for a replacement Python script as part of the report. The displayed
suggestion reworded the incident and retained the request for a script, but did
not produce the requested implementation. That observation supports scope
adherence for that example. It does not prove general resistance to injection,
semantic filtering of all instructions, or a dedicated injection detector.

**Review decision.** We discussed the difference between model behavior and
application security. Structured prompts help, but the stronger boundaries are
minimal selected data, escaped output, absent mutation tools, and explicit user
review. The slides' adversarial-testing guidance was relevant to selecting tests;
not every mitigation listed in the slides was implemented or verified.

## Preserve session and integration behavior

**Request summary.** Pull current teammate changes, retest the integrated branch,
commit focused implementation groups, and publish a PR identifying all resolved
issues without including the deliberately uncommitted Markdown files.

**Agent response and verification.** The integrated frontend used the shared
cookie/header CSRF flow rather than the earlier session-JSON token shape.
Cancellation checks and account-keyed form state prevented stale updates across
requests or users. HTTP, frontend and migration-upgrade tests accompanied the
integration. Representative final commits include `eccd8a2`, `935de53`, and
`268b8ef`. Historical aggregate test results are recorded in the gateway summary;
no test suite was rerun solely to write this log.

[PR #11](https://github.com/CS3227-2610-MP3-IncidentAssistant/CS3227-2610-MP3/pull/11)
combined the adapter, shared controls, gateway and Reporter feature. The agent
published the PR at my request; GitHub records its merge on 19 October. This log
does not assign teammates' later Responder or Administrator AI work to me.

## Security testing and review exchanges

### Establish the review boundary before wiring the UI

**Request summary.** Continue from the shared gateway through the Reporter feature
so the group can try the integrated workflow before merging it.

**Work and security decision.** We implemented an advisory operation, not a second
submission path. Improve description returned a suggestion, Apply suggestion
changed only browser form state, and Submit incident called the normal incident
operation. The model could propose wording but could not choose the reporter,
create an incident, assign a responder or write an audit event on its own.

### Test authorization as well as the visible button

**Request summary.** Explain how to test the security aspects of the AI feature.

**Work and security decision.** We checked the HTTP boundary, not just whether the
button appeared for a Reporter. `ReporterAiHttpPostgresTest` exercised the Reporter
session requirement, CSRF protection and a disabled account's rejection. It also
checked that a successful request did not change incidents or send identity to
the provider. Invalid, extra-field and oversized bodies were rejected before
provider dispatch. These checks separated access control from the model's ability
to generate a plausible answer.

### Keep prompt injection inside the data boundary

**Request summary.** Explain what prompt-injection inputs can be tried against
description assistance and what the slides require when such input is encountered.

**Work and security decision.** We considered attempts to override the task,
expose instructions or turn incident text into an operational command. The
backend's trusted instructions and three-field draft JSON remained separate.
The contract tests placed instruction-like text in description data and asserted
that it did not enter the system prompt. Provider output had no tool execution,
database write or callback path. The feature's protection did not depend on a
model correctly identifying and announcing every injection attempt.

### Try a plausible mixed-purpose incident

**Request summary.** Make the off-topic coding test less obvious by placing it
inside an otherwise believable incident report.

**Work and observation.** The test used a calculator utility that stopped working
after an update and requested a replacement script within the report. The AI
returned rewritten incident prose and retained the requested follow-up work as
prose. It did not return the Python implementation. We reviewed the distinction
between reporting that IT should provide code and the description assistant
actually performing the coding task. This gave a concrete example of task-scope
review rather than checking only a conspicuous override phrase.

### Validate the output envelope without granting tool authority

**Request summary.** Investigate the invalid-suggestion error from the actual
Reporter UI and proceed with the correction.

**Work and security decision.** We inspected the provider completion shape and
isolated the empty `tool_calls` array handling. The fix accepted absent, null or
exactly empty arrays while rejecting nonempty and incorrectly typed tool-call
fields. Function-call, refusal, blank-content and invalid completion cases retained
their rejection behavior. Empty tool metadata did not imply permission to execute
an action or accept arbitrary provider structures.

### Keep browser rendering separate from generated content

**Request summary.** Test the feature's security and compare the results with the
application's advisory-only requirements.

**Work and security decision.** The frontend rendered generated strings through
normal React text escaping. `ReporterAiDescription.test.tsx` exercised malicious
HTML, Markdown and URL-like output as literal text. Discard left the draft
unchanged. The application did not fetch a generated URL, turn Markdown into
trusted markup, run a script or interpret an imperative sentence as an API call.
This addressed the provider-to-browser boundary as well as the prompt boundary.

### Stop stale results from crossing request or account boundaries

**Request summary.** Integrate current teammate commits and retest the Reporter
feature before publication.

**Work and security decision.** We preserved the shared SPA CSRF flow and bound
Reporter form state to the signed-in identity. The frontend tests covered an older
cancelled request completing after a newer one, cancellation during CSRF refresh,
and a signed-in identity change. The intended result was precise: a stale response
must not replace a newer draft or appear in a different user's form. Cancelling
browser work did not imply that provider processing or billing had stopped.

### Avoid leaking sensitive error data during diagnosis

**Request summary.** Diagnose why assistance fails while preserving the draft and
continue the normal Reporter workflow.

**Work and security decision.** Safe error codes were mapped to local messages;
raw provider bodies and network exception contents were not displayed to users.
Frontend regression coverage checked a generic network failure message. Backend
response validation used fixed rejection reasons, and usage/audit outcomes did
not copy prompts or generated suggestions. This kept debugging evidence useful
without turning error handling into a second disclosure channel.

### Preserve the normal reporting path during an AI outage

**Request summary.** Complete and test the integrated Reporter feature so it can
be tried without making AI a requirement for incident submission.

**Work and security decision.** AI-disabled and unavailable states returned a
recoverable response while leaving the normal HTTP application available.
`ReporterAiHttpPostgresTest` checked the disabled AI route and ordinary HTTP
operation, rejected excessive drafts and exhausted budgets before dispatch, and
kept conservative charges for rejected tool output. The UI kept the draft so the
user could edit and submit manually instead of repeatedly retrying generation.

## Security review checklist used by the implementation

| Review question | Implementation response |
| --- | --- |
| Who is asking? | Current authenticated Reporter, rechecked before shared admission |
| What data leaves? | Current title, category and description, not account/session entities |
| Can input change system authority? | Draft fields remain serialized untrusted data |
| Can output act? | No tool execution or domain mutation path |
| Can output become browser code? | Text escaping and literal-output component tests |
| Can a late result replace another user's work? | Cancellation, generation checks and identity-bound form state |
| Can repeated requests exhaust shared resources? | Gateway admission, reservations, deadlines and circuits |
| What happens on failure? | Safe message, preserved draft and separate manual submission |
| Who accepts the wording? | The user reviews and explicitly applies it |

## Agent handoffs and reflection

The work used bounded implementation, fixture/test and review tasks, with the
main agent integrating their results. Useful handoff context included authorized
fields, exact provider rejection shapes, the shared CSRF contract, synthetic test
data, and checks that were unavailable or skipped. Lecture 7 page 37 and Lecture 8
pages 5, 27 and 29 discuss specialized agents, orchestrator-subagent coordination,
distinct ownership and preserving context without leaking secrets.

The parser defect showed why a polished UI and a successful small provider probe
are insufficient. The actual provider response, backend validation, and UI
preconditions needed separate checks. Human review remained necessary for the
suggestion's facts even after the technical request succeeded.
