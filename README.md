# CS3227 MP 3

- Live application: <https://incidentdesk.cloud.run>
- Project website: <https://cs3227-2610-mp3-incidentassistant.github.io/CS3227-2610-MP3/>

Incident Desk is a concurrent web application for internal company incident
reporting, with Reporter, Responder, and Administrator roles. It targets
Java 25, is built with the checked-in Gradle Wrapper, and is deployed to
Cloud Run against shared PostgreSQL persistence.

See [the developer guide](docs/DeveloperGuide.md) for setup, build, test, and
architecture details.

See the [Administrator bootstrap guide](docs/AdministratorBootstrap.md) for
first-run, secret-removal, and recovery instructions.

## Spring Boot migration preview

The incremental MP3 migration now includes a Spring Boot web process with a
minimal health endpoint. Start it with:

```powershell
.\gradlew.bat bootRun
```

For local development, `bootRun` loads the git-ignored `.env.local` file when
present. Variables already supplied by the operating environment take
precedence. Invalid bootstrap configuration stops startup with the affected
variable name, without printing its value.

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
when that file already exists). The Gradle `bootRun` task loads `.env.local`
into the Spring backend process for local development; existing process
environment variables win. Packaged and production runs read `DATABASE_URL`
and the standard `AWS_*` variables from their process environment. Production
must also set `INCIDENT_DESK_NEON_REQUIRED=true` so missing configuration fails
startup; local development remains available without provider credentials. See
`.env.example` for variable names and safe defaults. Keep
`DATABASE_URL_UNPOOLED` reserved for future versioned migrations.

Vite proxies `/health` and future `/api` requests to Spring Boot. A normal
Gradle build compiles the React production assets into the Spring Boot web JAR.
Production deployment builds one container and deploys it to Cloud Run through
the protected `production` GitHub environment. The deployed service is
published at <https://incidentdesk.cloud.run>. Configure that environment with
the `GCP_PROJECT_ID`, `GCP_REGION`, `GCP_ARTIFACT_REPOSITORY`,
`CLOUD_RUN_SERVICE`, `GCP_WORKLOAD_IDENTITY_PROVIDER`,
`GCP_DEPLOY_SERVICE_ACCOUNT`, and `GCP_RUNTIME_SERVICE_ACCOUNT` variables. The
workflow uses GitHub OIDC and Google Workload Identity Federation; no Google
service-account key is stored in GitHub. Only `main` can reach the routine
deployment job; a manual run from another branch performs verification only.
`master` remains reserved for the separately approved assignment-submission
snapshot. Follow [the Cloud Run deployment runbook](docs/CloudRunDeployment.md)
for the one-time Google Cloud and GitHub environment setup.

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

### Local PostgreSQL registration tests

The normal build skips registration tests that require PostgreSQL when no test
database is configured. To run them locally against a dedicated empty Neon
test project or another isolated PostgreSQL database:

1. Copy `.env.test.example` to the git-ignored `.env.test.local`, or pull a
   dedicated Neon test project directly into that file:

```powershell
neon env pull --project-id <test-project-id> --branch <test-branch> --file .env.test.local --services postgres --services object-storage
```

2. Confirm `DATABASE_URL_UNPOOLED` is the complete direct URL for a test-only
   database. A Neon direct endpoint does not contain `-pooler` in its hostname.
   `registrationDatabaseTest` also accepts the legacy
   `INCIDENT_DESK_TEST_DATABASE_URL` override when testing non-Neon PostgreSQL.
3. Keep `INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED=true` only after
   confirming that the database contains no production or personal data.
4. Run:

```powershell
.\gradlew.bat registrationDatabaseTest
.\gradlew.bat neonTestServiceSmoke
```

The task refuses pooled Neon endpoints and any URL equal to the process's
`DATABASE_URL` or `DATABASE_URL_UNPOOLED`. Each test run creates a random
schema, applies the real Flyway migrations, runs the registration tests, and
drops that schema. Environment variables already defined by the operating
system take precedence over `.env.test.local`. `neonTestServiceSmoke` performs
`SELECT 1` plus one random Object Storage put/get/delete round trip and refuses
to run when `.env.test.local` targets the `.env.local` database. Never commit
the local file or use production credentials.

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
