# Deployment and Configuration

The selected provider topology, development proxy, storage direction, and cost
controls are documented in `.agents/deployment_strategy.md`. This file remains
the authoritative environment, release, and operational contract.

## Environments

- Maintain separate development and production configuration.
- Development may use local containers and test credentials. Production uses a
  managed shared PostgreSQL database, production-only credentials, HTTPS, and
  secure cookie settings.
- Never reuse production credentials in development, tests, previews, or CI.
- Commit an example configuration containing variable names and safe defaults,
  never real secret values.

## Production artifact

- Build one reproducible deployable web artifact, preferably a container image.
- The runtime image contains application code and static assets only; runtime
  data is external.
- Run as a non-root user where the platform permits.
- Bind to the host-provided port and expose a lightweight health/readiness
  endpoint.
- Pin or otherwise deliberately manage base images and production dependencies.

## CI/CD

GitHub Actions must automate:

1. compilation and unit tests;
2. PostgreSQL integration and migration tests;
3. formatting or Checkstyle and security-focused tests;
4. production artifact creation;
5. deployment from the canonical release branch to the independently managed
   host; and
6. a post-deployment health or smoke check.

The production deployment job uses a GitHub `production` environment with
environment secrets and an approval or branch protection gate where available.
Pull requests and untrusted forks never receive production or SoC LLM secrets.
The checked-in deployment workflow does not provision the first Administrator
bootstrap secrets. Production bootstrap therefore requires a separately
approved secret-store procedure; see `docs/AdministratorBootstrap.md`.

## Operations

- Production logs go to the hosting platform or another approved store and
  follow `.agents/security.md`, `.agents/anonymity-policy.md`, and
  `.agents/ai-policy.md`.
- Document database backup/restore, migration rollback or forward-fix policy,
  credential rotation, health checks, and provider-disable behavior.
- A failed deployment must not silently destroy the last working database or
  expose debug configuration.

## Release and submission

- `master` is the assessed release branch unless the teaching team confirms a
  different branch in writing.
- The public repository, GitHub Pages product site, deployed application, and
  tagged release must identify the same assessed commit.
- Verify the deployed application as Reporter, Responder, and Administrator,
  record evidence under `workflow/test-evidence/`, then freeze changes before
  the submission deadline.
- Codex/Claude build-and-host environments are not production deployment
  targets for this assignment.
