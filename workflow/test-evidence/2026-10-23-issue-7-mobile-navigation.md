# Issue #7 narrow-screen navigation accessibility step

- Date: 2026-10-23 (Asia/Singapore)
- Branch: `codex/issue-7-responder-api`; evidence recorded before this step's commit.
- Scope: shared role shell used by the Responder, Reporter, and Administrator portals.

At widths of 720px or less, the closed sidebar was translated offscreen but
its links and account actions remained keyboard-focusable and exposed to
assistive technology. The shared shell now makes the closed sidebar inert,
hides it at the responsive breakpoint, and returns focus to the visible Menu
button after a navigation choice. Wider layouts retain the always-visible
sidebar.

`RoleShell.test.tsx` exercises narrow and wide viewport states, open/closed
navigation semantics, and focus return. The test verifies the DOM state; it
does not substitute for browser keyboard or screen-reader observation.

| Check | Result |
| --- | --- |
| `npm run check` | Passed: typecheck, ESLint, 15 test files and 141 tests. |
| `./gradlew check` | Passed: frontend typecheck, ESLint, 15 test files and 141 tests; Java tests, production build, packaged JAR, and platform checks. |
| `git diff --check` | Passed. |

RSP-UI-01 and RSP-E2E-01 remain unchecked. A human still needs to verify
narrow-layout reflow, keyboard order and focus, screen-reader announcements,
comments and attachment download access, and the promoted-account
claim-to-resolution journey in a browser. This step did not use a personal or
production database and did not change incident data or authorization.
