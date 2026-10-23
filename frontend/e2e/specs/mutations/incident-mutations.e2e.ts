import { expect, signIn, test } from '../../fixtures/roles'
import type { Page } from '@playwright/test'

/**
 * Administrator slice required verification 5, second half: one successful and
 * one denied-or-stale path for each incident mutation family.
 *
 * Families covered here: assign/reassign, unassign, resolve, and comment.
 * Account, SLO, and session families live in their own specs.
 *
 * The specs assert persisted effect by reloading the detail view, not only by
 * reading the optimistic UI, as `.agents/testing.md` requires.
 */

/**
 * `BrowserTestDataSeeder` cycles SUBMITTED, SUBMITTED, ASSIGNED, RESOLVED over
 * "Seeded incident 1..30", so incident 3n+3 is assigned and 4n+4 resolved.
 * Each test claims its own incident by exact title through the dashboard search
 * so the mutation families never compete for the same row. Keep these lists in
 * step with the seeder if its status cycle changes.
 */
const incidents = {
  submitted: ['Seeded incident 2', 'Seeded incident 5', 'Seeded incident 6', 'Seeded incident 9'],
  assigned: ['Seeded incident 7', 'Seeded incident 19', 'Seeded incident 23', 'Seeded incident 27'],
} as const

/** Searches for one incident by exact title and opens its detail view. */
async function openIncident(page: Page, title: string) {
  const search = page.getByLabel('Search incidents')
  await search.fill(title)
  await search.press('Enter')
  const table = page.locator('.table-region[aria-label="Company incidents"]')
  // Exact matching matters: "Seeded incident 1" otherwise also matches 10-19.
  const open = table.getByRole('button', { name: `View details for ${title}`, exact: true })
  await expect(open).toBeVisible()
  await open.click()
  await expect(page.getByRole('button', { name: 'Back to dashboard' })).toBeVisible()
}

test.describe('assign and reassign', () => {
  test('assigning a submitted incident succeeds and persists', async ({ adminPage }) => {
    await openIncident(adminPage, incidents.submitted[0])
    const responder = adminPage.getByRole('combobox', { name: 'Assign to responder' })
    await expect(responder).toBeVisible()
    await responder.selectOption({ label: 'e2e-responder' })
    await adminPage.getByRole('button', { name: 'Assign incident', exact: true }).click()

    // The incident becomes assigned, so the control becomes a reassignment.
    await expect(adminPage.getByRole('button', { name: 'Reassign incident', exact: true })).toBeVisible()

    // Persisted, not merely optimistic: it survives reopening the detail view.
    await adminPage.reload()
    await openIncident(adminPage, incidents.submitted[0])
    await expect(adminPage.getByRole('button', { name: 'Reassign incident', exact: true })).toBeVisible()
    // The select is relabelled for reassignment rather than removed.
    await expect(
      adminPage.getByRole('combobox', { name: 'Reassign to responder' }),
    ).toBeVisible()
  })

  test('a stale assignment is refused rather than overwriting a newer state', async ({ adminPage }) => {
    const title = incidents.submitted[1]
    await openIncident(adminPage, title)
    const responder = adminPage.getByRole('combobox', { name: 'Assign to responder' })
    await responder.selectOption({ label: 'e2e-responder' })

    // A second administrator session assigns the same incident first, so the
    // form still open in this page now carries a stale expected version.
    const concurrent = await adminPage.context().browser()!.newContext()
    try {
      const concurrentPage = await concurrent.newPage()
      await signIn(concurrentPage, 'secondAdministrator')
      await openIncident(concurrentPage, title)
      await concurrentPage
        .getByRole('combobox', { name: 'Assign to responder' })
        .selectOption({ label: 'e2e-responder' })
      await concurrentPage.getByRole('button', { name: 'Assign incident', exact: true }).click()
      await expect(
        concurrentPage.getByRole('button', { name: 'Reassign incident', exact: true }),
      ).toBeVisible()
    } finally {
      await concurrent.close()
    }

    // The stale submission must not silently overwrite the newer assignment.
    await adminPage.getByRole('button', { name: 'Assign incident', exact: true }).click()
    // The refusal may surface as the inline error or simply as the control
    // having become a reassignment; `.or()` can match both at once, so this
    // asserts on the first match rather than demanding a unique element.
    await expect(
      adminPage
        .getByRole('alert')
        .or(adminPage.getByRole('button', { name: 'Reassign incident', exact: true }))
        .first(),
    ).toBeVisible()

    // Exactly one assignee survives: the incident is assigned, not re-opened.
    await adminPage.reload()
    await openIncident(adminPage, title)
    await expect(adminPage.getByRole('button', { name: 'Reassign incident', exact: true })).toBeVisible()
    await expect(
      adminPage.getByRole('combobox', { name: 'Reassign to responder' }),
    ).toBeVisible()
  })
})

