# ADR-001: Web Persistence and Session Foundation

Status: **Accepted for implementation; independent security review pending**

- Date: 2026-10-18
- Decider: repository user through approval of the administrator slice and the
  previously approved deployment direction

## Context

The approved deployment strategy requires one Spring Boot and React artifact,
stateless Cloud Run instances, PostgreSQL canonical data, server-managed
authentication, CSRF protection, and no process-global session state. Ordered
migrations and shared sessions were left open.

## Decision

- Spring Boot serves the Vite production assets and `/api/v1/**` endpoints from
  one origin.
- Spring Security owns authentication, authorization, session fixation
  protection, logout, CSRF, and security headers.
- Spring Session JDBC stores opaque server-side sessions in PostgreSQL so
  requests remain correct across Cloud Run instances.
- Flyway owns forward-only PostgreSQL migrations. Production migrations use
  the direct `DATABASE_URL_UNPOOLED` connection in a guarded release step;
  application traffic uses the pooled `DATABASE_URL`.
- Credentials use Spring Security's established adaptive salted password
  encoder. Plaintext credentials are accepted only at the request boundary,
  never stored, logged, audited, or returned except for the approved one-time
  temporary-password workflow.
- The first administrator is created by a one-time, environment-supplied
  bootstrap operation. Later administrators are provisioned only by an
  authenticated administrator and receive a one-time temporary credential
  through the same show-once response boundary as password reset.
- Production cookies are Secure, HttpOnly, host-only, SameSite=Lax, and have a
  bounded lifetime. Authenticated and protected responses use private/no-store
  caching.
- Incident-scoped audit UI is omitted; the global administrator audit UI is
  retained.

## Consequences

The web application cannot use the JavaFX `InMemorySessionService` or
`LocalApplicationStore`. HTTP principals adapt explicitly to application actor
access, and domain services remain independent of Spring and SQL. Migrations,
session invalidation, rollback, concurrent conflicts, cookie behavior, and
generic authentication failures require automated evidence before release.

The SoC LLM adapter is deliberately outside this ADR because its provider
contract remains unapproved.
