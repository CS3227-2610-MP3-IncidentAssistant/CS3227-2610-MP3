# Production AI Enablement Authorization

- Date: 2026-10-23 (Asia/Singapore)
- Service: `incidentdesk-run`, `asia-southeast1`
- Setting changed: `AI_ENABLED=false` -> `AI_ENABLED=true`
- Authorizing role: repository user, as operator
- Decision: AI is enabled in production

## Basis

The operator enabled AI on the deployed service and verified an AI feature
working against `https://incidentdesk-run-...run.app`. When the resulting
conflict between the deployed state and the specifications was raised — the
artifacts required AI to remain disabled pending a gate — the operator directed
that the specifications be updated to match the enabled service rather than
disabling AI.

Supporting configuration evidence is recorded in
`test-evidence/2026-10-23-ai-runtime-configuration.md` and
`test-evidence/2026-10-23-cloud-run-pipeline-verification.md`:

- `SOCLAAS_API_KEY` resolves Secret Manager secret
  `incidentdesk-soclaas-api-key` version `1`, enabled.
- Secret-level IAM grants `roles/secretmanager.secretAccessor` on that secret
  to `incident-desk-runtime` only.
- All 19 expected AI setting names are present as non-secret Cloud Run values.
- GitHub holds no secrets at any scope and no application runtime
  configuration.
- The opt-in `SoclaasLiveSmokeTest` completed one bounded synthetic provider
  request without persisting an incident or printing the response or key.

## Scope and limits

This record authorizes the production `AI_ENABLED=true` setting and the
corresponding specification updates. It is **not**:

- an independent architecture/security review (gate 2);
- an independent code review (gate 4);
- release approval (gate 5); or
- evidence for any acceptance criterion requiring deployed per-role
  verification.

## Known accepted conditions

1. Provider-retention risk, already accepted in
   `2026-10-23-ai-features-and-admin-insights-approval.md`, now applies to live
   production traffic rather than to an unreleased feature.
2. `DATABASE_URL`, `AWS_ACCESS_KEY_ID`, and `AWS_SECRET_ACCESS_KEY` resolve
   `:latest`. The operator declined numeric pinning for these three on the
   basis that no rotation is planned. Each secret currently has exactly one
   enabled version, so `:latest` resolves to `:1` and no behaviour differs
   today. The residual risk is that adding a version would change the resolved
   value on the next deployment without an explicit configuration change.
3. `user:Pokerus8893@gmail.com` holds project-level `roles/owner` and can
   therefore read every secret, including the provider key. This remains open
   for the security reviewer.

## Outstanding before release

- Deployed per-role AI verification: authorized path, denial path, safe
  logging/audit metadata, and non-AI fallback for each role (`CR-CD-06`).
- Confirmation that an ordinary subsequent image deployment preserves both the
  secret reference and the non-secret AI settings (`CR-CD-08`, `AI-GW-05`).
  The operator authorized this redeployment on 2026-10-23, but the agent's
  sandbox denies production `gcloud run deploy` regardless of operator consent,
  so it must be run by a human or satisfied by the next ordinary `main`
  deployment. Baseline for comparison: revision `incidentdesk-run-00015-fdz`,
  27 environment entries, fingerprint `64bfb164bc27465c`.
- Architecture/security reviewer and release approver sign-off.
