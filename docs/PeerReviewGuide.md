---
layout: default
title: Peer Review Guide
nav_order: 3.5
---

# Incident Desk Peer Review Guide

This guide gives peer reviewers a repeatable way to test the complete implemented
application described in the [User Guide](UserGuide.md). Follow the scenarios in
order because later checks use accounts and incidents created earlier.

Use fictional information and a new disposable data directory. Do not use a real
Incident Desk data directory: several checks intentionally change passwords,
disable an account, and create test records.

## Scope

Review these implemented workflows:

- application launch, registration, sign-in, password change, logout, and restart persistence;
- Reporter submission, tracking, editing, withdrawal, reopening, comments, anonymity, and image attachments;
- Administrator account management and Responder category access;
- Responder queues, claiming, resolution, and handoff;
- Administrator incident search, filters, sorting, assignment, reassignment, unassignment, and resolution;
- SLO configuration, statistics, and in-session notifications.

Do not assess the following as working features because the User Guide identifies
them as unavailable or incomplete:

- Reporter-to-Responder promotion requests and Administrator promotion review;
- Reporter drafts;
- video or audio attachment upload and playback;
- an **In Progress** action;
- Responder personal statistics, search, filtering, or sorting;
- notifications surviving an application restart;
- the separate **Sample UI** preview.

## Test setup

### 1. Check the environment

Check the Java version with:

```text
java -version
```

The Java feature version must be 25. Use the regular JAR on Windows/Linux x86-64
and Intel macOS. Use the macOS ARM64 JAR on Apple Silicon.

### 2. Use isolated storage

Create an empty disposable folder outside the repository's tracked files. Set
`INCIDENT_DESK_DATA_DIR` in the terminal that will launch the app, replacing the
example path with that folder.

Windows PowerShell:

```powershell
$env:INCIDENT_DESK_DATA_DIR = "C:\temp\incident-desk-peer-review"
java -jar .\incident-desk.jar
```

macOS or Linux:

```sh
export INCIDENT_DESK_DATA_DIR=/tmp/incident-desk-peer-review
java -jar ./incident-desk.jar
```

If reviewing from source, build first with `./gradlew check shadowJar` (or
`.\gradlew.bat check shadowJar` on Windows), then launch
`build/libs/incident-desk.jar`.

### 3. Prepare accounts and files

Register these accounts with test-only passwords. Login names are case-sensitive.

| Account | Role | Purpose |
| --- | --- | --- |
| `reviewer-admin` | Admin | Administration checks |
| `reviewer-reporter` | Reporter | Main Reporter workflow |
| `reviewer-other` | Reporter | Ownership and deletion checks |
| `reviewer-responder` | Responder | Facilities Responder |
| `reviewer-responder-2` | Responder | Reassignment and category-denial checks |
| `reviewer-delete` | Reporter | Disposable account-deletion check |

Prepare one small valid PNG or JPEG image. Do not use a personal image because
its pixels or metadata may reveal private information.

## Review scenarios

### 1. Launch, registration, and authentication

1. Launch the app against the empty directory. Verify that the **Incident Desk**
   sign-in window opens and application data is created only in the disposable
   directory.
2. Register all six accounts above. Verify that each registration is confirmed
   and that duplicate login-name registration is rejected.
3. Try signing in with a wrong password. Verify that sign-in fails without
   revealing password or account internals.
4. Sign in as `reviewer-reporter`. Verify that the Reporter dashboard opens.
5. Select **Update password**. Try an incorrect current password, a blank new
   password, and mismatched confirmation. Verify that each is rejected.
6. Change to a valid new password, log out, and verify that the old password
   fails while the new password succeeds.
7. Open the notification bell. Verify that it opens and that opening it clears
   the unseen badge for displayed notifications.

Also review that navigation matches the active role: Reporter and Responder see
their dashboard, while an Admin also sees **Accounts**, **SLO configuration**,
and **Statistics**. No screen should expose password text, password
hashes, session identifiers, or temporary reset credentials after its one-time
display.

### 2. Reporter submission, validation, ownership, and anonymity

Sign in as `reviewer-reporter`.

1. Submit with a blank title, then with a blank description, and without a
   category. Verify that the relevant field is highlighted and no incident is
   created.
2. Submit a named Facilities incident:

   - Title: `Water leak near pantry`
   - Description: `Water is dripping from the ceiling beside the third-floor pantry.`
   - Category: **Facilities**

3. Verify the success message, cleared form, and a **Submitted** row in **My incidents**.
4. Submit an anonymous Facilities incident titled `Anonymous facilities report`
   with **Submit anonymously** selected. Verify that the checkbox clears after
   success and that the owner can still see the incident in **My incidents**.
