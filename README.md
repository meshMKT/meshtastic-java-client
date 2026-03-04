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

