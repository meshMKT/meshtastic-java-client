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
- `NodeDatabase` is a public storage extension point; `InMemoryNodeDatabase` is the default, not the only intended implementation.
- handlers decode protocol messages and update state/events
- transports handle physical link concerns and reconnect behavior
- platform-specific edges such as BLE backends belong behind extension interfaces

When adding functionality:

- prefer extending existing facades over creating new public service types unless the public API clearly benefits
- prefer internal helper classes for coordination/state-machine logic
- keep protocol-specific mutation/comparison logic close to the domain it belongs to
- preserve the `NodeDatabase` abstraction so applications can swap in persistent or application-specific storage without rewriting client logic
- treat `NodeDatabase` cleanup behavior as implementation-defined; do not assume every implementation wants a background purge scheduler
- keep cleanup semantics focused on retention/purging; status derivation (`LIVE`, `IDLE`, `CACHED`, `OFFLINE`) should remain a separate concern

## Object Modeling Conventions

The project standardizes on Lombok-based classes instead of mixing Lombok and Java records.

Use **`@Value`** for:

- immutable request, result, and event-style objects
- small value objects that are passed around but not mutated
- helper types where the state should stay fixed after construction

Use **`@Builder`** when:

- a type has several fields
- defaults or optional fields improve readability
- named construction makes call sites easier to understand

Use **`@Data`** sparingly for:

- mutable in-memory state holders
- internal storage objects whose job is to be updated over time

Use **regular classes** for:

- services
- engines
- stateful coordinators
- anything where behavior matters more than the shape of the data

Default to immutability unless mutation is part of the design. In practice, that usually means
`@Value` is the normal choice, `@Builder` is added where it helps, and `@Data` is reserved for
true mutable state.

## Commit Message Style

Use a short, readable subject line in this format:

`type: summary`

Examples:

- `feat: add TCP reconnect handling`
- `fix: handle admin request timeout correctly`
- `docs: clarify startup sync behavior`
- `refactor: rename AdminRequestGateway to AdminClientAccess`
- `build: add GitHub release workflow`
- `chore: remove experimental OTA support`

Guidelines:

- keep the first line short and easy to scan
- describe the change, not the implementation process
- use the body only when extra context is actually helpful
- prefer one logical change per commit when practical

Common types:

- `feat` for new functionality
- `fix` for bug fixes
- `docs` for README or guide changes
- `refactor` for code structure changes without behavior changes
- `test` for test-only changes
- `build` for Maven, CI, or release workflow changes
- `chore` for maintenance and cleanup

## Code Formatting

The repository uses Spotless with Palantir Java Format as the source of truth for formatting.

Useful commands:

- `mvn spotless:apply`
- `mvn spotless:check`

The normal build already enforces formatting during `verify`, so CI will fail if formatting drifts.

If your Maven mirror does not resolve the short `spotless:` prefix, use the full plugin coordinates instead:

- `mvn com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply`
- `mvn com.diffplug.spotless:spotless-maven-plugin:3.0.0:check`

Recommended local workflow:

- let your IDE help with editing, but do not rely on arbitrary IDE formatting rules as the final source of truth
- run `mvn spotless:apply` before committing if you made Java or `pom.xml` changes
- if Spotless rewrites files, review the diff and commit the formatting changes with the rest of your work

Optional pre-commit hook:

```bash
./scripts/install-git-hooks.sh
```

Run that once after cloning if you want the formatting hook installed locally.

That hook runs Spotless using the full Maven plugin coordinates before the commit is created.
It checks the repository's Java sources and `pom.xml` and blocks the commit if formatting is off.

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
