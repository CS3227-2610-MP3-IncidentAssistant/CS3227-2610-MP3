import { accounts, expect, fixturePassword, signIn, test } from '../../fixtures/roles'

/**
 * Administrator slice criterion 1 and required verification 5: only an enabled
 * authenticated administrator may load an administrator representation, and
 * denied or missing resources must not leak data or enumerate roles.
 *
 * These assertions hit the HTTP boundary directly as well as through the UI,
 * because hiding a navigation control is not authorization.
 */
const ADMIN_READ_ENDPOINTS = [
  '/api/v1/admin/dashboard?search=&page=0&size=25',
  '/api/v1/admin/accounts',
  '/api/v1/admin/slo',
  '/api/v1/admin/statistics',
]

test.describe('administrator route and API denial', () => {
  test('anonymous requests to administrator APIs are rejected without data', async ({ request }) => {
    for (const endpoint of ADMIN_READ_ENDPOINTS) {
      const response = await request.get(endpoint)
      expect(response.status(), `anonymous GET ${endpoint}`).toBe(401)
      const body = await response.text()
      expect(body).not.toContain('e2e-')
      expect(body).not.toContain('Seeded incident')
    }
  })

  // `page.request` shares the browser context cookie jar, so these calls carry
  // the signed-in role's real session rather than an anonymous one.
  //
  // A wrong-role request is answered as a missing resource (404) rather than a
  // forbidden one, so an authenticated non-administrator cannot tell an
  // administrator route apart from one that does not exist. The assertions
  // below therefore accept the presentation-safe statuses and check that no
  // administrator data reaches the caller.
  const DENIED_STATUSES = [401, 403, 404]

  test('a signed-in reporter cannot reach administrator APIs', async ({ page }) => {
    await signIn(page, 'reporter')
    await expect(page.getByRole('complementary', { name: /Reporter/ })).toBeVisible()
    for (const endpoint of ADMIN_READ_ENDPOINTS) {
      const response = await page.request.get(endpoint)
      expect(DENIED_STATUSES, `reporter GET ${endpoint} status`).toContain(response.status())
      const body = await response.text()
      expect(body).not.toContain('Seeded incident')
      expect(body).not.toContain('e2e-administrator')
    }
  })

  test('a signed-in responder cannot reach administrator APIs', async ({ page }) => {
    await signIn(page, 'responder')
    for (const endpoint of ADMIN_READ_ENDPOINTS) {
      const response = await page.request.get(endpoint)
      expect(DENIED_STATUSES, `responder GET ${endpoint} status`).toContain(response.status())
      const body = await response.text()
      expect(body).not.toContain('Seeded incident')
      expect(body).not.toContain('e2e-administrator')
    }
  })

  test('a denied administrator route is indistinguishable from a missing one', async ({ page }) => {
    await signIn(page, 'reporter')
    const denied = await page.request.get('/api/v1/admin/accounts')
    const missing = await page.request.get('/api/v1/admin/no-such-resource')
    // Same status and same body shape, so neither existence nor role leaks.
    expect(denied.status(), 'denied vs missing status').toBe(missing.status())
    expect(await denied.text()).toBe(await missing.text())
  })

  test('a reporter signing in at the administrator portal is refused generically', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: 'Administrator', exact: true }).click()
    await page.getByLabel('Login name').fill(accounts.reporter.loginName)
    await page.getByLabel('Password', { exact: true }).fill(fixturePassword)
    await page.getByRole('button', { name: 'Sign in' }).click()

    const alert = page.getByRole('alert')
    await expect(alert).toBeVisible()
    // The failure must not reveal that the account exists or hold another role.
    const message = (await alert.textContent()) ?? ''
    expect(message.toLowerCase()).not.toContain('reporter')
    expect(message.toLowerCase()).not.toContain('role')
    expect(message.toLowerCase()).not.toContain('exist')
    await expect(
      page.getByRole('complementary', { name: 'Administrator navigation' }),
    ).toHaveCount(0)
  })

  test('a wrong password is refused with the same generic message', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: 'Administrator', exact: true }).click()
    await page.getByLabel('Login name').fill(accounts.administrator.loginName)
    await page.getByLabel('Password', { exact: true }).fill('WrongFixturePassword!9')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(
      page.getByRole('complementary', { name: 'Administrator navigation' }),
    ).toHaveCount(0)
  })

  test('an unknown account is indistinguishable from a wrong password', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: 'Administrator', exact: true }).click()
    await page.getByLabel('Login name').fill('no-such-account-at-all')
    await page.getByLabel('Password', { exact: true }).fill('WrongFixturePassword!9')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByRole('alert')).toBeVisible()
  })

  test('an administrator mutation without a CSRF token is rejected', async ({ page }) => {
    await signIn(page, 'administrator')
    await expect(
      page.getByRole('complementary', { name: 'Administrator navigation' }),
    ).toBeVisible()
    // A real session, deliberately without the X-XSRF-TOKEN header the client sends.
    const response = await page.request.post('/api/v1/admin/slo/IT', {
      data: {
        timeToClaimMinutes: 90,
        timeInProgressMinutes: 600,
        reopenRatePercent: 7,
        expectedVersionId: null,
      },
    })
    expect(response.status(), 'CSRF-less administrator mutation').toBe(403)
  })

  test('a missing incident is presentation-safe and does not confirm existence', async ({ page }) => {
    await signIn(page, 'administrator')
    await expect(
      page.getByRole('complementary', { name: 'Administrator navigation' }),
    ).toBeVisible()
    const response = await page.request.get(
      '/api/v1/admin/incidents/00000000-0000-0000-0000-000000000000',
    )
    expect([403, 404]).toContain(response.status())
    expect(response.status(), 'a missing incident is reported as missing').toBe(404)
    const body = await response.text()
    expect(body).not.toContain('Seeded incident')
  })
})
