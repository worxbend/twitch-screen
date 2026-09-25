# 🧾 Documentation decisions

Architecture decision records preserve the context behind consequential choices. This directory starts with the documentation structure chosen for this guidebook. It does not retroactively assign approval dates or rejected alternatives to the existing firmware and relay.

| Record | Status | Scope |
| :--- | :--- | :--- |
| [0001 — Organize the guidebook around reader journeys](0001-guidebook-structure.md) | Accepted | Documentation |
| [0002 — Make the device-link trust boundary explicit](0002-device-link-trust-boundary.md) | Accepted | TSB/3 deployment security |

New records use sequential four-digit filenames, a short title, and the sections **Status**, **Date**, **Context**, **Decision**, **Alternatives considered**, and **Consequences**. Preserve old records; a replacement should reference the record it supersedes.

For the current system’s architecture and source-backed trade-offs, read [Architecture](../reference/architecture.md). For writing and reviewing a guide, read [Documentation maintenance](../maintaining-documentation.md).
