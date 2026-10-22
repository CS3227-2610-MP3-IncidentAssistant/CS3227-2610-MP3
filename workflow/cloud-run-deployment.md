# Cloud Run CI/CD specification

Status: **Implementation and external configuration prepared; review and application deployment evidence pending**

## Scope

This specification covers the GitHub Actions path that verifies the production
artifact, authenticates to Google Cloud, publishes it to Artifact Registry,
deploys it to Cloud Run in Singapore, and verifies `/health`. It does not
provision Neon, automate the one-time Administrator bootstrap, or claim release
approval.

## Actors and trust boundaries

- A repository contributor may run verification, but only an eligible `main`
  workflow run may enter the protected `production` environment. `master` is
  reserved for the separately approved submission operation.
- GitHub restricts the `production` environment to `main`. This school project
  intentionally has no required environment reviewer, so an eligible workflow
  does not pause for a second person to approve the job.
- GitHub OIDC authenticates the workflow to the WIF provider. The provider
  condition constrains the immutable repository ID, `main` ref, and
  `production` environment; the IAM binding uses the mapped repository ID and
  therefore supports both GitHub OIDC subject formats.
- The deployer service account publishes and deploys. A distinct runtime
  service account is attached to the Cloud Run revision.
- Pull requests and forked workflows receive no Google credential or production
  runtime secret.

## Configuration contract

The GitHub `production` environment supplies these non-secret variables:

- `GCP_PROJECT_ID`
- `GCP_REGION` (`asia-southeast1` for the approved topology)
- `GCP_ARTIFACT_REPOSITORY`
- `CLOUD_RUN_SERVICE`
- `GCP_WORKLOAD_IDENTITY_PROVIDER`
- `GCP_DEPLOY_SERVICE_ACCOUNT`
- `GCP_RUNTIME_SERVICE_ACCOUNT`

Sensitive runtime database, Object Storage, and SoCLaaS credential values are
stored in Google Secret Manager and exposed to Cloud Run through secret
references. Secret-backed environment variables pin a numeric enabled secret
version rather than `latest`. Non-secret runtime settings, including
`AI_ENABLED`, `SOCLAAS_MODEL`, `AI_CONFIGURATION_REVISION`, and any approved
downward-only AI limits, are configured directly on Cloud Run. They are not
GitHub environment variables or secrets. AI stays disabled until its separate
production enablement gate is approved, and bootstrap values remain a separate
procedure. The deployment workflow changes the image and declared deployment
metadata without printing, copying, or replacing runtime values.

## Failure and recovery

- Missing GitHub environment variables fail before OIDC authentication.
- A failed verification, authentication, push, or deployment stops the workflow.
- Cloud Run revisions are immutable and the deployed image is addressed by
  digest. A failed new revision must not destroy the last working revision or
  modify production data.
- The post-deployment check retries transient connection failures and requires
  `/health` to return JSON whose `status` is `UP`.
- Rollback is an explicit, human-approved redeployment of a previously verified
  image digest. Database migrations remain forward-only; image rollback must
  not assume schema rollback.

## Traceability

| Requirement | Acceptance criterion | Implementation | Planned evidence |
| --- | --- | --- | --- |
| `.agents/deployment.md` CI/CD 1–4 | CR-CD-01 | `ci.yml`, `deploy.yml`, `Dockerfile` | CI run and image digest |
| `.agents/deployment.md` CI/CD 5 | CR-CD-02, CR-CD-03 | `deploy.yml`, WIF/IAM setup | GitHub deployment and Google audit log |
| `.agents/deployment.md` CI/CD 6 | CR-CD-04 | `deploy.yml` health step | Successful deployed check |
| `.agents/security.md` configuration | CR-CD-02, CR-CD-05 | OIDC, environment variables, runbook | Settings review without values |
| `.agents/testing.md` deployment | CR-CD-01, CR-CD-04, CR-CD-06 | build, health, later role smoke tests | Automated and manual evidence |
| `.agents/ai-policy.md` provider configuration | CR-CD-05, CR-CD-08 | Secret Manager reference, Cloud Run settings, runbook | Sanitized service configuration and runtime-IAM review |

## Acceptance criteria

- [ ] **CR-CD-01:** A pull request can run compilation, frontend checks, unit
  tests, isolated PostgreSQL integration/migration tests, security-focused
  tests, and production container construction without any production or AI
  credentials.
- [ ] **CR-CD-02:** A non-`main` ref, untrusted fork, wrong repository,
  recreated repository with a different immutable ID, or job outside the
  `production` environment cannot obtain the Google deployment identity.
- [ ] **CR-CD-03:** An eligible `main` deployment authenticates without a
  service-account key, pushes a commit-tagged image, resolves its digest, and
  deploys that digest with the dedicated runtime service account.
- [ ] **CR-CD-04:** After deployment, the workflow discovers the Cloud Run URL
  and passes a bounded retry check proving `/health` returns status `UP`.
- [ ] **CR-CD-05:** Repository files, workflow logs, container layers, GitHub
  variables, and recorded evidence contain no production database, Object
  Storage, AI, bootstrap, session, or Google private credential.
- [ ] **CR-CD-06:** A human performs one end-to-end smoke path for Reporter,
  Responder, and Administrator against the exact deployed commit and records
  sanitized evidence.
- [ ] **CR-CD-07:** A failed candidate revision preserves the last working
  revision and production data; rollback/forward-fix follows the documented
  operator decision and does not reverse database migrations.
- [ ] **CR-CD-08:** The deployed service obtains `SOCLAAS_API_KEY` from a
  numeric-version Google Secret Manager reference accessible only to the
  runtime identity. Its non-secret AI switch, model, configuration revision,
  and approved limits are Cloud Run runtime settings, while GitHub contains
  neither the key nor application runtime configuration.

## Verification evidence

- Repository-only: pending after workflow and documentation validation.
- GitHub Actions: pending authenticated run.
- Manual/deployed: database and Object Storage runtime secret references are
  configured. The SoCLaaS secret reference and non-secret AI runtime settings,
  application image, Neon connectivity, `/health`, rollback, and three-role
  smoke verification are pending.
- Architecture/security reviewer: pending.
- Release approver/date: pending.
