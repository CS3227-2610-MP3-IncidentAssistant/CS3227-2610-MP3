# Administrator Temporary-Password Reset Verification

Date: 2026-10-22 (Asia/Singapore)

Scope: `ADM-ACC-04`. The original backend work was based on commit
`94d78fc8b4f28bf52d623c27d4640198f0db73a3`; the current backend, React, full
repository, and isolated-PostgreSQL reruns verified exact implementation commit
`6e9eb73aaff8d1eb78e662a5a750772f80bb1393`.

The product owner initially approved a backend-only scope for this change in the
current chat. The React follow-up is now implemented and recorded separately.
The repository user explicitly approved `ADM-ACC-04-A` through `ADM-ACC-04-E`
as written in the current Codex chat on 2026-10-22, and the required focused and
isolated PostgreSQL evidence passes. All six `ADM-ACC-04` criteria are now
ticked in the owning specification.

## Traceability

| Criterion | Implementation | Evidence status |
| --- | --- | --- |
| `ADM-ACC-04-A` | `JdbcAdministratorAccountStore.resetTemporaryPassword` actor `FOR UPDATE` lock, role/status recheck, self-denial; route guard and CSRF | MVC denied-path tests and isolated PostgreSQL denial test passed |
| `ADM-ACC-04-B` | Bcrypt(12) hash, `temporary = TRUE`, 24-hour expiry, session deletion, `PASSWORD_RESET_INITIATED` audit in one transaction | Isolated PostgreSQL commit/hash/session/audit test passed |
| `ADM-ACC-04-C` | `TemporaryPasswordResetOutcome` single-reveal value, `Cache-Control: no-store`, secret-free `toString`, failure mapping | MVC success/failure/single-reveal and PostgreSQL rollback/audit tests passed |
| `ADM-ACC-04-D` | `DatabaseAccountDetailsService` disables an expired temporary credential using the injected UTC clock | `TemporaryCredentialGateTest` passed |
| `ADM-ACC-04-E` | `CREDENTIAL_TEMPORARY` authority plus `WebSecurityConfiguration` route rules; password replacement clears both the persisted and active-session markers | `TemporaryCredentialGateTest` and `OwnPasswordControllerTest` passed |
| `ADM-ACC-04-F` | React reset confirmation, one-time reveal, state clearing, and safe failure paths | Passed; see `2026-10-22-password-management-frontend.md` |

## Automated checks

- `.\gradlew.bat --no-daemon check`: **passed**. The Java report discovered
  747 tests with 0 failures, 0 errors, and 124 environment-gated skips.
  Checkstyle, SpotBugs, packaging, and frontend verification also passed.
- `.\gradlew.bat --no-daemon test --tests "*AdministratorAccountControllerTest*"`:
  **passed** all 11 tests, including the 4 new reset tests.
- `.\gradlew.bat --no-daemon test --tests "*TemporaryCredentialGateTest*"`:
  **passed** all 5 tests (expired rejection, unexpired marker, permanent
  credential, role-workflow denial, retained normal access).
- `.\gradlew.bat --no-daemon test --tests "*OwnPasswordControllerTest*"`:
  **passed** all 4 tests.
- Spring wiring: `OwnPasswordSpringWiringTest` (3 tests) and
  `AdministratorAccountStoreSpringWiringTest` (1 test) **passed**, proving the
  repository proxies are creatable and the qualified security clock resolves.

## Additional checks

- `.\gradlew.bat --no-daemon registrationDatabaseTest --tests
  com.company.incidentdesk.web.admin.AdministratorAccountPostgresTest` —
  **passed** all 11 tests with 0 failures, errors, or skips against the explicitly
  isolated test database.
- React component verification passed and is recorded in
  `2026-10-22-password-management-frontend.md`.
- Browser end-to-end, manual accessibility/responsive, and deployed HTTPS
  verification remain open.
- No human independent-review or release approval is claimed by this record.

## Remaining risks

- The forced-replacement React flow is implemented; manual browser and deployed
  verification remain open.
- The 12-through-128-character replacement-password rule is now recorded in
  `.agents/requirements.md`.
- Delivery of the plaintext temporary password to the account holder remains an
  unresolved product decision; the endpoint returns it once to the acting
  Administrator only.
