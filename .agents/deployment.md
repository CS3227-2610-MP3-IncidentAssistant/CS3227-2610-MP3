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
5. deployment from `main`, the canonical day-to-day branch, to the
   independently managed host; and
6. a post-deployment health or smoke check.

The production deployment job uses a GitHub `production` environment with
non-secret deployment identifiers and a branch restriction allowing only
`main`. A required GitHub environment reviewer is optional; this repository
does not require one for routine school-project deployments. Pull requests and
untrusted forks never receive production or SoC LLM secrets.
The checked-in deployment workflow does not provision the first Administrator
bootstrap secrets. Production bootstrap therefore requires a separately
approved secret-store procedure; see `docs/AdministratorBootstrap.md`.

Application runtime configuration is owned by Cloud Run, not by the GitHub
Actions workflow. Store credential-bearing values such as `SOCLAAS_API_KEY` in
Google Secret Manager and expose them to the runtime service account through a
Cloud Run secret reference. Configure non-secret AI settings such as
`AI_ENABLED`, `SOCLAAS_MODEL`, `AI_CONFIGURATION_REVISION`, and approved
downward-only limits directly on Cloud Run. GitHub environment variables remain
limited to non-secret deployment identifiers; neither GitHub variables nor
GitHub secrets are the source of production application configuration.

## Operations

- Production logs go to the hosting platform or another approved store and
  follow `.agents/security.md`, `.agents/anonymity-policy.md`, and
  `.agents/ai-policy.md`.
- Document database backup/restore, migration rollback or forward-fix policy,
  credential rotation, health checks, and provider-disable behavior.
- A failed deployment must not silently destroy the last working database or
  expose debug configuration.

## Release and submission

- `main` is the routine production deployment branch. `master` remains the
  assessed submission branch and is updated only through the separately
  approved submission procedure in `AGENTS.md`.
- The public repository, GitHub Pages product site, deployed application, and
  tagged release must identify the same assessed commit.
- Verify the deployed application as Reporter, Responder, and Administrator,
  record evidence under `workflow/test-evidence/`, then freeze changes before
  the submission deadline.
- Codex/Claude build-and-host environments are not production deployment
  targets for this assignment.
