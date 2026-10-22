# Administrator Incident Detail React Evidence

## Scope

- Branch: `codex/admin-incident-detail-backend`
- Builds on the backend recorded in
  [the 2026-10-22 administrator incident detail backend verification](2026-10-22-administrator-incident-detail-backend.md)
- Criteria: React automated evidence for `ADM-INC-02`, `ADM-INC-03`,
  `ADM-INC-04`, and `ADM-INC-05`
- Environment: Windows, Node (Vite 8 / Vitest 5), jsdom
- No production database or production credentials were used.

## Shared-across-roles design

The MP2 desktop application already shared one detail view between roles
(`ui/shared/components/IncidentDetailView` driven by `IncidentDetailActions`),
and `.agents/STYLE_GUIDE.md` requires shared incident detail sections. The
React implementation mirrors that design rather than duplicating per role:

| Shared module | Responsibility |
| --- | --- |
| `shared/incident/incidentDetail.ts` | Role-neutral model, status/role/SLO labels, duration and instant formatting |
| `shared/incident/IncidentDetailSections.tsx` | Description, attachments, comments, lifecycle, resolution history, SLO progress |
| `shared/incident/IncidentDetailView.tsx` | Capability-driven shell; omitting a capability hides its control |

Role exposure is by capability, not by hidden markup:

| Capability | Reporter | Responder | Administrator |
| --- | --- | --- | --- |
| Detail sections | shared | shared | shared |
| Claim | — | yes | — |
| Assign / reassign | — | — | yes |
| Unassign | — | — | yes |
| Resolve | — | yes (assigned) | yes (assigned) |
| Comment composer | — | — | yes |
| Attachment read | — | responder endpoint | admin endpoint |
| Attachment add | — | — | no (read-only) |

`ResponderPortal` was migrated onto the shared sections in this change; its 22
existing tests still pass. One heading was unified from `Previous resolutions`
to the desktop view's `Resolution history`, and that single assertion was
updated deliberately rather than to accommodate a defect.

The reporter dashboard was intentionally left on its own rendering this round;
it is outside the ADM-INC criteria and is a separate migration.

## Deliberate divergence from desktop parity

The MP2 detail view rendered an `Audit timeline` section containing the
placeholder `Audit timeline not available yet`. `ADM-INC-02` states this is not
a feature to reproduce, and audit records remain internal-only with no HTTP
read endpoint. The React view omits the section, and both
`AdministratorIncidentDetail` and `IncidentDetailView` assert that no audit text
renders.

## Traceability

| Requirement | Implementation | Evidence |
| --- | --- | --- |
| Privacy-safe detail without anonymous deanonymization | Server-supplied `Anonymous reporter` labels rendered as text | `renders lifecycle, SLO, resolution, comment and attachment data as escaped text`, App-level anonymous assertion |
| Lifecycle, resolution, SLO, comment and attachment sections | Shared sections composed by `IncidentDetailView` | shared view suite plus admin detail suite |
| Stable SLO text, not colour alone | `incidentSloStateLabel` plus badge text | `states SLO progress in words as well as colour` |
| `Assign` versus `Reassign` labelling | Label derived from persisted status | `sends the loaded version…`, `labels the action Reassign…` |
| Eligible-responder selection only | Options come from the server's `eligibleResponders` | assign tests; empty case below |
| No eligible responders explains category access | Guidance text replaces the control | `explains that category access must be configured when no responder is eligible` |
| Unassign states its consequence before submission | Native `<dialog>` naming the queue-position effect | `states the consequence before unassigning and only mutates after confirmation` |
| Resolve requires non-blank remarks, whitespace retained | Client non-blank check, raw text submitted | `rejects blank resolution remarks…`, `sends remarks with an idempotency key…` |
| Resolution only for an assigned incident | Capability omitted unless `status === 'ASSIGNED'` | `does not offer resolution for an unassigned incident` |
| Duplicate-submission prevention | Per-submission `requestId` idempotency key plus disabled controls while pending | assign/resolve/comment body assertions |
| Stale state returns a safe conflict and refreshes | `409` mapped to a conflict message plus reload | `reports a stale mutation as a conflict and reloads the latest detail` |
| Recoverable failure preserves entered text | Composer retains text until success | `sends a comment with an idempotency key and keeps the text on failure` |
| Denied and missing are indistinguishable | `404` renders one neutral unavailable state | `reports a denied or missing incident without revealing which` |
| Untrusted text is escaped | React text rendering, no `dangerouslySetInnerHTML` | `keeps untrusted comment and resolution text as text` (asserts no injected `script`/`img`) |
| Administrators view but do not add attachments | No upload control exists | `renders lifecycle…` asserts no add-attachment control |

## Automated verification

### Frontend type, lint, and component checks

Command:

```text
npm run check
```

Result: passed. `tsc -b` reported no errors, `eslint . --max-warnings 0`
reported none, and Vitest reported 117 tests across 13 files with 0 failures
(87 before this change). New suites: `AdministratorIncidentDetail.test.tsx`
(14 tests), `shared/incident/IncidentDetailView.test.tsx` (10 tests), plus one
added `App.test.tsx` case covering dashboard-to-detail navigation and return.

### Production bundle

Command:

```text
npm run build
```

Result: passed.

### Repository-wide verification

Command:

```text
.\gradlew.bat check
```

Result: passed. Java tests reported 725 tests, 0 failures, 121
environment-gated skips; frontend typecheck, lint, and tests passed;
cross-platform package and JavaFX checks passed.

### Contrast

The two new badge tokens were checked against WCAG 2.2 AA for normal text:
success `#1f7a55` on `#e2f4ec` is 4.63:1 and danger `#b33a3a` on `#fce8e8` is
4.98:1, both above the 4.5:1 threshold.

### Diff hygiene

Command:

```text
git diff --check
```

Result: passed.

## Remaining evidence

`ADM-INC-02`, `ADM-INC-03`, `ADM-INC-04`, and `ADM-INC-05` remain open. Backend
and React automated evidence now exist, but the following are not claimed:

- browser end-to-end verification against a real deployment;
- an interaction and accessibility audit against `.agents/STYLE_GUIDE.md`
  (keyboard-only traversal, screen-reader naming, 200% zoom, narrow reflow),
  beyond the role- and label-based assertions in the component tests;
- deployed HTTPS verification against the release commit.

The reporter incident detail migration onto the shared sections also remains
outstanding.