5. Submit an IT incident titled `Withdraw test` for the withdrawal scenario.
6. Select **Refresh**, open each incident by button and by double-click, and
   verify that the list and details show the saved title, description, category,
   status, and timestamps.
7. Log in as `reviewer-other`. Verify that the first Reporter's incidents are not
   listed or otherwise accessible through the UI.

When the anonymous incident is later viewed as a Responder or Admin, verify that
its reporter is shown as **Anonymous reporter**. Also check comments,
notifications, filters, statistics, audit details, and attachment labels for
identity leakage. The owner identity must remain hidden in normal views, although
this local-file application does not claim cryptographic anonymity from the data
owner.

### 3. Reporter editing, withdrawal, comments, and attachments

Return to `reviewer-reporter` and open the unassigned `Water leak near pantry`
incident.

1. Select **Edit**, change the description and category to **IT**, then select
   **Cancel**. Verify that the original values remain.
2. Edit again, change only the description, and select **Save changes**. Reopen
   the detail and verify the saved text.
3. Add a non-blank comment and verify that it appears in the comment thread.
   Verify that a blank or whitespace-only comment is rejected.
4. Select **Add attachment**, choose the prepared PNG/JPEG, and verify that it is
   listed. Select **View attachment** and verify that the in-app viewer displays it.
5. Attempt to select an unsupported non-image file. Verify that it is rejected
   and does not appear in the list.
6. Open `Withdraw test`, select **Withdraw**, cancel the confirmation, and verify
   that it remains **Submitted**. Repeat and confirm; verify that it becomes
   withdrawn and cannot be edited or claimed.

Reporter edit, withdrawal, and attachment upload must be available only while the
owned incident is submitted and unassigned. Recheck this after the main incident
is claimed in Scenario 6.

### 4. Administrator account management

Sign in as `reviewer-admin` and open **Accounts**.

1. Verify that login name, role, and category access are displayed without
   credential data.
2. For `reviewer-responder`, select **Configure categories**, grant
   **Facilities**, and confirm. Reopen the dialog and verify that the setting was
   saved.
3. Leave `reviewer-responder-2` without Facilities access for the denial check.
4. Reset `reviewer-other`'s password. Verify that the temporary password is shown
   once. Log out, sign in with it, and verify that the app requires a replacement
   password before normal use. Confirm that the temporary password no longer works
   after replacement.
5. For `reviewer-delete`, select **Delete** and first cancel the confirmation.
   Verify that login still works. Delete it with confirmation and verify that
   login is disabled while historical incident references remain intact.
6. Sign back in as `reviewer-admin` before continuing.

Password reset and deletion are security-sensitive. Keep passwords and temporary
credentials private.

### 5. Administrator incident list and controls

Still signed in as Admin, open **Dashboard**.

1. Search by part of the title, part of the description, and an incident ID.
   Verify that matching is case-insensitive and unrelated incidents are excluded.
2. Exercise category, status, assignment, reporter, responder, creation-date, and
   SLO-state filters. Combine at least two filters and verify that both apply.
3. Change the sort field and direction. Verify the visible ordering. Select
   **Reset** and verify that the default view is restored.
4. Open an incident by double-click and by selecting it and pressing Enter.
   Verify description, status, identities, timestamps, resolution history,
   comments, attachments, and the SLO indicator. Use **Back to dashboard**.
5. Open the anonymous incident and verify that the owner identity is redacted.

For assignment controls, submit additional Facilities incidents as needed:

1. Select **Assign** on an unassigned incident. Verify that only Responders with
   Facilities access are enabled. Assign it to `reviewer-responder`.
2. Select **Reassign** and move it to another eligible Responder. If no second
   Responder is eligible, first grant Facilities to `reviewer-responder-2` under
   **Accounts**. Verify that only one assignee is shown.
3. Select **Unassign**, cancel, and verify no change. Confirm the action and verify
   that the incident returns to the unassigned queue.
4. Assign an incident and select **Resolve**. Verify that blank remarks are
   rejected, then enter non-blank remarks and verify the **Resolved** status and
   appended resolution history.
5. Under **Accounts**, remove Facilities access from `reviewer-responder-2` so it
   remains the unauthorized account for the next scenario.

### 6. Responder category access, claim, resolve, and handoff

Before starting, make sure `Water leak near pantry` is still an unassigned
Facilities incident and `reviewer-responder` has Facilities access.

1. Sign in as `reviewer-responder-2` while it lacks Facilities access. Verify that
   the incident is absent from **Eligible queue**.
2. Sign in as `reviewer-responder`. Verify that the incident appears in
   **Eligible queue** but not **My assigned incidents**.
3. Select **Refresh**, open the incident, and select **Claim**. Return to the
   dashboard and verify that it moved to **My assigned incidents**.
