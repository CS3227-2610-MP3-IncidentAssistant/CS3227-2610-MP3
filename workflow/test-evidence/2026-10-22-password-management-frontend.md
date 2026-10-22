# Password-management React verification — 2026-10-22

## Scope and traceability

This working-tree change implements the React portion of `ADM-ACC-04-F`, the
temporary-credential gate required by `ADM-ACC-04-E`, and the shared own-password
interaction described by `ADM-SELF-01` and the shared product requirements.
The implementation was committed as
`6e9eb73aaff8d1eb78e662a5a750772f80bb1393` and the full repository and isolated
PostgreSQL gates were rerun against that exact commit.

| Requirement | Implementation | Automated evidence |
| --- | --- | --- |
| `ADM-ACC-04-F` | Account reset confirmation, one-time reveal, secret-state clearing, safe failure mapping | `AdministratorAccounts.test.tsx` |
| `ADM-ACC-04-E` | Session DTO flag, required dialog before role shells render, and removal of the temporary authority after replacement | `App.test.tsx`, `PasswordChangeDialog.test.tsx`, `TemporaryCredentialGateTest`, and `OwnPasswordControllerTest` passed |
| `ADM-SELF-01` | Bottom-left shell action and shared current/new/confirm dialog for every role | `PasswordChangeDialog.test.tsx` and the full frontend suite |

## Commands and results

- `cd frontend && npm.cmd run check` — **passed**: TypeScript, ESLint, 14 test
  files and 123 tests.
- `cd frontend && npm.cmd run build` — **passed**; Vite produced the production
  bundle successfully.
- `gradlew.bat --no-daemon test --tests TemporaryCredentialGateTest --tests
  OwnPasswordControllerTest --tests AdministratorAccountControllerTest` —
  **passed** after using the existing user Gradle cache to avoid transient
  sandbox cache access failures.
- `gradlew.bat --no-daemon registrationDatabaseTest --tests
  AdministratorAccountPostgresTest` — **passed**: 11 tests, 0 failures, 0
  errors, 0 skips against the explicitly isolated test database.
- `gradlew.bat --no-daemon check` — **passed**: 747 Java tests, 0 failures, 0
  errors, and 124 environment-gated skips; frontend verification, production
  bundles, packaged artifacts, and platform checks also passed.
- `gradlew.bat --no-daemon registrationDatabaseTest --tests
  com.company.incidentdesk.web.admin.AdministratorAccountPostgresTest
  --rerun-tasks -PfrontendSkipBuild=true` — **passed** against the exact
  implementation commit: 11 tests, 0 failures, 0 errors, 0 skips.
- An initial forced database rerun without `frontendSkipBuild` — **failed before
  database tests ran** because Windows denied `npm ci` permission to unlink the
  in-use Rolldown native module. The documented frontend-skip retry above
  avoided that unrelated reinstall and passed.
- `git diff --check` — **passed**.

## Security and data effects

- The temporary password exists only in the reset response, transient React
  state, and the read-only reveal field. Closing the reveal clears the field and
  removes the state object. Tests verify no local/session storage writes and no
  reveal on failure.
- Password confirmation remains browser-only; the request contains only the
  current and new passwords.
- The session endpoint adds only `credentialTemporary`; it exposes no credential
  value or expiry. Successful password replacement removes that authority from
  the active trusted security context so the same session can resume role work.
- No persistence schema, concurrency, SLO, anonymity, attachment, or AI boundary
  changed.

## Remaining evidence

- Deployed smoke and release approval remain open.

## Human verification and approval

On 2026-10-22, the repository user confirmed completion of the requested manual
browser checks for focus return, required-versus-optional Escape behavior,
screen-reader output, narrow-width layout, 200% zoom, and safe long/malicious
text rendering. The user also explicitly approved `ADM-ACC-04-A` through
`ADM-ACC-04-E` as written and confirmed human review of this bounded feature.
Deployed HTTPS smoke and release approval were explicitly deferred until after
merge to `main`.
