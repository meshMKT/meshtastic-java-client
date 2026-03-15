# meshtastic-java-client

Async Java client library for Meshtastic radios.

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

Required environment variable:
- `MESHTASTIC_TEST_PORT` (example: `/dev/cu.usbmodem80B54ED11F101`)

Optional environment variables:
- `MESHTASTIC_TEST_TIMEOUT_SEC` (default: `45`)
- `MESHTASTIC_TEST_MUTABLE_CHANNEL_INDEX` (default: `2`, should be an active non-primary slot)
- `MESHTASTIC_TEST_ENABLE_OWNER_WRITE` (default: `false`; enables reversible owner write/readback/restore test)
- `MESHTASTIC_TEST_ENABLE_SECURITY_WRITE` (default: `false`; enables reversible security config write/readback/restore test)
- `MESHTASTIC_TEST_ENABLE_MQTT_WRITE` (default: `false`; enables reversible MQTT module config write/readback/restore test)
- `MESHTASTIC_TEST_ENABLE_REBOOT_TEST` (default: `false`; enables reboot/reconnect resilience test)

Run:

```bash
MESHTASTIC_TEST_PORT=/dev/cu.usbmodem80B54ED11F101 \
mvn -Phardware-it verify
```

Hardware IT currently validates:
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
