# Administrator Temporary-Password Reset Verification

Date: 2026-10-22 (Asia/Singapore)

Scope: `ADM-ACC-04` (backend only) on branch `main`, working tree based on
commit `94d78fc8b4f28bf52d623c27d4640198f0db73a3`.

The product owner approved a backend-only scope for this change in the current
chat: the reset endpoint plus the full temporary-credential login gate, with the
React surface deferred. The acceptance criteria `ADM-ACC-04-A` through
`ADM-ACC-04-F` were drafted in this change and are **not** ticked; they await
the owner's approval of the criteria text and the isolated PostgreSQL evidence
below.

## Traceability

| Criterion | Implementation | Evidence status |
| --- | --- | --- |
| `ADM-ACC-04-A` | `JdbcAdministratorAccountStore.resetTemporaryPassword` actor `FOR UPDATE` lock, role/status recheck, self-denial; route guard and CSRF | MVC denied-path tests passed; isolated PostgreSQL denial test written but **skipped** (no test database configured here) |
| `ADM-ACC-04-B` | Bcrypt(12) hash, `temporary = TRUE`, 24-hour expiry, session deletion, `PASSWORD_RESET_INITIATED` audit in one transaction | Isolated PostgreSQL test written but **skipped** |
| `ADM-ACC-04-C` | `TemporaryPasswordResetOutcome` single-reveal value, `Cache-Control: no-store`, secret-free `toString`, failure mapping | MVC success/failure and single-reveal tests passed; audit/rollback assertions **skipped** |
| `ADM-ACC-04-D` | `DatabaseAccountDetailsService` disables an expired temporary credential using the injected UTC clock | `TemporaryCredentialGateTest` passed (3 tests) |
| `ADM-ACC-04-E` | `CREDENTIAL_TEMPORARY` authority plus `WebSecurityConfiguration` route rules; `OwnPasswordController`/`OwnPasswordService` clear the marker | `TemporaryCredentialGateTest` and `OwnPasswordControllerTest` passed |
| `ADM-ACC-04-F` | Not started | Deferred by approved scope |

## Automated checks

- `.\gradlew.bat --no-daemon check`: **passed**. The Java report discovered
  745 tests with 0 failures, 0 errors, and 124 environment-gated skips.
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

## Checks NOT run

- `AdministratorAccountPostgresTest` — all 11 tests, including the 3 new reset
  tests, were **skipped**. `INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED` and
  `INCIDENT_DESK_TEST_DATABASE_URL` are unset in this environment, so no
  isolated database was available. `ADM-ACC-04-A`, `-B`, and `-C` therefore have
  **no** transaction, atomicity, rollback, or audit evidence yet and remain
  unticked.
- No React component, browser end-to-end, accessibility, responsive, or
  deployed HTTPS verification was performed for this change.
- No human independent-review or release approval is claimed by this record.

## Remaining risks

- The forced-replacement gate denies every `/api/v1/{admin,reporter,responder}/**`
  route while a credential is temporary. No React flow yet guides a user to the
  password-replacement endpoint, so a reset user currently sees generic
  unavailable responses in the existing UI until `ADM-ACC-04-F` is built.
- `OwnPasswordService` enforces a 12-character minimum for replacement
  passwords. That limit is not yet recorded in a canonical artifact, as
  required by decision 3 of the slice specification.
- Delivery of the plaintext temporary password to the account holder remains an
  unresolved product decision; the endpoint returns it once to the acting
  Administrator only.
