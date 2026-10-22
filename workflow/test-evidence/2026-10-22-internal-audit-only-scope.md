# Internal-audit-only scope verification — 2026-10-22

## Scope and revision

- Branch: `main`
- Base commit: `8de6bc159994521296f9a9af6b0ce7c6156638ef`
- Verification state: uncommitted working tree containing the internal-only
  audit scope amendment
- Product-owner decision:
  [`2026-10-22-internal-audit-only-scope.md`](../reviews/2026-10-22-internal-audit-only-scope.md)

## Observable boundary

Source inspection found no production Java HTTP mapping containing an audit
route, no `AdministratorAudit` web implementation, and no React audit
destination. The public User Guide, Peer Review Guide, and documentation home
page contain no audit-history workflow. `AdminShell.test.tsx` independently
asserts that the Administrator navigation exposes no audit control.

The internal audit policy, domain records, persistence ports, database schema,
mutation writes, and rollback requirements remain in place.

## Commands and results

```powershell
cd frontend
npm run check
```

Passed: TypeScript typecheck, ESLint, and all 10 Vitest files (87 tests).

```powershell
.\gradlew.bat --gradle-user-home 'C:\Users\seani\Desktop\MP3\.gradle' test `
  --tests 'com.company.incidentdesk.application.audit.*' `
  --tests 'com.company.incidentdesk.persistence.InMemoryAuditedMutationStoreTest' `
  --tests 'com.company.incidentdesk.web.admin.AdministratorSloServiceTest' `
  -PfrontendSkipBuild=true
```

Passed: `BUILD SUCCESSFUL`; focused audit construction, audited mutation-store,
and SLO mutation/audit behavior remained green. Shadow JAR duplicate-strategy
warnings were emitted but did not fail the build.

```powershell
rg -n -i "@(Get|Post|Put|Patch|Delete|Request)Mapping...audit" src/main/java
rg -n "AdministratorAudit" src/main src/test build.gradle
rg -n -i "audit log|audit-history|/audit" frontend/src
rg -n -i "audit log|audit-history|audit timeline|audit records|audit entries" `
  docs/UserGuide.md docs/PeerReviewGuide.md docs/index.md
git diff --check
```

Passed: no production HTTP audit mapping, discarded Administrator audit web
classes, React destination, or public-guide audit-history workflow was found.
The only React match was the negative regression-test assertion. `git diff
--check` passed with a line-ending warning for ADR-001.

## Failed and unavailable checks

The first Gradle attempt failed before task execution because the wrapper tried
to create its lock under unwritable `C:\.gradle`. The same focused suite was
rerun with the repository-local Gradle user home shown above and passed. No
database integration suite was required because this change neither changes
the audit schema nor any audit write path. No deployed/manual verification was
run, and no release approval is claimed.