4. As `reviewer-reporter`, verify that **Edit**, **Withdraw**, and **Add attachment**
   are no longer available for that claimed incident.
5. As the assigned Responder, open it, select **Hand off**, and cancel. Verify it
   remains assigned. Repeat and confirm; verify that it returns unassigned to the
   eligible queue.
6. Claim it again and select **Resolve**. Verify that blank remarks are rejected.
   Enter `The leak was isolated and the ceiling was repaired.` and confirm.
7. Verify that the incident leaves both active Responder queues. As the Reporter
   and Admin, verify its **Resolved** status and saved resolution remarks.

To review queue ordering, submit two new Facilities incidents. Claim and hand off
the older one, then verify that it returns to its original relative position in
the category queue.

### 7. Reporter reopen workflow

Sign in as `reviewer-reporter` and open the resolved `Water leak near pantry`
incident.

1. Select **Reopen**, submit a blank explanation, and verify that no state changes.
2. Enter `The ceiling is leaking again after the repair.` and select
   **Submit follow-up**.
3. Verify that the incident becomes **Submitted**, the explanation appears in the
   comment thread, and the previous resolution remains in **Resolution history**.
4. Sign in as `reviewer-responder` and verify that the incident is again eligible
   in Facilities. For an anonymous incident, the follow-up author must use a
   neutral anonymous label for other roles.

Adding a normal comment to a resolved incident must not reopen it; only the
dedicated **Reopen** action with a non-blank follow-up does so.

### 8. SLO configuration and display

Sign in as `reviewer-admin` and select **SLO configuration**.

1. Select **Facilities** and note its current effective target and history.
2. Try a negative whole-number target and a reopen rate below 0 or above 100.
   Verify that invalid values are rejected without creating a history entry.
3. Enter average claim `60`, average in-progress `240`, and reopen rate `10`, then
   select **Save new target version**.
4. Verify that a new effective version appears in configuration history.
5. Inspect applicable incidents on the dashboard and detail page. Verify that the
   SLO summary/indicator is present and consistent with their lifecycle state.

Changing a target is prospective: existing historical compliance must not be
silently rewritten.

### 9. Statistics

Select **Statistics** as Admin.

1. Verify that the company summary includes incidents resolved, average time to
   claim, average time in progress, reopen rate, and Administrator-resolved count.
2. Verify that the Responder table contains resolution-related measures per
   Responder and does not attribute queue time-to-claim to an individual.
3. Filter by category and by **Period from**/**Period through**. Verify that the
   summary and rows update consistently.
4. Choose a filter combination with no matching incidents. Verify that the UI
   reports no matching data instead of displaying invented values.
5. Verify that anonymous reporter identity is not exposed in any result.

### 10. Notifications and session boundaries

1. During the cross-role workflows above, inspect the bell after changes relevant
   to the signed-in account. Verify the unseen badge, readable notification text,
   and that opening the tray marks displayed items as seen.
2. Log out while an incident detail or attachment image is open. Sign in as a
   different account and verify that the previous user's detail, image, and
   notifications are not exposed.
3. Do not expect notification history to survive restart; it is session-only.

### 11. Persistence, restart, and single-process protection

1. Close the app normally, launch it again with the same disposable data
   directory, and sign in.
2. Verify that accounts, password changes, category access, incidents, comments,
   assignments/statuses, resolution history, SLO versions, and
   the image attachment remain available.
3. While the app is running, start a second instance against the same data
   directory. Verify that the second instance is refused and that the first
   instance's data remains intact.
4. Close both processes normally. Do not edit, corrupt, restore, or delete the
   storage files as part of ordinary peer review.

## Cross-cutting review checklist

While running the scenarios, also assess the following:

- **Authorization:** controls appear only when appropriate, and changing role,
  ownership, assignee, category access, or lifecycle state never exposes another
  user's data or enables an invalid action.
- **Validation:** invalid input produces specific, understandable feedback and
  does not create partial records. User input should remain available after a
  recoverable failure where the User Guide promises this behavior.
- **Privacy:** anonymous identity and all credential material stay out of normal
  views, notifications, statistics, filenames, and diagnostics.
- **State consistency:** lists, details, notifications, statistics, and SLO
  displays agree after refresh, navigation, logout/login, and restart.
- **Usability:** labels match the User Guide; confirmations protect destructive
  or state-changing actions; keyboard/double-click interactions work where
  documented; long pages remain scrollable; errors and empty states are legible.
- **Reliability:** no freeze, crash, duplicate mutation, partially saved state,
  or stale data exposure occurs during normal use.

Treat an unavailable feature listed under [Scope](#scope) as out of scope, not as
a defect.
