# Cloud Run pipeline verification evidence — 2026-10-23

## Scope

- Repository commit under review: `c5c9db2` (`Specify AI runtime configuration
  ownership for Cloud Run`)
- Deployment evidence commit: `2982ca8`, GitHub Actions deploy run
  `39839379339`, completed 2026-10-22T19:32:54Z
- Pull-request CI evidence: run `39836905404` (commit `2ccb323b`), all jobs
  successful
- Purpose: verify `CR-CD-01` through `CR-CD-08` from recorded pipeline,
  repository, and deployed-service evidence
- No application code, workflow, or production configuration was changed while
  collecting this evidence. All Google Cloud commands were read-only.

## Criteria verified

### CR-CD-01 — pull-request verification without production credentials

- Run `39836905404` (`pull_request` event) completed with all four jobs
  successful: `Build and test`, and `Attachments and packaging` on
  `windows-latest/x64`, `macos-latest/arm64`, and `macos-latest/x64`.
- `ci.yml` provisions an isolated `postgres:18-alpine` service with
  `INCIDENT_DESK_TEST_DATABASE_URL` pointing at `localhost:5432` and
  `INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED=true`.
- `./gradlew build` covers compilation, frontend verification, unit tests, and
  the PostgreSQL-backed security, authorization, and migration suites,
  including `AdministratorIncidentSecurityTest`, `ReporterWebSecurityTest`,
  `ReporterAccountRegistrationHttpSecurityTest`, the
  `application/authorization` policy tests,
  `AdministratorStatisticsMigrationUpgradePostgresTest`, and
  `AiMigrationUpgradePostgresTest`.
- Production container construction is verified by the `Verify production
  artifact` job's `docker build` step in `deploy.yml`, which runs on the same
  commit without entering the `production` environment.
- No production or AI credential was reachable: `ci.yml` declares no
  `environment: production`, and `gh secret list` returns no secrets at either
  repository or `production` environment scope.

### CR-CD-02a — a non-`main` ref cannot reach the deployment job

Proved by execution. `deploy.yml` triggers only on `push` to `main` and on
`workflow_dispatch`, and its `deploy` job carries
`if: github.ref == 'refs/heads/main'`. A push to any other branch therefore
starts no run at all, and a `workflow_dispatch` on a non-`main` ref starts the
run but skips `deploy` before any credential is requested.

- Executed denial: run `39851817031`, `workflow_dispatch` on branch
  `evidence/cr-cd-02a`, 2026-10-22T20:47:04Z. Run conclusion `success`;
  `Verify production artifact` concluded `success` and `Deploy to Cloud Run`
  concluded `skipped`. No `Authenticate to Google Cloud` step ran, so no OIDC
  token was minted and none was presented to Google.
- Also observed: pushing branch `evidence/cr-cd-02a` triggered no workflow run
  at all (`gh run list --branch evidence/cr-cd-02a` returned empty), because
  `deploy.yml` triggers only on `push` to `main` and `workflow_dispatch`, and
  `ci.yml` only on `main`/`develop`. The dispatch above was therefore needed
  to exercise the `if:` guard itself.
- Scope limit: this proves the GitHub-side ref guard. The Workload Identity
  condition's own `assertion.ref` pin was not exercised, since the run never
  reached authentication.
- The skip happens in GitHub before the `production` environment is entered,
  so this case is denied independently of the Workload Identity condition
  that covers CR-CD-02b.

### CR-CD-02b — fork, wrong repository, recreated repository, and non-`production` job

Verified 2026-10-23 from live read-only configuration capture. All three
enforcement layers pin the same immutable GitHub repository ID. **Not ticked:
this is configuration assertion, not an executed denial.**

- Workload Identity provider
  `workloadIdentityPools/github-actions/providers/incident-desk` is `ACTIVE`,
  issuer `https://token.actions.githubusercontent.com/`, attribute condition:
  `assertion.repository_id == '<REPO_ID>' && assertion.ref ==
  'refs/heads/main' && assertion.sub.endsWith(':environment:production')`.
  Mapping exposes `google.subject`, `attribute.repository_id`, and
  `attribute.ref` only.
- The deployer service account's IAM policy contains exactly one binding,
  `roles/iam.workloadIdentityUser` for
  `principalSet://.../attribute.repository_id/<REPO_ID>`. No wildcard
  `principalSet://.../*` and no `principal://` member exists.
- The project IAM policy contains no `principalSet`, `principal://`,
  `allUsers`, or `allAuthenticatedUsers` binding, so no broader grant bypasses
  the pinned binding. The pool contains exactly one provider.
- GitHub: the repository is not a fork and has 0 forks; its immutable ID
  matches the pinned value. The `production` environment carries a
  `branch_policy` protection rule whose only admitted branch is `main`.
  `gh secret list` returns no secrets at repository or `production` scope, so a
  forked or non-`main` workflow receives no credential. `deploy.yml` guards the
  deploy job with `if: github.ref == 'refs/heads/main'` and
  `environment: production`.

Case coverage:

