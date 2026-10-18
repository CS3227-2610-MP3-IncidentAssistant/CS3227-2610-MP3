# Threat Model

Status: **TODO — complete during specification and update after architecture or
provider changes.**

## System and trust boundaries

The SoCLaaS-specific boundary is browser -> Spring authorization and
feature-specific data selection -> PostgreSQL-backed admission/budget control
-> fixed HTTPS SoCLaaS gateway -> untrusted result validation -> escaped browser
text. The provider receives no application session, database credential, domain
tool, callback, or mutation authority. React never receives the provider key.
The remaining browser, PostgreSQL, attachment, CI/CD, hosting, administrator,
and logging boundaries are TODO.

## Protected assets

TODO: Cover credentials, sessions, incident content, anonymous identity,
attachments, authorization state, audit integrity, SLO data, provider secrets,
and deployment credentials.

## Threat actors and assumptions

TODO: Cover unauthenticated users, malicious authenticated users in each role,
compromised browser input, prompt injection, provider failure, leaked secrets,
concurrent requests, and privileged infrastructure operators.

## Required threat analysis

| Threat | Entry point | Asset/impact | Preventive controls | Detection/evidence | Residual risk |
| --- | --- | --- | --- | --- | --- |
| Direct prompt injection | Reporter draft or incident description | Model follows attacker text or fabricates unsafe advice | Structured data boundary, no tools, no additional data, plain-text advisory output, human review | Fake-provider injection tests and payload capture | Misleading text may still influence a human; label and review remain required |
| Indirect injection in incident/comment/filename/output | Stored untrusted fields | Instruction reaches provider or is chained into another request | Per-feature allowlist; v1 responder excludes comments and attachments; no response chaining | Payload and negative-selection tests | Allowed title/description remains untrusted content |
| Cross-role or cross-incident leakage | Forged IDs or stale browser state | Protected incident disclosure | Reauthorize immediately before canonical server-side selection; generic denial | Allowed/denied/stale authorization tests | Application authorization defect |
| Anonymous identity leakage | Entity serialization, logs, errors, aggregates | Reporter identity reaches provider or another user | Explicit DTO allowlists, aggregate suppression below five, no entity serialization, redacted observability | Anonymous payload/log/audit tests | Statistical inference in larger aggregates |
| Excessive provider disclosure | Over-broad prompt builder | Unnecessary incident or account data leaves application boundary | Feature-specific fields, 16 KiB record cap, payload contract tests | Captured fake-provider request reviewed field by field | Provider retention is undocumented and requires risk acceptance |
| Malicious HTML/Markdown/script output | Provider response | Browser code execution or unsafe navigation | Strict response shape/size, escaped plain text, no Markdown/HTML/URL fetch | Browser/component malicious-output tests | Social-engineering text remains possible |
| AI-requested unauthorized action | Tool call or imperative model output | Incident/account mutation | Chat-completions only, no tools, no output interpreter, normal mutation APIs remain separately authorized | Tool-call and mutation-absence tests | Human may manually act on poor advice |
| Rate-limit exhaustion or resource abuse | Repeated or concurrent AI requests | Shared key quota exhausted; normal app degraded | Per-account and global rate/concurrency limits, atomic budget reservation, 25% provider reserve, circuit breaker | Deterministic limit, PostgreSQL concurrency, and load tests | Provider can lower limits without notice; production re-verification required |
| Oversized input/output | Large incident text or provider body | Cost, memory, or availability exhaustion | Byte/token caps before dispatch, output-token cap, HTTP body cap, bounded decoder | Exact-boundary and streaming/oversize tests | Token estimator may differ; worst-case budget reservation is required |
| Provider timeout/malformed/unavailable | Network or SoCLaaS failure | Worker exhaustion or broken incident workflow | Deadlines, cancellation, one narrowly bounded retry, circuit breaker, non-AI fallback | Timeout, retry, cancellation, malformed JSON, and outage tests | No documented provider SLO |
| Prompt/response leakage through logs | HTTP/client tracing, exceptions, audits | Sensitive content or key disclosure | Metadata-only logs/audits, provider body suppression, secret redaction, bounded labels | Log/trace/metric/audit capture tests | Infrastructure operators may access outbound traffic metadata |
| Human-approval bypass | Auto-apply or persistence of output | Unreviewed domain change | Advisory component only; explicit local apply where allowed; separate authorized save/mutation | UI and persistence negative tests | User may accept output without careful reading |
| CSRF/session theft/fixation | TODO | TODO | TODO | TODO | TODO |
| Concurrent double claim/stale update | TODO | TODO | TODO | TODO | TODO |
| Attachment spoofing or active content | TODO | TODO | TODO | TODO | TODO |
| Database or deployment secret exposure | TODO | TODO | TODO | TODO | TODO |

## Approval

- Security reviewer: TODO
- Date: TODO
- Accepted residual risks: TODO
