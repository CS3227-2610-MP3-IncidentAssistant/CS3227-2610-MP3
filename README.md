# CS3227 MP 2

[Open the Incident Desk project website](https://cs3227-2610-mp2-incidentassistant.github.io/CS3227-2610-MP2/)

Incident Desk is a local JavaFX desktop application for company incident
reporting. It targets Java 25 and is built with the checked-in Gradle Wrapper.

See [the developer guide](docs/DeveloperGuide.md) for setup, build, test, and
architecture details.

## Spring Boot migration preview

The incremental MP3 migration now includes a Spring Boot web process with a
minimal health endpoint. Start it with:

```powershell
.\gradlew.bat bootRun
```

Then request `http://localhost:8080/health`. The endpoint returns only the
aggregate application status. The existing JavaFX launcher remains available
while the web migration is in progress.

## Agent usage guide

This repository provides three project-specific agent skills:

- `code-quality-review`: Reviews non-trivial code changes for readability,
  naming, unsafe shortcuts, and comment quality.
- `git-commit`: Inspects changes, prepares an accurate commit message, and
  creates a commit when requested.
- `log-agent-conversation`: Saves the visible conversation as a Markdown
  snapshot when explicitly requested.
