# Neon services verification — 2026-10-17

Branch: `main`

Base commit: `4a83a05913a3b91a4e0e48126d0ef28970e0794b`

State tested: uncommitted working tree containing the Neon integration. No
credential values, connection strings, endpoints, or object keys are recorded
in this evidence.

## Automated evidence

Command:

```powershell
$env:GRADLE_USER_HOME='C:\Users\seani\Desktop\MP3\.gradle'
npm run build
```

Result: **PASS** in 55 seconds.

- Frontend TypeScript build: passed.
- Frontend typecheck and ESLint: passed.
- Frontend Vitest: 1 file, 3 tests passed.
- Java compilation and complete JUnit suite: passed (the repository reported
  474 tests with one existing skip on the preceding complete run).
- Spring Boot and JavaFX distribution assembly: passed.
- JavaFX platform and packaged shadow-JAR verification: passed.
- Gradle final result: `BUILD SUCCESSFUL`; 23 actionable tasks.

Focused affected-path command:

```powershell
.\gradlew.bat test `
  --tests 'com.company.incidentdesk.HealthEndpointIntegrationTest' `
  --tests 'com.company.incidentdesk.infrastructure.neon.*' `
  --no-daemon
```

Result: **PASS**. This covers the existing aggregate health contract plus the
new URL conversion/redaction, missing required configuration, bounded JDBC
pool, failure-health, and object-client configuration tests.

`git diff --check`: **PASS**; only Git's existing LF-to-CRLF working-copy
warnings were emitted.

Packaged application smoke command:

```powershell
$env:PORT='18080'
java -jar build\libs\incident-desk-web.jar
Invoke-RestMethod http://localhost:18080/health
Invoke-WebRequest http://localhost:18080/
```

Result: **PASS**. The aggregate health response was `UP`; the frontend returned
HTTP 200 with `text/html` and the expected React root element. This offline
check intentionally ran without Neon configuration; the separate live-provider
check below also passed.

## Live Neon evidence

Status: **PASS**.

Authenticated target verified before deployment:

- Project: `orange-wind-42169934` (`IncidentDesk`)
- Organization: `org-morning-block-99522944`
- Branch: `production` (`br-morning-morning-a1c45jrr`), default and unprotected
- Region: `aws-ap-southeast-1`

Deployment command and sanitized result:

```powershell
& "$env:APPDATA\npm\neon.cmd" deploy
```

Result: **PASS**. Neon created the private `incident-attachments` bucket,
reported Postgres and Object Storage as utilized services, and pulled seven
managed variable names into the git-ignored `.env.local`. Credential values
were not printed or recorded here. The pre-existing private `attachments`
bucket was left unchanged.

Application-owned live service command:

```powershell
npx --no-install neon-env run -- cmd.exe /d /c `
  gradlew.bat neonServiceSmoke --no-daemon
```

Result: **PASS**.

- Neon PostgreSQL: `SELECT 1` passed.
- Neon Object Storage: random-byte put/get comparison passed.
- Object cleanup: deletion passed.
- Gradle: `BUILD SUCCESSFUL` in 11 seconds.

Packaged live application command:

```powershell
$env:PORT='18081'
npx --no-install neon-env run -- java -jar build\libs\incident-desk-web.jar
```

Result: **PASS**. `http://localhost:18081/health` returned status `UP` with one
JSON property only, so provider details remained hidden. The bundled frontend
returned HTTP 200, `text/html`, and the expected React root element. Graceful
shutdown closed Tomcat and the JDBC pool.

Required-configuration negative command ran the packaged JAR with
`INCIDENT_DESK_NEON_REQUIRED=true` after removing the five Neon service
variables from that child process. Result: **PASS**—the process exited 1,
reported only the five missing variable names, and shut down without printing
credential values.

## Failures encountered and resolved

- The requested comma-separated `--services postgres,object-storage` form was
  rejected by Neon CLI 8.8.0 on Windows. Repeating `--services` for each value
  succeeded with identical scope.
- The initial sandboxed Gradle run could not access a downloaded AWS SDK jar;
  the approved unsandboxed test run succeeded.
- The first focused test attempted a localhost JDBC connection during pool
  construction. Lazy pool initialization fixed the offline configuration test.
- The first full build failed three health endpoint tests because Spring JDBC
  auto-configuration attempted to create an unspecified default data source.
  Disabling default data-source auto-creation retained the explicit conditional
  Neon data source and restored all health tests.
- The first Windows live-smoke invocation passed the Gradle `.bat` wrapper
  directly to `neon-env` and failed before service access with `spawn EINVAL`.
  Invoking the same wrapper through `cmd.exe /d /c` succeeded.

## Pending gates

- Live service evidence: passed.
- Independent architecture/security review: pending.
- Release approval: pending.
