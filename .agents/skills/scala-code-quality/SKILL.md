---
name: scala-code-quality
description: Apply Scala 3 coding standards, domain types, total functions, and pragmatic clean code when writing or reviewing the twitch-screen-relay backend. Use for Scala source and API changes, not firmware, CAD, or Python work.
---

# Scala code quality for the relay

Combine FP for data and transformations with OOP for encapsulation and dependency
boundaries. Keep the relay's direct-style design. These are project conventions,
not claims that every Scala application must use the same architecture.

## Establish the context

Read `twitch-screen-relay/build.mill`, `twitch-screen-relay/.scalafmt.conf`, and the
affected source and tests. Paths in this skill are relative to the repository
root unless qualified.
For runtime, concurrency, or HTTP work, also use the sibling
[`scala-vss-backend`](../scala-vss-backend/SKILL.md) skill. Current code and the
protocol specification take precedence over stale examples in project notes.

## Style and public contracts

- Follow the [Scala Style Guide](https://docs.scala-lang.org/style/) for naming
  and conventions, adapted to Scala 3 and this repository's indentation syntax.
  Let the pinned Scalafmt configuration decide whitespace and line wrapping.
- Use descriptive domain names; types use UpperCamelCase, methods and values
  lowerCamelCase, packages lowercase. Avoid vague `Manager`, `Helper`, and `Utils`
  containers when a specific responsibility has a name.
- Give public methods, values, and givens explicit types. Infer local types when
  clear. Choose the narrowest useful visibility after inspecting callers.
- Keep imports deliberate and remove unused imports. Scala 3 `*` imports are
  acceptable for established DSLs such as Ox and Tapir; do not confuse wildcard
  imports with uncontrolled mutation.
- Prefer expressions, exhaustive pattern matches, and ordinary collection
  operations. Avoid side effects inside `map` or predicates; use a named effectful
  step or `foreach`. Keep a named intermediate value when it explains intent.

## Data, state, and errors

- Default to `val`, immutable collections, case classes, enums, and opaque types.
  A `val` referencing mutable data is not immutable. Do not expose mutable
  collections or writable arrays across domain or concurrency boundaries.
- Keep `var` local to a method when a loop or binary codec needs it. Existing
  actor-confined state, such as `DeviceHub`, is a deliberate exception: preserve
  serialized access, immutable snapshots, and pure transition logic where useful.
  Do not add unprotected mutable fields or replace actors with shared `var`s.
- Reuse types such as `DeviceId`, `ConnectionId`, `SeqNo`, and `Port`. Introduce
  validated opaque types or small value types for concepts with identity, units,
  or invariants; a type alias alone does not prevent mixing two primitive values.
- Use enums for named modes and states instead of positional Boolean switches.
  Ordinary predicates such as `isAfter` may return Boolean. Keep primitives for
  local arithmetic and low-level wire operations where their meaning is clear.
- Make validation unavoidable at external boundaries, including JSON, HOCON,
  path/query codecs, Java callbacks, and device frames. A schema annotation alone
  does not validate input. Preserve existing wire encodings when adding types.
- Represent meaningful state alternatives with ADTs instead of combinations of
  flags and unrelated optional fields. Use `Option` for legitimate absence and
  `Either` with a domain error ADT for expected failures. Keep existing public
  error contracts stable during unrelated changes.
- Write total domain functions: handle empty collections, missing keys, invalid
  numbers, overflow, and every ADT case. Avoid unchecked `.get`, `.head`, map
  indexing, casts, and parsing that throws. A low-level checked indexing loop is
  acceptable when its bounds are explicit and tested.
- Confine nullable Java values and library exceptions to adapters. Translate
  expected failures; preserve interruption/cancellation and unexpected defects.
  Do not turn every exception into a successful default or empty result.

## Clean code and object design

- Give a function one coherent responsibility or a short orchestration of named
  steps. Extract for meaning and cohesion, not a fixed line-count target.
- Keep classes focused, dependencies explicit, and constructors free of running
  threads or network work. Inject clocks and ID/random sources where behavior
  depends on them and tests need control.
- Prefer composition. Introduce a trait for a real capability, substitution, or
  integration boundary; avoid an interface for every class and speculative layers.
- Apply SOLID proportionately: cohesive reasons to change, small consumer-facing
  interfaces, substitutable implementations, and domain logic independent of
  transport details. An enum plus a match can be simpler than an inheritance tree.
- Separate queries from commands where practical; make effects obvious in names
  and signatures. Preserve contractual command results such as assigned sequence
  numbers. Comments explain invariants and decisions, not the visible syntax.

## Verify the change

Use [`scala-quality-tooling`](../scala-quality-tooling/SKILL.md) for formatting,
compilation, and tests. Test observable behavior and boundary cases; a refactor
must preserve HTTP responses, protocol bytes, ordering, and lifecycle behavior.
Report unresolved warnings or unchecked assumptions explicitly.
