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

## Release Positioning

This library is currently strongest as a serial/TCP-first Meshtastic client for desktop/headless apps.  
BLE parity and full OTA integration are the major remaining items for full protocol surface parity.

## Documentation

- API guide: `src/docs/asciidoc/index.adoc`
- Generated Javadocs and docs are produced during Maven package phases (see `pom.xml` plugin config)

## Hardware Integration Tests (Opt-In)

Real-radio tests are available behind the Maven profile `hardware-it` and are not executed during normal `test`.

Required environment variable:
- `MESHTASTIC_TEST_PORT` (example: `/dev/cu.usbmodem80B54ED11F101`)

Optional environment variables:
- `MESHTASTIC_TEST_TIMEOUT_SEC` (default: `45`)
- `MESHTASTIC_TEST_MUTABLE_CHANNEL_INDEX` (default: `2`, should be an active non-primary slot)
- `MESHTASTIC_TEST_ENABLE_OWNER_WRITE` (default: `false`; enables reversible owner write/readback/restore test)

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
- reversible channel write/readback/restore
- optional reversible owner write/readback/restore (explicitly enabled)
