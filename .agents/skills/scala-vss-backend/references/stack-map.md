# Stack choices and authoritative references

Read only the references relevant to the requested change. The dependency list in
`twitch-screen-relay/build.mill` is the source of truth for installed libraries and
versions; this map records the architecture, not an upgrade schedule.

## Components used in this relay

| Component | Project role and constraint | Reference |
| --- | --- | --- |
| Scala 3 + JVM | ADTs, opaque types, direct style, virtual threads; use the build's JVM pin | [Scala 3](https://docs.scala-lang.org/scala3/book/introduction.html) |
| Mill | Existing build, tests, packaging, formatter; keep the included launcher | [Mill](https://mill-build.org/) |
| Ox | Scope ownership, cancellation, actors/channels, bounded flows | [Ox](https://ox.softwaremill.com/) |
| Tapir + Netty sync | Typed management endpoints and Swagger UI | [Tapir](https://tapir.softwaremill.com/) |
| jsoniter-scala | Existing JSON codecs; preserve DTO and enum representation | [jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala) |
| PureConfig | Typed HOCON, readers, validation, environment overrides | [PureConfig](https://pureconfig.github.io/) |
| MacWire | Compile-time constructor wiring | [MacWire](https://github.com/softwaremill/macwire) |
| MUnit + Tapir stubs | Domain and HTTP tests, with socket suites for the device link | [MUnit](https://scalameta.org/munit/) |
| Twitch4J | Existing Twitch integration; isolate Java boundary behavior | [Twitch4J](https://twitch4j.github.io/) |
| OpenTelemetry + Logback | Existing traces, metrics, logs, and fork context propagation | [OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/) |

The relay adds project-specific choices to VSS. PureConfig, MUnit, and Twitch4J,
for example, are not a requirement to adopt every component advertised by VSS.

## Optional components listed by VSS

Consult [VSS](https://vss.virtuslab.com/llms.txt) for the current catalog. Evaluate
these when a task needs their capability; their presence here is not an instruction
to install them or regenerate the application.

| Component | When it can fit here |
| --- | --- |
| sttp | A new outbound HTTP/WebSocket client. Prefer its synchronous backend with scoped cleanup. Tapir's sttp test dependencies do not establish a production HTTP client. |
| Parlance | New relational persistence. First establish transaction, schema, and migration needs; the current relay does not have a SQL layer. |
| Quicklens | Repeated, deeply nested immutable updates where `.copy` becomes unclear. |
| Bootzooka / Adopt Tapir | A genuinely new application or isolated prototype. Do not overwrite this relay with a starter. |
| Scala CLI / Metals | Isolated experiments and editor support. Mill remains the relay build. |
| Scala Native | A separately justified native component; it is not a drop-in runtime for this JVM/Ox backend. |
| sttp-ai / Chimp | Explicit LLM or MCP feature requirements. |
| scala-skill / Cellar | Upstream implementation guides and dependency API lookup; useful tooling, not required project runtime dependencies. |
| Orca / Sandcat | Explicit development workflow or sandbox setup tasks. |
| Besom | Explicit infrastructure-as-code work. |

Reconcile differences between a current VSS page, older guides, and pinned APIs.
For example, some upstream database chapters describe Magnum while the current
VSS catalog lists Parlance; neither is already a dependency of this project.
Keep working architecture stable and describe a migration only when required.

## Upstream implementation chapters

The [VirtusLab scala-skill repository](https://github.com/VirtusLab/scala-skill)
contains focused direct-style guides. Read the full raw chapter, including its
code and lifecycle caveats, rather than relying on a search summary. Adapt build
examples to Mill; do not copy sbt tasks or unpinned starter dependencies.

- Resources: [resource management](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/100-resource-management.md).
- Background work and shared state: [background processes](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/110-background-processes.md), [concurrency](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/150-shared-state-across-threads.md).
- Blocking foreign resources: [subprocesses and external streams](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/170-subprocesses-and-external-streams.md).
- Configuration and wiring: [type-safe configuration](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/120-type-safe-configuration.md), [compile-time DI](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/130-compile-time-dependency-injection.md).
- Errors: [error handling](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/200-error-handling.md), [error outputs](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/210-error-output-customisation.md).
- HTTP: [server configuration](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/310-http-server-configuration.md), [JSON bodies](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/350-json-bodies.md), [endpoint inputs](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/360-endpoint-inputs.md).
- Verification: [HTTP tests](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/500-testing-http-endpoints.md), [OpenTelemetry](https://raw.githubusercontent.com/VirtusLab/scala-skill/refs/heads/master/direct-style-scala/skills/direct-style-scala/510-opentelemetry-observability.md).
