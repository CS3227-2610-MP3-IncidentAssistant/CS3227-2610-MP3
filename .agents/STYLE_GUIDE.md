# Incident Desk React Style Guide

This guide preserves the Calm Blue Operations visual language from MP2 while
defining its MP3 implementation for a responsive React web application. Role
identity comes from navigation labels, permissions, and content—not separate
color palettes. Visual consistency must not weaken authorization, privacy,
semantic HTML, keyboard access, or safe rendering.

## Design tokens

Define these values once as CSS custom properties or equivalent theme tokens.
Components consume tokens rather than repeating literal colors.

| Token | Value | Use |
| --- | --- | --- |
| `--color-navy` | `#17233F` | Navigation, login art, strong contrast areas |
| `--color-action` | `#4169D8` | Primary actions, active navigation, charts |
| `--color-canvas` | `#F5F7FB` | Application background |
| `--color-surface` | `#FFFFFF` | Cards, forms, panels, dialogs |
| `--color-text` | `#18243A` | Headings and body text |
| `--color-text-muted` | `#5D6678` | Supporting text and table headers |
| `--color-border` | `#DCE2EC` | Cards, controls, and separators |
| `--color-focus` | `#8EAAFF` | Visible keyboard focus |

Text tokens must satisfy the WCAG 2.2 AA contrast minimum in the accessibility
baseline below against both `--color-surface` and `--color-canvas`. Normal-size
text needs 4.5:1. `--color-text-muted` is the tightest of these and measures
5.77:1 on `#FFFFFF` and 5.38:1 on `#F5F7FB`; it carries most of the supporting
copy, so re-measure both ratios before changing it. The automated axe scan in
the administrator browser suite fails on any regression here.

| Semantic tone | Foreground | Soft background | Use |
| --- | --- | --- | --- |
| Info | `#2854B6` | `#E4ECFF` | Default status |
| Success | `#1F7A55` | `#DDF4E9` | Resolved, healthy, positive metric |
| Warning | `#946016` | `#FFF0D3` | At risk, medium priority |
| Danger | `#B33A3A` | `#FCE4E4` | Error, destructive action, high priority |
| Neutral | `#596579` | `#E9EDF3` | Inactive or unavailable state |

Supporting tokens should cover an 8px spacing scale, 8–12px radii, restrained
shadows, control heights, content widths, typography, and breakpoints. Add new
tokens deliberately; do not introduce one-off variants for individual pages.

## Typography

- Prefer the system UI font stack; do not require a remote font for core use.
- Page titles use approximately 28–34px bold text, panel headings 17–20px,
  body and controls 14–16px, and labels/table headers 12–14px semibold.
- Use semantic heading order. Do not select heading elements only for size.
- Keep primary reading text left aligned and allow user zoom to 200% without
  clipped content or hidden actions.

## Responsive layout

- Use an 8px spacing rhythm where practical.
- Use a full sidebar near 232px on wide screens. At narrower widths, collapse
  it to an accessible menu rather than an icon-only navigation with no labels.
- On authenticated role pages, place the signed-in account identity and logout
  action in a separated block anchored to the bottom of the sidebar. Reserve
  the top bar for the accessible menu trigger when the sidebar is collapsed.
- Do not show backend or infrastructure health indicators in the role sidebar.
  The deployment health endpoint is for automated probes, not user-facing
  availability reporting.
- Use approximately 32px horizontal content padding on wide screens, 24px at
  medium widths, and 16px on narrow screens.
- Metrics move from four columns to two and then one. Forms and detail layouts
  move from two columns to one before horizontal overflow occurs.
- Tables with essential row relationships may scroll within a labelled region;
  simpler data should become stacked cards at narrow widths.
- Long titles, descriptions, comments, filenames, and AI output wrap without
  breaking the page.

## Shared React components

Before creating a component, search the existing shared component directory and
reuse or extend a suitable primitive. Shared components own presentation and
accessibility behavior only. They do not perform authorization, load protected
records directly, or infer identity from hidden data.

The shared system should provide or compose:

- application shell, responsive navigation, page header, and breadcrumb;
- primary, secondary, destructive, ghost, small, loading, and disabled buttons;
- labelled inputs, text areas, selects, checkboxes, field errors, and form
  summaries;
- semantic badges for incident status, category, SLO, priority, and role;
- cards, metric cards, tables or lists, filters, pagination, and empty states;
- incident detail sections, comment thread, attachment tile, lifecycle timeline,
  resolution history, and SLO progress;
- confirmation dialog, toast or status message, loading state, and page error;
- AI suggestion panel with unambiguous generated-content labeling, review,
  apply/copy, retry, rate-limit, and unavailable states.

### Role entry and authentication

- Provide a public role-entry screen with clearly labelled Reporter, Responder,
  and Administrator choices leading to separate login routes or portal views.
- Keep the brand, form structure, validation, recovery behavior, and
  accessibility consistent across portals while making the selected role and
  destination unmistakable.
