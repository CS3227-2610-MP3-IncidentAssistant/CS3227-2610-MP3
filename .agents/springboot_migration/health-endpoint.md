# Spring Boot Migration: Health Endpoint

Status: **Implemented and locally verified; independent review pending**

Approved by: repository user request on 2026-10-16

## Objective

Introduce the Spring Boot web runtime and provide a lightweight health endpoint
that deployment infrastructure and the future React client can use to determine
whether the Java web process is available.

## Scope

This migration slice includes:

- a Spring Boot web application entry point alongside the existing JavaFX entry
  point;
- Spring Boot Actuator health support;
- an unauthenticated, read-only `GET /health` endpoint; and
- automated tests for the endpoint contract and exposure boundary.

This slice does not include React, authentication, PostgreSQL, migrations,
business APIs, containers, CI/CD changes, or removal of JavaFX. Database
readiness will be added when PostgreSQL becomes a required runtime dependency.

## Architecture and dependency decisions

- Use Spring Boot 4.1.1, which supports the repository's Java 25 toolchain and
  Gradle 9.x wrapper.
- Use Spring MVC with embedded Tomcat through
  `spring-boot-starter-webmvc`.
- Use Spring Boot Actuator rather than a custom health controller so later
  health contributors can participate in one standard availability contract.
- Map the Actuator web base path to `/` and expose only the `health` endpoint.
- Keep the JavaFX launcher and packaging tasks available during the incremental
  migration. The Spring Boot application uses a separate main class and
  executable archive.

Adding Spring Boot and its starters is explicitly authorized by the user for
this migration slice. No other production dependency is introduced.

## HTTP contract

### Healthy application

Request:

```http
GET /health
Accept: application/json
```

Response:

```http
HTTP/1.1 200 OK
Content-Type: application/vnd.spring-boot.actuator.v3+json

{"status":"UP"}
```

### Unhealthy application

When a registered health contributor reports `DOWN` or `OUT_OF_SERVICE`, the
endpoint returns `503 Service Unavailable` with only the aggregate status.

The exact media type may include compatible parameters selected by Spring
Boot. Consumers must depend on the JSON `status` field rather than an exact
`Content-Type` string.

## Security and privacy

- No authentication is required because deployment infrastructure must be able
  to probe the endpoint before user authentication exists.
- The endpoint accepts no request body or user-controlled identifier.
- Health details and component names are never exposed.
- No other Actuator endpoint is exposed over HTTP.
- The response contains no credentials, configuration, stack traces, database
  details, user information, incident content, or anonymous-reporter identity.
- A health read does not mutate domain state and does not create an audit
  record.

## Configuration

- The web server uses Spring Boot's `server.port` configuration. Deployment may
  set the host-provided port with the standard `SERVER_PORT` environment
  variable.
- Safe endpoint exposure defaults are committed in
  `src/main/resources/application.properties`.
- No secret or environment-specific credential is committed.

## Acceptance criteria

1. The Spring Boot application context starts with the Java 25 toolchain.
2. `GET /health` returns HTTP 200 and JSON containing exactly
   `{"status":"UP"}` while the current application has no failing health
   contributor.
3. The response does not include `components` or `details`.
4. A representative non-health Actuator route, `/env`, is not exposed.
5. The existing JavaFX source and packaging remain available during this
   migration slice.
6. The Gradle test suite and Spring Boot executable archive build pass.

## Verification mapping

| Criterion | Evidence |
| --- | --- |
| Application context and `/health` contract | Spring Boot integration test using a real embedded server |
| No details or components | JSON response assertions |
| No other Actuator endpoint exposed | HTTP 404 assertion for `/env` |
| Existing JavaFX remains buildable | Existing startup/package tests and Gradle checks |
| Executable Spring Boot artifact | `bootJar` build output |

## Future reconciliation

When PostgreSQL persistence is introduced, update this specification and the
tests to define whether `/health` is readiness or liveness, include only the
safe database availability signal required by `.agents/persistence.md`, and
avoid treating an external dependency failure as a reason for restart loops.

## Local verification

Verified on 2026-10-16 with Java 25 and Gradle 9.6.1:

```text
gradlew.bat --no-daemon clean check bootJar
BUILD SUCCESSFUL
```

The formal independent-review and release gates in `workflow/README.md` remain
open.
