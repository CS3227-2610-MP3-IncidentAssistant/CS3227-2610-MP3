# AI runtime configuration preparation evidence — 2026-10-23

## Scope

- Branch: `main`
- Scope: specify and document where production SoCLaaS credentials and
  non-secret AI runtime settings belong
- Production credential source: the user explicitly authorized using the
  private `.env.local` SoCLaaS key and model; the key value was streamed to
  Secret Manager through standard input and was never printed or added to a
  command argument
- External resources changed: one Secret Manager secret/version, its
  secret-scoped runtime IAM binding, and one Cloud Run revision

## Traceability

| Requirement | Criterion | Repository change | Evidence |
| --- | --- | --- | --- |
| `.agents/deployment.md` runtime configuration | `CR-CD-08` | Cloud Run owns non-secret settings; Secret Manager owns the key | Specification and runbook review |
| `.agents/ai-policy.md` gateway configuration | `AI-GW-05` | Numeric secret-version reference and runtime-only IAM boundary | Specification and runbook review |
| `.agents/security.md` secret handling | `CR-CD-05`, `AI-GW-03` | No key in GitHub, image, command arguments, or evidence | Scoped diff and secret-free documentation review |

## Repository checks

1. `git diff --check`
   - Passed with no whitespace errors.
2. PowerShell parser over all fenced PowerShell blocks in
   `docs/CloudRunDeployment.md`
   - Passed for all 10 blocks.
3. Scoped diff review
   - The changes contain names and placeholders only; no provider key, model
     value, production URL, or other runtime credential was added.

## Google Cloud configuration and verification

- Google Cloud CLI 590.0.0 was found at its installed absolute path; it was not
  inherited into this shell's `PATH`.
- The active authenticated project is the expected Incident Desk project.
- Cloud Run service `incidentdesk-run` in `asia-southeast1` uses the dedicated
  `incident-desk-runtime` service account.
- Secret `incidentdesk-soclaas-api-key` version `1` was created and is enabled.
- The runtime service account received
  `roles/secretmanager.secretAccessor` on that secret only.
- Cloud Run revision `incidentdesk-run-00014-kj4` serves 100% of traffic.
- `SOCLAAS_API_KEY` references numeric secret version `1`; the configured model
  and configuration revision match the private source without printing either
  value.
- `AI_ENABLED=true`; all 19 expected AI setting names are present.
- An unauthenticated request to the deployed `/health` endpoint returned `UP`.
- Secret payloads and unrelated runtime values were not printed.

## Provider smoke evidence

1. Initial focused Gradle invocation
   - Did not reach the provider test because `npm ci` received `EPERM` while
     trying to replace the active Rolldown native module in
     `frontend/node_modules`.
2. `./gradlew.bat test -PfrontendSkipBuild=true --tests
   'com.company.incidentdesk.infrastructure.soclaas.SoclaasLiveSmokeTest'
   --rerun-tasks --console=plain`
   - Passed in 23 seconds.
   - The opt-in synthetic test authenticated to the provider, found the
     configured model, and completed one bounded non-blank synthetic request.
   - It did not persist an incident or print the provider response or key.

## Unavailable and pending evidence

- GitHub CLI authentication is expired, but no GitHub change is required for
  this runtime configuration split.
- `CR-CD-05`, `CR-CD-08`, `AI-GW-03`, and `AI-GW-05` remain unticked because
  a complete GitHub settings audit, project-level IAM review, ordinary
  subsequent image-deployment preservation test, history/image/log scan, and
  role-level deployed AI paths have not all been verified.
- The user explicitly instructed the operator to enable AI before public
  release. This records authorization for this configuration action only; it
  is not recorded as independent architecture/security review or release
  approval.
- Despite not being advertised, the Cloud Run service is technically reachable
  from the internet: the health check succeeded without authentication and the
  deployment workflow configures unauthenticated invocation. Role and
  application authentication still govern protected application routes.

No executable application code or checked-in deployment workflow was changed.
