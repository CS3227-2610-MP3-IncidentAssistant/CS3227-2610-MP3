# Cloud Run deployment setup

This runbook prepares the approved GitHub Actions to Google Cloud Run path. It
uses GitHub OIDC, Google Workload Identity Federation, and two Google service
accounts. It intentionally creates no service-account key.

Run these commands only from an authenticated administrator workstation. Review
every placeholder before execution. Resource creation can incur Google Cloud
charges; configure a budget and alerts before the first deployment.

## 1. Finish the local CLIs

Restart the terminal after installing Google Cloud CLI, then verify and sign in:

```powershell
gcloud version
gcloud auth login
gcloud auth list
```

GitHub CLI is installed, but authenticate it for this repository if
`gh auth status` reports an invalid token:

```powershell
gh auth login --hostname github.com --web --git-protocol https
gh auth status
```

## 2. Choose the deployment names

Use the existing Google Cloud project if the team has already created one.
Singapore Cloud Run is `asia-southeast1`.

```powershell
$GcpProjectId = 'REPLACE_WITH_PROJECT_ID'
$GcpRegion = 'asia-southeast1'
$ArtifactRepository = 'incidentdeskrepo'
$CloudRunService = 'incidentdesk-run'
$DeployServiceAccountId = 'incident-desk-deployer'
$RuntimeServiceAccountId = 'incident-desk-runtime'
$WorkloadPoolId = 'github-actions'
$WorkloadProviderId = 'incident-desk'
$GitHubRepository = 'CS3227-2610-MP3-IncidentAssistant/CS3227-2610-MP3'

gcloud config set project $GcpProjectId
$ProjectNumber = gcloud projects describe $GcpProjectId --format='value(projectNumber)'
$GitHubRepositoryId = gh api "repos/$GitHubRepository" --jq '.id'
$DeployServiceAccount = "$DeployServiceAccountId@$GcpProjectId.iam.gserviceaccount.com"
$RuntimeServiceAccount = "$RuntimeServiceAccountId@$GcpProjectId.iam.gserviceaccount.com"
```

Stop if either numeric ID is empty. The GitHub repository ID is deliberately
used in the trust condition because it does not change when a repository is
renamed and is not inherited if a same-named repository is recreated.

## 3. Enable APIs and create identities

```powershell
gcloud services enable run.googleapis.com artifactregistry.googleapis.com iamcredentials.googleapis.com sts.googleapis.com

gcloud iam service-accounts create $DeployServiceAccountId --display-name='Incident Desk GitHub deployer'
gcloud iam service-accounts create $RuntimeServiceAccountId --display-name='Incident Desk Cloud Run runtime'

gcloud artifacts repositories create $ArtifactRepository --repository-format=docker --location=$GcpRegion --description='Incident Desk production images'
```

If a command reports that the resource already exists, describe it and verify
its project, region, and purpose before continuing. Do not silently reuse an
unrelated identity or repository.

## 4. Create the GitHub OIDC trust

```powershell
gcloud iam workload-identity-pools create $WorkloadPoolId --location=global --display-name='GitHub Actions'

$AttributeMapping = 'google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.ref=assertion.ref'
$AttributeCondition = "assertion.repository_id == '$GitHubRepositoryId' && assertion.ref == 'refs/heads/main' && assertion.sub.endsWith(':environment:production')"

gcloud iam workload-identity-pools providers create-oidc $WorkloadProviderId `
  --location=global `
  --workload-identity-pool=$WorkloadPoolId `
  --display-name='Incident Desk production deploys' `
  --issuer-uri='https://token.actions.githubusercontent.com' `
  --attribute-mapping=$AttributeMapping `
  --attribute-condition=$AttributeCondition

$WorkloadProvider = gcloud iam workload-identity-pools providers describe $WorkloadProviderId `
  --location=global `
  --workload-identity-pool=$WorkloadPoolId `
  --format='value(name)'

