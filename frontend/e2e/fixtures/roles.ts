import { test as base, expect, type Page } from '@playwright/test'

/**
 * Role fixtures for the administrator browser suite.
 *
 * Every sign-in goes through the real portal UI against the real Spring Boot
 * application, so server-side authentication, session rotation, and CSRF are
 * exercised rather than simulated. No fixture writes a cookie or a session row
 * directly, and no fixture bypasses the portal role check.
 *
 * The credentials below mirror `BrowserTestDataSeeder` and are fixture-only
 * values for a throwaway schema. They are never valid against a deployed
 * environment.
 */
export const accounts = {
  administrator: { loginName: 'e2e-administrator', portal: 'Administrator' },
  secondAdministrator: { loginName: 'e2e-administrator-two', portal: 'Administrator' },
  responder: { loginName: 'e2e-responder', portal: 'Responder' },
  reporter: { loginName: 'e2e-reporter', portal: 'Reporter' },
  temporaryCredential: { loginName: 'e2e-temporary', portal: 'Reporter' },
} as const

export const fixturePassword = 'E2eFixturePassword!1'

export type RoleName = keyof typeof accounts

/** Signs in through the portal UI and waits for the authenticated shell. */
export async function signIn(page: Page, role: RoleName, password = fixturePassword) {
  const account = accounts[role]
  await page.goto('/')
  await page.getByRole('button', { name: account.portal, exact: true }).click()
  await expect(page.getByRole('heading', { name: `${account.portal} sign in` })).toBeVisible()
  await page.getByLabel('Login name').fill(account.loginName)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
}

/**
 * The administrator sidebar. `RoleShell` puts `aria-label` on the `<aside>`
 * element, so this is a `complementary` landmark rather than a named
 * `navigation` landmark.
 */
export function adminSidebar(page: Page) {
  return page.getByRole('complementary', { name: 'Administrator navigation' })
}

/**
 * `RoleShell` collapses the sidebar behind a menu button at 720px and below,
 * marking it `inert` until opened. This opens it when collapsed so the same
 * helpers work at every width.
 */
async function revealNavigation(page: Page) {
  const menu = page.getByRole('button', { name: 'Menu' })
  if (await menu.isVisible().catch(() => false)) {
    if ((await menu.getAttribute('aria-expanded')) !== 'true') {
      await menu.click()
    }
  }
}

/** Signs in as an administrator and waits for the authenticated shell. */
export async function signInAsAdministrator(page: Page) {
  await signIn(page, 'administrator')
  // The topbar menu button only exists inside the authenticated shell, so its
  // presence (wide or narrow) confirms sign-in completed.
  await expect(
    adminSidebar(page).or(page.getByRole('button', { name: 'Menu' })).first(),
  ).toBeVisible()
}

/** The four administrator navigation destinations, as labelled in `AdminShell`. */
export type AdminDestination = 'Dashboard' | 'Accounts' | 'SLO configuration' | 'Statistics'

/** Moves to one of the administrator navigation destinations. */
export async function openAdminPage(page: Page, destination: AdminDestination) {
  await revealNavigation(page)
  await adminSidebar(page).getByRole('button', { name: destination }).click()
  await revealNavigation(page)
  await expect(
    adminSidebar(page).getByRole('button', { name: destination }),
  ).toHaveAttribute('aria-current', 'page')
}

type RoleFixtures = {
  /** An administrator page already signed in and resting on the dashboard. */
  adminPage: Page
}

export const test = base.extend<RoleFixtures>({
  adminPage: async ({ page }, use) => {
    await signInAsAdministrator(page)
    await use(page)
  },
})

export { expect }