| Ineligible context | Basis | Status |
| --- | --- | --- |
| Wrong repository | Provider condition and SA binding both pin the repository ID | Asserted |
| Recreated repository, different ID | ID pinned numerically, not as `owner/name`; a recreated repository receives a new ID and fails both layers | Asserted |
| Untrusted fork | No repository- or environment-scoped secrets; fork `pull_request` runs receive no `id-token` write and no environment access; ID also pinned | Asserted |
| Job outside `production` | `assertion.sub.endsWith(':environment:production')` | Asserted |
| Non-`main` ref | Provider condition pins `refs/heads/main`; environment branch policy admits `main` only; workflow `if:` guard | **Proved** — see CR-CD-02a |

Scope limit: the four contexts above are configuration assertion, not executed
denials. No token from an ineligible context was presented to Google, so no
Google-side denial appears in the audit log. Executing one would require a
throwaway fork or a second repository with a different immutable ID, and
executing the non-`production` case would require loosening the workflow guard
or widening the `production` branch policy; none was done. The condition is
evaluated by Google on every token exchange. CR-CD-02b stays unticked until an
executed denial exists.

Trust note: the criterion addresses contexts outside this repository, so it is
not discharged by trusting repository contributors. Separately, and as `cloud-run-deployment.md`
states by design, a `workflow_dispatch` on `main` by any contributor with write
access reaches `production` with no required reviewer; CR-CD-02 constrains
ref, fork, repository, and environment, not which trusted actor triggers an
eligible `main` run.

### CR-CD-03 — keyless authentication, commit-tagged image, digest deployment

- `Authenticate to Google Cloud` used `google-github-actions/auth@v3` with
  `workload_identity_provider` and `service_account` only, no service-account
  key, issuing a credentials file with a 3600s access-token lifetime.
- The image was pushed tagged with the commit SHA `2982ca80...` and its digest
  resolved to
  `sha256:9fb53a2c563a9220fd6d51c7451fe99a7f1bb7c9b8c5e1681e1fb37525a3fc76`.
- `gcloud run deploy` deployed that digest by immutable reference with
  `--service-account incident-desk-runtime@...`, labelled
  `managed-by=github-actions,commit-sha=2982ca80...`.
- The live service's serving revision runs under the `incident-desk-runtime`
  service account, confirmed by read-only describe.

### CR-CD-04 — deployed health verification

- `Deploy immutable revision` discovered the service URL via
  `gcloud run services describe --format='value(status.url)'` and asserted it
  non-empty.
- `Verify deployed health endpoint` performed a bounded retry check
  (`curl --fail --silent --show-error --retry 5 --retry-all-errors
  --retry-delay 5`) and gated on `jq -e '.status == "UP"'`.
- The step logged `Verified health for commit
  2982ca80500d2462d1e84996dce43df24298d2c1`.

## Supporting scans (CR-CD-05)

These are recorded review conclusions, not reproducible scanner output: the
commands, tool versions, scanned commit, and match counts were not captured,
and the workflow-log and recorded-evidence artifact sets named by the criterion
are not covered by a mechanical scan. CR-CD-05 is therefore unticked. Closing
it requires a scanner (for example `gitleaks`/`trufflehog`) run against tracked
files, full history, the deployed image layers and jar, both reviewed run logs
via `gh run view --log`, and `workflow/` itself, with commands and output
recorded.

1. Tracked-file credential scan
   - Every match is a synthetic test fixture (`synthetic-web-test`,
     `synthetic-reporter-test`, `synthetic-responder-test`,
     `plain-text-registration-secret`) or a variable name. No real credential
     is present.
2. Full-history scan (`git log --all`)
   - No `.env`, `.env.local`, `.env.prod`, or `.env.production` file was ever
     added. Remaining matches are variable names, validation messages, and
     test fixtures.
   - `.gitignore` excludes `.env` and `.env.*` while allowing only
     `.env.example` and `.env.test.example`, both of which carry placeholders.
3. Container-layer review
   - `Dockerfile` declares no `ENV`, `ARG`, or `RUN` carrying a key, secret,
     password, token, or database URL.
   - The runtime stage copies only `build/libs/incident-desk-web.jar`.
   - `.dockerignore` excludes `.git`, `logs`, `workflow/test-evidence`, and
     `gha-creds-*.json`.
4. GitHub configuration audit
   - The `production` environment exposes exactly the seven documented
     non-secret identifiers: `CLOUD_RUN_SERVICE`, `GCP_ARTIFACT_REPOSITORY`,
     `GCP_DEPLOY_SERVICE_ACCOUNT`, `GCP_PROJECT_ID`, `GCP_REGION`,
     `GCP_RUNTIME_SERVICE_ACCOUNT`, `GCP_WORKLOAD_IDENTITY_PROVIDER`.
   - No repository-level variables and no secrets at any scope exist.
   - GitHub therefore contains neither the provider key nor application runtime
     configuration.
5. Deploy-log review
   - The reviewed deploy run printed no secret value. Only resource names,
     service-account identifiers, the project ID, and image digests appear.

## Findings that block criteria

