# meshtastic-java-client

[![Maven](https://img.shields.io/maven-central/v/com.meshmkt.meshtastic/meshtastic-java-client.svg?style=for-the-badge)](https://repo1.maven.org/maven2/com/meshmkt/meshtastic/meshtastic-java-client/)
[![License](https://img.shields.io/github/license/meshmkt/meshtastic-java-client?style=for-the-badge&logo=gnu)](https://www.gnu.org/licenses/gpl-3.0.en.html)
[![Java CI with Maven](https://img.shields.io/github/actions/workflow/status/meshmkt/meshtastic-java-client/build.yml?branch=main&logo=GitHub&style=for-the-badge)](https://github.com/meshmkt/meshtastic-java-client/actions/workflows/build.yml)

**Meshtastic Java Client** is an open-source Java library for building applications that connect to Meshtastic radios over serial and TCP. It provides a clean, practical API for working with messages, telemetry, node and channel data, and radio administration, making it easier to build desktop tools, services, bots, and other Java-based integrations. Firmware upgrades are intentionally out of scope for this library; for that work, we recommend the official Meshtastic tools such as the web flasher.

Licensed under the GNU General Public License v3.0. See `LICENSE`.

## Why This Project Exists

This project started because there was no clean, reusable Java client library for Meshtastic that could be dropped into a normal Java application.

Most existing Java support lived inside the Android client, which was valuable as application code but not ideal as a standalone library for:

- headless services
- bots that listen and respond to mesh traffic
- desktop and server-side Java applications
- custom Java UI clients

This library was built to fill that gap with a Java-first client that is practical to embed, easy to extend, and focused on real application use rather than one specific app.

## Licensing Note

This project is moving from Apache-2.0 to GPL-3.0 to match the licensing of the Meshtastic protobuf definitions it now depends on and generates code from.

The important distinction is that this library is no longer just an independent implementation of the protocol. The distributed artifact now includes generated classes derived from the Meshtastic protobuf schema definitions, and those upstream definitions are published by the Meshtastic project under GPL-3.0.

To avoid sending mixed signals about the licensing of the shipped artifact, the project documentation and published metadata are being aligned to GPL-3.0 as well.

## What It Supports

- Serial and TCP transports
- Automatic reconnect handling and startup sync state tracking
- Event-driven APIs for node discovery, text, telemetry, position, and admin model changes
- Admin/config/channel refresh and write operations through `AdminService`
- Request lifecycle events for accepted, rejected, and timed-out flows
- Snapshot-first node model for responsive UI and service integrations
- BLE transport SPI contract for pluggable backends

Note:
- this project does not handle firmware flashing or upgrades
- for firmware updates, use the official Meshtastic web flasher or other official firmware tools
- if an application needs a custom upgrade flow, that logic should live in app-specific code rather than in this core client library

## Requirements

To use this library, you will need:

- JDK 17+
- a Meshtastic-compatible radio such as a HELTEC V3, T-DECK, or similar device

## Installation

The library is available on Maven Central.

**Apache Maven**

```xml
<dependency>
    <groupId>com.meshmkt.meshtastic</groupId>
    <artifactId>meshtastic-java-client</artifactId>
    <version>${version}</version>
</dependency>
```

**Gradle**

```groovy
implementation group: 'com.meshmkt.meshtastic', name: 'meshtastic-java-client', version: '${version}'
```

## Quick Start

The smallest useful setup is still just a few lines of Java:

```java
NodeDatabase db = new InMemoryNodeDatabase();
MeshtasticClient client = new MeshtasticClient(db);

SerialConfig cfg = SerialConfig.builder()
        .portName("/dev/ttyUSB0")
        .build();

client.connect(new SerialTransport(cfg));
```

If you want runnable examples instead of a blank starting point, the user guide now includes:

- a JBang responder that works over either serial or TCP, watches a chosen channel slot for `/announce`, and replies by direct message
- an hourly weather beacon that posts to `Weather Updates`

Those runnable samples also live directly in the repository under `examples/jbang/`.

## Documentation

The full project documentation is available through the generated user guide and API docs:

- User guide: GitHub Pages / generated AsciiDoc documentation
- API reference: generated Javadocs
- Contributor guide: `CONTRIBUTING.md`

The user guide covers topics such as:

- startup and event-driven application flow
- messaging patterns
- admin/config usage
- settings-page refresh and write patterns
- transport behavior and reconnect expectations
- extension points such as BLE and custom node database implementations
- hardware integration testing

## Project Status

This library is currently strongest as a serial/TCP-first Meshtastic client for desktop, server, and headless Java applications.

Current limitations:

- no production BLE backend is bundled yet

In other words, the core client API is in place, and BLE is the main platform-specific area still left open for custom implementations.

## Testing

### Standard test suite

Run the normal test suite with:

```bash
mvn test
```

### Hardware integration tests

Real-radio tests are available through the `hardware-it` Maven profile and support both serial and TCP transport modes.

Example:

```bash
MESHTASTIC_TEST_TRANSPORT=serial \
MESHTASTIC_TEST_PORT=/dev/your-device \
mvn -Phardware-it verify
```

or:

```bash
MESHTASTIC_TEST_TRANSPORT=tcp \
MESHTASTIC_TEST_TCP_HOST=192.168.1.40 \
mvn -Phardware-it verify
```

See the user guide for the full hardware test configuration and optional write, node-db, and reboot test flags.

## Contributing

Contributions, bug reports, and design feedback are welcome.

Code formatting is enforced with Spotless for Java sources and `pom.xml`. See `CONTRIBUTING.md` for the formatter commands and optional git hook setup.

For project conventions, development notes, and contribution guidance, see:

- `CONTRIBUTING.md`

## License

Licensed under the GNU General Public License v3.0. See `LICENSE`.
