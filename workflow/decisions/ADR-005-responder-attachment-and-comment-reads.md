# ADR-005: Responder attachment and comment reads

- Status: Accepted for issue #7's read scope
- Decision owner: Product owner (`lqr1019`)
- Decision date: 2026-10-20 (Asia/Singapore)
- Scope: Responder and shared read-side components only
- Approval record: explicit product-owner decision in the issue #7 implementation conversation; attachment upload/write behavior is not approved here

## Context

The Responder detail acceptance criteria require privacy-safe comments and
authorized attachment content. Existing MP2 attachment handling uses local
files and byte-array reads, while the production web deployment uses a private
Neon Object Storage bucket. The web database did not yet define comment or
attachment metadata tables. Reporter-owned upload and reopen/comment writes
are separate work and must not be implicitly enabled by this decision.

## Decision

- Store attachment metadata and incident relationships in PostgreSQL. Store
  content in the existing private Neon Object Storage bucket.
- Stream attachment bytes through the Spring backend. Never expose storage
  credentials or signed URLs to browsers.
- On every download, resolve the attachment UUID and incident relationship from
  PostgreSQL, then verify the current enabled Responder can access that
  incident before calling Object Storage.
- Use generated attachment UUIDs as the storage-key basis, with extension
  derived only from the persisted allowlisted media type. Do not accept a key,
  filename, or storage path from a request.
- Return PNG/JPEG only with `Content-Disposition: attachment`, a fixed generic
  filename, `X-Content-Type-Options: nosniff`, and `Cache-Control: no-store`.
  Enforce the metadata size bound while streaming and close the upstream
  object stream.
- Persist comments with incident, author, role, type, text, and UTC timestamp.
  Responder reads comments only through an already-authorized incident detail
  query. For anonymous incidents, render a reporter-authored comment as
  `Anonymous reporter`; preserve other author labels and render all comment
  text as text.
- Reporter upload, object-write compensation, comment writing/reopening, and
  population of the new web tables are not part of this decision.

## Consequences

The authorized read API is safe to integrate with future Reporter-owned
writers using the shared schema. The read path does not make local MP2 files
available to the web application and cannot produce live attachments until a
separately approved writer stores both metadata and object bytes consistently.
Missing or forbidden attachments use the same safe unavailable response.
Object-store failures return a generic temporary-unavailable response without
provider details. Partial network failures after a stream starts terminate the
download; headers cannot be changed after streaming begins.

## Verification still required

- Isolated PostgreSQL migration and authorization tests, including revoked
  category access, disabled accounts, another Responder's assignment,
  attachment/incident mismatch, and whitespace-only comments.
- Object-store adapter and HTTP header/stream tests, including absent/missing
  objects, byte-length mismatch, and zero storage reads after denial.
- Independent implementation review and manual keyboard/screen-reader checks.
- Reporter-owned write-flow integration before marking detail acceptance or
  end-to-end attachment journeys complete.
