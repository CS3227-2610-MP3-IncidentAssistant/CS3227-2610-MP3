# Administrator AI Insights Specification

Status: **No-minimum-sample amendment approved, implemented, and automatically
verified on 2026-10-23 — independent, manual/deployed, and release approval
remain pending.**

This specification refines the Administrator AI capability in
`.agents/ai-policy.md` and `ai-features.md`. It allows an authenticated
Administrator to ask a bounded natural-language question about operational
statistics. The provider may interpret the question into a closed query plan;
the Java application remains responsible for authorization, calculation,
privacy, responder-name resolution, and the factual response.

This document does not authorize SQL generation, retrieval of incident rows or
text, a general chatbot, provider-executed tools, an externally reachable MCP
server, persistent conversation memory, or any application mutation.

## 1. Product outcome and non-goals

The feature answers common operational questions such as:

- Who had the longest average time in progress during the past 30 days?
- Which category had the longest average time to claim this week?
- Which category metrics were above their configured SLO targets?
- How did incident volume this month compare with the previous month?
- Where is the oldest unassigned backlog?
- What statistically supported operational changes deserve attention?

Every factual name, count, duration, rate, ranking, date bound, and sample size
in the answer is produced by deterministic application code. Provider output
is never displayed as a factual answer and cannot select arbitrary data.

Version one is one-shot question and answer. It has no retained chat history,
follow-up context, response chaining, autonomous investigation, scheduled
analysis, alerting, personnel evaluation, disciplinary recommendation, or
forecasting.

## 2. Authorized actor and request lifecycle

Only a currently enabled authenticated Administrator may submit an insight
request. The server derives the actor and role from the trusted security
context and accepts no actor, role, account identifier, model, prompt, tool,
host, or authorization decision from the browser.

The request lifecycle is:

1. The browser submits one question of 1 through 500 Unicode code points and
   optional structured category and responder selections.
2. The server reauthorizes the actor, validates the selections, establishes an
   application-generated request instant, and applies the shared AI admission
   controls in `ai-features.md`.
3. Before provider disclosure, the server rejects questions containing a
   known account login name, account/incident identifier, email address, URL,
   or control character. The UI tells the Administrator not to enter names,
   incident details, comments, reporter information, or other personal data.
   This validation is defense in depth; named responder analysis uses the
   structured selector and is not included in provider input.
4. A single non-streaming SoCLaaS request receives only the question, the
   request instant, and the closed query-plan vocabulary in section 4. It
   receives no application statistics, responder selection, identity, or
   incident data.
5. The provider must return exactly one JSON query plan. The server validates
   every field against the closed schema. Unknown query types, fields, enum
   values, excessive collections, invalid bounds, or prose outside the JSON
   object are rejected; they are never executed or shown.
6. The server reauthorizes the Administrator again immediately before query
   execution and resolves all selected resources from canonical data.
7. Exactly one allowlisted analytics handler, or one explicitly defined fixed
   composition, executes against an authorization-scoped, read-consistent
   statistics snapshot. No model-generated SQL, repository method, URL, class
   name, expression, or code is accepted.
8. A deterministic renderer produces the answer and supporting table from the
   validated result. Responder display names are joined locally only after the
   provider interaction.

If the question is unsupported or materially ambiguous, the plan outcome is
`NEEDS_CLARIFICATION` with one allowlisted clarification code. The application
shows a deterministic clarification prompt and makes no analytics query.
Questions about an individual incident are unsupported, including requests for
incident-level extrema, details, identifiers, titles or text, comments, or
attachments. The interpreter must not silently broaden such a request into an
operational overview or any other company- or category-level aggregate.

## 3. Interpretation and metric rules

### 3.1 Time periods

- `past month` and `last 30 days` mean the rolling 30-day interval ending at
  the application-generated request instant.
- `past week` and `last 7 days` mean the rolling seven-day interval ending at
  the request instant.
