# MVP Scope

## MVP objective

Deliver a deployed end-to-end web incident flow with enforced authorization,
shared persistence, one advisory AI feature per role, documented security
controls, automated verification, and reproducible CI/CD.

## Included in MVP

### Shared

- Web registration, login, logout, CSRF protection, and isolated concurrent
  sessions.
- Separate Reporter, Responder, and Administrator login portals backed by one
  authentication service and one identity store. Public registration appears
  only in the Reporter portal and cannot select a privileged role.
- Shared PostgreSQL persistence, versioned migrations, restart/redeploy
  recovery, and transactional audit writes.
- Role- and category-based authorization.
- Basic incident list and detail views.
- Production configuration from environment variables with no committed
  secrets.
- Shared SoC LLM gateway with fake-provider tests, rate limits, timeouts, input
  and output limits, safe logging, and failure isolation.
- Public deployment, health check, CI pipeline, and production artifact.

### Reporter

- Submit a non-draft incident.
- View the reporter's own incident list, details, status, and resolution notes.
- Submit anonymously without identity leakage.
- Select anonymity through a visible, accessible checkbox or switch whose value
  is persisted and reflected in privacy-safe views.
- Request promotion to Responder and view the request status.
- Use an advisory AI description-improvement feature with explicit review.

### Responder

- View incidents in assigned categories.
- Claim an unassigned incident.
- View claimed incidents.
- Resolve a claimed incident with remarks.
- Use an advisory AI incident-summary and next-step feature.

### Administrator

- View and filter all incident reports.
- Review accounts and approve responder promotion.
- Approve or reject a complete Reporter-to-Responder promotion request and
  require the affected account to reauthenticate before role access.
- Update responder category access.
- Complete one-time first-Administrator bootstrap and authorized subsequent
  Administrator provisioning without public privileged registration.
- Configure SLOs.
- Use an advisory AI summary of privacy-safe aggregate operational trends.

## Post-MVP unless scheduled earlier

- Draft reports.
- Reporter editing and withdrawal.
- Reporter reopening with a required explanatory follow-up comment.
- Responder handoff.
- Admin reassignment and direct resolution.
- Account deletion and password-reset workflow.
- Detailed statistics beyond those required for the administrator AI input.
- Personal responder statistics.
- Advanced searching, filtering, and sorting.
- Persistent notification history.
- Advanced visual polish and non-essential charts.

## MVP exit criteria

- [ ] A registered reporter can submit and later view an incident.
- [ ] An authorized responder can see, claim, and resolve it.
- [ ] An unauthorized responder cannot see or modify incidents outside assigned
  categories.
- [ ] An admin can grant responder status/category access and monitor incidents.
- [ ] Concurrent sessions remain isolated and competing claims cannot both win.
- [ ] State survives a normal application restart and redeploy.
- [ ] Every AI feature enforces authorization and data minimization before the
  provider call, requires human review, and fails safely.
- [ ] Required authorization, lifecycle, persistence, web-security, AI-security,
  and deployment smoke tests pass.
- [ ] The public deployment and GitHub Pages product site identify the assessed
  release commit.

This file sets delivery priority, not permission to violate future-facing domain
invariants. Post-MVP data structures should not be prematurely implemented, but
MVP choices should avoid making the documented lifecycle impossible.

Audit persistence remains an internal backend integrity requirement. An audit
history endpoint or user interface is intentionally excluded rather than
deferred.
