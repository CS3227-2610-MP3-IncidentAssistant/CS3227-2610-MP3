import AxeBuilder from '@axe-core/playwright'
import { expect, type Page } from '@playwright/test'

/**
 * Accessibility helpers for administrator slice required verification 7:
 * automated scanning plus wide, medium, and narrow widths and 200% zoom.
 *
 * `.agents/STYLE_GUIDE.md` targets WCAG 2.2 AA, so the scan runs the WCAG 2.0
 * and 2.1 A/AA tags that axe-core implements, plus `best-practice` is
 * deliberately excluded: it reports advisory items that are not AA failures.
 */
const WCAG_AA_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

/** The three widths named in the interaction and accessibility contract. */
export const widths = {
  wide: { width: 1440, height: 900 },
  medium: { width: 900, height: 800 },
  narrow: { width: 390, height: 844 },
} as const

export type WidthName = keyof typeof widths

/** Runs an axe scan and asserts no WCAG AA violations, naming any that appear. */
export async function expectNoAccessibilityViolations(page: Page, context: string) {
  const results = await new AxeBuilder({ page }).withTags(WCAG_AA_TAGS).analyze()
  const summary = results.violations.map(
    (violation) =>
      `${violation.id} (${violation.impact ?? 'unknown'}): ${violation.help} — ` +
      `${violation.nodes.length} node(s): ${violation.nodes
        .map((node) => node.target.join(' '))
        .join('; ')}`,
  )
  expect(summary, `axe-core WCAG AA violations at ${context}`).toEqual([])
}

/**
 * Emulates 200% zoom. A browser zoom of 200% at a 1280px viewport presents the
 * same CSS layout width as a 640px viewport at 100%, which is what the reflow
 * requirement (WCAG 1.4.10) actually constrains.
 */
export async function setZoomedViewport(page: Page) {
  await page.setViewportSize({ width: 640, height: 512 })
}

/** Asserts the page does not scroll horizontally, which reflow forbids. */
export async function expectNoHorizontalScroll(page: Page, context: string) {
  const overflow = await page.evaluate(() => {
    const root = document.documentElement
    return root.scrollWidth - root.clientWidth
  })
  expect(overflow, `horizontal page scroll at ${context}`).toBeLessThanOrEqual(1)
}

/** Collects the accessible names of the page's current tab sequence. */
export async function tabSequence(page: Page, steps: number): Promise<string[]> {
  const names: string[] = []
  for (let index = 0; index < steps; index++) {
    await page.keyboard.press('Tab')
    names.push(
      await page.evaluate(() => {
        const active = document.activeElement
        if (!active || active === document.body) return '<body>'
        const label =
          active.getAttribute('aria-label') ??
          active.textContent?.trim().replace(/\s+/g, ' ').slice(0, 60) ??
          ''
        return `${active.tagName.toLowerCase()}:${label}`
      }),
    )
  }
  return names
}