- `this month`, `last month`, `this week`, and `last week` use UTC calendar
  boundaries; UTC calendar weeks begin Monday at 00:00. The answer displays the
  exact inclusive start and exclusive end in UTC and may also show the
  configured display-zone representation.
- Explicit dates are converted to an inclusive start and exclusive end. A
  date-only end means the start of the following date.
- A question without a period returns `NEEDS_CLARIFICATION`; the application
  does not silently choose a reporting window.
- A reporting interval may not exceed 366 days. Future intervals and an end
  after the request instant are rejected.
- Period comparisons use adjacent, non-overlapping intervals of equal elapsed
  length unless the question explicitly selects calendar periods.

### 3.2 Metric definitions

All calculations use `.agents/slo.md` and persisted lifecycle timestamps:

- `RESOLVED_CYCLE_COUNT`: completed resolution cycles attributed to the
  responder assigned when resolution occurred.
- `AVERAGE_TIME_TO_CLAIM`: mean queue-entry-to-first-assignment duration for
  claimed queue cycles. It is a company/category queue metric and is not used
  to rank responders.
- `AVERAGE_TIME_IN_PROGRESS`: mean first-assignment-to-resolution duration for
  completed resolution cycles, including handoffs and reassignments.
- `REOPEN_RATE`: incidents reopened at least once divided by incidents
  resolved in the selected scope; division by zero is `not available`, never
  zero.
- `REOPEN_EVENT_COUNT`: number of reopen events in the selected scope.
- `SLO_TARGET_COMPARISON`: observed average time to claim, average time in
  progress, or reopen rate compared with the versioned category target that
  governed those observations. When a period spans target versions, each
  version is a separate result row; the application does not blend targets or
  invent one overall compliance percentage.
- `INCIDENT_COUNT`: incidents entering the selected reporting scope.
- `UNASSIGNED_BACKLOG_COUNT`: submitted, unassigned incidents at the snapshot
  instant.
- `OLDEST_UNASSIGNED_AGE`: snapshot instant minus the applicable queue-entry
  timestamp of the oldest submitted, unassigned incident.
- `ADMINISTRATOR_RESOLVED_COUNT`: resolutions performed by an Administrator,
  reported separately while retaining the attribution required by
  `.agents/slo.md`.

The renderer states the metric, exact period, filters, sample size, and any
reason that a value is unavailable. It reports ties rather than selecting an
arbitrary winner. When a ranking requests one result, every entry tied for
first place is preserved and displayed together, subject to the absolute
ten-row response ceiling. Stable secondary ordering is display name followed by
account identifier, both ascending; the identifier is not displayed or sent
to the provider.

### 3.3 Small samples and fairness

- Responder rankings include every responder with at least one qualifying
  completed cycle in the selected scope. There is no minimum-sample eligibility
  threshold and no hidden suppression of low-sample responder results.
- A structured comparison returns one row for every valid selected responder.
  If a selected responder has no qualifying completed cycle, count is shown as
  zero and duration or rate values with a zero denominator are shown as `not
  available`; the responder is not silently omitted.
- Category aggregates disclosed to the provider under the separate narrative
  trend feature continue to suppress or combine groups below five incidents.
  That provider-disclosure privacy rule does not suppress deterministic insight
  results, which are rendered locally to the Administrator.
- Ranking output is labelled an operational indicator rather than an employee
  performance or disciplinary judgment. It includes the exact sample size so
  the Administrator can judge the amount of supporting data, and does not infer
  cause, intent, competence, or misconduct.

## 4. Closed query-plan contract

The provider response is a JSON object with these common fields:

| Field | Allowed values |
| --- | --- |
| `outcome` | `QUERY` or `NEEDS_CLARIFICATION` |
| `queryType` | One value from section 5 when outcome is `QUERY` |
| `metric` | One metric allowed for that query type |
| `period` | `ROLLING_7_DAYS`, `ROLLING_30_DAYS`, `THIS_UTC_WEEK`, `PREVIOUS_UTC_WEEK`, `THIS_UTC_MONTH`, `PREVIOUS_UTC_MONTH`, or validated explicit bounds |
| `comparisonPeriod` | Absent or the one period allowed by the query type |
| `direction` | `ASCENDING` or `DESCENDING` where ranking applies |
| `limit` | Integer 1 through 10 where ranking applies |
| `grouping` | `COMPANY`, `CATEGORY`, or `RESPONDER` where allowed |
| `clarificationCode` | One application-owned code when clarification is required |

