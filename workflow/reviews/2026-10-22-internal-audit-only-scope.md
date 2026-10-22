# Internal-only audit scope amendment

- Date: 2026-10-22 (Asia/Singapore)
- Approver: repository user, through the current Codex chat
- Role represented: product owner
- Decision: approved scope amendment

The product owner clarified that canonical audit logging remains in the Java
backend as an internal integrity control, but the web application must not
expose audit history through an HTTP endpoint, React page, navigation item,
filter, detail view, or download. Public and user-facing documentation must not
advertise audit history as an available workflow.

This amendment supersedes only the user-visible audit-history portions of the
2026-10-18 Administrator slice approval and ADR-001. It does not waive or
weaken append-only audit persistence, mutation-plus-audit transaction
atomicity, redaction, retention, anonymous-reporter protection, or automated
verification of audit effects. Any future user-visible audit feature requires
a new approved specification and security/privacy review before implementation.
