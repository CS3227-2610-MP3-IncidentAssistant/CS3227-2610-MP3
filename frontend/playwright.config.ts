import { defineConfig, devices } from '@playwright/test'

/**
 * Browser and accessibility harness for the administrator vertical slice
 * (`workflow/administrator-vertical-slice.md` required verification 5 and 7).
 *
 * The suite drives the real Spring Boot application against an isolated,
 * seeded PostgreSQL schema so that server-side authentication, CSRF, session,
 * and authorization behaviour is exercised rather than mocked. The harness
 * refuses to run unless the operator has confirmed the database is isolated.
 */
// A dedicated default port so the suite never collides with, or worse drives,
// a developer's own instance on 8080.
const port = process.env.INCIDENT_DESK_E2E_PORT ?? '8097'
const reuseExistingServer = process.env.INCIDENT_DESK_E2E_REUSE_SERVER === 'true'
const baseURL = process.env.INCIDENT_DESK_E2E_BASE_URL ?? `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './e2e/specs',
  testMatch: /.*\.e2e\.ts/,
  // Mutation specs share one seeded dataset, so they must not race each other.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI
    ? [
        ['list'],
        ['junit', { outputFile: 'e2e-results/junit.xml' }],
        ['html', { outputFolder: 'e2e-results/html', open: 'never' }],
      ]
    : [['list'], ['html', { outputFolder: 'e2e-results/html', open: 'never' }]],
  outputDir: 'e2e-results/artifacts',
  use: {
    baseURL,
    // Evidence for failures only; traces/screenshots may embed seeded data, so
    // they stay out of version control via .gitignore.
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 10_000,
    // The application sets Secure cookies by default; the harness runs the
    // server with INCIDENT_DESK_SECURE_COOKIES=false over plain HTTP.
    ignoreHTTPSErrors: false,
  },
  projects: [
    {
      name: 'navigation-and-mutations',
      testMatch: /specs\/(navigation|mutations|denied)\/.*\.e2e\.ts/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'accessibility',
      testMatch: /specs\/accessibility\/.*\.e2e\.ts/,
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: reuseExistingServer
    ? undefined
    : {
        // Started from the repository root by the Gradle e2e task, which builds
        // the boot jar and seeds the isolated schema first.
        command: 'node e2e/support/start-server.mjs',
        url: `${baseURL}/health`,
        reuseExistingServer: false,
        timeout: 180_000,
        stdout: 'pipe',
        stderr: 'pipe',
      },
})
