---
name: scala-quality-tooling
description: Run or configure Scala quality checks for twitch-screen-relay using Mill, Scalafmt, compiler warnings, MUnit, Scalafix, and WartRemover. Use for validation, formatting, lint policy, or build-quality changes; distinguish configured checks from proposed integrations.
---

# Scala quality tooling for the relay

Run commands from `twitch-screen-relay/`. Inspect `build.mill`, `.scalafmt.conf`,
and relevant test suites first. Use the included `./mill` launcher and the pinned
JVM/Scala versions. An installed IDE integration may supply compiler diagnostics,
but report the actual commands/checks used; do not assume an sbt build or MCP tool.

## Existing checks

At skill creation, the project has Scalafmt configuration, compiler warnings, and
MUnit suites. It has no Scalafix or WartRemover integration. Recheck the build
before relying on that statement. Agent instructions are not a CI enforcement
mechanism and do not make an uninstalled linter pass.

```sh
./mill compile
./mill test
./mill test.testOnly twitchscreen.relay.protocol.Tsb3GoldenVectorSuite
./mill mill.scalalib.scalafmt/checkFormatAll
```

The `testOnly` command is an example of selecting a relevant suite, not a mandate
to run protocol tests for every edit. Discover current names in `test/src/`.
For command compatibility, use `./mill resolve _` or `./mill inspect <task>`.

### Deterministic formatting

Scalafmt is mandatory for changed Scala code. Respect `.scalafmt.conf`, including
its version, Scala 3 dialect, and line width. Run the check without rewriting
unrelated work. When a whole-relay formatting pass is in scope, Mill provides:

```sh
./mill mill.scalalib.scalafmt/
```

For a narrow change in a dirty tree, use a configured editor or Scalafmt CLI with
the same configuration and explicit changed-file paths. Check its `--help`
before constructing the invocation. Do not silently upgrade the formatter or
reformat all pre-existing changes to clear a check. Report baseline violations
separately from those introduced by the task.

### Compiler diagnostics and tests

- Preserve `-deprecation`, `-feature`, `-Wunused:all`, `-Wvalue-discard`, and
  `-Wnonunit-statement` while they remain part of this build's warning policy.
  Aim for zero warnings in changed code; fix causes instead of broad suppressions.
  Report pre-existing warnings and do not claim warnings-as-errors is enabled
  unless `-Werror` is actually configured.
- For behavior changes, add focused MUnit coverage for the behavior and meaningful
  boundary cases. Pure transition tests should avoid real clocks/network; HTTP
  tests can use the existing Tapir stubs. Socket/lifecycle changes need the
  corresponding real-socket tests, including termination or reconnect behavior.
- Run focused tests while iterating, then the full relay suite for source changes
  before declaring completion. Run assembly only when packaging is affected.
  For skill/documentation-only changes, validate the artifacts and links instead
  of starting the application or running unrelated backend tests.

## Scalafix policy

Use Scalafix for configured static checks and deliberate automated rewrites. It
complements the compiler and formatter; it does not verify every semantic rule.
When integrating it is part of the task:

1. Check the [Mill integration](https://mill-build.org/mill/scalalib/linting.html)
   and [Scalafix installation guide](https://scalacenter.github.io/scalafix/docs/users/installation.html)
   against the exact Mill and Scala versions. Verify required SemanticDB support
   and options for semantic rules rather than copying Scala 2 settings.
2. Pin a compatible integration and add an explicit `.scalafix.conf`. Consider
   unused-import cleanup and public API type checks only where supported by the
   selected Scala 3 rules and justified by the project's policy.
3. Separate check mode from rewrites. Inspect the diff after automatic fixes and
   recompile/test. Keep mass migrations separate from an unrelated feature.

## WartRemover policy

WartRemover performs compiler-time lint checks. When adding it is in scope, verify
compiler-plugin artifacts for the exact Scala version using the
[installation guide](https://www.wartremover.org/doc/install-setup.html) and
[rule catalog](https://www.wartremover.org/doc/warts.html). Pin a compatible version.

Select rules explicitly. Candidate checks include unsafe `Option.get`, casts,
nulls, and unintentionally inferred broad types. Avoid enabling every wart:
blanket bans on `var`, arrays, loops, or exceptions can conflict with checked
binary codecs, actor-confined state, and Java adapters. Keep justified exceptions
local and documented; do not suppress an entire package to get a green build.
If the pinned compiler is unsupported, report that gap without silently changing
Scala versions or claiming equivalent coverage from a text search.

## Completion evidence

Report formatting, compilation, tests, and configured lint results separately.
Include commands and failures that affect confidence. Never describe Scalafix or
WartRemover as passed when absent, skipped, or incompatible.
