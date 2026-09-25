---
name: scala-fowler-refactoring
description: Refactor or review twitch-screen-relay Scala 3 code using Martin Fowler's refactoring catalog and pragmatic clean-code principles. Use for code smells, responsibility changes, duplication, and simplifying existing code while preserving behavior.
---

# Refactoring the Scala relay

Use Martin Fowler's [refactoring catalog](https://refactoring.com/catalog/) for
small behavior-preserving transformations. Combine it with Robert C. Martin's
clean-code principles, adapted to Scala's functions, ADTs, and composition.
Follow [`scala-code-quality`](../scala-code-quality/SKILL.md) for project style.

## Establish what must stay true

Read the changed code, its callers, and relevant MUnit suites before selecting a
pattern. Name the concrete maintenance problem and the observable contract.
If behavior lacks coverage, add a focused characterization test before changing
it. Do not add tests that merely mirror a method's implementation or pin private
structure. A mechanical rename with compiler coverage may need no new test.

For this project, observable behavior includes:

- HTTP paths, statuses, response JSON, validation, and redaction.
- TSB/3 bytes, field limits, malformed-frame handling, sequence assignment, and
  reconnect/replay behavior in `twitch-screen-firmware/docs/PROTOCOL.md`.
- Event ordering, bounded queues and overflow policy, actor ownership, shutdown,
  and relevant metrics. Performance is part of the contract where bounded memory
  or timely delivery matters.

Do not mix a requested behavior change into a refactor without identifying and
testing it separately. Preserve unrelated in-progress work.

## Choose the smallest useful transformation

| Evidence in the code | Candidate transformation | Scala/project judgment |
| --- | --- | --- |
| Mixed validation, transformation, and I/O | Extract Function; Split Phase | Keep a short orchestrator and pure validation/transition functions. |
| Indirection that adds no meaning | Inline Function or Inline Class | Removing an abstraction can be the right cleanup. |
| Logic mostly uses another object's data | Move Function; Extract Class | Put behavior beside its invariant; avoid a generic utility bucket. |
| Repeated parameter groups or domain primitives | Introduce Parameter Object; Replace Primitive with Object | Reuse an existing case class or opaque type; validate at entry. |
| Boolean switches or invalid state combinations | Replace Type Code with Subclasses; Replace Conditional with Polymorphism | Consider an enum/ADT and an exhaustive match first. |
| Deep, nested conditionals | Decompose Conditional; Replace Nested Conditional with Guard Clauses | Keep expression-oriented returns and explicit errors. |
| A query unexpectedly mutates state | Separate Query from Modifier | Preserve intentional atomic operations such as allocating a sequence. |
| Repeated domain knowledge | Extract Function; Pull Up shared policy where appropriate | Similar-looking code with different reasons to change need not share an abstraction. |
| Fragile inheritance | Replace Superclass/Subclass with Delegate | Prefer composition and focused traits over deep hierarchies. |
| Broad public surface or oversized class | Encapsulate Record/Collection; Extract Class | Narrow visibility and expose immutable snapshots without breaking callers. |

These are options, not an obligation to introduce a design pattern. Avoid generic
repositories, factories, service layers, and typeclasses without a concrete need.
Keep simple `.copy` updates unless nesting makes a tool such as Quicklens useful
and a dependency change is part of the task.

## Make and verify small steps

1. Establish the relevant checks before the refactor; record pre-existing failures.
2. Make one coherent transformation and update its callers. Keep internal packages,
   resource lifetimes, and initialization order intentional.
3. Compile and run the affected tests. Use
   [`scala-quality-tooling`](../scala-quality-tooling/SKILL.md) for the actual Mill
   commands; broaden to the relay suite for cross-cutting changes.
4. Review the diff for unintended changes to error handling, wire representation,
   collection order, laziness, concurrency, or allocations in hot loops.
5. State what became easier to understand, what contract was preserved, and the
   checks that passed. Do not claim behavior preservation from formatting alone.

For review-only requests, report concrete findings with file locations, impact,
and a proportionate fix. Separate correctness issues from optional style advice.
