# Administrator insight planning guardrails — 2026-10-23

## Scope

- Classify individual-incident extrema, details, identifiers, text, comments,
  and attachments as unsupported during query planning.
- Prohibit silently broadening an unsupported incident-level question into an
  operational overview or another aggregate query.
- Reject ranking-only `direction` or `limit` fields on every non-ranking plan.

## Automated verification

- `npm test -- AdministratorStatistics.test.tsx` from `frontend/`: passed, 1
  file and 7 tests.
- `./gradlew.bat --no-daemon test --tests
  "com.company.incidentdesk.application.ai.insights.AdministratorInsightPlanParserTest"
  --tests
  "com.company.incidentdesk.infrastructure.soclaas.AdministratorInsightPromptBuilderTest"
  --tests
  "com.company.incidentdesk.web.admin.AdministratorInsightServiceTest" --tests
  "com.company.incidentdesk.web.admin.AdministratorInsightControllerTest"`:
  passed.
- `./gradlew.bat check --no-daemon`: passed. This included frontend typecheck,
  lint, 135 frontend tests, the Java test suite, JavaFX platform verification,
  and shadow-JAR verification.
- `git diff --check`: passed.

The Gradle commands used repository-local `GRADLE_USER_HOME=.gradle`. The first
restricted-sandbox attempt could not read one cached dependency; the identical
focused command passed after cache access was allowed.

## Remaining evidence

- Independent review and manual/deployed verification remain pending.
