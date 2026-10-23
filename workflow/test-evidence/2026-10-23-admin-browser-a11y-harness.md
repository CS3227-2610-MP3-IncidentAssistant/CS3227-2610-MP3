# Administrator browser and accessibility harness — Phase 2 evidence

Date: 2026-10-23
Commit: working tree based on `3b4c677747817f14986fc9096c5825ceb13c6ce3`
Branch: `complete-acceptance-criteria`
Scope: harness for `workflow/administrator-vertical-slice.md` required
verification 5 (browser end-to-end) and 7 (responsive and accessibility).

This record covers the harness, its first full run, and the two contrast
defects that run found and that were then fixed. It does **not** tick any slice
acceptance criterion: the criteria require evidence against an exact release
commit plus a deployed HTTPS leg (Phases 3 and 4).

## Environment

- Windows 11, Java 25 (Temurin), Node 24, Gradle 9.6.1.
- Application: `build/libs/incident-desk-web.jar`, started by the harness with
  `INCIDENT_DESK_SECURE_COOKIES=false` over plain HTTP on port 8097.
- Database: isolated Neon PostgreSQL 18.6 schema `e2e_browser_suite`, created
  and seeded out of band, with
  `INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED=true`. No production or
  deployed dataset was read or written.
- Browser: Chromium via Playwright 1.64.1; scanning via `@axe-core/playwright`
  4.10.2 / `axe-core` 4.10.3.
- `AI_ENABLED=false` (see "Deliberate boundaries").

## What was added

| Area | Path |
| --- | --- |
| Playwright configuration, two projects | `frontend/playwright.config.ts` |
| Server launcher (isolation-gated) | `frontend/e2e/support/start-server.mjs` |
| Role and navigation fixtures | `frontend/e2e/fixtures/roles.ts` |
| Accessibility helpers | `frontend/e2e/fixtures/accessibility.ts` |
| Navigation destinations | `frontend/e2e/specs/navigation/destinations.e2e.ts` |
| Incident mutations | `frontend/e2e/specs/mutations/incident-mutations.e2e.ts` |
| Account mutations | `frontend/e2e/specs/mutations/account-mutations.e2e.ts` |
| SLO and session mutations | `frontend/e2e/specs/mutations/slo-and-session.e2e.ts` |
| Route and API denial | `frontend/e2e/specs/denied/route-and-api-denial.e2e.ts` |
| Accessibility and keyboard | `frontend/e2e/specs/accessibility/admin-accessibility.e2e.ts` |
| Isolated seeder (test-only) | `src/test/java/com/company/incidentdesk/support/BrowserTestDataSeeder.java` |
| Contrast token fix | `frontend/src/styles.css`, `.agents/STYLE_GUIDE.md` |
| Comment-clearing fix and test | `frontend/src/admin/AdministratorIncidentDetail.tsx`, `...test.tsx` |
| Gradle orchestration | `build.gradle` (`administratorBrowserTest` and helpers) |
| CI job | `.github/workflows/ci.yml` (`administrator-browser-tests`) |

## Commands and results

Seeding, run against the isolated schema:

```
gradlew dropBrowserTestSchema seedBrowserTestSchema --offline -PfrontendSkipBuild=true
```

PASSED. 11 Flyway migrations applied to an empty schema; fixture accounts and
33 incidents seeded.

Functional project:

```
npx playwright test --project=navigation-and-mutations
```

**41 passed, 0 failed.** Covers all four navigation destinations plus incident
detail, promotion requests and the AI insight panel; one success and one
denied/stale path for assign/reassign, unassign, resolve, comment, category
replacement, temporary-password reset, tombstoning, promotion decision, SLO
version, password change and logout; and the denial sweep (anonymous, reporter,
responder, wrong-portal, wrong password, unknown account, missing CSRF token,
missing incident).

Accessibility project:

```
npx playwright test --project=accessibility
```

First run: **5 passed, 10 failed**, every failure the single `color-contrast`
rule. After the two fixes in "Findings": **15 passed, 0 failed** — zero WCAG AA
violations on the portal selection, administrator sign-in, the sign-in error
state, all four administrator destinations and incident detail, at 1440px,
900px and 390px widths and at 200% zoom. Also passing: keyboard-only traversal
of every destination, dialog focus trap, Escape close, focus return to trigger,
one level-one heading per destination, visible keyboard focus indicator, and
reflow with no horizontal scrolling.

Frontend checks, unchanged by this work:

```
npx tsc -b --pretty false      PASSED
npx eslint . --max-warnings 0  PASSED
npx vitest run                 PASSED (17 files, 151 tests)
```

Not run in this session: `gradlew check`, `registrationDatabaseTest`, the
deployed HTTPS smoke test. `gradlew bootJar` succeeded only with
`-PfrontendSkipBuild=true`; `frontendInstall` (`npm ci`) fails locally on a
Windows file lock over `@rolldown/binding-win32-x64-msvc`. CI runs `npm ci` on
a clean checkout and is unaffected.

## Findings

Both findings were defects in the application, not in the harness. Both are
fixed and re-verified in this session.

### 1. `--color-text-muted` failed WCAG 2.2 AA contrast (FIXED)

`frontend/src/styles.css` set `--color-text-muted: #6e788a`, which measures
**4.45:1** on `#FFFFFF` and **4.15:1** on the `#F5F7FB` page background. WCAG
2.2 AA requires 4.5:1 for normal-size text, so the token failed everywhere it
was used — up to 54 nodes on a single administrator page, and on the
unauthenticated portal and sign-in pages too.

This also exposed a conflict between two authoritative statements in
`.agents/STYLE_GUIDE.md`: the accessibility baseline requires WCAG 2.2 AA for
contrast, while the token table specified `#6E788A`. Both could not hold.

