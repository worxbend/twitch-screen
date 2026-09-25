I want to build a server application that will be able to connect to twitch api
and fetch/stream the data from it to the device that is connected to this server via TCP.

This server will be able to run on a raspberry pi and will be able to connect to the
twitch api,  fetch the data:
 - about status of the stream on the twitch channel
 - the viewers count
 - will be able to listen to the twitch chat messages
 - will be able to listen to the stream twitch events (stream start, stream end, raid, follow, subscribe, etc)

use @llm.txt to see details of the scala stack.

+ https://github.com/twitch4j/twitch4j use this library to connect to the twitch api
µPickle
jsoniter-scala

 com.softwaremill.sttp.tapir::tapir-netty-server-sync:1.13.19
 com.softwaremill.sttp.tapir::tapir-opentelemetry-metrics:1.13.19
 io.opentelemetry:opentelemetry-exporter-otlp:1.61.0
 io.opentelemetry:opentelemetry-sdk-extension-autoconfigure:1.61.0
 com.softwaremill.sttp.tapir::tapir-jsoniter-scala:1.13.19
 com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:2.40.1
 com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:2.40.1
 ch.qos.logback:logback-classic:1.6.3
 com.softwaremill.sttp.tapir::tapir-swagger-ui-bundle:1.13.19
 com.softwaremill.sttp.tapir::tapir-jsoniter-scala:1.13.19


For Scala 3 + Ox + virtual threads, use the JDK blocking socket API directly and let Ox provide structured concurrency + virtual threads.

Ox explicitly documents that java.net.Socket and ServerSocket blocking I/O is interruptible when running on virtual threads. Ox forks run on virtual threads, so the model is essentially:

1 connection = 1 virtual thread

This is exactly what Loom was designed for.


So to build a TCP protocol for the firmware client (see twitch-screen-firmware for reference), you will need to define the message formats, handle connection management, and implement the necessary commands and responses to interact with the server effectively.

So the application will act as a bridge between the Twitch API and the firmware client, relaying real-time stream data, chat messages, and events over a TCP connection. It will manage multiple client connections concurrently using virtual threads, ensuring efficient and responsive communication with the connected devices.
Additionally, the server should implement proper error handling, reconnection logic, and resource cleanup to ensure stability and reliability, especially when dealing with network interruptions or client disconnections. Logging and monitoring should also be incorporated to facilitate debugging and performance analysis.

In summary, the server will:
- Connect to the Twitch API and fetch real-time stream data, chat messages, and events.
- Relay this information to connected firmware clients over a TCP connection.
- Manage multiple client connections concurrently using virtual threads.
- Implement robust error handling, reconnection logic, and resource cleanup.
- Provide logging and monitoring for debugging and performance analysis.
- And HTTP endpoints for management and monitoring purposes.
    - Management of connected clients and server status:
        - List all connected clients and their current status.
        - Disconnect specific clients if necessary.
        - View server health and performance metrics.
    - Monitoring of server activity and logs:
        - Access real-time server logs.
        - Monitor active connections and data throughput.
        - Set up alerts for critical events or errors.
        - Review historical server activity and logs.
        - Generate and download server activity reports.
        - Configure monitoring and alerting settings.
    All these should be implemented as HTTP endpoints accessible through a web interface for ease of management and monitoring. Added to Swagger UI for documentation and testing purposes. All endpoints should be RESTful and follow AIP-136 guidelines. It is not canonical REST, but AIP-136 provides a practical approach for designing RESTful APIs in this context, resource-oriented API which allows custom verbs and actions where necessary.

The HTTP part should be implemented with Tapir best practices in mind, ensuring that all endpoints are well-defined, documented, and testable. Tapir allows for type-safe endpoint definitions, automatic generation of OpenAPI documentation, and seamless integration with various HTTP servers and clients. This will facilitate the creation of a robust and maintainable HTTP API for management and monitoring purposes.