No free-form explanation, SQL fragment, filter expression, field name, account
name, category name, identifier, or tool argument is accepted from the model.
Category and responder filters come only from validated browser selections and
are applied after plan validation. A selected responder must still exist and
be visible to the Administrator when the query executes.

A named `period` or `comparisonPeriod` is encoded as its enum string. Explicit
date bounds are encoded only as an object with exactly `fromDate` and
`throughDate` ISO-8601 date strings. `throughDate` is inclusive and resolves to
the exclusive start of the following UTC date. No timestamp, timezone, or
additional field is accepted in an explicit-period object.

Allowed clarification codes are `MISSING_PERIOD`, `AMBIGUOUS_METRIC`,
`UNSUPPORTED_QUESTION`, and `CONFLICTING_PERIODS`. The UI owns their display
text; provider-authored clarification text is ignored.

## 5. Read-only analytics capabilities

The capabilities are MCP-shaped typed application functions so their schemas
can be tested independently. In version one they are in-process application
services, not provider tools and not a network MCP server.

| Query type / handler | Supported questions and output |
| --- | --- |
| `RANK_RESPONDERS` / `rankResponders` | Rank every responder with at least one qualifying completed cycle by resolved-cycle count, average time in progress, or reopen rate. There is no minimum-sample threshold. Returns up to ten entries with value and sample size. Queue-level time to claim is deliberately unavailable for responder ranking. |
| `COMPARE_SELECTED_RESPONDERS` / `compareSelectedResponders` | Compare two through five responders selected outside the question across the same responder metrics. Every valid selection receives a row, including a zero-count / `not available` row when the selected responder has no qualifying completed cycle. Free-text responder names are unsupported. |
| `RANK_CATEGORIES` / `rankCategories` | Rank categories by volume, claim/progress duration, reopen count/rate, unassigned count, or oldest unassigned age. |
| `CATEGORY_TREND` / `categoryTrend` | Return daily, weekly, or monthly bounded series for one metric and at most ten selected categories. |
| `PERIOD_COMPARISON` / `comparePeriods` | Compare one company- or category-level metric across two adjacent or explicit non-overlapping periods. |
| `BACKLOG_SNAPSHOT` / `backlogSnapshot` | Return current unassigned counts and age buckets by category, plus oldest age. It never returns incident identifiers or text. |
| `SLO_TARGET_COMPARISON` / `compareSloTargets` | Compare category-level observed claim/progress averages and reopen rate with each applicable versioned target; never invent an overall compliance percentage across target versions. |
| `OPERATIONAL_OVERVIEW` / `operationalOverview` | Fixed composition of incident volume, resolved count, claim/progress averages, reopen rate, version-separated SLO target comparisons, and backlog count for one period, with optional equal-period comparison. |

Each handler accepts only typed period, category scope, optional validated
responder selection, metric, direction, and bounded limit values. It returns
immutable aggregate result records. The registry has no generic invocation,
reflection, SQL, URL fetch, file access, audit access, incident-detail lookup,
or mutation capability.

Explicitly unavailable functions include `runSql`, `searchIncidents`,
`getIncident`, `getIncidentText`, `getComments`, `getAttachments`,
`getReporter`, `getAnonymousIdentity`, `getAuditEvents`, account mutation,
incident mutation, SLO mutation, and arbitrary export.

## 6. Data and privacy contract

Provider input for query planning is limited to:

- the Administrator-authored question after validation;
- the application-generated request instant;
- fixed system instructions and the closed enum/schema catalog.

The provider never receives calculated statistics during query planning,
responder/category selections, responder or reporter identities, login names,
account or incident identifiers, incident/comment/attachment content, audit
rows, row-level timestamps, credentials, sessions, or application errors.