$ProductionRepositoryPrincipal = "principalSet://iam.googleapis.com/projects/$ProjectNumber/locations/global/workloadIdentityPools/$WorkloadPoolId/attribute.repository_id/$GitHubRepositoryId"

gcloud iam service-accounts add-iam-policy-binding $DeployServiceAccount `
  --role='roles/iam.workloadIdentityUser' `
  --member=$ProductionRepositoryPrincipal
```

The provider condition rejects other repositories, refs, and GitHub
environments. The binding uses the immutable repository ID, so it works whether
GitHub emits its legacy name-based OIDC subject or the immutable subject format
introduced for newer repositories.

## 5. Grant deployment permissions

```powershell
gcloud artifacts repositories add-iam-policy-binding $ArtifactRepository `
  --location=$GcpRegion `
  --member="serviceAccount:$DeployServiceAccount" `
  --role='roles/artifactregistry.writer'

gcloud run services add-iam-policy-binding $CloudRunService `
  --project=$GcpProjectId `
  --region=$GcpRegion `
  --member="serviceAccount:$DeployServiceAccount" `
  --role='roles/run.admin'

gcloud iam service-accounts add-iam-policy-binding $RuntimeServiceAccount `
  --member="serviceAccount:$DeployServiceAccount" `
  --role='roles/iam.serviceAccountUser'
```

Do not grant Owner, Editor, project-wide Cloud Run Admin, or service-account-key
administration. The deployer may administer only the existing
`incidentdesk-run` service. The runtime identity needs no Google project role
while the application uses only Neon PostgreSQL and Neon Object Storage.

## 6. Configure the protected GitHub environment

Create the environment and its non-secret variables with GitHub CLI:

```powershell
gh api --method PUT "repos/$GitHubRepository/environments/production"

gh variable set GCP_PROJECT_ID --env production --body $GcpProjectId
gh variable set GCP_REGION --env production --body $GcpRegion
gh variable set GCP_ARTIFACT_REPOSITORY --env production --body $ArtifactRepository
gh variable set CLOUD_RUN_SERVICE --env production --body $CloudRunService
gh variable set GCP_WORKLOAD_IDENTITY_PROVIDER --env production --body $WorkloadProvider
gh variable set GCP_DEPLOY_SERVICE_ACCOUNT --env production --body $DeployServiceAccount
gh variable set GCP_RUNTIME_SERVICE_ACCOUNT --env production --body $RuntimeServiceAccount
```

In **GitHub → Settings → Environments → production**:

1. allow routine deployments only from `main`;
2. leave required reviewers empty for this school project; and
3. confirm that no Google JSON key or production application secret is stored
   in repository-level Actions settings.

An environment reviewer would approve whether a particular workflow job may
enter the GitHub environment; they do not approve or edit environment
variables. This repository instead uses the `main` branch restriction as its
technical gate. The names above are non-secret identifiers. OIDC creates a
short-lived credential only for an eligible job.

## 7. Configure runtime secrets separately

Before the first real application deployment, store the production
`DATABASE_URL`, Object Storage credentials, and `SOCLAAS_API_KEY` in Google
Secret Manager and configure Cloud Run to reference them. Do not put these
values in the GitHub environment. Set non-secret production values directly on
Cloud Run as well:

- `INCIDENT_DESK_NEON_REQUIRED=true`
- `INCIDENT_DESK_SECURE_COOKIES=true`
- `INCIDENT_DESK_ATTACHMENT_BUCKET=incident-attachments`
- `AI_ENABLED=true`, enabled on 2026-10-23 under the authorization recorded in
  `workflow/reviews/2026-10-23-production-ai-enablement.md`; set `false` to
  fail AI safely without touching incident management
- `SOCLAAS_MODEL` to the approved production model
- `AI_CONFIGURATION_REVISION=1`, incremented after a key/model repair or
  rotation

The split is intentional: GitHub knows how to deploy, Secret Manager owns
credentials, and Cloud Run owns application runtime configuration. The workflow
deploys a new image without passing any of these values, so later revisions
inherit the service configuration unless an operator explicitly changes it.

