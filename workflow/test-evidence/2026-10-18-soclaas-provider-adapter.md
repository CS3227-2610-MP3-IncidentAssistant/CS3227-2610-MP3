# SoCLaaS provider adapter evidence

Date: 2026-10-18, Asia/Kuala_Lumpur.
Scope: issue #2; local working tree based on `541825c`, branch
`codex/issue-2-soclaas-provider-adapter`. No release commit or deployment.

## Verification record

- Test-first compilation failed on the missing adapter/settings types.
- The initial oversized-token fixture was corrected to give consistent total
  usage; inconsistent usage must remain unknown, rather than imply zero cost.
- A regression test failed when an already validated settings object reread a
  changed environment. Settings now snapshot their validated limits.
- First focused green run: 20 tests passed using the local fake HTTP provider.
  Command: `gradlew.bat -g E:\MP3\.gradle-cache --no-daemon test --tests
  'com.company.incidentdesk.infrastructure.soclaas.*' -PfrontendSkipBuild=true
  -x shadowJar -x shadowJarMacArm64`. Packaging was excluded only for this
  focused run; complete packaging/build checks are recorded separately below.
- Full frontend checks passed: TypeScript, ESLint, 12 component tests, and the
  production React build. Local Node 24.7 emitted engine-version warnings;
  these checks nevertheless passed. CI pins Node 24.16.0.
- The first `gradlew.bat -g E:\MP3\.gradle-cache --no-daemon check bootJar`
  packaged the desktop artifacts and built the web JAR, but its Java run failed
  an existing registration secret-logging assertion: 521 tests, 1 failure,
  4 skipped. An inherited shell `DEBUG` flag enabled Spring debug body logging.
  The identical failure reproduced on an untouched archive of base `541825c`
  using the health and registration-security suites (6 tests, 1 failure).
  No existing application code or acceptance test was weakened.
- With `DEBUG` removed only from the verification child environment, Java
  verification passed: **521 tests, 517 passed, 0 failed/errors, 4 skipped**.
  This includes **21 deterministic adapter tests** and existing web security,
  health, persistence, domain, UI, and packaged-startup checks. Command:
  `gradlew.bat -g E:\MP3\.gradle-cache --no-daemon check bootJar --rerun-tasks
  -x frontendInstall -x frontendBuild -x frontendCheck -x shadowJar
  -x shadowJarMacArm64`. The excluded frontend and packaging tasks had already
  passed earlier in this same verification; current artifacts were retained.
  JavaFX platform verification and Shadow JAR inspection passed too.
- Three skipped tests require an explicitly isolated PostgreSQL environment;
  the fourth was the opt-in live provider test. No database schema changed here.
- The subsequent opt-in live adapter smoke passed: **1 test, 0 failures**,
  verifying the configured model and exactly one synthetic completion. It used
  the compiled Java adapter, not the earlier standalone HTTP probe. The output
  cap was 1,200 tokens; credentials existed only in the child environment.
  Reports were separated under `build/test-results/soclaas-live/` so the full
  suite report remains available. Provider usage was 36 input + 2 output =
  **38 tokens**. No real incident data was sent.
- `git diff --check` passed. A scan of 522 tracked/unignored workspace text files
  found no occurrence of the actual local provider key. This is not a repository
  history, container-layer, or production observability audit.

## Review and limits

A read-only subagent reviewed the transport and tests. Its material finding was
that shared circuits require sanitized status information, and large error
bodies must not hide 401/429 status. The implementation now discards error
bodies at the headers and preserves only numeric status and a validated numeric
retry delay. Tests verify these outcomes. Retry jitter was also bounded by the
remaining catalog deadline.

This is agent review, not independent human security or release approval.
The attempted baseline test overlapped the addition of new test sources and
failed compilation on those not-yet-implemented types; it is not recorded as a
clean pre-change baseline. The later untouched-base reproduction above is the
evidence for the inherited debug-logging failure only.

No database schema, domain mutation, web endpoint, role selection, authorization
policy, or production dependency changed. Shared budgets/rates/concurrency,
circuits, audit orchestration, and role integrations remain in later issues.
The full AI-GW, AI-LIM, and AI-FEAT criteria remain open. Provider metadata is
not yet a validated accounting price contract.

CI and normal test runs use fake providers only. The separate
`SoclaasLiveSmokeTest` requires `SOCLAAS_LIVE_SMOKE=true`, backend settings,
and explicit operator intent. It sends only synthetic data and never prints
credentials or generated text. No private environment file belongs in Git.

## Reverification on 2026-10-19

- Fetched remote references, explicitly refreshing `origin/main` to `b878ece`.
  The current branch remains based on `541825c`, two commits behind main.
  Neither incoming commit was merged or rebased into the working tree.
- Read-only comparison found incoming login-CSRF and sidebar/layout changes,
  with no provider-adapter, backend API, dependency, or AI-contract overlap.
- Ran `gradlew.bat -g E:\MP3\.gradle-cache --no-daemon check bootJar
  --rerun-tasks`, removing the inherited `DEBUG` flag only in that child process.
  All 14 Gradle tasks executed and the build succeeded.
- Java: 521 total, **517 passed, 4 skipped, 0 failures/errors**. Skips remain the
  three isolated PostgreSQL registration tests and the opt-in live smoke test.
- Frontend: TypeScript and ESLint passed; **12 component tests passed**; the
  production React assets and web JAR built. Desktop packaging, platform checks,
  and Shadow JAR inspection passed. Existing Node engine and metadata warnings
  remain; no dependency or build configuration was changed.
- `git diff --check` passed. No live SoCLaaS request was made in this rerun.
- This verifies the current branch plus local adapter changes, not integration
  of the two newer main commits. No commit, merge, rebase, or push performed.

## Local commit checkpoint on 2026-10-19

After the successful reverification, the user authorized focused local commits
of up to four files each. The same implementation/test contents are checkpointed
at `48f5de2`, following `70459cf` (configuration/errors) and `1f2f848`
(transport/wiring/example configuration). Source/test modification times were
checked against compiled outputs before committing; no source/test content
changed after the successful rerun. Documentation/evidence use separate commits.
The changed-file scan found no occurrence of the actual local provider key.
No push, merge, or rebase was performed. Independent human release approval
remains outstanding.