The existing `AI-ADM-01` narrative trend operation may separately send the
privacy-safe aggregate record already defined in `ai-features.md`; a query-plan
response is never automatically fed into that operation. One user action causes
at most one provider request.

Anonymous incidents contribute to permitted company and category aggregates,
but reporter identity is neither selectable nor inferable through this feature.
Reporter grouping and reporter filtering are not available.

## 7. Output and human-review contract

The browser receives a structured application response containing:

- deterministic summary text;
- interpreted metric, ranking direction, limit, grouping, exact period, and
  active category and responder filters;
- metric definition label;
- zero through ten result rows;
- the sample size for every responder result;
- snapshot timestamp for backlog results;
- advisory notices; and
- a stable safe error or clarification code when applicable.

The response retains exact UTC period bounds. The browser presents those bounds
in Singapore time at hour precision and keeps the exclusive-end label visible.

All text is rendered through normal React escaping. There is no raw provider
text, Markdown, HTML, executable link, auto-apply operation, or response-fed
request. The answer cannot create, edit, claim, assign, resolve, reopen,
withdraw, delete, promote, reset, configure, audit, notify, or schedule.

The Administrator reviews the answer in place. Any later operational action
uses the ordinary separately authorized workflow; the insight creates no
approval, recommendation record, or domain mutation.

The browser explains every product constraint that can prevent or materially
change a result, including the required period, supported metrics, responder
selection cardinality, row limit, and zero-denominator behavior. No
undocumented data-volume, completion-count, or sample-size condition may hide a
result or convert a data-bearing request into `no qualifying data`.

## 8. Failure, stale, and concurrent behavior

- Blank, oversized, identity-bearing, or structurally invalid questions fail
  before a provider call and preserve the statistics page.
- Cross-role access, a disabled account, or loss of Administrator role returns
  the normal non-enumerating unavailable response with no provider disclosure.
- Provider timeout, rejection, malformed JSON, extra prose, unsupported plan,
  rate limit, budget exhaustion, or circuit-open state maps to the stable AI
  failures in `ai-features.md` and executes no analytics handler.
- Authorization is checked before provider disclosure and again before query
  execution. A stale role or selected responder fails closed.
- Query execution uses a read-consistent database snapshot and records its
  application-generated instant. Concurrent incident changes may appear only
  in a later request; no locks block ordinary incident mutations merely to
  preserve an insight result.
- An empty result is a successful `no qualifying data` response, not a
  fabricated ranking or provider retry. Low-sample responder data is not
  treated as empty or suppressed.
- AI failure never blocks the ordinary non-AI operational-statistics view.

## 9. Audit, observability, and limits

Every provider-admitted request records the content-free AI audit outcome
required by `.agents/audit-policy.md`. The audit may include feature identifier
`ADMIN_AI_INSIGHTS`, query type after successful validation, outcome category,
and correlation identifier. It contains no question, provider response,
responder/category selection, displayed result, prompt, or query value.

Logs, traces, metrics, the usage ledger, and CI evidence follow
`ai-features.md`. They never contain the question, plan body, result values,
display names, selections, provider body, or prohibited data. Metric labels are
bounded enums only.

The shared AI limits apply. One insight request consumes one admission and no
more than one provider request. Query execution returns at most ten displayed
rows and at most 366 daily, 53 weekly, or 12 monthly series points. The
application applies a bounded database query deadline; timeout returns a safe
statistics-unavailable result and never retries the provider.

## 10. Acceptance criteria

### Authorization and query planning (`AI-ADM-AUTH`)

- [ ] `AI-ADM-AUTH-01` — A currently enabled Administrator can submit a valid
  bounded question; Reporter, Responder, unauthenticated, disabled, and stale
  Administrator sessions receive a non-enumerating denial with no provider or
  analytics call.
- [x] `AI-ADM-AUTH-02` — The server derives actor, role, request instant, model,
  and capability catalog from trusted application state and ignores or rejects
  browser attempts to supply them.
