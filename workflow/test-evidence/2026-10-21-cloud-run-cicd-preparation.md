# Cloud Run CI/CD preparation evidence — 2026-10-21

## Scope

- Branch: `codex/cloud-run-cicd`
- Untouched base commit: `7c127a2b94b881c6ae7ef6df419eab9fff744fc4`
- Scope: repository workflow, keyless deployment decision/specification, and
  operator setup documentation only
- Production credentials or data used: none

This is pre-commit, repository-local evidence. It is not GitHub Actions,
deployed Cloud Run, architecture/security approval, or release evidence.

## Passed checks

1. `git diff --check`
   - Passed with no whitespace errors.
2. Ruby `YAML.parse_file('.github/workflows/deploy.yml')`
   - Passed; workflow YAML is syntactically valid.
3. Git for Windows Bash `bash -n` over every workflow `run` block
   - Passed for all embedded Bash scripts.
4. PowerShell parser over all seven `powershell` blocks in
   `docs/CloudRunDeployment.md`
   - Passed with no parser errors.
5. Referenced deployment document existence check
   - Passed.
6. `GRADLE_USER_HOME=C:\Users\seani\.gradle .\gradlew.bat build --offline`
   - Passed in 1 minute 9 seconds.
   - Frontend: production Vite build passed; 7 files and 72 tests passed.
   - Java/JUnit XML: 129 suites, 683 tests, 0 failures, 0 errors, 105 skipped.
   - JavaFX platform verification, shadow JAR verification, application checks,
     and final assembly passed.
   - Existing Shadow plugin duplicate Kotlin metadata warnings were emitted;
     they are unchanged by this documentation/workflow-only change.

## Failed or unavailable checks

- The first two build attempts did not reach compilation because the sandboxed
  workspace Gradle cache could not download a missing Maven artifact. The
  successful offline run used the already populated user Gradle cache.
- Docker/container build: unavailable because Docker is not installed or
  visible in this shell.
- Google Cloud CLI and live WIF/IAM validation: unavailable because `gcloud` is
  not installed or visible in this shell while local setup is in progress.
- GitHub environment/API validation: unavailable because `gh auth status`
  reports an invalid saved token; reauthentication is required.
- Isolated PostgreSQL tests: skipped by the normal build because no test-only
  PostgreSQL configuration was supplied. Production data was not accessed.
- OIDC token exchange, Artifact Registry push, Cloud Run deployment, deployed
  `/health`, rollback behavior, and three-role smoke tests: pending external
  setup, human approvals, and an approved release run.

## Criteria status

No criterion in `workflow/cloud-run-deployment.md` is ticked. The local results
support only part of CR-CD-01 and repository syntax portions of CR-CD-03 and
CR-CD-04. Each criterion still requires CI, external, deployed, or human
evidence described in that specification.

## External identity bootstrap

After commit `ec16071`, the repository owner authorized the Google Cloud and
GitHub identity bootstrap. The following sanitized state was created beginning
on 2026-10-21 and read back on 2026-10-22; no application deployment or
production-data access was performed:

- Google project `project-d78274d3-3ad4-431f-bc4` (`IncidentDesk`), project
  number `660566661066`, remained the selected active project.
- Existing Singapore resources were preserved: Artifact Registry repository
  `incidentdeskrepo` and Cloud Run service `incidentdesk-run`.
- IAM Credentials and Security Token Service APIs were enabled.
- Dedicated `incident-desk-deployer` and `incident-desk-runtime` service
  accounts were created. No service-account key was created.
- WIF pool `github-actions` and provider `incident-desk` were created. The
  provider condition requires immutable GitHub repository ID `1411389653`, ref
  `refs/heads/master`, and a subject ending in `environment:production`.
- The immutable repository principal may impersonate only the deployer service
  account. The deployer has Artifact Registry Writer only on
  `incidentdeskrepo`, Cloud Run Admin only on `incidentdesk-run`, and Service
  Account User only on the runtime service account. It has no project-level
  Cloud Run role.
- GitHub environment `production` was created with a custom deployment branch
  policy allowing only `master`. Seven non-secret workflow variables were set;
  no Google credential or application secret was stored in GitHub.

Required reviewer selection remains pending a human team decision. OIDC token
exchange, image publication, deployment, health verification, rollback, and
three-role smoke evidence also remain pending, so no acceptance criterion is
ticked.

### Branch-policy correction — 2026-10-22

The repository user clarified that routine production deployment must use
`main`; `master` remains reserved for the final assignment submission snapshot
described in `AGENTS.md`. The original bootstrap state above accurately records
the initial `master` restriction. The specification, workflow, WIF provider,
and GitHub production environment were then changed together to require
`refs/heads/main`. No application deployment was triggered by this correction.
