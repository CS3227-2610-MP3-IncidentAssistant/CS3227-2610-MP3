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
references. The `SOCLAAS_API_KEY` reference pins a numeric enabled secret
version rather than `latest`, so an ordinary image deployment cannot silently
adopt a newly added key version. Non-secret runtime settings, including
`AI_ENABLED`, `SOCLAAS_MODEL`, `AI_CONFIGURATION_REVISION`, and any approved
downward-only AI limits, are configured directly on Cloud Run. They are not
GitHub environment variables or secrets. AI is enabled in production under the
recorded operator authorization in
`reviews/2026-10-23-production-ai-enablement.md`; `AI_ENABLED=false` remains the
fail-safe an operator sets if AI verification fails. Bootstrap values remain a
separate procedure. The deployment workflow changes the image and declared deployment
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
| `.agents/deployment.md` CI/CD 5 | CR-CD-02a, CR-CD-02b, CR-CD-03 | `deploy.yml`, WIF/IAM setup | GitHub deployment and Google audit log |
| `.agents/deployment.md` CI/CD 6 | CR-CD-04 | `deploy.yml` health step | Successful deployed check |
| `.agents/security.md` configuration | CR-CD-02a, CR-CD-02b, CR-CD-05 | OIDC, environment variables, runbook | Settings review without values |
| `.agents/testing.md` deployment | CR-CD-01, CR-CD-04, CR-CD-06 | build, health, later role smoke tests | Automated and manual evidence |
| `.agents/ai-policy.md` provider configuration | CR-CD-05, CR-CD-08 | Secret Manager reference, Cloud Run settings, runbook | Sanitized service configuration and runtime-IAM review |

## Acceptance criteria

- [x] **CR-CD-01:** A pull request can run compilation, frontend checks, unit
  tests, isolated PostgreSQL integration/migration tests, security-focused
  tests, and production container construction without any production or AI
  credentials.
- [x] **CR-CD-02a:** A non-`main` ref cannot reach the deployment job. The
  `deploy` job is guarded by `if: github.ref == 'refs/heads/main'`, so no
  Google token is requested from any other ref. Proved by executed run
  `39851817031`.
- [ ] **CR-CD-02b:** An untrusted fork, wrong repository, recreated repository
  with a different immutable ID, or job outside the `production` environment
  cannot obtain the Google deployment identity. Verified by read-only
  configuration assertion only; no denial has been executed against Google.
- [x] **CR-CD-03:** An eligible `main` deployment authenticates without a
  service-account key, pushes a commit-tagged image, resolves its digest, and
  deploys that digest with the dedicated runtime service account.
- [x] **CR-CD-04:** After deployment, the workflow discovers the Cloud Run URL
  and passes a bounded retry check proving `/health` returns status `UP`.
- [ ] **CR-CD-05:** Repository files, workflow logs, container layers, GitHub
  variables, and recorded evidence contain no production database, Object
  Storage, AI, bootstrap, session, or Google private credential. Unticked: the
  recorded scans state conclusions rather than reproducible scanner output, and
  the workflow-log and recorded-evidence artifact sets named by the criterion
  are not covered by a mechanical scan.
- [ ] **CR-CD-06:** A human performs one end-to-end smoke path for Reporter,
  Responder, and Administrator against the exact deployed commit and records
  sanitized evidence. Unticked: an operator attestation of 2026-10-23 exists,
  but the deployed-commit linkage, the per-role results, and the sanitized
  evidence the criterion requires are unrecorded.
- [ ] **CR-CD-07:** A failed candidate revision preserves the last working
  revision and production data; rollback/forward-fix follows the documented
  operator decision and does not reverse database migrations.
- [ ] **CR-CD-08:** The deployed service obtains `SOCLAAS_API_KEY` from a
  numeric-version Google Secret Manager reference accessible only to the
  runtime identity. Its non-secret AI switch, model, configuration revision,
  and approved limits are Cloud Run runtime settings, while GitHub contains
  neither the key nor application runtime configuration. Unticked: the
  secret-reference, runtime-identity, and configuration-split requirements are
  captured, but the ordinary-subsequent-image-deployment preservation test has
  not been performed.

## Verification evidence

- Repository-only: reviews of tracked files, full history, container layers,
  and GitHub configuration found no credential exposure; recorded in
  `test-evidence/2026-10-23-cloud-run-pipeline-verification.md`. These are
  recorded conclusions, not reproducible scanner output, and the workflow-log
  and recorded-evidence artifact sets are not mechanically scanned, so CR-CD-05
  stays unticked.
- GitHub Actions: pull-request run `39836905404` and `main` deploy run
  `39839379339` (commit `2982ca8`) verify CR-CD-01, CR-CD-03, and CR-CD-04.
- Google/GitHub configuration: the Workload Identity provider condition, the
  deployer service-account binding, the absence of broader project-level
  grants, and the `production` branch policy and secret scoping support
  CR-CD-02b by read-only assertion. No denial has been executed against
  Google, so CR-CD-02b remains unticked; see the scope limit in
  `test-evidence/2026-10-23-cloud-run-pipeline-verification.md`. CR-CD-02a is
  proved by an executed run recorded in the same file.
- Manual/deployed: database, Object Storage, and SoCLaaS runtime secret
  references are configured, and the deployed `/health` endpoint returns `UP`.
  AI is enabled under `reviews/2026-10-23-production-ai-enablement.md`, which
  also records the accepted `:latest` deviation for the database and Object
  Storage secrets. Rollback, three-role smoke verification, and the
  configuration-preservation redeployment are pending.
- Operator attestation: the operator states they completed the three-role
  end-to-end smoke path and a thorough repository credential search on
  2026-10-23. Recorded, with its limits, in
  `test-evidence/2026-10-23-cloud-run-pipeline-verification.md`.
- Architecture/security reviewer: pending. The project-level `roles/owner`
  disclosure surface recorded in the evidence file is a live IAM observation
  for that review; it is outside CR-CD-05, which scopes to credentials present
  in repository files, workflow logs, container layers, GitHub variables, and
  recorded evidence.
- Release approver/date: pending.
