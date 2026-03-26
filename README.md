# meshtastic-java-client

[![Maven](https://img.shields.io/maven-central/v/com.meshmkt.meshtastic/meshtastic-java-client.svg?style=for-the-badge)](https://repo1.maven.org/maven2/com/meshmkt/meshtastic/meshtastic-java-client/)
[![License](https://img.shields.io/github/license/meshmkt/meshtastic-java-client?style=for-the-badge&logo=apache)](https://opensource.org/license/Apache-2.0)
[![Java CI with Maven](https://img.shields.io/github/actions/workflow/status/meshmkt/meshtastic-java-client/build.yml?branch=main&logo=GitHub&style=for-the-badge)](https://github.com/meshmkt/meshtastic-java-client/actions/workflows/build.yml)


Async Java client library for Meshtastic radios.

Licensed under the Apache License, Version 2.0. See `LICENSE`.

# Requirements

To use this library you will need the following:

- JDK 21+
- Meshtastic radio (ie. HELTEC V3, TDECK, etc)

# Usage

The library is available on Maven Central for use in Maven/Gradle/Ivy etc.

**Apache Maven:**
```xml
<dependency>
    <groupId>com.meshmkt.meshtastic</groupId>
    <artifactId>meshtastic-java-client</artifactId>
    <version>${version}</version>
</dependency>
```

**Gradle:**
```groovy
implementation group: 'com.meshmkt.meshtastic', name: 'meshtastic-java-client', version: '${version}'
```

## Why This Project Exists

This project started because there was no clean, low-level Meshtastic Java client library available for regular Java
applications.

Java support existed mostly inside the Android client itself, but that code was part of a full application rather than
a reusable library designed for:

- headless services
- bots that listen and respond to mesh traffic
- desktop/server Java applications
- custom Java UI clients

The original need here was practical: build a Meshtastic bot in Java that could listen for messages and respond to
them cleanly. Since there was no current standalone Java client that fit that use case, this library was built to fill
that gap.

The architecture aims to support both:

- headless/bot scenarios that want a straightforward event-driven client
- full UI applications that need refresh/write APIs, state snapshots, and lifecycle hooks

The goal has been to keep the architecture clean, composable, and friendly to extension. There may still be missing
features or bugs, especially around less-common firmware behaviors or hardware combinations.

Contributions, bug reports, and design feedback are welcome.

## What It Supports Today

- Serial and TCP transports
- Automatic reconnect handling and startup sync state tracking
- Event-driven API for node discovery, text, telemetry, position, and admin model changes
- Admin/config/channel refresh and write operations through `AdminService`
- Request lifecycle events for accepted/rejected/timed-out flows
- Snapshot-first model for fast UI rendering
- OTA framework/orchestration hooks
- BLE transport SPI contract for pluggable backends

## Current Gaps

- No production BLE backend is bundled yet (SPI is present; backend implementation is pending)
- OTA has framework support, but no fully integrated, production-ready end-to-end flashing path yet

In other words, the core API and extension points are present, but BLE and OTA are not 100% turnkey in this
repository today. They require:

- a concrete BLE driver/backend for the target platform
- a concrete OTA uploader/link implementation validated against real hardware

Those are good community-extension areas and can be added without changing the higher-level client API.

## Release Positioning

This library is currently strongest as a serial/TCP-first Meshtastic client for desktop/headless apps.  
BLE parity and full OTA integration are the major remaining items for full protocol surface parity.

## Documentation

- API guide: `src/docs/asciidoc/index.adoc`
- contributor guide: `CONTRIBUTING.md`
- Generated Javadocs and docs are produced during Maven package phases (see `pom.xml` plugin config)

The generated documentation is an important part of this project, not just a side artifact.
During a normal build, Maven produces:

- the compiled library JAR
- the sources JAR
- the Javadocs JAR
- rendered HTML documentation
- rendered PDF documentation

Treat the generated docs as the user manual for the library, especially for:

- startup and event-driven workflow
- messaging patterns
- `AdminService` usage
- settings-page refresh/write patterns
- OTA/BLE extension guidance

Contributor-facing project conventions such as logging levels and `record` vs Lombok usage are documented in
`CONTRIBUTING.md`.

## Node Database Extension Point

The client is designed so applications are not locked into the default in-memory node store.

At a glance:

| Topic | Meaning |
| --- | --- |
| `NodeDatabase` | Public extension point for node storage, freshness tracking, and snapshot reads |
| `InMemoryNodeDatabase` | Default implementation used by the examples and quick starts |
| Custom implementations | Supported when an app needs persistence, custom retention, or integration with another data layer |

Reasons to supply a custom implementation might include:

- persistence across application restarts
- integration with an existing cache, database, or repository layer
- custom eviction/retention behavior
- application-specific indexing or query patterns

For most applications, `InMemoryNodeDatabase` is the simplest starting point. If you need something more durable or
more specialized, implement `NodeDatabase` and pass it into `MeshtasticClient`.

Quick API reference:

| Area | Main methods | Purpose |
| --- | --- | --- |
| Self identity | `setSelfNodeId(int)`, `getSelfNodeId()`, `isSelfNode(int)` | Tracks which node is the current local/self node |
| Inbound updates | `updateUser(...)`, `updatePosition(...)`, `updateMetrics(...)`, `updateEnvMetrics(...)`, `updateSignal(...)` | Applies radio/user/telemetry/signal updates into the snapshot model |
| Snapshot reads | `getNode(int)`, `getSelfNode()`, `getAllNodes()` | Lets apps render or query the current node view |
| Observers and lifecycle | `addObserver(...)`, `removeObserver(...)`, `clear()`, `shutdown()` | Lets apps react to updates and manage database lifecycle |
| Optional cleanup | `startCleanupTask(NodeCleanupPolicy)`, `stopCleanupTask()`, `purgeStaleNodes(Duration)`, `isCleanupTaskRunning()` | Supports manual or scheduled purging of very old node records |

Cleanup behavior is intentionally implementation-defined:

- `InMemoryNodeDatabase` supports both scheduled cleanup and manual one-shot purging
- automatic cleanup is opt-in and does not start unless the application enables it
- custom `NodeDatabase` implementations may support automatic cleanup, manual cleanup, both, or neither
- cleanup is purge-oriented; status calculation (`LIVE`, `IDLE`, `CACHED`, `OFFLINE`) is a separate concern

Example cleanup policy:

```java
db.startCleanupTask(NodeCleanupPolicy.builder()
        .staleAfter(Duration.ofDays(7))
        .initialDelay(Duration.ofMinutes(5))
        .interval(Duration.ofMinutes(1))
        .build());
```

Manual one-shot purge:

```java
db.purgeStaleNodes(Duration.ofDays(7));
```

## Node Status Semantics

`MeshNode.getCalculatedStatus()` is a client-side freshness heuristic, not a radio-native truth value.

Why the client calculates status itself:

- Meshtastic gives us timestamps, signal metadata, and snapshot data, but not a single app-ready status label that works well for every UI
- applications usually want to distinguish:
  - a node heard live during this app session
  - a node only known from startup snapshot/history
  - a node that used to be current but has gone quiet
- the client calculates these labels so applications can use the library as-is without inventing their own status model first

Default status reference:

| Status | Default Threshold | Meaning |
| --- | --- | --- |
| `SELF` | n/a | This is the local node the client is connected to. |
| `LIVE` | `MeshConstants.LIVE_THRESHOLD` (`15 minutes`) | The app has heard a real packet from this node recently in the current session. |
| `IDLE` | until `MeshConstants.NON_LIVE_NODE_THRESHOLD` (`24 hours`) | The app has heard this node before in the current session, but not recently enough to still call it `LIVE`. |
| `CACHED` | until `MeshConstants.NON_LIVE_NODE_THRESHOLD` (`24 hours`) | The node came from startup snapshot/history, but this app session has not yet heard it speak live. |
| `OFFLINE` | older than non-live threshold | The node is too old to keep showing as current. |

Real examples:

| Status | Example |
| --- | --- |
| `LIVE` | You started the client, and that node sent a text, telemetry, or position packet 2 minutes ago. |
| `IDLE` | That node spoke 40 minutes ago during this app session, but nothing newer has arrived since then. |
| `CACHED` | The node appeared in the startup dump when the client connected, but it has not sent any live traffic since your app started. |
| `OFFLINE` | The node has not been heard live or via recent radio snapshot data within the non-live threshold window. |

Why these rules exist:

- they distinguish “heard live by this app” from “present in radio snapshot/history”
- they let UIs show a more useful middle state than just online/offline
- they avoid treating cached startup data as if the node had spoken during the current app session

Example custom policy:

```java
NodeStatusPolicy customPolicy = NodeStatusPolicy.builder()
        .liveThreshold(Duration.ofMinutes(2))
        .nonLiveThreshold(Duration.ofHours(6))
        .build();

MeshNode.NodeStatus status = node.getCalculatedStatus(customPolicy);
```

How `NodeStatusPolicy` works:

- `NodeStatusPolicy` is the default threshold-based calculator used by `node.getCalculatedStatus()`
- it controls the two time windows that drive the status transitions
- it does not define all five statuses separately because not all statuses are threshold-based

Why it only has two settings:

- `SELF` does not need a threshold; it is determined by identity
- `LIVE` needs one threshold: how long a recent live packet keeps a node in the `LIVE` bucket
- `IDLE` and `CACHED` share the same outer freshness boundary
- `OFFLINE` is simply the fallback once a node is older than that outer boundary

So the two settings are:

| Setting | Meaning |
| --- | --- |
| `liveThreshold` | How long a locally heard node stays `LIVE`. |
| `nonLiveThreshold` | How long an `IDLE` or `CACHED` node can remain non-offline before becoming `OFFLINE`. |

What that example policy does:

| Setting | Example Value | Effect |
| --- | --- | --- |
| `liveThreshold` | `2 minutes` | A node will stop being `LIVE` much sooner than the default. |
| `nonLiveThreshold` | `6 hours` | An `IDLE` or `CACHED` node will become `OFFLINE` after 6 hours instead of 24 hours. |

So compared with the defaults, that example makes the UI more aggressive about aging nodes out of the “current” view.

How to customize if these semantics do not fit your app:

- use your own `NodeStatusCalculator` if threshold-based logic is not enough
- interpret `MeshNode.getCalculatedStatus()` differently in your UI/application layer
- provide your own `NodeDatabase` implementation if you want different snapshot/freshness semantics
- if needed, keep the library as-is and apply your own status semantics at the app layer without changing the client itself

The exact thresholds currently come from:

- `MeshConstants.LIVE_THRESHOLD`
- `MeshConstants.NON_LIVE_NODE_THRESHOLD`

## Building

Standard build:

```bash
mvn clean package
```

This performs the normal project build and generates the documentation artifacts alongside the library.

Build outputs include:

- `target/meshtastic-java-client-<version>.jar`
- `target/meshtastic-java-client-<version>-sources.jar`
- `target/meshtastic-java-client-<version>-javadoc.jar`
- rendered docs under `target/docs`

If you are evaluating or onboarding to the project, it is worth opening the generated HTML docs after a build.
They are intended to function as a practical guide to the API, not just reference output.

The guide includes application patterns for UI clients and headless/bot services built with this library. Those
sections are examples of how to use the library API; this repository does not bundle a ready-made UI app or bot.

## Messaging Examples

Common message patterns are exposed directly on `MeshtasticClient`:

```java
// Direct message to one node using the primary channel context.
client.sendDirectText(targetNodeId, "hello");

// Broadcast to the public/default channel.
client.sendChannelText(MeshConstants.PRIMARY_CHANNEL_INDEX, "hello mesh");

// Broadcast to a specific shared channel such as channel 1 ("Test").
client.sendChannelText(1, "hello test");

// Advanced: direct message to one node using a non-primary channel context.
client.sendDirectText(targetNodeId, 1, "hello on channel 1");
```

Rule of thumb:
- use `sendDirectText(...)` for one-to-one conversations
- use `sendChannelText(...)` for channel/group chat
- use the overload with `channelIndex` when you need a DM to use a specific shared channel's key/context

## Retry And Reconnect Model

The library intentionally splits resilience behavior into two layers:

- request-level retry/correlation logic in the core client
- link-level reconnect behavior in the transport

The core client owns:

- request correlation by packet/request id
- request timeout handling
- radio lock/cooldown sequencing
- admin write verification and verification retries
- startup resync sequencing after a connection is restored

The transport owns:

- opening and closing the physical link
- detecting disconnects
- reconnecting when supported
- delivering framed Meshtastic bytes back to the client

In practice, this means request verification and admin retry behavior are transport-agnostic. Serial, TCP, and BLE
can all use the same request lifecycle machinery. What changes between transports is whether the physical connection
can be restored automatically and how that restoration happens.

If you are implementing a new transport, you do not need to re-implement:

- request correlation
- admin verification retries
- startup resync logic
- request lifecycle event publishing

Your transport does need to provide:

- framed reads/writes
- connection/disconnection callbacks
- reconnect behavior if that transport should recover automatically

So for BLE specifically: the core retry model already applies, but BLE reconnect reliability depends on the BLE backend
and transport implementation, not on separate retry logic in application code.

## BLE And OTA Extension Notes

BLE and OTA are intentionally documented as extension-oriented features rather than fully bundled production paths.

- `BleTransport` gives the core framing/lifecycle contract, but a real backend still needs to be supplied by the app
  or by a future community module.
- `OtaService` gives the orchestration flow, but a real uploader strategy still needs to be supplied for the chosen
  transport/mechanism.

Examples of Java BLE stacks/backends that may be suitable depending on platform/runtime:

- Gluon Attach BluetoothLE for Gluon/JavaFX-oriented applications
- TinyB for Linux/BlueZ environments
- a BlueZ D-Bus backend using a Java D-Bus library for Linux-specific implementations

These should be treated as examples, not official project dependencies. The right choice depends on OS, packaging,
and whether the target app is desktop, mobile, or embedded.

For transport authors, the key point is that `BleTransport` only needs to solve BLE link behavior. The shared
Meshtastic request, retry, and startup mechanics already live in the core client layers.

## Testing

The project has two main test layers:

- normal unit/integration-lite tests that run without radio hardware
- opt-in hardware integration tests that exercise a real device

### Standard Test Suite

Run the normal test suite with:

```bash
mvn test
```

This covers the core client, frame decoding, chunking, handler routing, admin flows, OTA orchestration pieces,
BLE transport skeleton behavior, and other non-hardware logic using fake/in-memory test infrastructure.

### Hardware Integration Tests (Opt-In)

Real-radio tests are available behind the Maven profile `hardware-it` and are not executed during normal `test`.
This is one hardware test profile with two transport modes:
- `serial`: validates direct USB/serial access to a radio
- `tcp`: validates Meshtastic TCP access over the network

Both modes run the same `RealRadioAdminIT` suite. This is useful because serial and TCP can expose different timing, reconnect, and startup-sync behavior even when the admin/config APIs are the same.

Transport selection:
- `MESHTASTIC_TEST_TRANSPORT` (`serial` by default, or `tcp`)

Required transport-specific environment variables:
- `MESHTASTIC_TEST_PORT` when `MESHTASTIC_TEST_TRANSPORT=serial` (example: `/dev/cu.usbmodem80B54ED11F101`)
- `MESHTASTIC_TEST_TCP_HOST` when `MESHTASTIC_TEST_TRANSPORT=tcp` (example: `192.168.1.40`)

Optional environment variables:
- `MESHTASTIC_TEST_TCP_PORT` (default: `4403`, only used for TCP hardware tests)
- `MESHTASTIC_TEST_TIMEOUT_SEC` (default: `45`)
- `MESHTASTIC_TEST_MUTABLE_CHANNEL_INDEX` (default: `2`, should be an active non-primary slot)
- `MESHTASTIC_TEST_ENABLE_OWNER_WRITE` (default: `false`; enables reversible owner write/readback/restore test)
- `MESHTASTIC_TEST_ENABLE_SECURITY_WRITE` (default: `false`; enables reversible security config write/readback/restore test)
- `MESHTASTIC_TEST_ENABLE_MQTT_WRITE` (default: `false`; enables reversible MQTT module config write/readback/restore test)
- `MESHTASTIC_TEST_ENABLE_REBOOT_TEST` (default: `false`; enables reboot/reconnect resilience test)

What the hardware suite validates in either mode:
- startup to `READY`
- metadata/owner/channel read paths
- core config read matrix
- module config read matrix (capability-tolerant)
- request burst lifecycle terminal events
- reversible channel write/readback/restore
- optional reversible owner write/readback/restore (explicitly enabled)
- optional reversible security config write/readback/restore (explicitly enabled)
- optional reversible MQTT module config write/readback/restore (explicitly enabled)
- optional reboot/reconnect resilience (explicitly enabled)

#### Serial Hardware Mode

Use serial mode when you want to validate the library against a directly attached USB/serial radio. This is typically the simplest and most deterministic hardware test path.

Required:
- `MESHTASTIC_TEST_TRANSPORT=serial`
- `MESHTASTIC_TEST_PORT=/dev/...`

Example:

```bash
MESHTASTIC_TEST_TRANSPORT=serial \
MESHTASTIC_TEST_PORT=/dev/cu.usbmodem80B54ED11F101 \
mvn -Phardware-it verify
```

#### TCP Hardware Mode

Use TCP mode when you want to validate the same admin/config flows over Meshtastic's network socket interface. This is useful for catching transport-specific issues such as slower startup sync, reconnect behavior, or network timing differences that may not appear over serial.

Required:
- `MESHTASTIC_TEST_TRANSPORT=tcp`
- `MESHTASTIC_TEST_TCP_HOST=<radio-ip>`

Optional:
- `MESHTASTIC_TEST_TCP_PORT=4403`

Example:

```bash
MESHTASTIC_TEST_TRANSPORT=tcp \
MESHTASTIC_TEST_TCP_HOST=192.168.1.40 \
MESHTASTIC_TEST_TCP_PORT=4403 \
mvn -Phardware-it verify
```

#### Shared Optional Flags

These apply to both serial and TCP hardware runs:
- `MESHTASTIC_TEST_TIMEOUT_SEC`
- `MESHTASTIC_TEST_MUTABLE_CHANNEL_INDEX`
- `MESHTASTIC_TEST_ENABLE_OWNER_WRITE`
- `MESHTASTIC_TEST_ENABLE_SECURITY_WRITE`
- `MESHTASTIC_TEST_ENABLE_MQTT_WRITE`
- `MESHTASTIC_TEST_ENABLE_REBOOT_TEST`
