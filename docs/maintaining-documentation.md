# ✍️ Maintaining the guidebook

Write for the person doing the next step. The landing page can have personality; a pin number, redirect URL, or recovery instruction needs precision.

## Put information where a reader expects it

| Location | Responsibility |
| :--- | :--- |
| [Root README](../README.md) | Explain the project, show the device, give a first success, and route each audience |
| [Guidebook index](README.md) | Connect users, builders, and developers to a complete journey |
| `docs/guides/` | A linear tutorial or instructions for a specific task |
| `docs/reference/` | Exact configuration, API behavior, and system explanation |
| Component READMEs | Local entry points, commands, tooling, and links to detailed guides |
| Existing protocol/CAD records | Authoritative wire rules and mechanical evidence |
| [Decision records](decisions/README.md) | Context, choices, trade-offs, and consequences |

The [organization decision](decisions/0001-guidebook-structure.md) explains this split. [GitHub’s README guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes) informs the landing page; [Diátaxis](https://diataxis.fr/start-here/) informs the separation of learning, tasks, reference, and explanation.

## Check facts against the implementation

Read the source before rewriting an old guide. Use the current code to establish behavior, configuration files for defaults, tests for edge cases, and history for the reason behind a choice. Treat plans and previous review reports as historical evidence, not a guarantee of current behavior.

Link to source for details likely to drift. For external requirements such as Twitch app registration, read the current official documentation and include a direct link. Record the review date. Distinguish a provider requirement from a limitation of this relay.

Avoid claiming a feature exists because a protocol enum names it. Verify which live producer actually emits it and whether the UI supports it. Explain known differences between the HTTP API and wire vocabulary.

## Make commands usable

State prerequisites, the working directory, and which terminal remains running. Separate placeholder values from runnable examples. Explain what a successful command returns, where files are written, and how to stop a service.

For a learning tutorial, use one path, introduce one concept at a time, annotate every command block, and show intermediate results. State learning objectives up front and mirror them in the recap. Task guides can offer clearly labeled choices such as native versus Docker execution.

Use `sh`, `bash`, `json`, `yaml`, `cpp`, or `text` fences as appropriate. Parse shell examples without executing destructive actions. Never test upload, erase, live authorization, or production mutations merely to check prose. Run local simulation with temporary credentials and isolated ports when functional evidence is needed.

The current docs use existing runnable tools as examples. There is no sbt/mdoc documentation module, Docusaurus site, or Scala tutorial companion module in this repository. Do not claim those checks passed or add incompatible embeds that GitHub cannot render. Any future addition of such tooling needs its own scoped change.

## Keep the style friendly and accessible

- Use clear headings and short paragraphs. Put the result or instruction before background detail.
- Use emoji alongside descriptive text; never make an icon the only navigation label.
- Keep jokes in introductions and transitions. Troubleshooting should name the symptom and the next diagnostic action.
- Use repository-relative links for local files. Check every target and section anchor.
- Use descriptive image alt text. Identify renders, mockups, and measured results honestly.
- Reuse the checked-in CAD previews; avoid duplicating large image files.
- Keep centered HTML to the README hero and compact galleries. The instructions should remain readable as ordinary Markdown.
- Prefer GitHub-compatible callouts and code fences. Do not introduce site-specific directives into these pages.

## Record decisions without inventing history

Follow the [ADR convention](decisions/README.md). A new decision needs context, the actual chosen approach, considered alternatives, and consequences. Do not retroactively call a historical design “accepted” or invent alternatives no one evaluated. Source-backed architecture explanations can document current trade-offs without pretending to be an approval record.

Keep old records when a decision changes and link a superseding record. Documentation-only edits do not imply a software release, version bump, or release-note entry.

## Review before sharing

1. Read the complete changed pages, including preserved sections in component READMEs.
2. Follow each reader journey through its links. Check the first-run path without relying on undocumented environment variables.
3. Check local links, headings, anchors, image paths, and alt text; inspect the rendered landing page at desktop and narrow widths.
4. Parse shell blocks and validate JSON/YAML examples. Check commands against the actual tools and configuration.
5. Compare configuration tables and API examples against source, including auth requirements and limits.
6. Use a separate reviewer for factual claims and tutorial structure. Fix findings in the documentation and run the focused checks again.
7. Report what was executed, what was source-reviewed, and what still requires live Twitch or a physical device.

The [documentation review record](../tasks/docs-quality-review.md) applies the requested documentation skills to this revision and records explicit exceptions to their ZIO-specific tooling requirements.
