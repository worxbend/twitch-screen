# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build: Mill produces one self-contained jar.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src

# The build definition alone first, so editing a source file does not re-download the dependency tree.
COPY mill build.mill ./
RUN ./mill resolvedMvnDeps

COPY .scalafmt.conf ./
COPY resources ./resources
COPY src ./src
RUN ./mill assembly

# ---------------------------------------------------------------------------
# Run: a JRE, the jar and nothing else.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre

# curl is here for HEALTHCHECK; the image has no shell tooling otherwise.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Unprivileged. Its only writable state is data/, which holds the Twitch token granted through /api/v1/twitch/authorize;
# created here so a named volume mounted over it inherits the relay user's ownership.
RUN useradd --system --create-home --uid 10001 relay
USER relay
WORKDIR /home/relay
RUN mkdir -m 700 data
VOLUME ["/home/relay/data"]

COPY --from=build --chown=relay:relay /src/out/assembly.dest/out.jar relay.jar

# Bind to every interface: inside a container, localhost would be unreachable from the device.
ENV RELAY_HTTP_HOST=0.0.0.0 \
    RELAY_HTTP_PORT=8080 \
    RELAY_DEVICE_HOST=0.0.0.0 \
    RELAY_DEVICE_PORT=8099 \
    RELAY_TWITCH_MODE=disabled \
    JAVA_OPTS=""

# 8080: management API and Swagger UI. 8099: the firmware's persistent TCP link.
EXPOSE 8080 8099

# Liveness only, matching GET /api/v1/health: Twitch being down must not restart a relay that is serving devices.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD curl --fail --silent "http://127.0.0.1:${RELAY_HTTP_PORT}/api/v1/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar relay.jar"]