- [x] `AI-ADM-AUTH-03` — Blank, over-500-code-point, control-bearing, known
  login-name, identifier, email, and URL inputs fail before provider disclosure;
  structured responder selection is excluded from the provider payload.
- [x] `AI-ADM-AUTH-04` — Captured provider requests contain only the validated
  question, request instant, fixed instructions, and closed plan vocabulary,
  with no statistics, selections, identities, incident data, or credentials;
  fixed instructions classify individual-incident extrema, details,
  identifiers, text, comments, and attachments as unsupported and prohibit
  broadening such a question into an operational overview.
- [x] `AI-ADM-AUTH-05` — Malformed JSON, extra prose, unknown fields or enums,
  invalid periods, excessive limits, and unsupported or ambiguous questions
  execute no analytics handler and return only an application-owned safe error
  or clarification.

### Deterministic analytics (`AI-ADM-QUERY`)

- [x] `AI-ADM-QUERY-01` — Each query type dispatches only to its allowlisted
  typed handler; adversarial plans cannot invoke SQL, reflection, URLs, files,
  incident details, audit data, repository methods, or mutations.
- [x] `AI-ADM-QUERY-02` — Deterministic-clock tests prove every relative and UTC
  calendar period boundary, inclusive/exclusive behavior, comparison interval,
  future-date rejection, and 366-day maximum.
- [ ] `AI-ADM-QUERY-03` — Ranking tests prove metric attribution, direction,
  preservation and display of every first-place tie within the ten-row response
  ceiling, stable ordering, sample counts, division-by-zero behavior, inclusion of
  every responder with a qualifying cycle regardless of sample size, explicit
  zero-sample rows for selected-responder comparisons, and
  no-qualifying-data behavior.
- [ ] `AI-ADM-QUERY-04` — Category trend, period comparison, backlog snapshot,
  and operational overview tests prove their metric definitions, bounds, row
  limits, and empty-result behavior against isolated persisted lifecycle data.
- [x] `AI-ADM-QUERY-05` — Responder names and factual values are resolved and
  rendered only from the post-provider application result; fabricated names or
  values in provider output cannot enter the answer.
- [ ] `AI-ADM-QUERY-06` — A read-consistent snapshot and application-generated
  timestamp produce a coherent result while concurrent incident mutations
  remain non-blocking and become visible only according to database isolation.

### Privacy, output, and failure (`AI-ADM-SAFE`)

- [ ] `AI-ADM-SAFE-01` — Anonymous incidents contribute only to allowed
  aggregates; responder ranking, category filtering, repeated comparisons,
  errors, logs, metrics, audits, and evidence reveal no reporter identity.
- [ ] `AI-ADM-SAFE-02` — Comparative responder output applies no hidden
  minimum-sample threshold, reports each result's sample size and ties, states
  the metric and exact period, and carries the operational-indicator notice.
- [x] `AI-ADM-SAFE-03` — The browser renders only escaped deterministic text and
  structured rows; provider HTML, Markdown, URLs, tool calls, and instructions
  are rejected and cannot execute, fetch, persist, or mutate anything.
- [ ] `AI-ADM-SAFE-04` — Each admitted request writes one content-free audit
  outcome and safe bounded observability metadata; the question, plan, result,
  identities, selections, prompt, and provider response are absent from logs,
  traces, metrics, ledger rows, audits, and test evidence.
- [ ] `AI-ADM-SAFE-05` — Timeout, cancellation, provider rejection, malformed
  output, rate limit, budget exhaustion, circuit-open state, database failure,
  and query timeout produce the specified safe failure, make no partial domain
  change, and leave the non-AI statistics workflow available.
- [ ] `AI-ADM-SAFE-06` — Load and concurrency tests prove one provider call per
  user action, bounded query/result sizes, no response chaining, and continued
  availability of normal authenticated incident endpoints.
- [ ] `AI-ADM-SAFE-07` — UI and response tests prove every result-affecting
  product constraint is disclosed, no undocumented data-volume or sample-size
  condition suppresses a result, and a data-bearing responder request is not
  reported as `no qualifying data` solely because its sample is small.

