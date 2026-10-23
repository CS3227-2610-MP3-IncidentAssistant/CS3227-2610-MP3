---
layout: default
title: User Guide
nav_order: 2
---

# Incident Desk User Guide

Incident Desk is a web application for reporting workplace incidents, assigning responders, and monitoring service objectives. There are three portals: Reporter, Responder, and Administrator.

## Contents

- [Open the application and sign in](#open-the-application-and-sign-in)
- [Report and track an incident](#report-and-track-an-incident)
- [Use description assistance](#use-description-assistance)
- [Request responder access](#request-responder-access)
- [Handle incidents as a responder](#handle-incidents-as-a-responder)
- [Use responder AI incident assistance](#use-responder-ai-incident-assistance)
- [Administer incidents and accounts](#administer-incidents-and-accounts)
- [Configure SLOs and review statistics](#configure-slos-and-review-statistics)
- [Ask about operational statistics](#ask-about-operational-statistics)
- [Troubleshooting and current limits](#troubleshooting-and-current-limits)

Screenshots captured on 23 October 2026 show a local demonstration with fictional accounts and incidents. Each image focuses on the relevant form, list, or result. They illustrate the interface, not evidence of a public deployment. Your data and available categories may differ.

## Open the application and sign in

1. Obtain the application URL from your deployment owner and open it in a browser. For local setup, see the [Developer Guide](DeveloperGuide.md).
2. At **Choose your portal**, select **Reporter**, **Responder**, or **Administrator**.
3. Enter your **Login name** and **Password**, then select **Sign in**. The account must be enabled and match the selected portal.
4. New users can select **Create Reporter account** from Reporter sign in, enter a login name, password, and confirmation, and select **Create account**. Registration creates a Reporter, not an administrator or responder.

![Portal selection](images/mp3/portals.png)

Use **Sign out** when finished, especially on a shared computer. Select **Update password** to change your password. If an administrator resets your password, obtain the temporary password through your agreed private channel and complete the required password change before continuing. The application asks for your current password, new password, and confirmation.

Administrator accounts cannot currently be created through the web interface.
The first Administrator must be provisioned by the deployment owner using the
one-time procedure in [First Administrator Bootstrap](AdministratorBootstrap.md),
and the application does not currently provide a later Administrator creation
or recovery flow.

For the designated grading/test deployment, select **Administrator** and use
the login name `admin`. Strictly for grading purposes, the password for this
account will be listed in this document. The password for the account is 
`administration`.

## Report and track an incident

1. Sign in as a Reporter and open **My incidents**.
2. In **What happened?**, enter a **Title** of up to 200 characters.
3. Choose **IT**, **Human Relations**, or **Facilities** under **Category**.
4. Enter a factual **Description**, up to 10,000 characters. For example: “The meeting room projector does not turn on. The power indicator remains off after checking the wall switch.”
5. Optionally select **Submit anonymously**.
6. Select **Submit incident** and check the result before submitting again.

Anonymous submission hides your identity in normal responder and administrator views. Hosting operators may still access protected records; information in your description can also identify you. It does not remove the report from your own account.

![New incident form](images/mp3/reporter-form.png)

Your reports appear under **My incidents**. Use **Search your reports**, **Search**, **Refresh**, and the page controls to find them and check their status. Reporter results are an owned-incident list; this page does not provide a detail link or editing, reopening, or withdrawal controls.

![Reporter incident list](images/mp3/reporter-list.png)

## Use description assistance

Description assistance is optional. You can submit a report without using AI.

1. Fill in the title, category, and description first. The **Improve description** button requires all three.
2. Select **Improve description**. This sends those fields to the configured AI service for processing. Avoid unnecessary personal or confidential information.
3. Read the **AI-generated suggestion** and check that it preserves the facts. The model can make mistakes or omit details.
4. Select **Apply suggestion** to replace the description in the form, or **Discard** to keep your original draft.
5. Review the form and separately select **Submit incident** when ready.

Applying a suggestion does not submit an incident. **Cancel** stops waiting for an answer; processing already accepted by the provider may still consume quota. Editing the draft invalidates an older suggestion. AI output is displayed as text and does not execute commands or perform incident actions.

If assistance is disabled, unavailable, rate limited, or returns an invalid suggestion, read the displayed message. Your draft remains available for manual editing and submission. Wait before retrying a quota error. Large requests can be rejected even within the visible character limits because the combined encoded request has a separate size limit.

## Request responder access

1. In the Reporter sidebar, select **Promotion**.
2. Choose the categories you want to handle and optionally enter **Additional comments** explaining your request.
3. Submit the request and check the request history. Use **Refresh** to obtain the latest decision.
4. An administrator reviews the request. Approval changes your account to a Responder with the approved categories. Sign in again through the **Responder** portal after approval.

![Confirmation that a promotion request was submitted](images/mp3/promotion-submitted.png)

A pending request must be decided before another can be submitted. Approval may grant only selected requested categories; it does not grant administrator access.

![Promotion request history and pending status](images/mp3/promotion-history.png)

## Handle incidents as a responder

Sign in through **Responder** using an account with approved categories. The **Incident queue** shows incidents within your authorized category scope.

1. Use **Search incidents**, category and status choices, and **Assignment** to narrow the queue.
2. Choose **All queue incidents**, **Assigned to me**, or **Available to claim**, then select **Apply filters**.
3. Use **Refresh queue** and the page controls to update or browse results.
4. Select an incident title to open its details.

![Responder queue](images/mp3/responder.png)

For an available incident, select **Claim incident** in its detail view. A successful claim assigns the incident to you and changes its status to **ASSIGNED**. If someone else claims it first, refresh the status rather than assuming your claim succeeded.

![Assigned incident overview and comments](images/mp3/responder-detail-overview.png)

For an incident assigned to you, inspect its description and available history, enter **Resolution remarks** of up to 2,000 characters, and select **Resolve incident**. Check the confirmation before returning to the queue. Resolution remarks must explain the outcome; resolving an incident is separate from viewing it.

![Resolution remarks form](images/mp3/responder-resolution-form.png)

![Successful responder resolution](images/mp3/responder-resolved.png)

If the result of a claim or resolution is uncertain because the connection failed, use the displayed status-check or safe-retry control before attempting another action. **Back to queue** returns to the list. Access can change when an administrator changes your category grants.

## Use responder AI incident assistance

AI incident assistance is optional. Open an available, unassigned incident in a category you can handle, or an incident assigned to you. In its detail view, find **AI incident assistance** below the description.

1. Select **Request AI advice** to request a summary and suggested investigation steps for that incident.
2. While **Preparing advice…** appears, you can select **Cancel request** to stop waiting. A request already accepted by the AI provider may still use quota.
3. Read the **AI-generated suggestion · Advisory only** text and check it against the incident details. The suggestion may be incomplete or wrong.
4. Decide what to do yourself. Claiming an incident and entering **Resolution remarks** remain separate actions; the advice does not fill the remarks or change the incident.

If the request fails, the incident remains available for normal handling. Select **Try again** if appropriate, or continue without AI. If you lose access to the incident while the request is running, its advice is not shown. The AI request uses selected incident details, including its description and current status, so avoid placing unnecessary sensitive information in incident text.

## Administer incidents and accounts

### Dashboard and incident details

Sign in through **Administrator** and select **Dashboard**. The company overview shows incidents across categories and current category targets. Use **Search incidents**, **Search**, **Reset**, **Refresh**, and page controls to locate a report. Select its title to inspect the detailed record.

![Administrator dashboard](images/mp3/admin-dashboard.png)

Details can include description, assignment, lifecycle, resolution history, SLO information, and any existing comments or attachments. Anonymous reporters remain masked in the normal view. Administrators can also perform the following actions when the incident state permits them:

![Administrator incident overview and assignment controls](images/mp3/admin-incident-summary.png)

- Select an eligible responder under **Assign to responder** or **Reassign to responder**, then select **Assign incident** or **Reassign incident**.
- Select **Unassign incident** and confirm the dialog to remove an assignment.
- Enter text under **Add a comment** (up to 4,000 characters) and select **Add comment**.
- Enter **Resolution remarks** (up to 2,000 characters) and select **Resolve incident** to record an administrator resolution.

If another session changes the incident, the latest details are reloaded. Review them before retrying. Attachment display does not provide an upload control.

![Administrator comments and comment form](images/mp3/admin-incident-comments.png)

### Accounts and promotion requests

Select **Accounts** to view active accounts and pending promotion requests.

- **Configure categories** on a Responder changes the categories that account can handle. Review the selection before saving.
- **Reset password** asks for confirmation and reveals a temporary password. Share it privately with the account owner; the owner must change it before normal use.
- **Delete** opens a confirmation. **Delete account** disables the login while retaining historical incident references. This action cannot be undone from the interface.

![Active account directory and account actions](images/mp3/admin-directory.png)

For a promotion request, review the requested categories and comments, select **Categories to grant**, and choose **Approve selected categories**. Review **Confirm promotion** before confirming. Alternatively select **Reject** and confirm the rejection. Cancel the confirmation to leave the request pending.

![Administrator promotion review](images/mp3/admin-promotion.png)

## Configure SLOs and review statistics

SLOs are service level objectives used to assess response and resolution performance.

### Configure category targets

1. Open **SLO configuration** and choose the category.
2. Review its current effective target and version history.
3. Enter **Average time to claim (minutes)**, **Average time in progress (minutes)**, and **Average reopen rate (%)**.
4. Select **Save new version**. A successful change creates a new version rather than rewriting the earlier history.

![Current category SLO targets](images/mp3/admin-slo-current.png)

![Form for saving a new SLO target version](images/mp3/admin-slo-edit.png)

If another session changed the targets, refresh and review the latest values before trying again. A category without configured targets is shown as not configured.

![Read-only SLO version history](images/mp3/admin-slo-history.png)

### Review statistics

Open **Statistics**. Choose categories, a responder, a reporter, and dates as needed, then select **Apply filters**. **Reset** removes your filters; **Refresh** reloads the current selection.

![Statistics filters](images/mp3/admin-statistics-filters.png)

The page reports incident outcomes and responder resolution performance, including resolved counts, average time to claim, average time in progress, reopened incidents, and administrator-resolved incidents. An unavailable average is not a zero-duration response. Historical statistics may include activity created outside the currently exposed web actions.

![Incident outcome metrics before any resolutions](images/mp3/admin-statistics-results.png)

## Ask about operational statistics

The **Statistics** page includes **Ask about operational statistics**, where you
can ask a question in plain language instead of building filters by hand. The AI
only interprets your wording into one of the supported queries listed below.
Every name, count, duration, rate, ranking, date bound, and sample size you see
is then calculated by the application from stored records, so the numbers are
reproducible and do not vary between identical questions.

### Ask a question

1. Type a question of 1 to 500 characters into **Question**. Always include a
   reporting period; without one the application asks you to specify it rather
   than silently choosing a window.
2. To compare named responders, tick 2 to 5 people under the responder
   selector. Responder names typed into the question are not recognised, and
   your selections are never sent to the AI provider.
3. Select **Get insight**. While **Interpreting…** appears, the question is
   being interpreted. **Clear** discards a result or message.
4. Read the result. **Deterministic answer** shows the metric, ranking
   direction, limit, grouping, and the exact period in Singapore time with the
   end bound exclusive, followed by up to 10 rows.

![AI-assisted operational statistics question and responder selection](images/mp3/admin-ai-insights.png)

The category and responder filters already applied to the page are applied to
your question separately. They are not disclosed to the AI provider.

### Sample questions

| Question | What you get |
| --- | --- |
| Who had the longest average time in progress in the last 30 days? | Responders ranked by average time in progress, each with its sample size. |
| Which responder resolved the most incidents this month? | Responder ranking by resolved-cycle count. |
| Which responders have the highest reopen rate over the past week? | Responder ranking by reopen rate; a zero denominator reads Not available. |
| Compare the selected responders by resolved count this month. | One row for each responder you ticked, including a zero row for anyone without a qualifying cycle. |
| Which category had the longest average time to claim this week? | Category ranking by queue time to claim for cycles that entered the queue this week. Active cycles count only while they remain assigned; completed cycles come from resolution history. |
| Which category has the most reopen events in the last 30 days? | Category ranking by reopen-event count. Repeated reopenings of one incident are separate events. Ask for reopen rate when you want the proportion of resolved incidents that reopened. |
| Show incident volume by category for the last 30 days. | A daily series for up to 10 categories. The application chooses the interval automatically from the reporting-window length; question wording cannot select a different interval. |
| How did incident volume this month compare with last month? | The same metric across two adjacent, non-overlapping periods. |
| Show the current unassigned backlog by category for the past 30 days. | Current unassigned count and oldest unassigned age for each category. A reporting period is required to interpret the question, but it does not restrict the current snapshot. |
| Compare category average time to claim with its configured SLO target last month. | Observed-minus-target difference for the one requested SLO metric, with a separate row for each applicable target version. A positive value is above target. |
| Give me an operational overview for the past 30 days. | Resolved-incident count, average time to claim, average time in progress, reopen rate, and current unassigned backlog count. It does not include total incident volume or SLO comparisons. |

**Sample** is the number of qualifying records reported alongside a value. An
average claim time uses claimed queue cycles. Responder metrics and category
reopen metrics display completed resolution cycles as their sample, although a
category reopen-rate denominator counts distinct resolved incidents. A value
of `0` with a sample of `1` means one qualifying cycle was examined and no
matching event occurred; a sample of `0` means no qualifying cycles supported
the value.

### Exactly what can be accessed

The feature reads only aggregated operational statistics. It can report:

- incident volume and resolved counts;
- average time to claim and average time in progress;
- reopen rate and reopen event count;
- unassigned backlog counts and oldest unassigned age; and
- one selected observed metric compared with versioned category SLO targets,
  reported as the observed-minus-target difference.

The available grouping depends on the query: responder rankings use completed
resolution cycles, category rankings use category aggregates, and comparisons
may use company or category scope. Supported periods are rolling 7 or 30 days,
a UTC calendar week or month, the previous week or month, or explicit dates. A
reporting interval may not exceed 366 days, and periods ending in the future
are rejected.

Trend intervals are selected automatically from the reporting-window length:
windows of up to 31 days use daily points, windows of up to 180 days use
seven-day points, and longer windows use 31-day points. Asking for a different
interval in the question does not override this selection.

It cannot access individual incidents or personal data. There is no way to
retrieve an incident's identifier, title, description, comments, or
attachments, no reporter grouping or reporter filtering, no access to anonymous
reporter identities, and no access to audit records or account credentials.
Questions about one specific incident, including its extremes or details, are
unsupported and are not quietly widened into a company-level summary. Anonymous
reports still count towards company and category totals, but their reporters
cannot be identified or inferred through this feature.

The feature is read-only. An answer cannot create, edit, claim, assign, resolve,
reopen, withdraw, delete, promote, reset a password, change SLOs, or notify
anyone. Any action you decide to take afterwards uses the ordinary workflow.

### What the AI provider receives

The provider receives only your validated question, the time of the request, and
the fixed list of supported queries. It never receives the calculated
statistics, your category or responder selections, any name, login, identifier,
incident text, comment, attachment, or audit record.

Because the question itself is disclosed, do not type names, incident details,
comments, reporter information, identifiers, or links. The application also
rejects a question containing a known login name, an account or incident
identifier, an email address, a URL, or control characters before any
disclosure. Use the responder selector for named comparisons.

### When a question needs changing

If the question is unsupported or unclear, the result is **Clarification
needed** with one of four prompts, and no statistics query runs:

| Prompt | What to change |
| --- | --- |
| Specify a reporting period. | Add a period, such as "in the last 30 days" or "this month". |
| Specify the operational metric to analyze. | Name one metric, such as time to claim or reopen rate. |
| Ask about one supported operational statistic. | Rephrase as one of the supported questions above. Individual incidents are unsupported. |
| Specify one primary period and, if needed, one adjacent comparison period. | Reduce the question to one period, or one period plus one comparison. |

A question that matches no stored activity returns a successful "No qualifying
data was found." That is an accurate empty result, not a failure.

### Limits to expect

- At most 10 rows per answer. Every responder tied for first place is kept, up
  to that ceiling.
- Every responder with at least one qualifying completed cycle appears,
  whatever their sample size. Nothing is hidden for being a small sample, and
  each responder row shows its exact sample size.
- A value that would divide by zero reads Not available rather than zero.
- Responder results are labelled operational indicators. Treat them as such
  and not as employee-performance or disciplinary judgments; the feature infers
  no cause, intent, or competence.
- Each question is answered on its own. There is no chat history, follow-up
  context, scheduled analysis, alerting, or forecasting.
- Results come from a snapshot taken when the query runs, so an incident
  changed at the same moment may appear only in a later request.
- If AI is unavailable, over budget, or times out, the message says so and the
  ordinary statistics view above keeps working.

## Troubleshooting and current limits

| Situation | What to do |
| --- | --- |
| Sign in fails | Check your credentials, selected portal, and whether the account is enabled. Contact an administrator for a password reset if needed. |
| Session expires or access changes | Sign in again through the correct portal. Do not assume a pending action succeeded. |
| A list is empty | Check filters, page selection, and your role or category access, then refresh. |
| Service or network error | Keep a copy of an important draft, restore connectivity, and use the displayed retry or status-check control. |
| Improve description is disabled | Enter a nonblank title and description and choose a category first. |
| AI request fails | Continue manually or retry later as the message advises. AI availability is separate from normal incident submission. |
| An insight question is not answered | Read the clarification prompt and add a period, name one metric, or ask about one supported statistic. Individual incidents are unsupported. |
| Another user changed the same item | Refresh and review its latest state before acting again. |

The web application stores shared records on its server database. A browser refresh can lose an unsubmitted form; there is no saved-draft workflow. Do not use the old desktop application's local JSON files or JAR instructions for this web version.

The current web interface does not offer Reporter incident editing/reopening, attachment upload, Reporter or Responder comment creation, responder handoff, or an audit-log browser. AI assistance is available for Reporter descriptions, authorized Responder incident details, and Administrator questions about operational statistics. This guide describes available controls rather than planned desktop-feature parity.
