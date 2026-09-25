---
name: scala-vss-backend
description: Build and review twitch-screen-relay using the VirtusLab Scala Stack (VSS), direct-style Scala 3, Ox, synchronous Tapir, and the existing integration libraries. Use for backend architecture, concurrency, endpoints, resources, configuration, and stack choices.
---

# VirtusLab Scala Stack in twitch-screen

Use the [VSS overview](https://vss.virtuslab.com/) and its
[agent reference](https://vss.virtuslab.com/llms.txt) as the upstream stack guide.
VSS is a set of composable libraries. Adopt components for the task at hand;
preserve this project's established Mill build and direct-style architecture.

## Read the project before choosing an API

Start with `twitch-screen-relay/build.mill`, the relay README, `Main.scala`,
`Dependencies.scala`, and the affected packages under `src/twitchscreen/relay/`.
Use the versions actually pinned in the build, not versions from online snippets.

Read [references/stack-map.md](references/stack-map.md) when selecting a component
or looking up a library. It distinguishes installed components from optional VSS
tools and links the relevant upstream use-case chapters. Fetch the applicable
chapter before introducing unfamiliar library APIs; verify its signatures against
the pinned dependency. These skills do not require a global skill installation or
a particular MCP server. Use available IDE tools or the included Mill launcher.

The core here is Scala 3 on a virtual-thread-capable JVM, Ox, Tapir's synchronous
Netty server, jsoniter-scala, PureConfig, and MacWire. MUnit, Twitch4J,
OpenTelemetry, and Logback complete the project's current integration stack.

## Direct style and lifetimes

- Return ordinary values or `Either` for expected domain errors. Keep the current
  codebase free of a new `Future`, Cats Effect, or ZIO layer unless an architecture
  migration is explicitly part of the task. Foreign asynchronous APIs belong in
  adapters with explicit lifecycle and failure handling.
- Use Ox for structured concurrency, channels, actors, and flows. Decide whether
  each worker belongs to the application, device connection, request, or job
  before forking it. Prevent workers and live resource handles from escaping
  their owning scope; return results or run a consumer inside that scope.
- Use `supervised` where concurrent work is needed. Use `ResourceScope` for
  resource-only factories and `resourceScope` for standalone resource-only work;
  within an existing concurrent scope, register resources with that scope.
- Keep constructors plain. Acquire resources in scoped factories, using
  `useInScope` or `useCloseableInScope` and an intentional release order.
- Check whether blocking foreign I/O responds to interruption. For subprocess
  pipes or other non-interruptible readers, arrange unblocking teardown before
  the scope joins the reader; an after-scope finalizer alone can deadlock. Read
  the external-streams chapter before changing this lifecycle.
- Bound fan-out, buffering, retries, and timeouts. Virtual threads do not make
  CPU work, memory, or remote capacity unlimited. Use the pinned Ox facilities
  for substantial CPU work and bounded pipelines instead of spawning unbounded
  work or collecting infinite streams.
- Preserve `DeviceHub` serialization of sequence allocation and replay. Keep
  slow devices and consumers from blocking the hub or Twitch ingest. Inspect
  `EventBus`, `DeviceHub`, and `DeviceSession` for actual queue/drop policies.
  Do not replace actor-confined state with unprotected shared mutation.

## HTTP, configuration, and integration boundaries

- Define typed Tapir inputs, outputs, and errors; wire direct-style logic with
  `.handle`, `.handleSuccess`, and `.handleSecurity` as appropriate. Use the
  existing `Http.baseEndpoint`, `Fail`, and synchronous endpoint collection.
- Keep HTTP DTOs, codecs, validation, and OpenAPI schemas consistent. Follow
  existing jsoniter-scala derivation and enum encoding. Keep TSB/3 binary codecs
  separate from management API JSON; preserve the protocol's exact byte layout.
- Read HOCON through existing PureConfig readers, validate dependent settings at
  startup, and preserve environment overrides. Keep credentials inside the
  `Sensitive` boundary and redact them in logs and configuration responses.
- Use constructor injection and existing MacWire wiring in `Dependencies`/`Apis`.
  Keep wiring separate from domain rules; avoid a service locator or hidden
  globally acquired clients.
- Adapt Java/Twitch4J nulls, exceptions, callbacks, and mutable objects before
  they enter the domain. Keep `live`, `simulated`, and `disabled` source behavior
  compatible at the `TwitchSource`/event-bus boundary.
- Preserve OpenTelemetry context and MDC propagation across Ox forks. Follow
  `Main`, `Otel`, and `RelayMetrics`; do not create another SDK per request or
  expose secrets and unbounded user/device identifiers as metric labels.

## Verification

Use [`scala-quality-tooling`](../scala-quality-tooling/SKILL.md). Test pure domain
rules directly, HTTP behavior with the existing Tapir stub/MUnit patterns, and
socket or cancellation changes through the relevant lifecycle suites. Use the
relay's simulated mode when runtime checks need Twitch events without credentials.
Read the current protocol specification rather than relying on legacy NDJSON demo
instructions. Report the layers actually verified.
