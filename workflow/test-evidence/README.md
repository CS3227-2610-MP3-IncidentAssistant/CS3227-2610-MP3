# Test Evidence

Status: **Active evidence directory. Individual records state their exact
commit, environment, results, skips, limitations, and approval boundary.**

Evidence is grouped by dated feature or release activity. Notable cross-cutting
records include:

- [`2026-10-23-release-candidate-verification-sweep.md`](2026-10-23-release-candidate-verification-sweep.md): automated, browser, accessibility, migration, and partial deployed checks, including its explicit reasons for not claiming final release evidence;
- [`2026-10-23-cloud-run-pipeline-verification.md`](2026-10-23-cloud-run-pipeline-verification.md): GitHub OIDC/WIF, branch/repository restrictions, runtime configuration, secret references, and remaining deployment criteria;
- [`2026-10-23-ai-runtime-configuration.md`](2026-10-23-ai-runtime-configuration.md): production SoCLaaS runtime ownership and bounded live verification;
- [`2026-10-23-admin-insight-phase-1-criteria.md`](2026-10-23-admin-insight-phase-1-criteria.md) and [`2026-10-23-admin-insight-load-and-adversarial.md`](2026-10-23-admin-insight-load-and-adversarial.md): deterministic analytics, privacy, audit, resilience, load, concurrency, and hostile-provider evidence;
- [`2026-10-23-issue-23-responder-ai-implementation.md`](2026-10-23-issue-23-responder-ai-implementation.md): Responder AI authorization, data selection, stale access, and safe-failure evidence; and
- [`2026-10-22-promotion-journey.md`](2026-10-22-promotion-journey.md): promotion transaction, category subset, session, UI, and remaining end-to-end evidence.

New records state the commands, tool/runtime versions, exact commit and branch,
test environment, passed/failed/skipped counts, reports or screenshots, deployed
URL and release identifier where applicable, sanitized manual observations, and
remaining risks. They distinguish automated, manual, deployed, and human
approval evidence.

Do not commit credentials, session data, production database content, sensitive
incident text, raw prompts, or raw LLM responses. A passing component suite is
not release evidence, and an agent-authored record is not human approval.
