import type { Page } from '@playwright/test'
import { expect, openAdminPage, test } from '../../fixtures/roles'

/**
 * The dashboard labels both the outer panel and the inner scrollable table
 * region "Company incidents". `.table-region` is the inner scroll container,
 * which is the one holding the rows.
 */
function incidentTable(page: Page) {
  return page.locator('.table-region[aria-label="Company incidents"]')
}

/**
 * Administrator slice required verification 5, first half: every administrator
 * navigation destination loads for an authenticated administrator.
 *
 * `AdminShell` is a single-page, state-driven shell rather than a router, so a
 * "destination" is a view transition. Incident detail, promotion requests, and
 * the AI insight panel are reached inside those views, so they are covered here
 * as destinations too.
 */
test.describe('administrator navigation destinations', () => {
  test('dashboard is the landing destination and lists seeded incidents', async ({ adminPage }) => {
    await expect(adminPage.getByRole('heading', { name: 'Company incident overview' })).toBeVisible()
    const incidents = incidentTable(adminPage)
    await expect(incidents).toBeVisible()
    await expect(incidents.getByRole('row')).not.toHaveCount(0)
  })

  test('incident detail opens from the dashboard', async ({ adminPage }) => {
    await incidentTable(adminPage)
      .getByRole('button', { name: /^View details for / })
      .first()
      .click()
    // The detail view titles the incident at level 2 under the shell heading.
    await expect(adminPage.getByRole('button', { name: 'Back to dashboard' })).toBeVisible()
    await expect(adminPage.getByRole('heading', { level: 2 }).first()).toBeVisible()
  })

  test('accounts destination loads the directory', async ({ adminPage }) => {
    await openAdminPage(adminPage, 'Accounts')
    await expect(adminPage.getByRole('table').first()).toBeVisible()
    // The login name is the row's header cell, not a plain cell.
    await expect(
      adminPage.getByRole('rowheader', { name: 'e2e-responder', exact: true }),
    ).toBeVisible()
  })

  test('promotion requests are reachable on the accounts destination', async ({ adminPage }) => {
    await openAdminPage(adminPage, 'Accounts')
    // Reachability only. The exact match matters because the panel's empty
    // state renders "No pending promotion requests", which a substring match
    // would also select once a decision test has consumed the seeded request.
    await expect(
      adminPage.getByRole('heading', { name: 'Promotion requests', exact: true }),
    ).toBeVisible()
  })

  test('SLO configuration destination loads current targets', async ({ adminPage }) => {
    await openAdminPage(adminPage, 'SLO configuration')
    await expect(adminPage.getByRole('heading', { name: /IT$/ }).first()).toBeVisible()
  })

  test('statistics destination loads company statistics', async ({ adminPage }) => {
    await openAdminPage(adminPage, 'Statistics')
    await expect(adminPage.getByRole('heading', { name: 'Company statistics' })).toBeVisible()
    await expect(
      adminPage.getByRole('heading', { name: 'Incident outcomes' }),
    ).toBeVisible()
  })

  test('AI insight panel is reachable from statistics', async ({ adminPage }) => {
    await openAdminPage(adminPage, 'Statistics')
    await expect(
      adminPage.getByRole('heading', { name: 'Ask about operational statistics' }),
    ).toBeVisible()
    await expect(adminPage.getByLabel('Question')).toBeVisible()
  })

  test('every destination marks exactly one navigation item current', async ({ adminPage }) => {
    for (const destination of ['Dashboard', 'Accounts', 'SLO configuration', 'Statistics'] as const) {
      await openAdminPage(adminPage, destination)
      await expect(adminPage.locator('[aria-current="page"]')).toHaveCount(1)
    }
  })
})