### Configure the SoCLaaS key and AI settings

Create `incidentdesk-soclaas-api-key` in **Google Cloud Console → Security →
Secret Manager** and add the production key as its first version. Do not reuse a
development/test credential, paste the key into a shell argument, or capture the
value in screenshots. Record only the numeric enabled version shown by Secret
Manager.

Then attach that version and the non-secret settings from the authenticated
administrator workstation. These commands leave AI disabled so that the secret
reference and settings can be confirmed before any provider call is possible:

```powershell
$AiSecretName = 'incidentdesk-soclaas-api-key'
$AiSecretVersion = 'REPLACE_WITH_NUMERIC_ENABLED_VERSION'
$SoclaasModel = 'REPLACE_WITH_APPROVED_MODEL'
$AiConfigurationRevision = '1'

gcloud secrets add-iam-policy-binding $AiSecretName `
  --project=$GcpProjectId `
  --member="serviceAccount:$RuntimeServiceAccount" `
  --role='roles/secretmanager.secretAccessor'

gcloud run services update $CloudRunService `
  --project=$GcpProjectId `
  --region=$GcpRegion `
  --update-secrets="SOCLAAS_API_KEY=$AiSecretName`:$AiSecretVersion" `
  --update-env-vars="AI_ENABLED=false,SOCLAAS_MODEL=$SoclaasModel,AI_CONFIGURATION_REVISION=$AiConfigurationRevision"
```

Read the service back without accessing the secret value:

```powershell
gcloud run services describe $CloudRunService `
  --project=$GcpProjectId `
  --region=$GcpRegion `
  --format='yaml(spec.template.spec.serviceAccountName,spec.template.spec.containers[0].env)'
```

Confirm that `SOCLAAS_API_KEY` is a secret reference to the intended numeric
version, the runtime service account is correct, and `AI_ENABLED` is still
`false`. Never put the describe output in public evidence if it contains other
configuration that has not been reviewed for disclosure.

Enable AI only once its specification/security approvals, production model and
quota checks, and an operator authorization record exist. For the current
production service this was done on 2026-10-23 under
`workflow/reviews/2026-10-23-production-ai-enablement.md`; repeat this step only
when re-enabling after a fail-safe disable or when configuring a new service:

```powershell
gcloud run services update $CloudRunService `
  --project=$GcpProjectId `
  --region=$GcpRegion `
  --update-env-vars='AI_ENABLED=true'
```

Verify startup, `/health`, one authorized AI path per role, denial paths, safe
logs/audit metadata, and the non-AI fallback before recording deployed evidence.
If verification fails, set `AI_ENABLED=false` immediately; do not remove or
alter incident-management configuration to make AI start.

For rotation, add a new Secret Manager version, update the Cloud Run reference
to that numeric version, and increment `AI_CONFIGURATION_REVISION`. Verify the
new revision before disabling the old secret version. Google recommends Secret
Manager for Cloud Run credentials and recommends pinning environment-variable
secret references to a version rather than `latest`.

Do not paste secret values into `gcloud` command arguments, GitHub variables,
tickets, screenshots, or evidence. The first Administrator bootstrap remains a
separate, approval-gated procedure in `AdministratorBootstrap.md`.

## 8. Verify without releasing

Push this feature branch and open a pull request to `main`. Its normal CI must
pass without production access. A manual run of `deploy.yml` from any branch
other than `main` runs verification only; the deploy job is skipped.

The first production deployment is allowed only after the repository's human
architecture/security and release gates are recorded. Routine production
deployments come from `main`. At assignment-submission time, the separately
approved process moves the exact verified `main` commit to `master`; do not
force or replace `master` merely to test this workflow.

After an approved deployment, record the run URL, commit SHA, image digest,
Cloud Run revision, health result, and sanitized three-role smoke evidence under
`workflow/test-evidence/`. Never record credentials or user data.
