# MP3 documentation and delivery

Contributor account: `haowern98`. Local work date: 23 October 2026
(Asia/Kuala_Lumpur).

## Define the documentation scope before editing

**Request summary.** Update the User Guide and Developer Guide for the MP3 web
application, create the tracking issues and branch first, and wait for permission
before changing the documents.

**Agent response and output.** Read-only inspection found that the guides still
contained MP2 desktop instructions. Issues #21 and #22 were created, assigned to
my account, and grouped on `codex/issue-21-mp3-documentation`. Switching the main
checkout was blocked by overlapping local Developer Guide edits. Those seven
local Markdown changes were preserved in a named stash before switching.

**Human gate.** I explicitly replied proceed before the agent began the guide
updates and screenshot work. This was an actual editing authorization, not an
agent-generated claim of independent security or release approval.

## Divide evidence gathering and documentation work

**Request summary.** Capture the actual web interface using fictional data and
update both guides to match implemented behavior, rather than copying planned
features or desktop assumptions.

**Agent response and handoffs.** The main agent handled local browser workflows,
source review and integration. Narrow documentation subagents owned the UG and
DG separately. Review follow-ups checked promotion category behavior, internal
audit visibility, endpoint contracts and test names. The agents were not given
independent authority to change application security or production configuration.

**Course connection.** This follows specialized-agent and orchestrator-subagent
ideas from Lecture 7 page 37 and Lecture 8 pages 5 and 27. Distinct file ownership
reduced overlap; findings returned to the main agent for integration. The requests
included scope, evidence expectations, exclusions and the prohibition on commits.

## Verify the product described by the guides

**Request summary.** Check user instructions and developer claims against current
source and the running application, keeping local evidence separate from deployed
or live-provider verification.

**Agent response and verification.** The web `bootJar` build passed against the
reviewed application snapshot `aea4f4d`. A fresh local PostgreSQL 18 database
validated ten migrations. Browser checks exercised Reporter registration,
submission and promotion request; Administrator promotion approval, comment
creation and SLO saving; and Responder claim and resolution. AI was disabled.
These checks did not establish a successful live AI call, public deployment,
all account-administration actions, or a new full-suite test run.

The DG included an explicit Git-based MP2 reuse measurement: 271 unchanged files
among 373 selected production source files at that snapshot. It stated that
retained desktop source was included and that this was not the proportion of the
deployed web execution path. The UG distinguished available controls from planned
features. Later commits on main can make a snapshot-based guide stale.

## Correct the screenshot presentation

**Request summary.** Explain the awkward screenshot without editing application
code, then split the long screenshots into readable sections.

**Human correction.** I rejected the full-page Reporter image because it shrank in
the Markdown preview, contained excessive empty form space, and showed a sidebar
ending above the page bottom. The first capture also combined a post-submission
empty form and success banner with the incident list.

**Agent response and correction.** The documentation images were split into 19
focused crops, including separate Reporter form/list, account directory/promotion,
SLO target/editor/history, statistics filters/results, and incident/resolution
views. Existing captured pixels were used; the crops did not manufacture new
product states. The Reporter form crop still showed the reset empty fields, not
a fabricated filled-in form. The original full-page images were preserved outside
the repository. Image references were updated beside the relevant instructions.

**Verification.** Image existence and PNG integrity, relative links, heading
anchors, code fences, source test names and whitespace were checked. Visual review
checked representative crops. This was documentation verification; no application
code was changed to make the screenshots look different.

## Publish only after the requested checks

**Request summary.** Commit and push the User Guide first, then update and publish
the Developer Guide. Open one PR and merge it only after every check passes.

**Human decisions and agent output.** I removed the earlier per-commit file limit
for this documentation publication. The User Guide and its 19 images were committed
as `41da3f0`. The Developer Guide remained uncommitted until I separately requested
its publication; its commit was `1c80b3d`.

[PR #24](https://github.com/CS3227-2610-MP3-IncidentAssistant/CS3227-2610-MP3/pull/24)
linked both documentation issues. Hosted CI reported success for Build and test,
Windows x64 packaging, macOS x64 packaging and macOS ARM64 packaging at exact head
`1c80b3d007a4e25b530b4e485f36b90568d5c9b7`. The overall workflow summary lagged
behind its completed job records; the agent checked the individual jobs and PR
mergeability before merging. GitHub records merge commit `fd2ac2a` and closure of
#21 and #22. The agent subsequently switched to main and fast-forwarded to the
then-current `24a1ae7` at my request.

## Reflection

The useful review loop included my objections, not just the generated guides.
Source inspection caught desktop assumptions; browser work checked actual role
flows; my screenshot correction improved readability; and exact-head hosted
checks controlled publication. These stages provided different evidence and were
not interchangeable. The current log update is limited to `logs/haowern98/` as I
requested, with no teammate logs or workflow index changes.

## Additional security-focused request summaries

### Keep screenshots separate from private operating data

**Request summary.** Capture the web application for the guides and update the
instructions to match the interface. Later, correct the screenshot presentation
without changing application code.

**Security decision and output.** The screenshots used the local demonstration
accounts and fictional incident data described above. They documented role
controls without publishing a production account, provider key, session cookie
or private environment file. Splitting captured images improved readability
without inventing successful AI output or changing the application's security
behavior. AI remained disabled during the documented browser checks.

### Describe implemented AI safeguards precisely

**Request summary.** Earlier exchanges asked what the gateway handles, what Spring
AI would contribute, how to test prompt injection, and what the slides require
when injection is detected. The guide update needed to explain the resulting
implementation accurately.

**Security decision and output.** The DG described backend authorization,
allowlisted data, shared usage admission, strict response validation, safe errors
and explicit human review. It separated the implemented Reporter endpoint from
planned role features. It did not turn the calculator example into proof of a
universal injection detector. The explanation connected the actual controls to
Lecture 6 pages 24-25 while retaining the distinction between structural response
validation and the accuracy or intent of generated text.

### Keep delegated documentation work inside its boundaries

**Request summary.** Update the UG and DG after the issues and branch exist;
preserve the earlier local edits and wait for my proceed authorization.

**Coordination decision and output.** UG and DG work had separate file owners.
Read-only review tasks returned specific source findings, including the absence
of a public audit viewer and the difference between planned and exposed AI
features. The main agent integrated these findings. These bounded handoffs used
agent specialization without giving a documentation task authority to modify
authorization code, production configuration or stored user data. The earlier
environment-file and Markdown-publication corrections are recorded in the shared
AI gateway summary; documentation authorization did not authorize publishing
secrets or every preserved local Markdown file.

### Apply the publication gate to the exact reviewed revision

**Request summary.** Publish the guides and merge the PR only after all checks
pass. For this later interaction-log update, change only my account's log folder.

**Security and delivery decision.** Publication checked hosted job results for
PR #24's exact head rather than borrowing a green result from another revision.
The completed jobs and mergeability were checked when the workflow summary
lagged. This provided a concrete delivery gate, alongside the narrower browser
verification recorded above. The current summary additions stay within
`logs/haowern98/`; they do not edit teammate logs, application controls or the
workflow index. No new live-provider test or deployment is implied by editing
these summaries.
