# meshtastic-java-client

[![Maven](https://img.shields.io/maven-central/v/com.meshmkt.meshtastic/meshtastic-java-client.svg?style=for-the-badge)](https://repo1.maven.org/maven2/com/meshmkt/meshtastic/meshtastic-java-client/)
[![License](https://img.shields.io/github/license/meshmkt/meshtastic-java-client?style=for-the-badge&logo=apache)](https://opensource.org/license/Apache-2.0)
[![Java CI with Maven](https://img.shields.io/github/actions/workflow/status/meshmkt/meshtastic-java-client/build.yml?branch=main&logo=GitHub&style=for-the-badge)](https://github.com/meshmkt/meshtastic-java-client/actions/workflows/build.yml)

**Meshtastic Java Client** is an open-source Java library for building applications that connect to Meshtastic radios over serial and TCP. It provides a clean, practical API for working with messages, telemetry, node and channel data, and radio administration, making it easier to build desktop tools, services, bots, and other Java-based integrations. BLE transport SPI hooks and OTA framework support are present, but this repository does not yet bundle a production BLE backend or a fully integrated end-to-end OTA flashing implementation, so those paths still require platform- or application-specific work.

Licensed under the Apache License, Version 2.0. See `LICENSE`.

## Why This Project Exists

This project started because there was no clean, reusable Java client library for Meshtastic that could be dropped into a normal Java application.

Most existing Java support lived inside the Android client, which was valuable as application code but not ideal as a standalone library for:

- headless services
- bots that listen and respond to mesh traffic
- desktop and server-side Java applications
- custom Java UI clients

This library was built to fill that gap with a Java-first client that is practical to embed, easy to extend, and focused on real application use rather than one specific app.

## What It Supports

- Serial and TCP transports
- Automatic reconnect handling and startup sync state tracking
- Event-driven APIs for node discovery, text, telemetry, position, and admin model changes
- Admin/config/channel refresh and write operations through `AdminService`
- Request lifecycle events for accepted, rejected, and timed-out flows
- Snapshot-first node model for responsive UI and service integrations
- OTA framework/orchestration hooks
- BLE transport SPI contract for pluggable backends

Note:
- BLE and OTA support at this stage are extension-oriented rather than turnkey. The library includes the core contracts and orchestration points, but it does not yet ship with a production BLE backend or a full end-to-end OTA uploader implementation because those pieces are platform- and transport-specific.

## Requirements

To use this library, you will need:

- JDK 21+
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
- extension points such as BLE, OTA, and custom node database implementations
- hardware integration testing

## Project Status

This library is currently strongest as a serial/TCP-first Meshtastic client for desktop, server, and headless Java applications.

Current limitations:

- no production BLE backend is bundled yet
- OTA has framework support, but no fully integrated production-ready end-to-end flashing path is included yet

In other words, the core client API is in place, but BLE and OTA still require platform- or application-specific implementations.

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

See the user guide for the full hardware test configuration and optional write/reboot test flags.

## Contributing

Contributions, bug reports, and design feedback are welcome.

For project conventions, development notes, and contribution guidance, see:

- `CONTRIBUTING.md`

## License

Licensed under the Apache License, Version 2.0. See `LICENSE`.