Resolution: the token is now **`#5D6678`**, which measures **5.77:1** on
`#FFFFFF` and **5.38:1** on `#F5F7FB`. It keeps the original hue and stays a
clearly secondary tone against `--color-text` (`#18243A`), so the visual
hierarchy is unchanged while the margin above 4.5:1 leaves room for future
tuning. `#687283` would have been the smallest compliant change at 4.53:1 on
the canvas, but that margin is too thin to survive incidental edits.

Both the specification and the implementation were updated together:

- `.agents/STYLE_GUIDE.md` token table now reads `#5D6678`.
- A note under the table records the measured ratios, states that text tokens
  must meet AA against both `--color-surface` and `--color-canvas`, and points
  at the axe scan that now guards the rule.
- `frontend/src/styles.css` sets the new value.

### 2. `.metric-card small` hardcoded a failing colour (FIXED)

With finding 1 fixed, the scan surfaced a second, narrower defect that the
first had masked: `.metric-card small` hardcoded `color: #8993a6`, measuring
only **3.09:1** on `#FFFFFF` and **2.88:1** on the canvas. At 11px this is
normal-size text for WCAG purposes, so it needed 4.5:1. It affected the nine
SLO metric-card captions on the dashboard and the SLO page.

The literal also violated the style guide's own rule that components consume
tokens rather than repeating literal colors, which is why it escaped the token
fix. Resolution: it now uses `var(--color-text-muted)`.

Every other hardcoded color in the stylesheet was then measured against its
real background and passes: the sidebar greys `#9EA9C0`, `#AEBBD2`, `#B7C1D7`,
`#C7CEE0` and `#CBD7FF` sit on `--color-navy` at 6.59:1 to 10.90:1, and the
semantic tones `#14532D` and `#713F12` sit on their soft backgrounds at 7.98:1
and 8.07:1. `#8993A6` was the only offender.

### 3. The comment field was not cleared after a successful comment (FIXED)

`CommentComposer` kept its text after a successful post (verified: the API
returns 200 and the comment is persisted). The intent was already correct — its
own code comment states that "the parent reloads on success, which remounts this
composer empty" — but the remount never happened: `reload()` bumps a revision
counter that re-fetches data, while React kept the same element position, so the
component's state survived.

Resolution: the composer is keyed on the revision, so a successful mutation
remounts it empty while a failure, which does not reload, still preserves the
unsent text for another attempt. This makes the existing behaviour match its
documented intent rather than changing the contract.

`ResolutionForm` needs no equivalent change: it renders only while the incident
is `ASSIGNED`, so a successful resolve unmounts it entirely.

Coverage added:

- `AdministratorIncidentDetail.test.tsx` now asserts the field clears on
  success, alongside the existing test that it is preserved on failure.
- The browser spec asserts the comment appears in the thread, no alert is
  raised, the field is cleared, and the comment survives a reload.

## Deliberate boundaries

- **No test-only endpoint ships.** Seeding is a `src/test/java` main class using
  JDBC, mirroring the existing PostgreSQL suites. The running application keeps
  its production security wiring.
- **AI is disabled in the browser suite.** `SoclaasSettings.from` rejects any
  `SOCLAAS_BASE_URL` other than the pinned production HTTPS origin, and the fake
  provider is reachable only through a package-private Java seam. Driving the
  browser suite at a fake provider would require weakening that control, so the
  suite verifies the specified AI-unavailable path instead, and the adversarial
  AI evidence stays in the Java gateway suites (slice criterion 9).
- **Administrator provisioning is not exercised.** ADM-ACC-06-G gates any
  provisioning endpoint or UI behind recorded independent security and
  architecture approval. No browser evidence is produced for it.
- **Schema isolation.** The application builds its own Hikari pool from
  `DATABASE_URL` and accepts no schema setting, and this provider ignores the
  driver's `currentSchema` parameter (verified: `search_path` stayed
  `"$user", public`). The seeder therefore pins the schema with
  `ALTER ROLE CURRENT_USER SET search_path`, and `dropBrowserTestSchema` resets
  it. This only ever targets a test role on an explicitly confirmed isolated
  database.
- **Artifacts are not committed.** Traces and screenshots can embed seeded
  fixture data, so `frontend/e2e-results/` is gitignored and uploaded only as a
  CI artifact.

## Residual risks and remaining work

- No criterion is ticked here. Criteria 1, 3, 4, 5, 6, 7, 8, 12 and 13 still
  need their evidence assembled against an exact release commit, plus the
  deployed HTTPS leg (Phases 3 and 4).
- Criterion 13's documented human visual review is now **recorded** in
  `workflow/reviews/2026-10-23-admin-contrast-and-comment-visual-review.md`
  (approver Izn432, 2026-10-23, visual presentation only). The criterion still
  does not close: it additionally requires this automated responsive and
  accessibility evidence against an exact release commit plus the deployed
  HTTPS leg (Phases 3 and 4).
- That review covers visual presentation only. Workflow gate 4, independent
  review, remains open for this change set, as does explicit sign-off on
  resolving the style-guide contrast contradiction in favour of the WCAG AA
  baseline.
- The mutation specs run serially against one seeded dataset and claim distinct
  incidents by title. They depend on `BrowserTestDataSeeder`'s status cycle; if
  that cycle changes, the title lists in `incident-mutations.e2e.ts` must change
  with it.
- The promotion-decision specs skip themselves if no pending request remains,
  since the seeder creates exactly one and approve consumes it.
- The CI job has not yet run on GitHub Actions; it is wired but unverified
  against a hosted PostgreSQL service container.
- Human approval gates are untouched. No approval is recorded in this document.
