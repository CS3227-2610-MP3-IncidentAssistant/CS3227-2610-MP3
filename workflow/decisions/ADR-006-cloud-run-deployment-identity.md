# ADR-006: Cloud Run deployment identity

Status: **Proposed — independent architecture/security approval pending**

## Context

The approved deployment strategy selects a single container on Google Cloud
Run in Singapore. GitHub Actions must publish that container to Artifact
Registry and deploy the assessed `master` commit without placing a long-lived
Google credential in GitHub. The application also needs a runtime identity that
is separate from the deployment identity.

## Decision

Use GitHub Actions OpenID Connect (OIDC) with Google Workload Identity
Federation (WIF), impersonating a dedicated `incident-desk-deployer` service
account. Do not create or store a service-account JSON key.

The WIF provider admits only tokens for the immutable GitHub repository ID, the
`refs/heads/master` ref, and the protected `production` environment. The
service-account binding uses the mapped immutable repository ID rather than the
OIDC subject string, so it remains valid with GitHub's legacy and immutable
subject formats. The deployment job alone receives `id-token: write`;
verification jobs do not.

Use a separate `incident-desk-runtime` service account for Cloud Run revisions.
The deployment service account receives only:

- Artifact Registry Writer on the one deployment repository;
- Cloud Run Admin for service deployment and the public-invoker policy; and
- Service Account User on the runtime service account.

The runtime service account receives no project role by default. Add narrowly
scoped roles only if the application later consumes Google Cloud APIs. Neon
database and object-storage credentials remain runtime configuration and are
not permissions of either Google service account.

The GitHub `production` environment holds identifiers as environment
variables, not secrets. It must restrict deployments to `master` and use a
required human reviewer where the repository plan supports that control.

## Consequences

- GitHub exchanges a short-lived OIDC token for short-lived Google credentials;
  there is no Google private key to rotate or leak from repository settings.
- Renaming or transferring the GitHub repository requires an intentional WIF
  provider update. Deleting and recreating a repository does not inherit trust
  because the provider checks the immutable repository ID.
- The broad `roles/run.admin` role remains a known compromise because the
  workflow currently maintains unauthenticated public access. A later bootstrap
  process may set that policy once and reduce recurring deployment access to
  `roles/run.developer`.
- WIF, IAM, Artifact Registry, the GitHub environment, production runtime
  configuration, and the first deployment are external setup steps and cannot
  be proven by repository-only tests.

## Rejected alternatives

- **Service-account JSON key in a GitHub secret:** rejected because it is a
  long-lived credential and adds rotation and exfiltration risk.
- **A human IAM user in CI:** rejected because deployment would depend on a
  person's credentials and lifecycle.
- **One service account for deploy and runtime:** rejected because a compromised
  application revision would inherit deployment privileges.
- **Direct WIF without service-account impersonation:** deferred. It removes an
  intermediary, but the dedicated deployer account gives Cloud Run and Artifact
  Registry a conventional, auditable principal and a longer-lived access token
  for image build, push, and deployment.

## Required review

- Product owner: deployment topology was approved in
  `.agents/deployment_strategy.md`; this identity detail is pending review.
- Architecture/security reviewer: pending.
- Release approval and deployed evidence: pending.
