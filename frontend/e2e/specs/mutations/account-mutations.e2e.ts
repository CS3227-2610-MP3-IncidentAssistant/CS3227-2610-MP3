import { expect, openAdminPage, test } from '../../fixtures/roles'

/**
 * Administrator slice required verification 5: one successful and one
 * denied-or-stale path for each account mutation family — promotion decision,
 * responder category replacement, temporary-password reset, and tombstoning.
 *
 * Administrator provisioning is deliberately absent. ADM-ACC-06-G gates any
 * provisioning endpoint or UI behind recorded independent security and
 * architecture approval, so no browser evidence is produced for it here.
 */
test.beforeEach(async ({ adminPage }) => {
  await openAdminPage(adminPage, 'Accounts')
})

test.describe('responder category replacement', () => {
  test('replacing categories succeeds and reports the change', async ({ adminPage }) => {
    await adminPage
      .getByRole('button', { name: 'Configure categories for e2e-responder' })
      .click()
    const dialog = adminPage.getByRole('dialog', { name: 'Configure categories' })
    await expect(dialog).toBeVisible()
    // Seeded access is IT only, so toggling Facilities on is a real change.
    await dialog.getByRole('checkbox', { name: 'Facilities' }).click()
    await dialog.getByRole('button', { name: 'Save categories' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(adminPage.getByRole('status').first()).toBeVisible()
  })

  test('an unchanged category set cannot be submitted', async ({ adminPage }) => {
    await adminPage
      .getByRole('button', { name: 'Configure categories for e2e-responder' })
      .click()
    const dialog = adminPage.getByRole('dialog', { name: 'Configure categories' })
    // The contract disables the control and relabels it when nothing changed.
    await expect(dialog.getByRole('button', { name: 'No changes' })).toBeDisabled()
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(dialog).toHaveCount(0)
  })
})

test.describe('temporary-password reset', () => {
  test('resetting issues a one-time password and states the consequence', async ({ adminPage }) => {
    const trigger = adminPage.getByRole('button', { name: 'Reset password for e2e-reporter' })
    await trigger.click()
    const confirmation = adminPage.getByRole('dialog')
    await expect(confirmation).toBeVisible()
    // A credential-reset dialog must state its consequence before acting.
    await expect(confirmation).toContainText('expires in 24 hours')
    await confirmation.getByRole('button', { name: 'Generate temporary password' }).click()

    const reveal = adminPage.getByRole('dialog')
    const temporaryPassword = reveal.getByLabel('Temporary password')
    await expect(temporaryPassword).toBeVisible()
    await expect(temporaryPassword).not.toHaveValue('')
    await reveal.getByRole('button', { name: 'Done' }).click()

    // The one-time secret must not be retained in client storage.
    const stored = await adminPage.evaluate(() => ({
      local: JSON.stringify(window.localStorage),
      session: JSON.stringify(window.sessionStorage),
    }))
    expect(stored.local).not.toContain('Temporary')
    expect(stored.session).not.toContain('Temporary')
  })

  test('cancelling a reset issues nothing and restores focus', async ({ adminPage }) => {
    const trigger = adminPage.getByRole('button', { name: 'Reset password for e2e-reporter' })
    await trigger.click()
    const confirmation = adminPage.getByRole('dialog')
    await confirmation.getByRole('button', { name: 'Cancel' }).click()
    await expect(confirmation).toHaveCount(0)
    await expect(trigger).toBeFocused()
    await expect(adminPage.getByLabel('Temporary password')).toHaveCount(0)
  })
})

test.describe('account tombstoning', () => {
  test('tombstoning an account succeeds after an explicit confirmation', async ({ adminPage }) => {
    await adminPage.getByRole('button', { name: 'Delete account e2e-reporter' }).click()
    const confirmation = adminPage.getByRole('dialog')
    await expect(confirmation).toBeVisible()
    await expect(confirmation).toContainText('cannot be undone')
    await confirmation.getByRole('button', { name: 'Delete account' }).click()
    await expect(confirmation).toHaveCount(0)
    // The identity is retained as a tombstone rather than silently vanishing.
    await expect(adminPage.getByRole('status').first()).toBeVisible()
  })

  test('cancelling tombstoning leaves the account intact', async ({ adminPage }) => {
    // A distinct account from the successful-tombstone test above, so neither
    // test depends on the other's outcome.
    const trigger = adminPage.getByRole('button', { name: 'Delete account e2e-promotion-candidate' })
    await trigger.click()
    const confirmation = adminPage.getByRole('dialog')
    await confirmation.getByRole('button', { name: 'Cancel' }).click()
    await expect(confirmation).toHaveCount(0)
    await expect(trigger).toBeFocused()
    await adminPage.reload()
    await openAdminPage(adminPage, 'Accounts')
    // The login name is the row's header cell, not a plain cell.
    await expect(
      adminPage.getByRole('rowheader', { name: 'e2e-promotion-candidate', exact: true }),
    ).toBeVisible()
  })
})

test.describe('promotion decisions', () => {
  // The seeder creates exactly one pending request, for `e2e-promotion-candidate`.
  // Approving consumes it, so the non-consuming cancel path is declared first
  // and the suite runs serially. Promotion requests render on the accounts
  // destination, which the outer beforeEach has already opened.
  //
  // The request list loads asynchronously after the panel heading renders, so
  // the decision control is awaited rather than counted: an unawaited count()
  // observes the pre-load DOM and silently reports no pending request.
  //
  // Approving promotes the candidate to Responder, which cannot be undone
  // through the application, so these two tests are not idempotent against an
  // already-approved dataset. They therefore run only on a freshly seeded
  // schema: `administratorBrowserTest` reseeds before every run, and the
  // retry below is disabled so a retried attempt cannot re-enter them against
  // the dataset the first attempt consumed.
  test.describe.configure({ retries: 0 })

  test.beforeEach(async ({ adminPage }) => {
    await expect(
      adminPage.getByRole('heading', { name: 'Promotion requests', exact: true }),
    ).toBeVisible()
    await expect(
      adminPage.getByRole('button', { name: 'Approve selected categories' }),
    ).toBeVisible()
  })

  test('cancelling a decision leaves the request pending', async ({ adminPage }) => {
    const approve = adminPage.getByRole('button', { name: 'Approve selected categories' })

    await approve.click()
    await adminPage.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click()
    await expect(adminPage.getByRole('dialog')).toHaveCount(0)
    // Still pending, so the decision control is still offered.
    await expect(approve).toBeVisible()
  })

  test('approving a pending request requires explicit confirmation', async ({ adminPage }) => {
    const approve = adminPage.getByRole('button', { name: 'Approve selected categories' })

    // 'IT' is a substring of 'Facilities', so the checkbox is matched exactly.
    await expect(adminPage.getByRole('checkbox', { name: 'IT', exact: true })).toBeChecked()
    await approve.click()
    const dialog = adminPage.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: 'Confirm promotion' }).click()
    await expect(dialog).toHaveCount(0)
  })
})