The TCP part should be aligned with the virtual thread model, ensuring that each client connection is handled by a separate virtual thread. This allows for efficient concurrency and responsiveness, even under high load. Proper message framing, error handling, and connection management should be implemented to maintain reliable communication with the firmware clients.

The TCP part should be aligned with the firmware client's expectations and protocol specifications. This includes adhering to the defined message formats, handling connection lifecycle events appropriately, and ensuring that data is transmitted and received reliably.

This server application should be also packed in a Docker container for ease of deployment and environment consistency. The Docker image should include all necessary dependencies and configuration files, allowing the server to be run reliably across different environments. Additionally, the Docker setup should support environment variable configuration for flexible deployment settings and include health checks to ensure the container's operational status.


So application  should consist of the following components:
    - HTTP API for management and monitoring, implemented with Tapir and following RESTful principles.
    - TCP server for handling firmware client connections, aligned with the virtual thread model.
    - Background scheduler/thread for Twitch integration and listening for events.
        So twitch is integrated through a background scheduler or thread that listens for events and triggers appropriate actions within the server application.
        Then the message bus should be used to propagate events and actions triggered by the Twitch integration to other components of the server application, ensuring decoupled and efficient communication.
        and ensure those events passed through the message bus are correctly received and handled by the relevant components of the server application - like the TCP server or the HTTP API.
    - Monitoring and alerting mechanisms for server activity and performance.


The Twitch integration should be designed to handle events efficiently and trigger appropriate actions within the server application. It should interact with the message bus to propagate events to other components, ensuring that all relevant parts of the system are aware of and can respond to Twitch events in a timely manner.

We probably should write a Scala wrapper or integration layer for each of the following Twitch components, coz library is in Java and we want to have a more idiomatic Scala interface, especially for handling asynchronous events and integrating with Ox and other parts of our server application, as well as ensuring smooth interaction with our message bus:


The Twitch components that we need:
chat 	Chat (IRC) - to handle real-time chat messages and interactions within Twitch channels.
eventsub-common 	EventSub (can be used for Webhook-Transport) - to handle subscription-based events from Twitch using webhooks.
eventsub-websocket 	EventSub (WebSocket & Conduits) - to handle subscription-based events from Twitch using WebSocket connections.
helix 	REST-API - to interact with Twitch's RESTful API for various operations like fetching user information, channel details, and more.
pubsub 	PubSub - to handle real-time messages and events published by Twitch, such as channel point redemptions and bits transactions.

Application should be configuration driven, allowing for flexible and dynamic adjustments to various components (what to use Webhook or WebSocket for EventSub, which Twitch channels to monitor, login credentials, message bus settings, etc.).

For the application configuration:
I’d use PureConfig + Typesafe Config/HOCON.
VSS itself currently does not prescribe a configuration library; its published stack is intentionally modular. But its reference Bootzooka template uses exactly this combination: HOCON application.conf parsed into typed Scala configuration via PureConfig

So extension of the library stack for our application should follow the same principles: use PureConfig for typed configuration, Typesafe Config/HOCON for flexible source and overrides, and MacWire for wiring dependencies.

PureConfig              ← application configuration
Typesafe Config/HOCON   ← source/merging/overrides
MacWire                 ← wiring1

please make sure to leverage all the Scala skills and best practices available, including functional programming paradigms, type safety, immutability, and asynchronous programming, to build a robust and maintainable Twitch integration layer.

Regarding client-server communication, the server should expose a well-defined protocol over TCP. 
It should use a custom binary protocol for efficient communication between the server and connected devices, ensuring low latency and minimal overhead.

we need to send informations:
 - for the idle screen with number of messages in the chat, current viewers count and time since the start of the stream.
 - for the stream events:
    - stream start
    - stream end
    - raid, including the raider's information
    - follow, including the follower's information
    - subscribe, including the subscriber's information
    - donation, including the donor's information and amount
    - bits transaction, including the sender and amount
    - chat message, including the sender and content.

events from the bots should be ignored (both chat messages and other events, bots - streamelements, nightbot, moobot, etc.).