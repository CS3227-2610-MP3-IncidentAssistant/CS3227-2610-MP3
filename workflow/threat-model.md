# Threat Model

Status: **TODO — complete during specification and update after architecture or
provider changes.**

## System and trust boundaries

TODO: Diagram and describe browser, web application, PostgreSQL, attachment
storage, SoC LLM, CI/CD, hosting platform, administrators, and logs.

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
| Direct prompt injection | TODO | TODO | TODO | TODO | TODO |
| Indirect injection in incident/comment/filename/output | TODO | TODO | TODO | TODO | TODO |
| Cross-role or cross-incident leakage | TODO | TODO | TODO | TODO | TODO |
| Anonymous identity leakage | TODO | TODO | TODO | TODO | TODO |
| Excessive provider disclosure | TODO | TODO | TODO | TODO | TODO |
| Malicious HTML/Markdown/script output | TODO | TODO | TODO | TODO | TODO |
| AI-requested unauthorized action | TODO | TODO | TODO | TODO | TODO |
| Rate-limit exhaustion or resource abuse | TODO | TODO | TODO | TODO | TODO |
| Oversized input/output | TODO | TODO | TODO | TODO | TODO |
| Provider timeout/malformed/unavailable | TODO | TODO | TODO | TODO | TODO |
| Prompt/response leakage through logs | TODO | TODO | TODO | TODO | TODO |
| Human-approval bypass | TODO | TODO | TODO | TODO | TODO |
| CSRF/session theft/fixation | TODO | TODO | TODO | TODO | TODO |
| Concurrent double claim/stale update | TODO | TODO | TODO | TODO | TODO |
| Attachment spoofing or active content | TODO | TODO | TODO | TODO | TODO |
| Database or deployment secret exposure | TODO | TODO | TODO | TODO | TODO |

## Approval

- Security reviewer: TODO
- Date: TODO
- Accepted residual risks: TODO
