# CS3227 MP 2

[Open the Incident Desk project website](https://cs3227-2610-mp2-incidentassistant.github.io/CS3227-2610-MP2/)

Incident Desk is a local JavaFX desktop application for company incident
reporting. It targets Java 25 and is built with the checked-in Gradle Wrapper.

See [the developer guide](docs/DeveloperGuide.md) for setup, build, test, and
architecture details.

## Spring Boot migration preview

The incremental MP3 migration now includes a Spring Boot web process with a
minimal health endpoint. Start it with:

```powershell
.\gradlew.bat bootRun
```

Then request `http://localhost:8080/health`. The endpoint returns only the
aggregate application status. The existing JavaFX launcher remains available
while the web migration is in progress.

The React frontend lives in `frontend/`. Start the backend and frontend
development processes in separate terminals:

```powershell
.\gradlew.bat bootRun
```

```powershell
cd frontend
npm ci
npm run dev
```

The frontend can then be accessed at <http://localhost:5173/>.

## Neon services

The production service policy is declared in `neon.ts`: application traffic
uses the pooled Neon PostgreSQL URL and attachment bytes use the private
`incident-attachments` Object Storage bucket. After authenticating the Neon CLI,
provision the linked `production` branch:

```powershell
neon deploy
```

Neon pulls the branch variables into the git-ignored `.env.local` (or `.env`
when that file already exists). The Spring backend reads `DATABASE_URL` and the
standard `AWS_*` variables from its process environment. Production must also
set `INCIDENT_DESK_NEON_REQUIRED=true` so missing configuration fails startup;
local development remains available without provider credentials. See
`.env.example` for variable names and safe defaults. Keep
`DATABASE_URL_UNPOOLED` reserved for future versioned migrations.

Vite proxies `/health` and future `/api` requests to Spring Boot. A normal
Gradle build compiles the React production assets into the Spring Boot web JAR.
Production deployment builds one container and deploys it to Cloud Run through
the protected `production` GitHub environment. Configure that environment with
the `GCP_WORKLOAD_IDENTITY_PROVIDER` and `GCP_SERVICE_ACCOUNT` secrets, plus
`GCP_PROJECT_ID`, `GCP_REGION`, `GCP_ARTIFACT_REPOSITORY`, and
`CLOUD_RUN_SERVICE` variables. Only the assessed `master` branch can reach the
deployment job; a manual run from another branch performs verification only.

## Testing the entire application

The full automated verification path runs the frontend and Java checks together.
Use JDK 25 and Node.js 24.16.0, stop any running Vite development server, and
run from the repository root:

```powershell
.\gradlew.bat build
```

On macOS or Linux, use `./gradlew build`. In a headless Linux environment, run
the JavaFX tests through a virtual display, as CI does:

```bash
xvfb-run --auto-servernum ./gradlew build
```

The full build installs the locked npm dependencies, type-checks and lints the
React code, runs the frontend component tests, creates the production frontend
bundle, compiles the Java application, runs the JUnit and Spring integration
tests, and verifies the JavaFX distribution and shadow JAR.

For a production-style web smoke test, first complete the full build, then start
the packaged application:

```powershell
java -jar build\libs\incident-desk-web.jar
```

In another PowerShell terminal, verify the health endpoint and then open
<http://localhost:8080/> to confirm that the frontend loads:

```powershell
Invoke-RestMethod http://localhost:8080/health
```

The health response must report `status` as `UP`. Stop the application with
`Ctrl+C` after the check.

Run the legacy JavaFX application separately when a change affects reusable
desktop or domain behaviour:

```powershell
.\gradlew.bat run
```

Exercise the relevant end-to-end role and authorization paths described in
[DeveloperGuide.md](docs/DeveloperGuide.md), including denied paths for changed
behaviour. Automated tests must use isolated test data and must never connect to
production data.

To verify only the React section while developing it:

```powershell
cd frontend
npm ci
npm run check
npm run build
```

To smoke-test the same container shape used by deployment, with Docker
installed:

```powershell
docker build --tag incident-desk:local .
docker run --rm -p 8080:8080 incident-desk:local
```

Repeat the `/health` and browser checks against the container, then stop it with
`Ctrl+C`. Before a release or submission, confirm the GitHub Actions CI and
deployment smoke-test jobs pass for the exact commit and record the commands,
environment, commit SHA, and results under `workflow/test-evidence/`.

## Agent usage guide

This repository provides three project-specific agent skills:

- `code-quality-review`: Reviews non-trivial code changes for readability,
  naming, unsafe shortcuts, and comment quality.
- `git-commit`: Inspects changes, prepares an accurate commit message, and
  creates a commit when requested.
- `log-agent-conversation`: Saves the visible conversation as a Markdown
  snapshot when explicitly requested.
