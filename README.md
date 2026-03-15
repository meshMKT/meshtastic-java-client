# meshtastic-java-client

Async Java client library for Meshtastic radios.

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
