import { accounts, expect, fixturePassword, openAdminPage, signIn, test } from '../../fixtures/roles'

/**
 * Administrator slice required verification 5: the SLO version, password
 * change, and logout mutation families, each with a successful and a
 * denied-or-invalid path.
 */
test.describe('SLO target versions', () => {
  test.beforeEach(async ({ adminPage }) => {
    await openAdminPage(adminPage, 'SLO configuration')
  })

  test('saving a new version appends to history and preserves the old one', async ({ adminPage }) => {
    // Pick a category the seeder configured, so a prior version already exists.
    // Addressed by id: the page carries more than one "Category" label.
    await adminPage.locator('#slo-category').selectOption({ label: 'IT' })
    const history = adminPage.getByRole('region', { name: 'IT SLO version history' })
    await expect(history).toBeVisible()
    const versionsBefore = await history.getByRole('row').count()

    // The form disables submission until a value actually differs, so the new
    // version is derived from whatever the current target is.
    const claim = adminPage.getByLabel('Average time to claim (minutes)')
    const current = Number((await claim.inputValue()) || '60')
    await claim.fill(String(current + 15))
    const save = adminPage.getByRole('button', { name: 'Save new version' })
    await expect(save).toBeEnabled()
    await save.click()

    // Append-only: the history grows and the earlier version is still listed,
    // which is the durable outcome rather than the transient feedback line.
    await expect
      .poll(async () => history.getByRole('row').count())
      .toBeGreaterThan(versionsBefore)
    await expect(adminPage.getByRole('alert')).toHaveCount(0)

    // The new version survives a reload, so it was persisted, not just shown.
    await adminPage.reload()
    await openAdminPage(adminPage, 'SLO configuration')
    await adminPage.locator('#slo-category').selectOption({ label: 'IT' })
    await expect
      .poll(async () =>
        adminPage.getByRole('region', { name: 'IT SLO version history' }).getByRole('row').count(),
      )
      .toBeGreaterThan(versionsBefore)
  })

  test('an unchanged target cannot be submitted', async ({ adminPage }) => {
    // The form disables submission until a value actually differs.
    await expect(adminPage.getByRole('button', { name: 'Save new version' })).toBeDisabled()
  })

  test('an out-of-range reopen rate is rejected with a field error', async ({ adminPage }) => {
    await adminPage.getByLabel('Average reopen rate (%)').fill('150')
    await adminPage.getByRole('button', { name: 'Save new version' }).click()
    // Either the browser's own constraint blocks it or the server returns a
    // field error; in neither case may an invalid version be persisted.
    const reopen = adminPage.getByLabel('Average reopen rate (%)')
    const valid = await reopen.evaluate(
      (element) => (element as HTMLInputElement).checkValidity(),
    )
    if (valid) {
      await expect(adminPage.getByRole('alert').first()).toBeVisible()
    } else {
      expect(valid).toBe(false)
    }
  })

  test('a negative claim target is rejected', async ({ adminPage }) => {
    const claim = adminPage.getByLabel('Average time to claim (minutes)')
    await claim.fill('-5')
    const valid = await claim.evaluate(
      (element) => (element as HTMLInputElement).checkValidity(),
    )
    expect(valid).toBe(false)
  })
})

test.describe('own password change', () => {
  test('changing the password requires the current password', async ({ adminPage }) => {
    await adminPage
      .getByRole('complementary', { name: 'Administrator navigation' })
      .getByRole('button', { name: 'Update password' })
      .click()
    const dialog = adminPage.getByRole('dialog')
    await expect(dialog).toBeVisible()
    // A wrong current password must be refused without changing anything.
    await dialog.getByLabel('Current password').fill('WrongFixturePassword!9')
    await dialog.getByLabel('New password', { exact: true }).fill('AnotherFixturePassword!2')
    await dialog.getByLabel('Confirm new password').fill('AnotherFixturePassword!2')
    await dialog.getByRole('button', { name: 'Update password' }).click()
    // The dialog reports a rejected current password as a field error and the
    // dialog stays open; only form-level failures carry role="alert".
    await expect(
      dialog.getByRole('alert').or(dialog.locator('.field-error')).first(),
    ).toBeVisible()
    await expect(dialog).toBeVisible()
  })

  test('mismatched confirmation is refused', async ({ adminPage }) => {
    await adminPage
      .getByRole('complementary', { name: 'Administrator navigation' })
      .getByRole('button', { name: 'Update password' })
      .click()
    const dialog = adminPage.getByRole('dialog')
    await dialog.getByLabel('Current password').fill(fixturePassword)
    await dialog.getByLabel('New password', { exact: true }).fill('AnotherFixturePassword!2')
    await dialog.getByLabel('Confirm new password').fill('DifferentFixturePassword!3')
    await dialog.getByRole('button', { name: 'Update password' }).click()
    await expect(
      dialog.getByRole('alert').or(dialog.locator('.field-error')).first(),
    ).toBeVisible()
    await expect(dialog).toBeVisible()
  })

  test('a temporary credential is forced to change before any portal access', async ({ page }) => {
    await signIn(page, 'temporaryCredential')
    // The forced dialog appears instead of the portal shell.
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('complementary')).toHaveCount(0)
  })
})

test.describe('logout', () => {
  test('signing out clears the session and protected data', async ({ adminPage }) => {
    await adminPage
      .getByRole('complementary', { name: 'Administrator navigation' })
      .getByRole('button', { name: 'Sign out' })
      .click()

    await expect(adminPage.getByRole('heading', { name: 'Choose your portal' })).toBeVisible()
    // The session is gone server-side, not merely hidden in the client.
    const response = await adminPage.request.get('/api/v1/admin/dashboard?search=&page=0&size=25')
    expect([401, 403]).toContain(response.status())

    // No protected data or identity may linger in client storage.
    const stored = await adminPage.evaluate(() => ({
      local: JSON.stringify(window.localStorage),
      session: JSON.stringify(window.sessionStorage),
    }))
    expect(stored.local).not.toContain(accounts.administrator.loginName)
    expect(stored.local).not.toContain('Seeded incident')
    expect(stored.session).not.toContain(accounts.administrator.loginName)
    expect(stored.session).not.toContain('Seeded incident')
  })

  test('the back button does not restore an authenticated view after logout', async ({ adminPage }) => {
    await adminPage
      .getByRole('complementary', { name: 'Administrator navigation' })
      .getByRole('button', { name: 'Sign out' })
      .click()
    await expect(adminPage.getByRole('heading', { name: 'Choose your portal' })).toBeVisible()
    await adminPage.goBack()
    await expect(
      adminPage.getByRole('complementary', { name: 'Administrator navigation' }),
    ).toHaveCount(0)
  })
})
