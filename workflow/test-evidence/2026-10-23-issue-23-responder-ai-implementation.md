# Issue #23 responder AI implementation verification

- Approved specification: `workflow/ai-features.md` SHA-256 `c0f15be532786f2123a743c2be6352bc36293888080b229862c166b8d8be200c`, section 4.2, `AI-RES-02..08` and applicable `AI-FEAT-02/04..06`.
- Rebased base commit: `aea4f4d`; approved specification commit: `5ca0cc3`; implementation commit: `3d48cd3`. No provider key or production incident data was used. The approved specification SHA-256 remains unchanged after the rebase.
- Environment: Java 25, Gradle 9.6.1, Node 25.6.1; temporary isolated PostgreSQL 18 database with synthetic accounts/incidents and a local fake SoCLaaS HTTP provider. The database fixture created and dropped a unique schema.

## Checks

| Check | Result | Evidence |
| --- | --- | --- |
| `INCIDENT_DESK_TEST_DATABASE_URL=<isolated test URI> INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED=true ./gradlew build --quiet` after rebase | **Passed** | Exit 0. Gradle XML: 780 Java tests, 0 failures/errors, 2 skipped live-provider smoke tests. Embedded frontend check: 14 files, 139 tests passed; typecheck and lint passed. |
| Focused `./gradlew test --tests ...ResponderAiHttpPostgresTest --tests ...JdbcResponderAiSelectorPostgresTest --tests ...ResponderAiIncidentControllerTest` with the same isolated database configuration | **Passed** | Real Spring route, security filter, selector bean, gateway, fake provider, audit/usage ledger, and persisted incident state were exercised. |
| `npm run check` in `frontend/` after conflict resolution | **Passed** | Typecheck, lint, 139 tests in 14 files, including actor-change stale-result coverage. |
| `git diff --check` | **Passed** | No whitespace errors. |
| Live SoCLaaS smoke tests | **Skipped** | Live-provider credentials and production enablement are outside this implementation task. |
| Narrow viewport browser verification | **Unavailable** | CSS includes wrapping for long unbroken advice, but no rendered mobile viewport was inspected. `AI-RES-08` remains open. |

## Criterion evidence and remaining limits

- `AI-RES-02/03`: MockMvc verifies role/CSRF and the identifier-only route. The isolated HTTP test verifies wrong-responder denial before provider dispatch or usage reservation.
- `AI-RES-04`: The selector database test checks queue, assigned, handed-off, reopened, prospective SLO target, invalid timestamp, and revocation cases. The fake-provider capture checks the exact nine-field JSON and excludes identities, comments, and tools.
- `AI-RES-05`: The fake provider pauses while category access is revoked. The route returns a privacy-safe denial; the terminal usage/audit outcome is `AI_REJECTED`, and persisted incident state remains `SUBMITTED`. Frontend tests discard late output after navigation, signed-in actor change, and a denied detail refresh.
- `AI-RES-06/07`: Gateway contract tests and browser component tests cover bounded plain text, escaped markup, failure isolation, and no resolution-remarks prefill. The integration test checks usage/audit outcome and unchanged incident status.
- `AI-RES-08`: Both submitted and assigned detail views expose request/review; tests cover focus preservation and cancellation/navigation. Real narrow-layout, manual keyboard, and deployed checks remain open.
- Shared `AI-FEAT-04..06` criteria cover more than this responder slice; their broad checkboxes remain open pending cross-role and observability evidence.

No acceptance checkbox was ticked by this record. Independent review and human release approval remain separate gates.
