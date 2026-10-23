---
layout: default
title: Home
nav_order: 1
---

# Incident Desk

**Incident Desk is a production-style web application for reporting and
managing company incidents.** Reporters can submit and track incidents,
Responders can handle incidents in their assigned categories, and
Administrators can manage incidents, access, service-level objectives, and
operational statistics. Each role also has a bounded, advisory AI feature whose
output remains subject to human review.

- [Open the deployed application](https://incidentdesk.cloud.run)
- [View the public source repository](https://github.com/CS3227-2610-MP3-IncidentAssistant/CS3227-2610-MP3)

## Release identification

This site was generated from repository revision
`{{ site.github.build_revision | default: "local-preview" }}`. The assessed
release is valid only when the public repository's `master` and `main` refs,
the deployed Cloud Run revision, and this GitHub Pages revision identify that
same commit. The final human-approved submission SHA and verification evidence
are recorded under `workflow/test-evidence/` before the repository is frozen.

## Documentation

- Follow the **[User Guide](UserGuide.md)** to exercise the current web features.
- Read the **[Developer Guide](DeveloperGuide.md)** for architecture, setup,
  testing, deployment, SDD, and reuse details.
- Read the **[Reflections](Reflections.md)** for concrete lessons about AI
  security, specification-driven development, and multi-agent software
  engineering.
