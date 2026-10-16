# Incident Lifecycle

## States

- `DRAFT`: saved but not submitted; visible only to its reporter.
- `SUBMITTED`: submitted and currently unassigned.
- `ASSIGNED`: claimed or assigned to one responder and actively being handled.
- `RESOLVED`: completed with resolution remarks.
- `WITHDRAWN`: cancelled by its reporter before assignment; terminal unless a
  future requirement introduces restoration.

## Allowed transitions

| From | Action | To | Authorized actor | Required effects |
| --- | --- | --- | --- | --- |
| none | Save draft | `DRAFT` | Reporter | Set creator and draft timestamps |
| `DRAFT` | Submit | `SUBMITTED` | Owning reporter | Validate required fields; set submission and queue timestamps |
| none | Submit directly | `SUBMITTED` | Reporter | Validate required fields; set submission and queue timestamps |
| `DRAFT` | Edit | `DRAFT` | Owning reporter | Update mutable content |
| `SUBMITTED` | Edit | `SUBMITTED` | Owning reporter, only while unassigned | Preserve original submission/queue timestamps |
| `SUBMITTED` | Withdraw | `WITHDRAWN` | Owning reporter, only while unassigned | Record withdrawal timestamp |
| `SUBMITTED` | Claim | `ASSIGNED` | Eligible responder | Set assignee and first/current assignment timestamps |
| `SUBMITTED` | Assign/reassign | `ASSIGNED` | Admin | Assignee must be eligible for the category |
| `ASSIGNED` | Resolve | `RESOLVED` | Assigned responder or admin | Require remarks; set resolution timestamp |
| `ASSIGNED` | Hand off / unassign | `SUBMITTED` | Assigned responder or admin | Clear assignee; preserve original queue position |
| `ASSIGNED` | Reassign | `ASSIGNED` | Admin | Replace assignee with another eligible responder |
| `RESOLVED` | Reopen with follow-up comment | `SUBMITTED` | Owning reporter | Require a non-blank explanation; append it to the comment thread; increment reopen count; clear assignee; set reopened/current queue timestamp |

## Transition rules

- Every successful transition produces one incident audit event.
- Invalid transitions fail without partially changing the incident, audit log,
  attachments, or canonical database state.
- The current assignee must be absent in `DRAFT`, `SUBMITTED`, `RESOLVED`, and
  `WITHDRAWN`.
- Resolution remarks are retained when an incident is reopened. A later
  resolution appends a new resolution cycle rather than destroying history.
- Reopening and adding its explanatory comment are one operation. If either
  cannot be persisted, neither the state transition nor the comment is saved.
- A whitespace-only follow-up comment cannot reopen an incident. Preserve the
  comment as part of the incident thread and associate it with the reopen cycle.
- Handoff restores ordering using the incident's original submission queue key.
- Reopening starts a new queue cycle using the reopen timestamp; unlike handoff,
  it does not jump ahead of incidents submitted while the case was resolved.
- Admin resolution of a `SUBMITTED` incident is not currently allowed because
  the listed transitions require `ASSIGNED`. Change this rule explicitly if
  direct admin resolution is desired.
- Concurrent transitions use the current persisted version. Exactly one of two
  competing claims may succeed; stale transitions fail without overwriting the
  winning state or creating a success audit.
- AI suggestions never perform or imply a lifecycle transition. Every
  transition still enters through the normal authenticated, authorized,
  validated, audited transaction path.
