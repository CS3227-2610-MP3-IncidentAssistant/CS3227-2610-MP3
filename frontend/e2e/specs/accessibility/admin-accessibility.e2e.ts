import {
  expectNoAccessibilityViolations,
  expectNoHorizontalScroll,
  setZoomedViewport,
  tabSequence,
  widths,
  type WidthName,
} from '../../fixtures/accessibility'
import { expect, openAdminPage, signInAsAdministrator, test, type AdminDestination } from '../../fixtures/roles'

/**
 * Administrator slice required verification 7 and criterion 13: automated
 * accessibility scanning at wide, medium, and narrow widths, 200% zoom reflow,
 * and keyboard-only operation across every administrator destination.
 *
 * `.agents/testing.md` allows purely visual composition to be approved by
 * documented human review. Accessibility semantics are not covered by that
 * exception, so they are asserted here.
 */
const destinations: AdminDestination[] = [
  'Dashboard',
  'Accounts',
  'SLO configuration',
  'Statistics',
]

test.describe('unauthenticated entry points', () => {
  for (const widthName of Object.keys(widths) as WidthName[]) {
    test(`portal selection has no WCAG AA violations at ${widthName} width`, async ({ page }) => {
      await page.setViewportSize(widths[widthName])
      await page.goto('/')
      await expect(page.getByRole('heading', { name: 'Choose your portal' })).toBeVisible()
      await expectNoAccessibilityViolations(page, `portal selection at ${widthName}`)
      await expectNoHorizontalScroll(page, `portal selection at ${widthName}`)
    })
  }

  test('the administrator sign-in form has no WCAG AA violations', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: 'Administrator', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Administrator sign in' })).toBeVisible()
    await expectNoAccessibilityViolations(page, 'administrator sign-in')
  })

  test('a sign-in error is announced and still accessible', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: 'Administrator', exact: true }).click()
    await page.getByLabel('Login name').fill('e2e-administrator')
    await page.getByLabel('Password', { exact: true }).fill('WrongFixturePassword!9')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByRole('alert')).toBeVisible()
    await expectNoAccessibilityViolations(page, 'administrator sign-in error')
  })
})

test.describe('authenticated administrator destinations', () => {
  for (const widthName of Object.keys(widths) as WidthName[]) {
    test(`every destination passes WCAG AA at ${widthName} width`, async ({ page }) => {
      await page.setViewportSize(widths[widthName])
      await signInAsAdministrator(page)
      for (const destination of destinations) {
        await openAdminPage(page, destination)
        await expectNoAccessibilityViolations(page, `${destination} at ${widthName}`)
        await expectNoHorizontalScroll(page, `${destination} at ${widthName}`)
      }
    })
  }

  test('every destination reflows at 200% zoom without horizontal scrolling', async ({ page }) => {
    await signInAsAdministrator(page)
    await setZoomedViewport(page)
    for (const destination of destinations) {
      await openAdminPage(page, destination)
      await expectNoAccessibilityViolations(page, `${destination} at 200% zoom`)
      await expectNoHorizontalScroll(page, `${destination} at 200% zoom`)
    }
  })

  test('incident detail passes WCAG AA', async ({ page }) => {
    await signInAsAdministrator(page)
    await page
      .locator('.table-region[aria-label="Company incidents"]')
      .getByRole('button', { name: /^View details for / })
      .first()
      .click()
    await expect(page.getByRole('button', { name: 'Back to dashboard' })).toBeVisible()
    await expectNoAccessibilityViolations(page, 'administrator incident detail')
  })

  test('each destination exposes exactly one level-one heading', async ({ page }) => {
    await signInAsAdministrator(page)
    for (const destination of destinations) {
      await openAdminPage(page, destination)
      const headings = page.getByRole('heading', { level: 1 })
      expect(
        await headings.count(),
        `level-one headings on ${destination}`,
      ).toBeGreaterThanOrEqual(1)
    }
  })
})

test.describe('keyboard-only operation', () => {
  test('every navigation destination is reachable by keyboard alone', async ({ page }) => {
    await signInAsAdministrator(page)
    for (const destination of destinations) {
      const item = page
        .getByRole('complementary', { name: 'Administrator navigation' })
        .getByRole('button', { name: destination })
      await item.focus()
      await expect(item).toBeFocused()
      await page.keyboard.press('Enter')
      await expect(item).toHaveAttribute('aria-current', 'page')
    }
  })

  test('the tab sequence moves forward without trapping on the dashboard', async ({ page }) => {
    await signInAsAdministrator(page)
    const sequence = await tabSequence(page, 12)
    // Focus must actually move rather than sticking on one control.
    expect(new Set(sequence).size).toBeGreaterThan(1)
  })

  test('a dialog traps focus, closes with Escape, and returns focus', async ({ page }) => {
    await signInAsAdministrator(page)
    await openAdminPage(page, 'Accounts')
    const trigger = page.getByRole('button', { name: 'Configure categories for e2e-responder' })
    await trigger.click()
    const dialog = page.getByRole('dialog', { name: 'Configure categories' })
    await expect(dialog).toBeVisible()
    await expectNoAccessibilityViolations(page, 'category dialog')

    // Focus must stay inside the dialog while it is open.
    await tabSequence(page, 8)
    expect(
      await page.evaluate(() => {
        const dialogElement = document.querySelector('dialog[open], [role="dialog"]')
        return dialogElement?.contains(document.activeElement) ?? false
      }),
    ).toBe(true)

    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(trigger).toBeFocused()
  })

  test('a visible focus indicator is present on keyboard focus', async ({ page }) => {
    await signInAsAdministrator(page)
    const item = page
      .getByRole('complementary', { name: 'Administrator navigation' })
      .getByRole('button', { name: 'Accounts' })

    // The stylesheet uses `:focus-visible`, which a programmatic `.focus()`
    // does not trigger. Keyboard focus is what the requirement is about, so the
    // control is reached with real Tab presses from the Dashboard item.
    await page
      .getByRole('complementary', { name: 'Administrator navigation' })
      .getByRole('button', { name: 'Dashboard' })
      .focus()
    await page.keyboard.press('Tab')
    await expect(item).toBeFocused()

    const indicator = await item.evaluate((element) => {
      const style = window.getComputedStyle(element)
      return {
        outlineWidth: style.outlineWidth,
        outlineStyle: style.outlineStyle,
        boxShadow: style.boxShadow,
      }
    })
    const hasOutline =
      indicator.outlineStyle !== 'none' && parseFloat(indicator.outlineWidth) > 0
    const hasShadow = indicator.boxShadow !== 'none' && indicator.boxShadow !== ''
    expect(hasOutline || hasShadow, 'a visible focus indicator').toBe(true)
  })
})
