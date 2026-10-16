# Authorization Matrix

Authorization is deny-by-default. All conditions listed for an allowed action
must hold. UI visibility must mirror, but never replace, domain authorization.

| Action | Reporter | Responder | Admin |
| --- | --- | --- | --- |
| Register through public portal | Creates Reporter only | No public registration; responder is promoted | No public registration; controlled bootstrap or authorized provisioning only |
| Sign in through role portal | Reporter account through Reporter portal | Responder account through Responder portal | Administrator account through Administrator portal |
| Provision Administrator | No | No | Allowed after controlled first-Administrator bootstrap |
| Change own password | Allowed after current-password verification | Allowed after current-password verification | Allowed after current-password verification |
| View incident list | Own incidents only | Incidents assigned to them (regardless of current category access), and unassigned incidents in permitted categories | All incidents |
| View incident details | Own incidents only | Incident is assigned to them (regardless of current category access), or is unassigned in a permitted category | All incidents |
| View attachments/comments | Same permission as incident detail | Same permission as incident detail | Same permission as incident detail |
| Add attachments | Own `DRAFT`, or own unassigned `SUBMITTED` incident | No | No |
| Create/save draft | Allowed for self | Allowed only when acting as a reporter, if dual-role behaviour is supported | Not required |
| Submit incident | Allowed for self | Same caveat as create | Not required |
| Edit/withdraw | Own `DRAFT`, or own unassigned `SUBMITTED` incident | No | No current requirement |
| Claim | No | Unassigned `SUBMITTED` incident in a permitted category | No current requirement |
| Resolve | No | Incident assigned to self | Any `ASSIGNED` incident |
| Hand off / unassign | No | Incident assigned to self | Any `ASSIGNED` incident |
| Reassign | No | No | To a responder permitted for the incident category |
| Follow up/reopen | Own `RESOLVED` incident, with a non-blank explanatory comment | No | No current requirement |
| View reporter identity | Own identity | Only for non-anonymous incidents they may access | Non-anonymous identity; anonymous identity remains hidden by default |
| Request responder promotion | Allowed for self | Not applicable while already responder | No |
| Decide promotion request | No | No | Allowed |
| Change responder categories | No | No | Allowed |
| Configure SLO | No | No | Allowed |
| View own performance | No | Own statistics | All responder/statistical views |
| View audit log | No | No | Allowed |
| Delete/reset account | No | No | Allowed, subject to retention/security rules |
| AI improve draft | Own current draft text only | No | No |
| AI incident summary / suggested steps | No | An incident currently viewable under the detail rule | No |
| AI operational trend summary | No | No | Privacy-safe aggregate statistics only |

## Enforcement rules

- Obtain the actor from the current request's trusted authenticated security
  context, not a method argument originating from the browser.
- Re-evaluate authorization when an operation executes; do not rely on a list
  view checked earlier.
- A responder losing category access immediately loses access to unassigned
  incidents in that category, and can no longer claim new incidents in it.
  Incidents already assigned to them remain visible and actionable (resolve,
  hand off) regardless of that category change, until reassigned, handed off,
  or resolved. New claims and admin reassignments still require permitted
  category access.
- Account deletion disables authentication but preserves tombstoned references
  required by incidents and audit records.
- Statistics and exports must apply the same anonymity rules as detail views.
- When permission is denied, avoid revealing whether an inaccessible incident
  exists or who reported it.
- Authorize each AI request immediately before selecting provider data. Never
  rely on a previously authorized page, cached browser data, or an incident
  identifier supplied by the user.
- AI output grants no authority and cannot bypass the ordinary authorization,
  validation, lifecycle, audit, or transaction path.
- A login portal, URL, hidden field, or submitted role value never grants a
  role. The authenticated account's current persisted role determines access.
- Promotion approval updates the existing account rather than creating a second
  username or credential record. Rejection leaves role and category access
  unchanged.
