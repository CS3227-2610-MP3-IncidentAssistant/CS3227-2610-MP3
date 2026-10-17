# Neon PostgreSQL and Object Storage Integration

Status: **implemented and verified; independent review and release approval
pending**

Direction approved by: repository user via the explicit Neon setup instruction
on 2026-10-17. This does not satisfy the independent security, implementation,
or release approval gates in `workflow/README.md`.

## Scope and architecture

Incident Desk will use the existing Neon project `orange-wind-42169934` and its
`production` branch for two production services:

- Lakebase Postgres is the shared relational database. Normal application
  traffic uses Neon's pooled `DATABASE_URL` through the standard PostgreSQL JDBC
  driver. Migration and other session-oriented tools must use the direct
  `DATABASE_URL_UNPOOLED` connection.
- Neon Object Storage is the private store for attachment bytes. The
  `incident-attachments` bucket is declared in `neon.ts`; attachment metadata
  and authorization relationships remain in PostgreSQL. The Spring backend
  accesses the S3-compatible endpoint with the AWS SDK, SigV4, environment
  credentials, and required path-style addressing.

The browser never receives database or object-storage credentials and never
accesses the private bucket directly. Authorized attachment reads and writes
remain backend operations. Object keys use generated identifiers and do not
contain usernames, original client paths, or anonymous identities.

This integration establishes production service clients, readiness checks, and
live connectivity evidence. It does not by itself replace the existing MP2
file store with complete PostgreSQL repositories; that migration remains a
separate versioned-schema implementation slice.

## Configuration and failure behavior

- `neon.ts` is the source of truth for the private bucket and branch policy.
- Neon pulls `DATABASE_URL`, `DATABASE_URL_UNPOOLED`,
  `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_ENDPOINT_URL_S3`, and
  `AWS_REGION` into a git-ignored local environment file after deployment.
- `INCIDENT_DESK_ATTACHMENT_BUCKET` may override the bucket name and defaults
  to `incident-attachments`.
- Local development may start without Neon configuration. Production sets
  `INCIDENT_DESK_NEON_REQUIRED=true`; startup then fails fast if either service
  is missing required configuration.
- Readiness is `DOWN` when a configured PostgreSQL query or private-bucket
  metadata request fails. Health output remains aggregate-only and never
  exposes connection strings, credentials, bucket contents, or provider error
  detail.
- The object-storage client uses bounded SDK timeouts and default safe retry
  behavior. Application code must compensate for an object write if the
  related metadata transaction fails; that attachment workflow is outside this
  connectivity slice and remains approval-gated.

## Security and operational boundaries

- Commit only variable names and safe defaults. `.env`, `.env.local`, `.neon`,
  credentials, connection strings, and smoke-test payloads are not committed.
- The production bucket is private. Every future attachment download must
  recheck current incident authorization in the application before reading the
  object.
- The service-smoke command may run only against the explicitly linked branch.
  It performs `SELECT 1`, then writes, reads, verifies, and deletes one random
  object under `smoke-tests/`; it does not inspect or modify incident data.
- Production data migration, destructive bucket operations, public access, and
  credential rotation are not authorized by this specification.

## Traceability

| ID | Requirement | Implementation area | Recorded evidence |
| --- | --- | --- | --- |
| NEON-1 | Declarative private services | `neon.ts`, Neon production branch | `neon deploy`, sanitized status |
| NEON-2 | Pooled JDBC application connection | Spring Neon configuration | automated config tests, live `SELECT 1` |
| NEON-3 | Private branch-aware object client | Spring Neon configuration | automated client tests, live put/get/delete |
| NEON-4 | Safe startup and readiness | Spring configuration and Actuator | missing-config and health tests |
| NEON-5 | Reproducible application build | root npm script and Gradle build | `npm run build` |

## Acceptance criteria

- [x] **NEON-1:** Given the linked production branch, `neon deploy` provisions
  the declared private `incident-attachments` bucket and pulls all required
  PostgreSQL and S3-compatible variables into a git-ignored environment file.
  Evidence:
  [`test-evidence/2026-10-17-neon-services.md`](test-evidence/2026-10-17-neon-services.md).
- [x] **NEON-2:** Given valid Neon variables, the Spring backend constructs a
  PostgreSQL JDBC data source from the pooled `DATABASE_URL` without logging the
  URL or credentials, and a live application-owned probe completes `SELECT 1`.
  Evidence:
  [`test-evidence/2026-10-17-neon-services.md`](test-evidence/2026-10-17-neon-services.md).
- [x] **NEON-3:** Given valid Neon variables, the Spring backend constructs a
  path-style S3 client for the private bucket, and the live application-owned
  probe uploads random bytes under `smoke-tests/`, downloads identical bytes,
  and deletes the object. Evidence:
  [`test-evidence/2026-10-17-neon-services.md`](test-evidence/2026-10-17-neon-services.md).
- [x] **NEON-4:** With `INCIDENT_DESK_NEON_REQUIRED=true`, missing configuration
  prevents startup; configured provider failure makes aggregate readiness
  unavailable without disclosing provider or credential details. Local startup
  remains available when integration is not required. Evidence:
  [`test-evidence/2026-10-17-neon-services.md`](test-evidence/2026-10-17-neon-services.md).
- [x] **NEON-5:** No secret-bearing environment file is tracked, automated
  configuration tests pass, and `npm run build` completes successfully from the
  repository root. Evidence:
  [`test-evidence/2026-10-17-neon-services.md`](test-evidence/2026-10-17-neon-services.md).
- [x] **NEON-6:** The exact live branch, commands, sanitized output, and cleanup
  outcome are recorded under `workflow/test-evidence/`; independent security
  and release review remain explicitly pending. Evidence:
  [`test-evidence/2026-10-17-neon-services.md`](test-evidence/2026-10-17-neon-services.md).

## Verification evidence

- Automated: `npm run build` passed on the uncommitted working tree based on
  `4a83a05913a3b91a4e0e48126d0ef28970e0794b`; see
  [`test-evidence/2026-10-17-neon-services.md`](test-evidence/2026-10-17-neon-services.md).
- Live Neon services: production branch deployment, PostgreSQL query, Object
  Storage round trip and cleanup, and packaged health check passed; see
  [`test-evidence/2026-10-17-neon-services.md`](test-evidence/2026-10-17-neon-services.md).
- Independent security/release approver and date: pending
