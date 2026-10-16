# MP3 Deployment Strategy

Status: **Approved direction; implementation and release evidence pending**

Approved by: repository user on 2026-10-16

## Decision

Deploy Incident Desk as one containerized web application on Google Cloud Run
in Singapore. The container will run Spring Boot, expose the Java API and
health endpoint, and serve the production build of the Vite/React/TypeScript
frontend. Use Neon PostgreSQL in Singapore for shared relational persistence
and Google Cloud Storage for attachment bytes.

Vercel is not part of the initial production topology. It may be reconsidered
if frontend preview deployments provide enough value to justify a second
deployment pipeline and reverse-proxy layer.

## Production topology

```text
Browser
  |
  | HTTPS: one public origin
  v
Google Cloud Run, Singapore
  |- React static assets and client-side routes
  |- Spring Boot /api/**
  `- Spring Boot /health
       |
       |- Neon PostgreSQL, Singapore
       `- Google Cloud Storage, Singapore
```

The browser must use relative URLs such as `/api/incidents`. React and the API
therefore share the same scheme, host, and port. The intended production
topology requires no cross-origin CORS policy. CORS must not be treated as an
authorization control or enabled broadly as a workaround.

## Rationale

- One container and one public origin match the reproducible single-artifact
  direction in `.agents/deployment.md`.
- Same-origin requests simplify secure session-cookie and CSRF-token handling.
- Cloud Run can use request-based billing and scale to zero for low coursework
  traffic. AWS App Runner retains provisioned memory while idle and has a
  higher predictable baseline cost.
- Locating Cloud Run and Neon in Singapore minimizes backend-to-database
  latency for the expected deployment region.
- Keeping React static removes the need for an independent frontend runtime;
  Spring Boot remains authoritative for authentication, authorization,
  validation, audit, persistence, and AI data selection.

## Development topology

During local development, run Vite and Spring Boot as separate processes:

```text
Vite:        http://localhost:5173
Spring Boot: http://localhost:8080
```

Vite must proxy `/api/**` and `/health` to Spring Boot. React must still use
relative request URLs. To the browser, requests remain on the Vite origin, so
local development resembles the production same-origin contract without
adding development-only CORS permissions to Spring Boot.

The Vite proxy is development tooling, not a production security boundary.
CSRF protection and server-side authorization remain mandatory.

## Build and runtime contract

- The production build builds React first and packages its static output into
  the Spring Boot container image.
- Spring Boot serves client-side application routes without intercepting
  `/api/**`, `/health`, attachment responses, or other backend routes.
- The container binds to the port supplied by Cloud Run and runs as a non-root
  user where supported.
- Cloud Run instances are stateless. No incident, session, attachment, audit,
  or other runtime data may depend on the container filesystem.
- Development, test, and production use separate configuration and data.
- Secrets are supplied through deployment configuration or an approved secret
  manager and are never committed to the repository or included in the React
  bundle.

## PostgreSQL strategy

- Use Neon PostgreSQL through the standard PostgreSQL JDBC driver with TLS.
- Use Neon's pooled connection endpoint for the application connection string.
- Keep the application connection pool deliberately small and cap Cloud Run
  instance scaling so aggregate connections cannot exhaust the database limit.
- Use separate Neon projects or branches for development, automated tests, and
  production. Tests must never connect to production.
- Apply schema changes only through forward, versioned migrations. The exact
  migration tool and whether migrations run as a release job or under guarded
  application startup require an architecture decision before implementation.
- Reconcile `/health` readiness semantics with `.agents/persistence.md` when
  PostgreSQL becomes a required runtime dependency.

## Authentication and web security

- Use server-managed authentication and sessions. Cloud Run may run multiple
  instances, so authenticated state must not rely on process-global or
  instance-local mutable memory.
- Production cookies must be `Secure`, `HttpOnly`, and use an explicitly
  reviewed `SameSite` policy. Avoid setting a broad cookie `Domain` unless a
  later architecture requires it.