- Show registration only in the Reporter portal. Never show a public role
  selector inside registration, and never offer Administrator or Responder
  signup.
- Do not reveal whether a login failed because the account exists under another
  role. Use the generic authentication message defined by the security policy.
- Portal separation is navigation and expectation-setting only. Do not encode
  authorization solely in React routes or portal-specific components.

### Anonymous submission control

- Use an explicitly labelled checkbox or switch such as "Submit anonymously",
  with short supporting text explaining who will not see the reporter identity
  and the documented limits of anonymity.
- The selected state must be visible before submission, keyboard operable, and
  included in the form's accessible name/description relationships.
- Do not infer anonymity from missing identity fields. Submit an explicit
  boolean to the backend and render the persisted result after success.

Do not create parallel role-specific versions when composition and authorized
data can support one shared component.

## Interaction states

- Every interactive element has visible hover, focus-visible, active, loading,
  disabled, success, and error behavior where applicable.
- Buttons are at least 40px high with clear text. Destructive actions use a
  confirmation step that states the consequence.
- Disabled controls must not be the only explanation; provide adjacent text
  when the user needs to know why an action is unavailable.
- Do not use color alone for state. Combine color with text and, when helpful,
  an icon.
- Loading indicators retain layout where possible. Empty and error states give
  a clear next action.
- Prevent accidental duplicate form submissions, but rely on backend
  idempotency and concurrency controls for correctness.

## Forms and validation

- Every control has a persistent programmatic label and useful autocomplete
  metadata where appropriate.
- Show field errors next to the field and provide an accessible error summary
  for a rejected submission.
- Browser validation may improve feedback but never replaces backend
  validation.
- Preserve deliberate incident-description and resolution whitespace while
  still enforcing the centrally configured length limits.
- Never accept actor, role, ownership, assignee, audit identity, or trusted
  timestamps from React state as authoritative.

## Tables, timelines, and metrics

- Table headers use proper `<th>` scope and controls have accessible names.
- Interactive rows must also expose a real link or button; clicking a styled
  container alone is insufficient.
- Use stable text labels for status and SLO state. Charts require a textual
  value or table alternative.
- Display dates in the user's chosen/local zone while preserving UTC values and
  unambiguous server semantics.
- Skeletons or placeholders must not expose data from a previous authenticated
  user or incident while new content loads.

## Dialogs and notifications

- Use the native `<dialog>` element or an accessible, tested dialog primitive.
- Dialogs receive focus, trap it while open, close with Escape when safe, and
  return focus to the trigger.
- Notifications use an appropriate live region without repeatedly announcing
  routine updates.
- Page-level errors provide a concise explanation and recovery action without
  exposing stack traces, SQL, provider payloads, or protected resource details.

## AI presentation

- Label output as an AI-generated suggestion and show that it may be incorrect.
- Render output as text, never with `dangerouslySetInnerHTML`. Do not turn model
  text into links, commands, or React components.
- Keep the suggestion visually separate from saved incident content.
- Reporter output may be copied or explicitly applied to the editable form, but
  applying it must not submit the incident.
- Responder and Administrator output remains advisory and has no mutation
  control disguised as an AI action.
- Provider timeout, malformed output, or rate limiting uses a recoverable error
  state and leaves the normal role workflow usable.

## Accessibility baseline

- Target WCAG 2.2 AA for contrast, keyboard operation, focus visibility,
  labels, error identification, reflow, and target size.
- Prefer semantic HTML before ARIA. Use ARIA only to complete semantics that
  native elements cannot express.
- Preserve logical DOM and tab order. Never use positive `tabIndex` to repair a
  visual ordering problem.
- Support keyboard-only use, screen-reader names, 200% zoom, narrow reflow,
  reduced motion, and sufficient non-color cues.
- Decorative icons are hidden from assistive technology; meaningful icons have
  an accessible text equivalent.

## Security and privacy boundaries

- React route guards and hidden controls are presentation conveniences, not
  authorization. The Java backend enforces every resource access and mutation.
- Escape untrusted content by default. Avoid `dangerouslySetInnerHTML`; any
  future rich-text feature requires a restrictive sanitizer and security tests.
- Do not persist credentials, session identifiers, anonymous identity, raw AI
  prompts/responses, or protected incident data in `localStorage`.
- Clear role-specific cached state on logout and authentication changes.
- Do not include sensitive incident content or identity in URLs, analytics,
  browser logs, error-reporting breadcrumbs, or client-visible configuration.

## Verification checklist

For each new or materially changed shared component, verify:

- reuse was considered before adding another primitive;
- responsive behavior at wide, medium, and narrow widths;
- keyboard, focus, semantic HTML, labels, errors, and screen-reader status;
- loading, empty, denied, success, failure, and stale-data states;
- safe rendering of long text, malicious HTML, and AI output;
- absence of protected data from URLs, browser storage, and client logs; and
- backend authorization remains the source of truth.

Record implementation-specific completion evidence under
`workflow/test-evidence/`; do not mark visual components complete in this guide
before the React implementation exists.
