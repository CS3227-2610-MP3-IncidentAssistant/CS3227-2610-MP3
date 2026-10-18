# Spring Boot Migration: Temporary Account Registration

Status: **Approved only as an interim migration slice; MUST be replaced before
shipping**

Approved by: repository user request on 2026-10-18

## Objective

Move the legacy account-registration workflow from JavaFX/local persistence to
the Spring Boot, React, and PostgreSQL web foundation so migration work can
continue against web-created accounts.

## Temporary security exception and release blocker

This slice deliberately preserves the legacy registration form's insecure,
client-selected role. An unauthenticated caller can therefore create a
Reporter, Responder, or Administrator account. This is temporary migration
scaffolding only.

**This implementation MUST be removed or replaced before any public deployment,
release candidate, demonstration using public infrastructure, or assignment
submission. It MUST NOT ship.** The production requirements in
`.agents/requirements.md`, `.agents/security.md`,
`.agents/authorization-matrix.md`, and `.agents/mp2-grade-improvements.md`
remain the required end state: public registration exists only in the Reporter
portal and always creates a Reporter, regardless of browser-supplied data.

The release, security/architecture, and MVP exit gates remain blocked while an
unauthenticated request can select a role. A passing test for this temporary
behavior is migration evidence, not evidence that the production registration
security criteria are satisfied.

## Interim HTTP and UI contract

- The unauthenticated React sign-in view offers an account-creation form.
- The form accepts a case-sensitive login name, password, password
  confirmation, and one of `REPORTER`, `RESPONDER`, or `ADMINISTRATOR`.
- `POST /api/v1/auth/register` accepts JSON with those fields and uses the
  client-selected role. This is the known temporary insecurity described
  above.
- A successful registration returns `201 Created` and does not authenticate
  the new account automatically.
- Blank or oversized login names return `400` with `Enter a non-blank login
  name.` Blank or oversized passwords return `400` with `Enter a password.`
  Mismatched confirmation and unknown roles return their own stable `400`
  validation messages. These outcomes create no database write or success
  audit.
- A duplicate case-sensitive login name returns `409` with the legacy JavaFX
  message `That case-sensitive login name is already registered.`
- Other persistence failures return `503` with the legacy JavaFX message `The
  account could not be created. Try again.` without exposing SQL or
  implementation details.

Login names are limited to 128 characters to match the database column.
Passwords are temporarily limited to 128 characters at the request boundary.
No password-strength requirement is invented in this slice because the final
password policy remains a product decision.

This temporary slice adds no dedicated registration rate limit, retry, or
account-activation control. Requests are synchronous and are not retried after
a safe failure response. The missing abuse controls are another reason this
workflow is prohibited from public deployment and MUST be replaced before
shipping.

## Persistence and audit

- Registration generates account and audit identifiers in the application and
  an application timestamp from a UTC clock.
- The account and adaptive salted password hash are written to the existing
  PostgreSQL `accounts` and `account_credentials` tables.
- A successful `ACCOUNT_REGISTERED` audit event is inserted in the same
  database transaction. Validation, encoding, account insertion, credential
  insertion, or audit failure leaves no partial account.
- Plaintext passwords and password confirmation are never persisted, audited,
  logged, or returned.
- Concurrent attempts for the same case-sensitive login name rely on the
  database unique constraint: at most one succeeds and the remainder receive
  the duplicate result.

Registration has no incident, attachment, SLO, anonymous-identity, or AI data
effect.

## Required replacement before shipping

Before the application can ship, all of the following are required:

1. Remove the public role selector and ignore/reject any browser-supplied role.
2. Make public registration create `REPORTER` accounts only and place it only
   in the Reporter portal.
3. Keep Responder creation behind approved promotion and Administrator creation
   behind bootstrap or an authenticated Administrator operation.
4. Add allowed and tampered-request tests proving public callers cannot create
   privileged accounts.
5. Obtain the security/architecture, independent-review, and release approvals
   required by `workflow/README.md`.

## Acceptance criteria

- [ ] A valid interim request creates an enabled account of the requested
  client-selected role with an adaptive salted password hash and a successful
  `ACCOUNT_REGISTERED` audit event in one transaction.
- [x] The React registration form validates password confirmation, submits the
  selected role, reports a successful account creation, and does not sign the
  new account in automatically.
- [x] Blank or oversized fields, mismatched confirmation, and unknown roles are
  rejected without account, credential, or success-audit persistence.
- [x] Registration validation, duplicate, and unavailable outcomes are shown
  in React using the stable legacy-equivalent JavaFX messages.
- [ ] A case-sensitive duplicate login returns the stable duplicate outcome;
  concurrent duplicate attempts produce exactly one account.
- [ ] Persistence or audit failure rolls back the whole registration and
  returns a safe response without secret or SQL disclosure.
- [ ] CSRF protection applies to registration and plaintext credentials are
  absent from persistence, logs, audits, and responses.
- [ ] **Release blocker:** public registration creates only `REPORTER`, no
  public role selector or privileged-registration path remains, tampered-role
  tests pass, and the required human review gates are recorded. This criterion
  MUST remain unticked while the temporary insecure workflow exists.

## Verification evidence

- Automated: `gradlew.bat --no-daemon check bootJar` passed on 2026-10-18
  (Java unit suite, frontend typecheck/lint and 7 component tests, packaging,
  and repository verification). Focused service/controller tests also passed
  with `gradlew.bat --no-daemon test --tests
  "com.company.incidentdesk.web.security.TemporaryAccountRegistration*"
  -PfrontendSkipBuild=true`.
- Local runtime: `gradlew.bat --no-daemon bootRun` started the real Spring
  application with the configured PostgreSQL datasource, validated both Flyway
  migrations, and started Tomcat on port 8080. Non-mutating probes returned
  HTTP 200 from `/health` and `/api/v1/auth/session`; a CSRF-authenticated,
  intentionally invalid registration returned HTTP 400 without a database
  write. After the legacy-message change, the application was started on
  temporary port 18080 and the same request returned `INVALID_LOGIN_NAME` with
  `Enter a non-blank login name.` The server was then stopped.
- Still required: isolated PostgreSQL transaction, audit-failure rollback,
  concurrent duplicate, and HTTP CSRF/secret-absence evidence. The associated
  criteria remain unticked.
- Manual/deployed: not run; public deployment is prohibited for this interim
  slice
- Human approver/date for shipping: pending; cannot be approved while the
  release-blocking criterion is open