## 11. Traceability and verification evidence

| Criteria | Governing sources | Implementation area | Planned evidence |
| --- | --- | --- | --- |
| `AI-ADM-AUTH-*` | `.agents/ai-policy.md`, `.agents/authorization-matrix.md`, `.agents/security.md` | Admin endpoint, question validator, plan builder/parser | Role denied paths, stale session, captured provider payload, adversarial plan tests |
| `AI-ADM-QUERY-*` | `.agents/slo.md`, `.agents/persistence.md` | Typed analytics registry, statistics services, deterministic renderer | Domain calculations, PostgreSQL integration, deterministic-clock and concurrency tests |
| `AI-ADM-SAFE-*` | `.agents/anonymity-policy.md`, `.agents/audit-policy.md`, `threat-model.md` | Privacy projection, UI, audit and observability integration | Anonymous/differencing, escaped output, audit/log capture, resilience and load tests |

- Automated evidence: the question validator, strict plan parser, deterministic
  UTC resolver, trusted service orchestration, closed typed analytics registry,
  deterministic renderer, strict HTTP boundary, provider-payload builder,
  migration V11, and React review UI are implemented. On 2026-10-23,
  `.\gradlew.bat check --no-daemon` passed with 796 Java tests (0 failures,
  0 errors, 132 environment-dependent skips) plus 135 passing frontend tests,
  and `.\gradlew.bat registrationDatabaseTest --no-daemon
  -PfrontendSkipBuild=true` passed 63 isolated PostgreSQL tests with no skips or
  failures. Reproducible details are recorded in
  [`test-evidence/2026-10-23-administrator-ai-insights.md`](test-evidence/2026-10-23-administrator-ai-insights.md).
- No-minimum-sample amendment evidence: focused service/controller, isolated
  PostgreSQL, React interaction, and repository-wide checks prove that a
  one-cycle responder is ranked, a valid selected zero-cycle responder remains
  visible with an explicit `not available` value, obsolete exclusion fields and
  messaging are removed, and the UI discloses the applicable constraints. See
  [`test-evidence/2026-10-23-admin-insight-sample-threshold.md`](test-evidence/2026-10-23-admin-insight-sample-threshold.md).
- First-place tie evidence: focused ranking and React tests prove that a
  one-result ranking preserves and displays every equal first-place row in
  deterministic order within the ten-row response ceiling. Repository-wide
  and isolated PostgreSQL checks also passed. See
  [`test-evidence/2026-10-23-admin-insight-first-place-ties.md`](test-evidence/2026-10-23-admin-insight-first-place-ties.md).
- Planning-guardrail evidence: prompt-contract and strict-plan tests prove that
  individual-incident questions are explicitly unsupported, cannot be silently
  broadened by the planning instructions, and non-ranking plans reject lone
  ranking fields. The repository-wide check also passed. See
  [`test-evidence/2026-10-23-admin-insight-planning-guardrails.md`](test-evidence/2026-10-23-admin-insight-planning-guardrails.md).
- Manual/deployed evidence: pending; verify the supported question catalog,
  accessibility, responsive layout, production provider configuration,
  sanitized observability, and safe failure behavior.
- Product approver/date: repository user, 2026-10-23 (Asia/Singapore), including
  the no-minimum-sample amendment.
- Security/architecture approver/date: repository user, 2026-10-23
  (Asia/Singapore), including the no-minimum-sample amendment.
- Provider-retention risk acceptance: repository user explicitly accepted the
  provider's undocumented prompt/response-retention risk on 2026-10-23.

Unchecked criteria remain pending for their complete role, metric, concurrency,
privacy, audit-capture, resilience, load, manual, or deployed evidence. The
historical approval record remains in
`reviews/2026-10-23-ai-features-and-admin-insights-approval.md`; the amendment
approval is recorded in
`reviews/2026-10-23-admin-insights-sample-threshold-amendment.md`.