1. `AI_ENABLED=true` on the deployed service initially contradicted the
   specifications. **Resolved 2026-10-23 by operator decision.**
   - `workflow/cloud-run-deployment.md` and `docs/CloudRunDeployment.md`
     previously required AI to remain disabled until a separate production
     enablement gate was approved, while the deployed service already had it
     enabled.
   - The operator directed that the specifications be updated to match the
     enabled service. The decision, its basis, and its limits are recorded in
     `reviews/2026-10-23-production-ai-enablement.md`, which authorizes the
     setting but is explicitly not an architecture/security review or release
     approval.
2. Three of four secret-backed environment variables resolve `:latest` rather
   than a pinned numeric version.
   - `DATABASE_URL` -> `incidentdesk-database-url:latest`
   - `AWS_ACCESS_KEY_ID` -> `incidentdesk-aws-access-key-id:latest`
   - `AWS_SECRET_ACCESS_KEY` -> `incidentdesk-aws-secret-access-key:latest`
   - Only `SOCLAAS_API_KEY` -> `incidentdesk-soclaas-api-key:1` complies with
     the numeric-version rule introduced in commit `c5c9db2`.
   - Each of the three secrets currently has exactly one enabled version (`1`),
     so `:latest` resolves to `:1` today and no runtime behaviour differs. The
     residual risk is future-rotation only, not a present outage.
   - **Resolved 2026-10-23 as an accepted deviation.** The operator declined
     numeric pinning for these three because no rotation is planned. The
     numeric-version rule in `cloud-run-deployment.md` was narrowed to
     `SOCLAAS_API_KEY`, which complies, so no artifact now states a rule that
     production violates. The acceptance is recorded in
     `reviews/2026-10-23-production-ai-enablement.md`.
3. `user:Pokerus8893@gmail.com` holds project-level `roles/owner`.
   - Secret-level IAM on `incidentdesk-soclaas-api-key` grants
     `roles/secretmanager.secretAccessor` to the runtime service account only.
   - Project-level ownership nonetheless confers read access to every secret.
     This is a disclosure-surface decision for a human security reviewer, not a
     repository defect.
   - **Does not block CR-CD-05.** Corrected 2026-10-23: this finding was
     previously recorded as that criterion's blocker. CR-CD-05 scopes to
     credentials appearing in repository files, workflow logs, container
     layers, GitHub variables, and recorded evidence. A live Google Cloud IAM
     grant is none of those, so it cannot be discharged or violated by that
     criterion. It remains open for the architecture/security review.

## Criteria verified by operator attestation

- `CR-CD-06`: not ticked. Recorded as an operator attestation on 2026-10-23. The operator
  states they performed the end-to-end smoke path for Reporter, Responder, and
  Administrator against the deployed service, and separately that they
  searched the repository thoroughly for credential exposure.
  - Scope of this record: the attestation is the operator's own statement,
    relayed here at their instruction. The agent did not observe, script, or
    reproduce the smoke path, and no screenshots, request logs, timestamps, or
    per-role step results were captured.
  - Not established by this attestation: which deployed commit or revision the
    smoke path ran against, which specific actions were exercised per role, or
    whether denied paths were checked. CR-CD-06 asks for evidence "against the
    exact deployed commit"; that linkage is unrecorded.
  - A reviewer who needs reproducible evidence should treat this as weaker
    than the executed and configuration-captured criteria above, and may ask
    the operator to repeat the walk with the revision ID and sanitized
    per-role results recorded.
  - Because the criterion requires the smoke path be tied to the exact deployed
    commit, and that linkage is unrecorded, CR-CD-06 is unticked in
    `cloud-run-deployment.md`.
## Criteria not verified

- `CR-CD-02b`: see the scope limit above. Fork, wrong-repository,
  recreated-repository, and non-`production` denials rest on configuration
  assertion; none has been executed against Google.
- `CR-CD-07`: 15 Cloud Run revisions exist, so a rollback target is available,
  but no failed-revision or rollback drill has been performed and no operator
  decision record exists.
- `CR-CD-08`: the SoCLaaS key satisfies the criterion's secret-reference,
  runtime-identity, and configuration-split requirements, and finding 1 is now
  resolved. The criterion stays unticked solely because the
  ordinary-subsequent-image-deployment preservation test has not been
  performed. That test redeploys the current digest and confirms every
  environment entry survives. The baseline for comparison is revision
  `incidentdesk-run-00015-fdz`: 27 environment entries, fingerprint
  `64bfb164bc27465c`.
  - The operator authorized this redeployment on 2026-10-23, but it could not
    be executed: the agent's sandbox denies `gcloud run deploy` against the
    production service as a matter of policy, independently of operator
    consent. No deployment was attempted or performed, and the service remains
    on revision `incidentdesk-run-00015-fdz`.
  - To close this criterion, a human operator runs the redeployment of the
    current digest and compares the resulting revision's environment entries
    against the baseline above, or the next ordinary `main` deployment is used
    as the evidence instead.

## Approval status

- Architecture/security reviewer: pending.
- Release approver/date: pending.
- No human approval is claimed or recorded by this evidence record.
