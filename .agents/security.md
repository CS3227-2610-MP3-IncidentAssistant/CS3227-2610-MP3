# Web Application Security

## Authentication and sessions

- Use an established server-side security framework. Do not implement session
  cookies, CSRF tokens, or password verification in controllers.
- Reporter, Responder, and Administrator login portals use the same backend
  identity store and authentication implementation. Portal selection is an
  expected-role constraint, never trusted proof of role.
- Only the Reporter portal exposes public registration, and it creates only a
  Reporter. Responder access requires an approved promotion; Administrator
  access requires controlled bootstrap or an authorized Administrator action.
- Return a generic authentication failure for invalid credentials, unknown
  accounts, disabled accounts, and role/portal mismatch where a distinction
  would enable account or role enumeration.
- Derive the actor from the trusted request security context. Ignore actor,
  role, ownership, assignment, category access, audit identity, and timestamp
  values supplied by browser input.
- Rotate the session identifier on authentication and privilege change.
- Invalidate active sessions or require reauthentication after promotion,
  privileged account creation, role change, category-access change, password
  reset, or account disablement as specified by the feature workflow.
- In production, session cookies are Secure, HttpOnly, use an explicit SameSite
  policy, have bounded lifetime, and are never logged.
- Logout invalidates the server session. Disabling an account prevents new
  access and invalidates or rejects existing sessions on their next use.

## HTTP and browser controls

- Require CSRF protection for every state-changing browser request.
- Escape untrusted output by default. Never render incident text, comments,
  attachment names, or AI output as raw HTML.
- Apply a restrictive Content Security Policy and appropriate frame, content
  type, referrer, and transport security headers in production.
- Validate request content type, field length, collection count, upload size,
  and identifiers on the server.
- Use generic responses where distinguishing missing from forbidden resources
  would reveal protected existence or identity.
- Do not place secrets, session identifiers, anonymous identity, or sensitive
  incident text in URLs.

## Authorization

- Authorization is deny-by-default and enforced in application/domain logic as
  described in `.agents/authorization-matrix.md`.
- Controllers may add coarse route guards, but route guards and hidden controls
  never replace resource-level authorization.
- Recheck authorization at execution time and inside a transaction when stale
  ownership, assignment, category access, or account state could matter.
- The AI gateway receives only already-authorized, allowlisted data and cannot
  call domain mutations.
- An Administrator AI query plan is untrusted provider output. Validate it
  against the closed schema in `workflow/admin-ai-insights.md`; dispatch only
  typed read-only analytics handlers and never interpret it as SQL, code, a
  URL, a repository method, an authorization decision, or a mutation.

## Configuration and logging

- Secrets come from environment variables or the hosting platform's secret
  store. Commit only documented example names and non-secret defaults.
- Development and production use separate databases, credentials, session
  settings, provider credentials, and logging configuration.
- Production errors do not expose stack traces, SQL, environment values,
  provider payloads, or implementation details to users.
- Logs use structured, redacted metadata and must follow the anonymity and AI
  policies.

## Abuse and availability

- Bound request bodies, form fields, uploads, AI inputs/outputs, and expensive
  searches.
- Rate-limit authentication and AI endpoints. Preserve enough generic behavior
  to avoid account enumeration.
- Configure outbound provider timeouts and inbound server timeouts appropriate
  to the deployment.
- A provider failure must not make incident management unavailable.
