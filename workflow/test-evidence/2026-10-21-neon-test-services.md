# Dedicated Neon test services verification

Status: PostgreSQL and Object Storage connectivity, isolated PostgreSQL tests,
the packaged runtime, and the repository build passed on the uncommitted
working tree based on `06904be9bf54e199c63abfc27e5bd2cd4f64f889`.
Independent review remains pending.

## Target and configuration

- Organization: `org-morning-block-99522944`
- Project: `quiet-dawn-13912485` (`IncidentDeskTest`)
- Branch: `production`
- Region: `aws-ap-southeast-1`
- Services: PostgreSQL and private Object Storage bucket
  `incident-attachments`
- Local destination: git-ignored `.env.test.local`

The existing `neon.ts` was retained as the declarative source of truth. The
existing production `.env.local` was not overwritten. No credential values or
connection strings are recorded in this evidence.

## Provisioning evidence

- `npm i -g neon@latest` passed; Neon CLI version `8.10.1` was available.
- The CLI already had an authenticated default OAuth profile, so no browser
  sign-in was required.
- `neon config plan --project-id quiet-dawn-13912485 --branch production`
  planned the private bucket and both requested services.
- `neon deploy --project-id quiet-dawn-13912485 --branch production
  --no-env-pull -y` passed and created the declared bucket.
- The comma-separated `--services postgres,object-storage` pull failed on this
  Windows CLI because it interpreted both names as one service. Retrying with
  repeated flags passed:

  `neon env pull --project-id quiet-dawn-13912485 --branch production --file
  .env.test.local --services postgres --services object-storage`

  Seven provider variables were written without displaying their values.
  Application-only safe flags and the bucket name were then added locally.

## Live verification

- Passed: `./gradlew.bat neonTestServiceSmoke --console=plain`.
  Sanitized output:

  - `Neon PostgreSQL smoke check: PASS (SELECT 1)`
  - `Neon Object Storage smoke check: PASS (put/get)`
  - `Neon Object Storage smoke cleanup: PASS (delete)`
  - `BUILD SUCCESSFUL in 2s`

- Passed: packaged `incident-desk-web.jar` started on local port `18082` with
  `.env.test.local`; Flyway validated all eight migrations and reported schema
  `public` at version 8. `GET /health` returned HTTP 200 with
  `{"status":"UP"}`, and `GET /` returned HTTP 200 with the React mount.
  The process then completed a graceful shutdown.

## Automated verification

- Passed: `./gradlew.bat registrationDatabaseTest --console=plain`.
  All 15 isolated PostgreSQL tests passed with no failures or skips;
  `BUILD SUCCESSFUL in 23s`. Test connections now call JDBC
  `Connection.setSchema` and verify the selected schema before migrations or
  fixture mutations.
- Passed: `npm run build`. TypeScript compilation, ESLint, 71 Vitest tests in 7
  files, Java tests, platform verification, packaged artifact checks, and the
  Gradle build completed; `BUILD SUCCESSFUL in 46s`.
- Database tests now enable only from the explicit
  `INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED=true` acknowledgement. The
  inherited AI PostgreSQL fixture also uses a `BeforeAll` assumption because
  JUnit environment-condition annotations on an abstract base class are not
  inherited. Unconfirmed builds skip before opening a database connection.

## Safety evidence

- `.env.test.local` is ignored by `.gitignore` and does not appear in Git
  status.
- The live object used a random `smoke-tests/` key and was deleted.
- The service smoke refuses a database target matching `.env.local`.
- Isolated PostgreSQL fixtures verify `Connection.getSchema()` before use and
  drop only their random owned schema after completion.
- The packaged app and service checks used only the dedicated test project.
- No secrets, credentials, session identifiers, or connection strings were
  printed into repository evidence.
