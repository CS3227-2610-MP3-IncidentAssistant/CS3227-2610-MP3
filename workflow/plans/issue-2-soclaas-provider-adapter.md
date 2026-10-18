# SoCLaaS Provider Adapter Implementation Plan

Goal: implement issue #2 without a public AI endpoint or a new dependency.
Specification: `../ai-features.md`, AI-GW-01 through AI-GW-04 (adapter portion).
The user agreed the AI specification and authorized implementation in this
session. Independent security review and release approval remain outstanding.

## Boundaries

- Java 25 HttpClient, existing Jackson JSON support, Spring configuration.
- Fixed `https://soclaas-api.comp.nus.edu.sg/v1` origin; no redirects or tools.
- 3-second connect timeout, 30-second total deadline, 64-KiB response body,
  16-KiB input data/output text, 8,000 estimated input tokens, 1,200 output tokens.
- No automatic generation retry: a POST's acceptance cannot be proved after a
  transport failure. The specification permits at most one retry, not a minimum.
  Idempotent model lookup may retry once for 502/503/504 within its deadline.
- Disabled by default. Enabled provider configuration fails fast if invalid;
  unavailable catalog produces a safe unavailable result, not application failure.
- No feature authorization, quota ledger, shared circuit, or public controller
  in this issue; #3 and #4 own those controls. No production enablement yet.

## Tasks and interfaces

1. Configuration and startup wiring
   - Create `infrastructure/soclaas/SoclaasSettings.java` and
     `SoclaasConfiguration.java`.
   - `SoclaasSettings.from(Environment)` validates enabled settings and limits;
     diagnostics never include supplied secrets or prompt values.
   - Test disabled mode, absent credentials/model, invalid URL/limits, redaction,
     Spring bean wiring, and provider-unavailable startup.
2. Provider transport and contracts
   - Create `SoclaasClient.java`, `SoclaasException.java`, and a bounded body
     subscriber. `validateModel()` returns catalog records; `complete(system,
     dataJson)` returns text and optional trustworthy numeric token usage.
   - Fake loopback server tests exercise actual HTTP serialization, bearer auth,
     fixed paths, non-streaming options, redirects, safe errors, malformed JSON,
     tool calls, byte/token caps, stalled bodies, cancellation, retries, and
     credential/content redaction. Loopback URL injection is package-private
     for tests only; production settings cannot accept a custom origin.
3. Verification and evidence
   - Watch focused tests fail before implementation, then pass.
   - Run backend suite and `check bootJar`; distinguish unrelated baseline issues.
   - Run at most one synthetic completion plus catalog lookup using the compiled
     adapter, loading credentials only in a temporary process environment.
   - Update `.env.example`, developer guidance, specification progress, and
     sanitized test/interaction evidence. Leave broad acceptance criteria open.
   - Read-only subagent reviews final diff; root resolves material findings.

## Review focus

- Chunked/stalled bodies must be bounded before unbounded allocation.
- Exceptions, object stringification, and disabled paths must not reveal content.
- Timeout includes reading the complete response, not just receiving headers.
- Missing/malformed usage is unknown, never silently zero for accounting.
- Successful catalog lookup does not imply quotas or feature authorization exist.

## Execution record

- Base: `541825c`; branch `codex/issue-2-soclaas-provider-adapter`.
- Configuration, provider transport, Spring wiring, fake HTTP tests, and
  documentation implemented. The user authorized a local commit checkpoint on
  2026-10-19 and allowed up to four sensibly grouped files per commit.
- Implementation/test checkpoint: `48f5de2`, preceded by configuration/error
  contracts (`70459cf`) and bounded transport/wiring (`1f2f848`). Documentation
  and evidence are recorded separately. No push or merge performed.
- First failing compilation established missing adapter types; settings
  mutation regression subsequently failed and was fixed by snapshotting limits.
- 21 deterministic adapter tests passed in the broader Java run; frontend
  checks and 12 tests passed; production web JAR built.
- Whole Java verification passed with the inherited `DEBUG` flag removed:
  517 passed, 4 skipped. The debug-dependent registration failure reproduced
  on an untouched base snapshot and was not hidden by weakening its assertion.
- One opt-in synthetic live completion passed through the compiled adapter.
- Read-only agent review: material error-status retention and oversized-error
  masking findings fixed and covered by tests. Retry jitter bounded by the
  remaining deadline. No remaining material adapter finding reported.
- Full details: `../test-evidence/2026-10-18-soclaas-provider-adapter.md`.
