# Proposed issue #7 scope amendment — not approved

This draft separates the Responder-owned MVP from Reporter-owned writing flows.
It does not change the approved `responder-vertical-slice.md` criteria or
authorize closing issue #7. Product-owner review is required before applying
it to the specification or GitHub issue.

## Proposed criterion boundaries

1. Keep RSP-AUTH-01, RSP-QUEUE-01, RSP-CLAIM-01/02, RSP-RESOLVE-01,
   RSP-UI-01, and RSP-E2E-01 in issue #7.
2. For RSP-DETAIL-01, require authorized, privacy-safe reads of existing
   incident fields, history, comments, attachment metadata, and private
   attachment bytes using isolated fixtures. Reporter comment creation,
   attachment upload, failed-write compensation, and population of production
   attachment metadata move to separate Reporter-owned issue(s).
3. For RSP-RESOLVE-02, require an atomic resolution, audit, retry behavior,
   and preservation of prior cycles when test fixtures re-enter the eligible
   state. The real Reporter reopen/comment endpoint and the cross-role
   reopen→reclaim→second-resolution browser journey move to a separate
   Reporter-owned integration issue. The domain invariant requiring a
   non-blank reopen comment remains unchanged.
4. Keep the human keyboard, screen-reader, narrow-layout, and promoted-account
   browser checks in RSP-UI-01 and RSP-E2E-01. Automated or agent-operated
   checks cannot replace the human evidence required by the approved spec.

## Follow-up issue acceptance to create after approval

- An owning Reporter can add a comment and upload a validated attachment;
  wrong-owner and anonymous-identity leakage paths are denied.
- An owning Reporter can reopen a resolved incident only with a non-blank
  explanatory comment; comment, state change, and audit commit atomically.
- After reopening, an eligible Responder can reclaim and resolve the incident
  again, retaining both resolution cycles and their audit history.
- Browser and PostgreSQL tests cover allowed, denied, stale, failed-write,
  privacy, and attachment-read paths across both roles.

Until this amendment is approved and the human checks are recorded, issue #7
must remain open under its current criteria.
