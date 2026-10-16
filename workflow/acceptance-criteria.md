# Acceptance Criteria

Status: **TODO — convert each approved requirement into observable, testable
criteria and link evidence.**

## Release-level criteria

- [ ] Public deployment is reachable over HTTPS and identifies the release.
- [ ] `master`, the release tag, GitHub Pages, and the deployed artifact refer
  to the same assessed commit.
- [ ] Development and production use separate credentials and configuration.
- [ ] CI builds, tests, checks, packages, deploys, and smoke-tests the release.
- [ ] PostgreSQL data survives application restart/redeploy.
- [ ] Concurrent sessions are isolated and a competing claim has one winner.
- [ ] Reporter, Responder, and Administrator end-to-end workflows pass.
- [ ] Separate role login portals use one backend identity store, accept only
  the matching persisted role, and do not reveal usernames or roles on failure.
- [ ] Public registration can create only a Reporter; first and subsequent
  Administrator provisioning follow the approved controlled workflows.
- [ ] Anonymous submission is selected through an accessible control, persists
  the chosen value, and exposes only the neutral label to unauthorized roles.
- [ ] Reporter-to-Responder promotion works end to end through request, status,
  Administrator decision, audit, reauthentication, and Responder portal access.
- [ ] One advisory AI feature per role passes normal, denied, adversarial, and
  provider-failure tests.
- [ ] Anonymous identity is absent from unauthorized views, AI requests, logs,
  and evidence.
- [ ] Required documentation, workflow records, and reflections match the
  deployed release.

## Feature criteria template

Copy this section for every retained or new feature.

- Feature/requirement ID: TODO
- Authorized actor: TODO
- Preconditions: TODO
- Given/when/then normal path: TODO
- Denied path without information leakage: TODO
- Validation and limits: TODO
- Concurrent/stale path: TODO or not applicable with reason
- Persistence and audit result: TODO
- AI data and approval behavior: TODO or not applicable
- Failure/recovery behavior: TODO
- Automated evidence: TODO
- Manual/deployed evidence: TODO
- Human approver/date: TODO
