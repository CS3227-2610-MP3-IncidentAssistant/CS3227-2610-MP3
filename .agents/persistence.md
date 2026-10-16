# Database Persistence and Concurrency

## Architecture

PostgreSQL is the canonical production store. Persistence remains behind the
existing repository and mutation-store interfaces so domain and application
logic do not depend on SQL, HTTP, templates, or the hosting provider.

Local MP2 data files are not a supported production backend. Do not expose
them through HTTP or build a multi-user service around `LocalApplicationStore`.
No MP2 development-data migration is required unless a later requirement
explicitly introduces one.

## Schema management

- Create and evolve the schema with ordered, versioned migrations.
- Never edit or reorder a migration that has been applied to a shared or
  production database; add a forward migration instead.
- Enforce identifiers, required relationships, lifecycle-compatible values,
  uniqueness, and referential integrity in both application logic and the
  database where practical.
- Store application timestamps as UTC-capable instants.
- Store credentials only as established salted password hashes.
- Keep development, test, and production databases separate.

## Transactions and concurrency

- A domain mutation and its success audit event commit in one transaction.
- Validation, authorization, and transition checks are repeated inside the
  transaction when stale state could change the result.
- A competing claim for the same incident has exactly one winner. Implement
  this with an atomic conditional update, optimistic version check, or row lock
  and test the losing path.
- Reassignment, handoff, resolution, reopening, category-access changes, SLO
  changes, and account disabling must not partially commit.
- Use stable deterministic ordering with an identifier tie-breaker.
- Do not rely on one process, one request at a time, or sticky sessions for
  correctness.

## Attachments

- Production attachment content must use a shared durable store approved by
  the architecture decision. Bounded PostgreSQL binary storage is acceptable
  for the MP3 image-only limits; a private object store is also acceptable.
- Validate byte size, count, aggregate size, detected content type, and decoded
  image dimensions before making content available.
- Generate storage identifiers. Never put usernames, original client paths, or
  anonymous identity in storage keys or download URLs.
- Every read requires current incident authorization. Downloads use safe
  content headers and are never served as executable content.
- Metadata and content changes must have documented consistency and cleanup
  behavior when one part fails.

## Availability and recovery

- The service must fail startup or readiness when required migrations cannot be
  applied or the production database is unavailable.
- Provide a health/readiness check that verifies the application is ready and,
  where safe, that required database connectivity is available.
- Database backup, restore, retention, and credential rotation are deployment
  concerns and must be documented in `workflow/architecture.md` and the
  Developer Guide.
- Never log connection strings, credentials, SQL parameters containing
  protected content, or database dumps.

## Testing

- Repository integration tests use an isolated PostgreSQL instance or schema.
- Tests cover migrations from an empty database, constraints, rollback,
  mutation-plus-audit atomicity, restart persistence, and concurrent conflicts.
- Tests never use the production database or credentials.
