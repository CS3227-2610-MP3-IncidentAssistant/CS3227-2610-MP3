# First Administrator Bootstrap

The application can create its first Administrator only through the one-time
startup bootstrap. Public registration always creates a Reporter. There is no
public or self-service Administrator registration.

## First run

Set both `INCIDENT_DESK_BOOTSTRAP_ADMIN_LOGIN` and
`INCIDENT_DESK_BOOTSTRAP_ADMIN_PASSWORD` through a private environment file or
approved deployment secret store. The login must be 1–128 nonblank characters;
the password must be 12–128 characters. Do not put real values in source
control, a ticket, a command transcript, a screenshot, or an evidence file.

For local development, use the git-ignored `.env.local` file. Start the web
application once and confirm that startup completes. The application stores a
salted password hash, creates a durable one-time marker, and records a success
audit event in the same database transaction. The audit event identifies the
trusted application as actor `SYSTEM`, with no account ID, and targets the
created Administrator account. Rejected bootstrap attempts use the same system
actor and target the `administrator-bootstrap` application operation. It does
not log or return the configured password.

Immediately remove both bootstrap values from `.env.local` or the deployment
configuration after successful startup. Leaving them configured makes the next
startup reject the replay and fail. `.env.example` contains only empty
placeholders.

## Production deployment status

The current GitHub deployment workflow builds and deploys the container but
does not configure the two bootstrap secrets. Before the first production
Administrator can be created, the deployment owner must arrange an approved
secret-store reference in the Cloud Run service configuration and remove that
reference after the first successful startup. Do not pass the password as a
command-line argument or workflow log value. This one-time operation is not
automated by the checked-in deployment workflow; production bootstrap remains
an operational prerequisite until that procedure is reviewed and recorded.

## Failure and recovery

- If neither value is set, startup continues without attempting bootstrap.
- If only one value is set or a value is invalid, startup fails before creating
  an account and reports the variable name, never its value.
- If a database write or the success-audit write fails, the transaction rolls
  back the account, credential, marker, and success audit together. Correct the
  underlying cause and retry only after confirming that no Administrator and
  no bootstrap marker were committed.
- If an Administrator already exists, a marker exists, or another instance
  wins a concurrent startup, the attempt is rejected and a credential-free
  failure audit is recorded. Remove both bootstrap values and restart without
  them.
- Never delete the marker or repeat bootstrap to recover a lost or disabled
  Administrator account. The application does not yet provide a later-admin
  provisioning or break-glass recovery flow. Preserve the database state and
  obtain an independently reviewed security/architecture recovery procedure
  before changing privileged account state.

Do not include bootstrap values, password hashes, or database connection
strings in logs or support evidence. The automated bootstrap database tests use
only an explicitly isolated PostgreSQL schema and synthetic credentials.
