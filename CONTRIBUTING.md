# Contributing

## Goals

This project aims to be:

- easy to use for application developers
- easy to extend for contributors
- conservative about protocol behavior and radio safety
- explicit about what belongs in core versus platform-specific extensions

## Architecture Guidelines

Keep the public surface simple and push complexity inward.

- `MeshtasticClient` is the primary public facade for transport lifecycle, messaging helpers, startup sync, and utility requests.
- `AdminService` is the primary public facade for owner/config/channel/module refresh and write workflows.
- handlers decode protocol messages and update state/events
- transports handle physical link concerns and reconnect behavior
- platform-specific edges such as BLE backends and OTA upload strategies belong behind extension interfaces

When adding functionality:

- prefer extending existing facades over creating new public service types unless the public API clearly benefits
- prefer internal helper classes for coordination/state-machine logic
- keep protocol-specific mutation/comparison logic close to the domain it belongs to

## Records vs Lombok

The project intentionally uses both Java records and Lombok, but for different jobs.

Use **records** for:

- small immutable value carriers
- internal tuple-like coordination/state types
- simple fixed-shape data where builders add little value

Use **Lombok builders** for:

- configuration objects
- request/policy objects with optional fields
- places where named construction materially improves readability

Use **regular classes** for:

- services
- engines
- stateful coordinators
- anything with meaningful internal behavior or mutation

Avoid using Lombok bean-style annotations just to replace a small immutable record.
Avoid using records for large configurable objects where a builder is clearly easier to use.

## Logging Conventions

Logging is part of the public developer experience. Keep it intentional.

- `TRACE`
  - raw transport activity
  - framed IO details
  - verbose timing/correlation diagnostics
  - full payload dumps or very noisy protocol internals

- `DEBUG`
  - protocol decode summaries
  - admin snapshot/model update churn
  - request correlation completions
  - verification attempts and retry details
  - request intent logs that are useful during development but too noisy for normal runs

- `INFO`
  - meaningful lifecycle/state changes
  - startup sync phase transitions
  - reconnect/restored events
  - successful high-level operations a user/operator is likely to care about

- `WARN`
  - rejected requests
  - inconsistent or delayed firmware behavior
  - recoverable reconnect issues
  - fallback paths that may indicate degraded behavior

- `ERROR`
  - parse failures
  - unrecoverable request/transport failures
  - exceptions that prevent the intended operation from completing

If a log line can fire frequently during healthy normal operation, it likely belongs at `DEBUG` or `TRACE`, not `INFO`.

## Retry And Transport Responsibilities

The project intentionally splits resilience behavior across two layers:

- core client layer:
  - request correlation
  - request timeout handling
  - radio lock sequencing
  - admin verification retries
  - startup resync orchestration after reconnect

- transport layer:
  - physical connection setup/teardown
  - disconnect detection
  - reconnect policy/implementation
  - framed byte delivery to the client

If you add a new transport, do not duplicate request retry or admin verification logic in the transport itself.
That behavior already lives in the client/admin layers and is meant to work across serial, TCP, BLE, and future
transports.

What a transport should provide is a reliable framed IO boundary plus accurate lifecycle callbacks. If the transport
supports automatic reconnect, the existing startup synchronizer and request machinery will take over once the link is
back.

## Testing Expectations

Before merging substantial changes, prefer to keep both test layers green:

- `mvn test`
- `mvn -Phardware-it verify` for radio-affecting changes when hardware is available

If a change affects:

- request correlation
- startup sync
- admin write/readback behavior
- reconnect handling
- transport pacing/timeout behavior

then add or update tests in the relevant unit suite, and use hardware IT when the behavior depends on real firmware timing.

## Documentation Expectations

Documentation is a core deliverable of this project.

When adding or changing public behavior:

- update Javadocs
- update `README.md` if the onboarding/build/test story changes
- update `src/docs/asciidoc/index.adoc` for end-user usage flows and examples

The generated HTML/PDF docs are intended to function as the practical user manual for the library.
