# Release-candidate verification sweep — Phase 4

Date: 2026-10-23
Commit under test: `df9af7daefc85509773a5b787a8b5898df0f7293`
Branch: `complete-acceptance-criteria`
Scope: the eight required suites of
`workflow/administrator-vertical-slice.md` "Required verification", run against
one exact commit.

> **This record ticks no acceptance criterion.** It is the automated half of the
> release sweep. Criterion 14 and every criterion with a deployed leg stay open
> for the reasons recorded under "Why criterion 14 cannot tick yet".

## Environment

- Windows 11, Java 25.0.4.1 (Temurin), Node 24.16.0, Gradle 9.6.1.
- Application under browser test: `build/libs/incident-desk-web.jar`, rebuilt
  from this commit's frontend assets, started on port 8097 over plain HTTP with
  `INCIDENT_DESK_SECURE_COOKIES=false`.
- Database: isolated Neon PostgreSQL branch, schema `e2e_browser_suite` for the
  browser suite, with `INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED=true`. The
  test endpoint `ep-blue-king-a16hzpp0` is a different Neon endpoint from the
  production endpoint `ep-rough-band-a1g2t0hk`; no production or deployed
  dataset was read or written.
- Browser: Chromium via Playwright 1.64.1; scanning via `@axe-core/playwright`
  4.10.2 / `axe-core` 4.10.3.
- `AI_ENABLED=false` in the browser suite, by the boundary recorded in
  `2026-10-23-admin-browser-a11y-harness.md`.

## Results by required suite

| # | Required suite | Command | Result |
| --- | --- | --- | --- |
| 1 | Domain/application | `gradlew build` | PASSED — 854 tests, 0 failures, 0 errors, 149 skipped |
| 2 | Spring MVC/security | included in `gradlew build` | PASSED — counted in the 854 above |
| 3 | PostgreSQL integration | `gradlew registrationDatabaseTest --rerun-tasks` | PASSED — 13 classes, 94 tests, 0 failures, 0 skipped |
| 4 | React component | `npx vitest run` | PASSED — 17 files, 151 tests |
| 5 | Browser end-to-end | `gradlew administratorBrowserTest` | PASSED — 43 of 58, 0 failures, 0 skipped |
| 6 | AI gateway | included in suites 1 and 3 | PASSED — 121 AI tests in `:test` (73 skipped there, Postgres-backed) plus 19 in the PostgreSQL task, 0 failures |
| 7 | Responsive and accessibility | `gradlew administratorBrowserTest` (accessibility project) | PASSED — 15 of 58, 0 failures |
| 8 | Deployed HTTPS smoke | `curl` against the deployed service | **PARTIAL, and against a different commit** — see below |

Supporting frontend checks, same commit: `npx tsc -b --pretty false` PASSED,
`npx eslint . --max-warnings 0` PASSED.

Migration evidence: seeding applied 11 Flyway migrations to an empty schema,
reaching v11, with no manual schema edits.

### The 149 skips in suite 1 are expected

Every skip is a `*PostgresTest` class, which self-skips when no isolated
database is configured for the plain `:test` task. Those same classes run in
suite 3, where they report 94 tests and 0 skips. No test is skipped in both.

## Suite 5 and 7 detail

58 tests, all passing, zero skipped:

- Denial sweep (9): anonymous, reporter, and responder requests to
  administrator APIs; a denied route indistinguishable from a missing one;
  generic sign-in failure for wrong portal, wrong password, and unknown
  account; a mutation without a CSRF token; a missing incident.
- Mutation families, one success and one denied/stale path each: assign and
  reassign, unassign, resolve, comment, responder category replacement,
  temporary-password reset, tombstoning, promotion decision, SLO version,
  password change, logout.
- Navigation: all four destinations, incident detail, promotion requests, and
  the AI insight panel.
- Accessibility (15): zero WCAG 2.2 AA violations on portal selection, the
  sign-in form, the sign-in error state, all four destinations, and incident
  detail, at 1440px, 900px and 390px and at 200% zoom; one level-one heading
  per destination; keyboard-only traversal; forward tab order; dialog focus
  trap, Escape close, and focus return; a visible focus indicator; reflow with
  no horizontal scrolling.

