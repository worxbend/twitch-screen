# RLY-41 resolved runtime dependency audit

Reviewed 2026-09-25. Owner: relay maintainers. The scanner uses the actual
`./mill show resolvedRunMvnDeps` output and OSV's Maven coordinates. It includes
classifier jars, deduplicating only identical group/artifact/version queries.
Unsupported cache/repository/reference layouts fail the scan rather than being
silently omitted. Runtime matching is broader than an exploitability analysis;
no-advisory results mean only current OSV coverage.

## Fixed findings

The initial scan matched eight advisories: four in Netty 4.2.16.Final, two in
Jawn 1.6.0, and the two legacy findings below. These are dependency matches,
not claims that each vulnerable feature is enabled in this relay.

- Netty uses BOM 4.2.18.Final; all 40 resolved Netty modules are aligned. This
  removes matches GHSA-8c42-7qj2-3j46, GHSA-c4c3-7fpv-j4q5,
  GHSA-fccg-mwvh-qqg4 and GHSA-2qj4-mmr9-4v2f. The
  [official release notes](https://github.com/netty/netty/releases/tag/netty-4.2.18.Final)
  describe stricter HTTP/resource validation; integration must exercise actual
  HTTP listeners and container startup, not only compilation.
- Jawn parser is explicitly overridden to 1.7.0 for the Circe/OpenAPI dependency
  path. Its [security release](https://github.com/typelevel/jawn/releases/tag/v1.7.0)
  fixes GHSA-cc4v-rvgp-2pf3 and GHSA-w4cm-gvhj-cgw6. The application continues
  using jsoniter for HTTP and Twitch JSON; the override closes the transitive
  dependency matches without changing that application codec.

The upgrades were split into separate commits and API regressions run after
both. Other dependency versions were retained.

## OpenTelemetry SDK/instrumentation alignment

Before this change, the SDK was 1.66.0 but the instrumentation stayed on
2.9.0-alpha. That put instrumentation-api 2.9.0 and runtime-telemetry-java8/java17
on a classpath built for a much newer SDK, and no test exercised that linkage.

- `bomMvnDeps` imports `opentelemetry-bom` 1.66.0 and `opentelemetry-bom-alpha`
  1.66.0-alpha before `opentelemetry-instrumentation-bom-alpha` 2.31.1-alpha.
  2.31.1-alpha is the latest instrumentation release on Maven Central (checked
  2026-09-25). Its BOM targets SDK 1.65.0, so no instrumentation release targets
  1.66 yet. The SDK BOM is imported first on purpose so it wins.
- `opentelemetry-runtime-telemetry-java17` was renamed upstream to
  `opentelemetry-runtime-telemetry`, and `RuntimeMetrics` is now
  `io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry`.
  The java8/java17 artifacts are gone from the runtime classpath.
- Resolved runtime OTel jars: 21, each at a single version. Core api, context,
  common, sdk\*, exporter\* and autoconfigure\* are 1.66.0; api-incubator is
  1.66.0-alpha. instrumentation-api is 2.31.1; instrumentation-api-incubator,
  runtime-telemetry and logback-appender-1.0 are 2.31.1-alpha. semconv is 1.43.0,
  which matches instrumentation-api 2.31.1. No 2.9.0 or 1.65.0 jar remains.
- The linkage is tested by `OtelLinkageSuite`. It runs the same `Otel.instrument`
  code as startup against an SDK with an in-memory metric reader and log
  exporter. It asserts that a `jvm.*` metric and a relay log record are
  exported, and it fails if either linkage call is removed.

## Temporary legacy exceptions

Both exceptions are exact package/version/advisory tuples in
`tools/relay_dependency_exceptions.json`, reviewed 2026-09-25 and expiring
**2026-10-25**. The scanner reports them every run and rejects expired, stale
or malformed exceptions. A different version or newly reported advisory is not
covered. These dependencies remain vulnerable packages, not patched packages.

1. `commons-configuration:commons-configuration:1.10`,
   GHSA-pvp8-3xj6-8c6x (CVE-2025-46392). Apache describes resource exhaustion
   when loading untrusted configurations; 1.x will not receive a fix and 2.x
   changes namespace/API. The
   [Apache security report](https://commons.apache.org/proper/commons-configuration/security.html)
   distinguishes trusted configuration use. The runtime chain comes from
   Twitch4J/Feign-Hystrix/Hystrix/Archaius; removing the old namespace without
   replacing that integration would break linkage.
2. `commons-lang:commons-lang:2.6`, GHSA-j288-q9x7-2f5v (CVE-2025-48924).
   `ClassUtils.getClass` can recurse over excessively long class names. The
   fixed Commons Lang 3 line has a different package namespace; it does not
   replace the existing 2.x classes merely by being present alongside them.

Reachability was assessed against the pinned Maven source jars, not inferred
solely from the relay having authentication:

- [Archaius 0.4.1 sources](https://repo.maven.apache.org/maven2/com/netflix/archaius/archaius-core/0.4.1/archaius-core-0.4.1-sources.jar):
  `ConfigurationManager.getConfigInstance` builds a SystemConfiguration plus
  DynamicURLConfiguration. `sources/URLConfigurationSource` obtains its default
  `config.properties` from the classpath and additional URLs from the JVM system
  property `archaius.configurationSource.additionalUrls`. These are operator
  deployment inputs; treat every configured resource as trusted and do not
  configure attacker-controlled remote property sources.
- [Feign-Hystrix 13.0 sources](https://repo.maven.apache.org/maven2/io/github/openfeign/feign-hystrix/13.0/feign-hystrix-13.0-sources.jar):
  default SetterFactory derives command keys from declared target interface
  methods, not request/event body values.
- Scanning resolved class bytecode for `org/apache/commons/lang/ClassUtils`
  references found callers only in Commons Configuration. Inspection of the
  [Commons Configuration 1.10 sources](https://repo.maven.apache.org/maven2/commons-configuration/commons-configuration/1.10/commons-configuration-1.10-sources.jar)
  found `getClass` in BeanHelper, ConstantLookup and ExprLookup.Variable;
  AbstractConfiguration/DataConfiguration use non-loading primitive helpers.
  The class-name inputs belong to configuration bean/constant/expression values.
- Relay source has no Commons configuration, ClassUtils, Archaius property
  mutation, or HTTP endpoint accepting configuration documents/class names.
  Twitch event and device payloads do not become these configuration inputs.
  This is source and reference inspection, not an exhaustive reflective call
  graph or a live-account penetration test.

Reassessment task: repository owner to review the exact two exceptions by **2026-10-18**, one week before their **2026-10-25** expiry. Run the audit command below and record fresh reachability evidence; CI rejects expired exceptions.

Follow-up by the expiry: replace or update the legacy Twitch4J/Hystrix/Archaius
path, or explicitly reassess the exact findings with fresh evidence. Reassess
immediately if JVM/classpath configuration becomes writable by untrusted users,
remote property sources are enabled, or code starts accepting configuration
expressions/class names. Do not generalize these exceptions to other services.

## Scanner and validation

Run from the repository root:

```sh
python3 -m unittest discover -s tools/tests -v
python3 tools/audit_relay_dependencies.py --output /tmp/relay-dependency-audit.json
```

The [OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/) requires
following each result's own pagination token. The scanner does so, limits
batches to 100 coordinates and pages to 20, caps response size and network wait,
and rejects incomplete/error/unknown responses and repeating tokens. New
advisories return a nonzero exit. It does not scan OS packages, firmware
libraries, unavailable advisories or exploitability of every class.

Seven offline tests pass: coordinate/classifier completeness, unknown paths,
malformed/incomplete responses, per-query pagination, repeated/page-limit
protection, bounded batches, and exact/expired/stale exception behavior.
A live scan of the patched resolved runtime queried **182 coordinates** and
returned **two advisories, both explicitly excepted**, with no unexcepted
matches. After the OTel alignment (RLY-41), a fresh live scan queried **181
coordinates** (the java8/java17 runtime-telemetry pair was replaced by the single
`opentelemetry-runtime-telemetry`) and again returned **2 advisories, both
excepted, 0 unexcepted**. The 7 offline tests still pass. The pre-Jawn-upgrade live scan correctly failed on both Jawn advisories.
All 19 API regressions pass after each independent dependency update. Root owns
combined warnings-as-errors, full relay suite and real HTTP/container smoke on
the integrated security/runtime sources; those results are recorded separately.
