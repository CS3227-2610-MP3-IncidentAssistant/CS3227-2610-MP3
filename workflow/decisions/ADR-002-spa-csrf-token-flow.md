# ADR-002: SPA CSRF Token Flow

Status: **Accepted for implementation; independent security review pending**

- Date: 2026-10-18
- Decider: repository user through approval in the current Codex chat

## Context

The same-origin React client authenticates through an opaque server-side session
cookie, so every unsafe browser request requires CSRF protection. The initial
implementation returned a masked token from the session endpoint and retained
it in React component state while Spring retained the expected token in a
shared browser cookie. Authentication, logout, another tab, or concurrent
initialization could replace that cookie and leave React holding a stale token.

Replacing server-side sessions with browser-managed bearer credentials would
conflict with ADR-001 and would trade a bounded CSRF integration problem for a
larger authentication and XSS risk change.

## Decision

- Retain Spring Session JDBC authentication and same-origin session cookies.
- Use Spring Security's supported SPA CSRF configuration and its `XSRF-TOKEN`
  cookie plus `X-XSRF-TOKEN` request header conventions.
- The public session endpoint forces deferred CSRF-token creation but returns
  only authentication state; React reads the current cookie immediately before
  each unsafe request instead of retaining a token in component state.
- After authentication, React reloads the session endpoint so Spring can issue
  the post-authentication token and React can obtain trusted actor state.
- A response is eligible for CSRF recovery only when it is HTTP 403 with the
  stable `SECURITY_SESSION_EXPIRED` code. React reloads the session state and
  retries a replayable request at most once with the current cookie token.
- An authenticated mutation is not retried when refresh reports an anonymous
  session. Protected React state is replaced by the refreshed anonymous state.
- Logout is idempotent from the client's perspective: if recovery shows that
  the server session is already anonymous, protected client state is cleared.
- Missing/invalid CSRF failures occur before controller or domain mutation
  execution. Business idempotency and optimistic-concurrency controls remain
  required independently of this transport retry.

## Consequences

All unsafe API calls use one request helper, token rotation in another tab does
not leave a long-lived cached token, and logout follows the same bounded
recovery policy as other mutations. The readable CSRF cookie is not an
authentication credential and provides no XSS defense; CSP and output escaping
remain required. Independent security review and deployed multi-tab evidence
remain release gates.
