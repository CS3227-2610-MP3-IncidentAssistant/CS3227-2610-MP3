# MP3 Architecture

Status: **TODO — record approved choices and replace provisional statements
with implemented evidence.**

## Proposed shape

The current plan is a React browser application backed by a modular Java web
API with server-side security, PostgreSQL persistence, a shared SoC LLM
gateway, and a containerized independent deployment. Existing domain and
application services should be reused where they remain valid. An ADR must
decide whether React assets are served by the Java application or deployed as
a separate static frontend.

## Required sections

- Context and component diagrams: TODO
- Request/authentication/session flow: TODO
- Application authorization boundary: TODO
- PostgreSQL schema, migrations, and transaction boundary: TODO
- Concurrent incident-claim strategy: TODO
- Attachment storage choice and consistency: TODO
- Application event replacement for JavaFX-thread events: TODO
- AI gateway and provider trust boundary: TODO
- Development/test/production configuration: TODO
- Health checks, logging, monitoring, backup, and restore: TODO
- Deployment topology and rollback/forward-fix strategy: TODO
- Reused MP2 components and replaced components: TODO

## Architecture decisions

Create one record in `decisions/` for each material choice. At minimum cover:

- web framework and rendering approach;
- authentication/session model;
- PostgreSQL access/mapping and migrations;
- attachment storage;
- AI provider adapter and safety limits; and
- host, container, CI/CD, and release branch.

## Approval

- Architect/reviewer: TODO
- Security reviewer: TODO
- Date: TODO