- Every state-changing request requires CSRF protection in addition to
  application-layer authorization.
- Configure Spring's trusted forwarded-header behavior for Cloud Run and test
  secure-cookie, redirect, host, and scheme handling through the real public
  endpoint.
- Authenticated or user-specific API responses use private/no-store caching
  directives and must never be cached by a shared CDN.

## Attachment storage

- Store attachment metadata and authorization relationships in PostgreSQL.
- Store attachment bytes in a private Google Cloud Storage bucket under
  generated identifiers, never original filenames or executable application
  paths.
- Keep the bucket in or near the Singapore deployment region and block public
  object access.
- Validate every upload according to `.agents/requirements.md` and authorize
  every read. The choice between authorized backend streaming and narrowly
  scoped short-lived signed URLs must be specified and threat-modelled before
  implementation.
- Define cleanup and transaction-compensation behavior before permitting
  uploads so failed database or object-store operations do not leave unsafe or
  misleading state.

## Cost and scaling controls

- Start Cloud Run with request-based billing and zero minimum instances.
- Set a conservative maximum instance count and concurrency value compatible
  with the JDBC connection budget.
- Expect a cold start after idle periods. Do not add frequent synthetic pings
  merely to keep the service warm, because they defeat scale-to-zero savings.
- Configure Google Cloud budgets and alerts before public deployment. Also
  monitor Cloud Run, Artifact Registry, build, logging, network-egress, Cloud
  Storage, and Neon usage; a free allowance is not a guarantee of a zero bill.
- Set retention limits for non-essential build artifacts and operational logs
  without deleting required audit or incident records.

## CI/CD direction

The canonical release pipeline must:

1. install, lint, test, and build the React frontend;
2. compile and test the Java backend, including PostgreSQL integration tests;
3. build one production container image containing the React assets and Spring
   Boot application;
4. validate versioned database migrations against an isolated database;
5. deploy from the approved release branch using protected production secrets;
6. run the post-deployment health check and role smoke tests; and
7. record sanitized evidence tied to the exact deployed commit.

Production migration and deployment jobs require an approval gate. A failed
deployment must preserve the last working revision and must not destroy or
silently rewrite production data.

## Rejected initial alternative: Vercel frontend

Hosting React on Vercel and Spring Boot on Cloud Run is technically viable.
Vercel can rewrite `/api/**` to an external Cloud Run origin while keeping the
browser URL unchanged. It was not selected initially because it creates two
artifacts and two coordinated deployments, introduces proxy and cache behavior
that must be tested with sessions and CSRF, and does not materially improve a
static React application's runtime needs.

If this choice is revisited, record a new architecture decision covering
external rewrites, preview environments, cookie behavior, cache prevention,
origin protection, Git-organization limits, plan terms, and rollback
coordination.

## Follow-up decisions and evidence

Before production implementation is considered complete:

- record the deployment topology as an ADR under `workflow/decisions/`;
- select and approve the database migration and shared-session mechanisms;
- specify the attachment download and failed-write compensation design;
- establish Cloud Run resource, concurrency, scaling, and connection-pool
  limits using measured application behavior;
- document domain, TLS, secrets, backup/restore, credential rotation, and
  forward-fix procedures;
- verify the container and deployed workflows according to `.agents/testing.md`;
  and
- obtain the human approval gates required by `workflow/README.md`.

## Pricing and capability references

These provider details are time-sensitive and were checked on 2026-10-16:

- [Google Cloud Run pricing](https://cloud.google.com/run/pricing)
- [Google Cloud Run minimum instances](https://cloud.google.com/run/docs/configuring/min-instances)
- [Neon regions](https://neon.com/docs/introduction/regions)
- [AWS App Runner pricing](https://aws.amazon.com/apprunner/pricing/)
- [Vercel external rewrites](https://vercel.com/docs/routing/rewrites)
- [Vercel pricing](https://vercel.com/pricing)
- [Vercel limits](https://vercel.com/docs/limits)

Recheck current terms, regional availability, quotas, and pricing before
creating production resources.
