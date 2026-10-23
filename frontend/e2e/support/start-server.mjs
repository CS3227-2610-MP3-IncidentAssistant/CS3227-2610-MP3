// Starts the packaged application for the browser suite against an isolated,
// already-seeded PostgreSQL schema.
//
// This script never contacts a production database. It exits before starting
// the server unless the operator has confirmed the database is isolated.
//
// AI is intentionally disabled here. `SoclaasSettings.from` rejects any
// SOCLAAS_BASE_URL other than the pinned production HTTPS origin, and the fake
// provider is reachable only through a package-private Java test seam. Pointing
// the browser suite at a fake provider would require weakening that control, so
// the browser suite instead verifies the specified AI-unavailable path and the
// adversarial AI evidence stays in the Java gateway suites.
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..')

function required(name) {
  const value = process.env[name]
  if (!value || value.trim() === '') {
    console.error(`${name} must be set before starting the end-to-end server.`)
    process.exit(1)
  }
  return value
}

if ((process.env.INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED ?? '').toLowerCase() !== 'true') {
  console.error(
    'Refusing to start: set INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED=true to confirm the ' +
      'database is an isolated test database. Never point this harness at production data.',
  )
  process.exit(1)
}

const databaseUrl = required('INCIDENT_DESK_TEST_DATABASE_URL')
// The seeder pins this schema to the database role's search_path, so the
// application resolves to it without needing a schema setting of its own.
// Required here so the harness fails loudly if seeding was skipped.
required('INCIDENT_DESK_E2E_SCHEMA')

// `build/libs` also holds the JavaFX desktop shadow jars, so the web
// application's boot jar is selected by its exact configured name rather than
// by guessing among the available archives.
const BOOT_JAR_NAME = 'incident-desk-web.jar'
const bootJar = join(repositoryRoot, 'build', 'libs', BOOT_JAR_NAME)
if (!existsSync(bootJar)) {
  console.error(
    `${BOOT_JAR_NAME} was not found under build/libs. Run "gradlew bootJar" (or the ` +
      'administratorBrowserTest Gradle task, which does it for you) first.',
  )
  process.exit(1)
}

const server = spawn('java', ['-jar', bootJar], {
  cwd: repositoryRoot,
  stdio: 'inherit',
  env: {
    ...process.env,
    DATABASE_URL: databaseUrl,
    INCIDENT_DESK_NEON_REQUIRED: 'false',
    // Plain HTTP locally; the deployed HTTPS leg is verified separately.
    INCIDENT_DESK_SECURE_COOKIES: 'false',
    AI_ENABLED: 'false',
    PORT: process.env.INCIDENT_DESK_E2E_PORT ?? '8097',
  },
})

const shutdown = () => server.kill()
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
server.on('exit', (code) => process.exit(code ?? 0))
