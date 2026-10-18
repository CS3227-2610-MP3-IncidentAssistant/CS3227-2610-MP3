# SPA CSRF Flow Verification — 2026-10-18

## Scope and traceability

- Requirement: `REP-AUTH-01`, `REP-SELF-01`, and `ADM-SELF-01`
- Acceptance criterion: `REP-AC-02A`
- Architecture: `workflow/decisions/ADR-002-spa-csrf-token-flow.md`
- Tested working tree base: `b878ece2b2f825145d98b4a681d56f1272643593`
- Environment: Windows, Java `25.0.4.1`, Node.js `v24.16.0`

## Automated evidence

1. `npm.cmd test -- --run src/App.test.tsx src/reporter/ReporterDashboard.test.tsx`
   - Passed: 2 files, 17 tests.
   - Covered request-time `XSRF-TOKEN` use, bounded stale-token login and
     registration recovery, stale cross-tab logout recovery, already-anonymous
     logout completion, and Reporter mutation submission.

2. `npm.cmd run check`
   - Passed TypeScript typecheck, ESLint with zero warnings, and 19 React tests.
   - Additional cases proved that unrelated HTTP 403 responses do not refresh
     or retry and that an authenticated mutation is not retried after session
     refresh reports an anonymous actor.

3. `gradlew.bat --no-daemon test --tests
   com.company.incidentdesk.web.reporter.ReporterWebSecurityTest`
   - Passed all 4 focused Spring MVC security tests.
   - The lifecycle test proved anonymous token-cookie issuance, removal on
     successful login, post-login token issuance, rejection of a stale header
     against the current cookie, successful logout with the current cookie and
     header, and cookie removal on logout.

4. `gradlew.bat --no-daemon check`
   - Passed all repository checks.
   - Java: 500 tests completed, 4 skipped by the existing suite, 0 failed.
   - Frontend: typecheck, lint, 19 tests, and production Vite build passed.
   - Packaged startup probe, shadow JAR checks, and JavaFX platform verification
     passed.

The first complete Gradle run failed one legacy registration test because
Spring Security's generic `csrf().asHeader()` test helper supplies the masked
form-token representation while SPA mode expects the raw cookie token in the
header. The test was corrected to use the supported parameter representation;
the separate lifecycle test exercises the real SPA cookie/header protocol. The
complete Gradle check then passed.

## Review and remaining evidence

- Diff whitespace check: passed (`git diff --check`); Git reported only the
  repository's expected LF-to-CRLF checkout warnings.
- Code-quality review: passed with no findings after inspecting the completed
  security, React request helper, and test diff.
- Manual multi-tab browser verification: pending.
- Deployed HTTPS verification: pending.
- Independent security/architecture reviewer: pending.
- Human release approval: pending.