### A harness defect this sweep found and fixed

The previous run of this suite reported "56 passed" with two silent skips. Both
promotion-decision specs guarded themselves with an unawaited
`approve.count()`. The request list loads asynchronously after the panel heading
renders, so the guard always read the pre-load DOM, counted zero, and skipped —
on a freshly seeded schema, with the specs running alone, and with the seeded
request still pending. The decision paths had therefore never been exercised in
a browser, while the suite reported success.

Diagnosis is recorded by a temporary probe: `IMMEDIATE_COUNT 0` against
`AFTER_WAIT_COUNT 1`, with `GET /api/v1/admin/promotion-requests` returning the
seeded `PENDING` request and HTTP 200 throughout.

Running the approve path then failed on a second spec defect: `getByLabel('IT')`
also matches "Facilities", so the assertion failed Playwright strict mode. Both
checkboxes were already checked, so the application was correct here too.

Both defects were in the harness; no application behaviour changed. Fixed in
`df9af7d`, after which the suite reports 58 passed and 0 skipped. This is the
difference between a suite that passes and a suite that verifies.

## Deployed verification (suite 8) — partial

Service: `https://incidentdesk-run-660566661066.asia-southeast1.run.app`.
Read-only, unauthenticated requests only.

Observed:

- `GET /health` returns HTTP 200 and `{"status":"UP"}` over HTTPS with a valid
  certificate chain (`ssl_verify_result=0`).
- Plain HTTP redirects to HTTPS (302 to the `https://` origin).
- `GET /api/v1/admin/incidents`, `/api/v1/admin/promotion-requests` and
  `/api/v1/admin/statistics` each return 401 to an anonymous caller.
- Response headers carry `x-content-type-options: nosniff`,
  `x-frame-options: DENY`, `referrer-policy: no-referrer`, a
  `content-security-policy` of `default-src 'self'; base-uri 'none'; object-src
  'none'; frame-ancestors 'none'; form-action 'self'`, and `cache-control:
  no-cache, no-store, max-age=0, must-revalidate`.

Not covered: every authenticated, per-role deployed path. That needs production
administrator, responder, and reporter credentials, which this sweep
deliberately did not use and which no agent should exercise unattended. The
precedent for a deployed leg in this repository is a named human confirming the
check (see `ADM-ACC-03-E`).

Observation, not a defect: no `Strict-Transport-Security` header is present. No
authoritative artifact requires HSTS, so this is recorded for a human decision
rather than raised as a violation.

## Why criterion 14 cannot tick yet

Criterion 14 requires the checks to be recorded against "the exact release
commit". Three facts prevent that:

1. The release candidate `df9af7d` is **not on `main`**. `origin/main` is at
   `870a59c`, which this branch does not contain (it adds `7c94ee5` and the
   merge `870a59c`, a peer-review-guide removal). The branch is still based on
   `b48e2a8`.
2. The deployed service runs `870a59c`, not `df9af7d`. The deployed evidence
   above therefore describes **different code** from the commit the automated
   suites verified, and cannot be presented as same-commit evidence.
3. The deployed leg is unauthenticated only, so the per-role deployed
   verification several criteria name is absent.

The automated suites are green on one exact commit. The deployed half is not,
and tying them together is a release step that needs a merge to `main`, a deploy
of that merged commit, and a human-run authenticated check.

## Residual risks

- The browser suite's mutation specs run serially against one seeded dataset and
  claim incidents by title; a change to `BrowserTestDataSeeder`'s status cycle
  requires the title lists in `incident-mutations.e2e.ts` to change with it.
- The promotion-decision specs remain order-dependent by construction: the
  seeder creates exactly one pending request and approving consumes it, so the
  cancel path is declared first. This is now explicit in the spec's comment
  rather than hidden behind a skip.
- `.env.test.local` sets `NEON_BRANCH=production` while pointing at the isolated
  test endpoint. The value is misleading; it was verified not to be the
  production endpoint before any suite ran. Worth correcting so the label cannot
  be trusted wrongly later.
- The CI job for the browser suite has still not run on GitHub Actions against a
  hosted PostgreSQL service container.
- Workflow gates 2, 4 and 5 are untouched. No approval is recorded here, and no
  agent may record one.