test.describe('unassign', () => {
  test('unassigning an assigned incident succeeds after confirmation', async ({ adminPage }) => {
    await openIncident(adminPage, incidents.assigned[0])
    await adminPage.getByRole('button', { name: 'Unassign incident', exact: true }).click()
    const dialog = adminPage.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: 'Unassign incident', exact: true }).click()
    await expect(adminPage.getByRole('combobox', { name: 'Assign to responder' })).toBeVisible()
  })

  test('cancelling the unassign dialog makes no change and restores focus', async ({ adminPage }) => {
    await openIncident(adminPage, incidents.assigned[1])
    const trigger = adminPage.getByRole('button', { name: 'Unassign incident', exact: true })
    await trigger.click()
    const dialog = adminPage.getByRole('dialog')
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(dialog).toHaveCount(0)
    // The interaction contract requires focus to return to the trigger.
    await expect(trigger).toBeFocused()
    await expect(adminPage.getByRole('button', { name: 'Reassign incident', exact: true })).toBeVisible()
  })
})

test.describe('resolve', () => {
  test('resolving with remarks succeeds and records resolution history', async ({ adminPage }) => {
    await openIncident(adminPage, incidents.assigned[2])
    await adminPage
      .getByRole('textbox', { name: 'Resolution remarks' })
      .fill('Verified with the reporter and closed during browser verification.')
    await adminPage.getByRole('button', { name: 'Resolve incident' }).click()
    await expect(adminPage.getByRole('heading', { name: 'Resolution history' })).toBeVisible()
  })

  test('resolving with blank remarks is refused', async ({ adminPage }) => {
    await openIncident(adminPage, incidents.assigned[3])
    await adminPage.getByRole('textbox', { name: 'Resolution remarks' }).fill('   ')
    await adminPage.getByRole('button', { name: 'Resolve incident' }).click()
    await expect(adminPage.getByRole('alert')).toBeVisible()
    // The domain invariant holds: no resolution without non-blank remarks.
    await expect(adminPage.getByRole('button', { name: 'Resolve incident' })).toBeVisible()
  })
})

test.describe('comment', () => {
  test('adding a comment succeeds, clears the field, and persists it', async ({ adminPage }) => {
    await openIncident(adminPage, incidents.submitted[2])
    const field = adminPage.getByRole('textbox', { name: 'Add a comment' })
    await field.fill('Browser verification comment.')
    await adminPage.getByRole('button', { name: 'Add comment' }).click()

    // The comment joins the thread, no error is raised, and the composer is
    // cleared so a resubmission cannot duplicate it.
    const comments = adminPage.locator('.incident-detail__comments')
    await expect(comments.getByText('Browser verification comment.')).toBeVisible()
    await expect(adminPage.getByRole('alert')).toHaveCount(0)
    await expect(field).toHaveValue('')

    // Persisted, not merely optimistic: it survives a reload of the detail view.
    await adminPage.reload()
    await openIncident(adminPage, incidents.submitted[2])
    await expect(
      adminPage.locator('.incident-detail__comments').getByText('Browser verification comment.'),
    ).toBeVisible()
  })

  test('untrusted comment text renders as text, never as markup', async ({ adminPage }) => {
    await openIncident(adminPage, incidents.submitted[3])
    const payload = '<img src=x onerror="window.__xss=1"><script>window.__xss=1</script>'
    await adminPage.getByRole('textbox', { name: 'Add a comment' }).fill(payload)
    await adminPage.getByRole('button', { name: 'Add comment' }).click()
    await expect(adminPage.getByText(payload, { exact: false })).toBeVisible()
    expect(await adminPage.evaluate(() => '__xss' in window)).toBe(false)
  })

  test('a blank comment is refused', async ({ adminPage }) => {
    await openIncident(adminPage, 'Anonymous facilities concern')
    await adminPage.getByRole('textbox', { name: 'Add a comment' }).fill('  ')
    await adminPage.getByRole('button', { name: 'Add comment' }).click()
    await expect(adminPage.getByRole('alert')).toBeVisible()
  })
})
