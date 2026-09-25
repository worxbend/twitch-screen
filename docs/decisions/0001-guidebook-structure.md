# ADR-0001: Organize the guidebook around reader journeys

## Status

Accepted for this documentation revision.

## Date

2026-09-25

## Context

Twitch Screen combines a Scala relay, ESP32 firmware, and a parametric enclosure. The component READMEs hold useful local instructions, but a newcomer also needs a continuous path across tool installation, Twitch registration, networking, wiring, flashing, and daily use. The requested root README needs a visual, friendly introduction without making every visitor read the complete build manual.

## Decision

Use the root README as a visual project overview with real CAD renders, a local simulator quick start, and links by audience. Put the guidebook index at `docs/README.md`, step-by-step material in `docs/guides/`, and configuration/API/architecture material in `docs/reference/`. Keep component READMEs as entry points beside their tools and preserve the existing detailed protocol and CAD records.

Offer one linear, hardware-free simulator tutorial. Put choices such as Docker versus native execution and WebSocket versus webhook operation in task-focused guides. Render everything as ordinary GitHub Markdown. Use emoji and humor in navigation and introductions while keeping identifiers, commands, and troubleshooting precise.

This organization follows the reader needs described by [Diátaxis](https://diataxis.fr/start-here/) and the overview/getting-started/navigation role described in [GitHub’s README guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes).

## Alternatives considered

### One exhaustive root README

It keeps everything in one file, but makes first impressions and focused maintenance harder. Readers need different levels of detail. Keep a concise tour and link to complete guides.

### Only component READMEs

It keeps commands near their source, but leaves cross-component tasks without a route map. Retain those READMEs and connect them through the guidebook.

### A new documentation website with sbt/mdoc

A site could add search and compile Scala snippets. This task is documentation-only, the relay already uses Mill, and these guides teach shell workflows rather than a Scala library API. Adding a second build and deployment system would expand the task beyond its need. Use Markdown and validate the existing commands directly.

## Consequences

Readers can enter by goal and progress through the whole build. Repository-relative links work in clones and on branches. CAD imagery stays tied to the checked-in design.

Maintainers must update overview links and focused reference pages together when behavior changes. A documentation review must check command context, defaults, source claims, local links, and rendering. mdoc compilation is not a validation claim for this repository; firmware builds, relay checks, and targeted command walkthroughs remain the relevant evidence.
