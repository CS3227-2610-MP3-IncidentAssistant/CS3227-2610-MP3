# Administrator Insight Load, Concurrency, and Adversarial-Provider Evidence

- Date: 2026-10-23 (Asia/Singapore)
- Base commit: `af3c69b` (merge of pull request #32)
- Scope: two new automated suites closing `AI-ADM-SAFE-06` and adding the
  end-to-end adversarial fake-provider coverage for the Administrator insight
  boundary
- Criteria addressed: `AI-ADM-SAFE-06` (load and concurrency); supporting
  boundary evidence for `AI-ADM-SAFE-03`, `AI-ADM-QUERY-01`, and
  `AI-ADM-QUERY-05`

## What was added

1. `src/test/java/com/company/incidentdesk/web/admin/AdministratorInsightLoadTest.java`
   — 12 tests for the per-action provider-call budget, bounded query and result
   sizes, absence of response chaining, and concurrency behaviour.

2. `src/test/java/com/company/incidentdesk/web/admin/AdministratorInsightAdversarialProviderTest.java`
   — 33 tests driving a hostile fake provider through the controller the browser
   actually calls.

No production code was changed. Both suites run in the default `check` task and
require no database, so they execute in CI without the isolated-database
configuration.

## `AI-ADM-SAFE-06` coverage

| Criterion clause | Covering test |
| --- | --- |
| One provider call per user action | `oneUserActionIssuesExactlyOneProviderCall` |
| No retry after a clarification | `aClarificationOutcomeIssuesNoSecondProviderCallAndRunsNoAnalyticsQuery` |
| No retry after a rejected plan | `aRejectedPlanIssuesNoRetryProviderCall` |
| No response chaining | `theAnalyticsResultIsNeverFedBackToTheProvider`, `theProviderNeverReceivesAnalyticsLabelsOrValues` |
| Bounded query size | `aProviderLimitAboveTheTenRowCeilingIsRejectedBeforeAnalyticsRuns`, `anOversizedProviderPlanIsRejectedBeforeAnalyticsRuns` |
| Bounded result size | `theRenderedResponseStaysWithinTheTenRowCeiling` |
| Bounded request size | `anOversizedRequestBodyIsRejectedWithoutAnyProviderCall`, `anOverlongQuestionIsRejectedWithoutAnyProviderCall` |
| Concurrency: no extra calls or queries | `concurrentAdministratorActionsEachIssueExactlyOneProviderCallAndOneQuery` (16 concurrent callers) |
| Concurrency: no service-held lock | `aSlowProviderDoesNotSerializeConcurrentAdministratorActions` (8 callers held in flight simultaneously) |

Continued availability of the normal authenticated incident endpoints while AI
requests are slow or exhausted is proved end to end over HTTP by the
pre-existing `AiGatewayWebAvailabilityPostgresTest`, whose
`authenticatedIncidentHttpEndpointsRemainAvailableDuringSlowAndFailedAi` test
holds eight provider calls in flight and then drives the provider to repeated
`503` responses while asserting that `/health` and
`/api/v1/reporter/incidents` keep answering `200` within five seconds. That
test is part of the isolated PostgreSQL suite recorded below.

## Adversarial-provider coverage

The suite treats the provider as hostile rather than merely unreliable. Each
case asserts a safe application-owned response, that no analytics handler ran,
and that no provider text reached the browser:

- SQL in a closed enum field, and SQL smuggled through the metric field.
- A Java class name, in case any field were ever resolved reflectively.
- A URL and a directory-traversal file path in plan fields.
- An OpenAI-style `tool_calls` payload.
- Mutation, row-level incident detail, and audit-data query types.
- An unknown extra field (`rawSql`) that the closed object refuses outright.
- Prompt-injection prose, `<script>`, `<img onerror>`, Markdown links, and bare
  SQL returned in place of a plan.
- Structurally degenerate JSON: truncated objects, arrays, `null`, bare strings
  and numbers, and `{}`.
- A duplicated plan field, resolved to neither value.
- Trailing prose after an otherwise valid plan.
- A real enum metric that is not allowed for the given query type, so a valid
  looking metric cannot widen a query.
- A clarification carrying query fields, which must not be partly executed.
- An inverted explicit date range.
- Questions carrying a known login name, an email address, a UUID, or a link,
  each rejected before any provider disclosure.
- A single responder selection against a comparison plan, which cannot be
  widened into a ranking.

The decisive positive case is
`anAcceptedPlanRendersOnlyApplicationOwnedLabelsAndValues`: even for a
well-formed accepted plan, every label and value the browser receives comes
from the application's own analytics result.

The safe-message assertion checks that each error names only the
application-owned outcome and never echoes the question, the provider output,
SQL, markup, or a URL.

## Verification commands and results

1. Focused new suites:

   `./gradlew.bat test --no-daemon -PfrontendSkipBuild=true --tests com.company.incidentdesk.web.admin.AdministratorInsightLoadTest`

   Result: 12 tests passed, 0 failures, 0 errors, 0 skips.

   `./gradlew.bat test --no-daemon -PfrontendSkipBuild=true --tests com.company.incidentdesk.web.admin.AdministratorInsightAdversarialProviderTest`

   Result: 33 tests passed, 0 failures, 0 errors, 0 skips.

2. Repository verification:

   `./gradlew.bat check --no-daemon`

   Result: passed with 899 Java tests (0 failures, 0 errors, 149
   environment-dependent skips) plus the frontend tests, type checking, lint,
   production frontend build, packaged JAR checks, and JavaFX platform checks.
   The Java total rose from 796 to 899; every skip belongs to a pre-existing
   environment-gated PostgreSQL class, and neither new suite skips.

3. Isolated PostgreSQL verification:

   `./gradlew.bat registrationDatabaseTest --no-daemon -PfrontendSkipBuild=true`

   Result: 94 tests passed, 0 failures, 0 errors, 0 skips, using the
   repository's explicitly confirmed isolated test-database configuration. The
   count matches the previous sweep exactly, so no database behaviour changed.

## Limitations and remaining risk

- Both new suites use a mocked `SecuredAiGateway` rather than the HTTP fake
  provider, so they prove the boundary's own behaviour, not the gateway's
  transport handling. The gateway's transport, budget, circuit, and
  concurrency behaviour is covered separately by the `AiGateway*PostgresTest`
  classes against the HTTP `AiGatewayFakeProvider`.
- `aSlowProviderDoesNotSerializeConcurrentAdministratorActions` proves the
  insight service holds no lock across the provider call. It does not measure
  servlet-container throughput; the HTTP-level availability assertion for that
  lives in `AiGatewayWebAvailabilityPostgresTest`.
- Verification here is automated only. No criterion requiring deployed,
  manual, UI-review, or human-approval evidence is affected by this change, and
  none is ticked on the strength of it.

## Approval status

- Automated evidence: recorded above.
- Manual/deployed evidence: not applicable to this change.
- Human approver/date: not recorded. `AI-ADM-SAFE-07` still requires the
  product owner's UI review, and `ADM-ACC-06-G` still requires independent
  security/architecture approval. Neither is affected by this change.
