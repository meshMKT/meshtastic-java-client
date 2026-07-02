# Migration Guide

## 2.0.2 to 3.0.0

`3.0.0` includes a breaking protobuf source and package-layout change.

The library no longer consumes the pre-generated `buf.build` Java artifact. It now generates Java classes from the
official Meshtastic `protobufs` repository pinned as a git submodule.

## Why This Is A Major Version Change

This project exposes Meshtastic protobuf types directly in its public API surface.

That means the protobuf package move is visible to downstream callers:

- previous releases exposed types from `build.buf.gen.meshtastic...`
- `3.0.0` exposes the official Meshtastic Java types from `org.meshtastic.proto...`

If your application only consumed the published jar and stayed entirely inside higher-level wrappers, your behavioral
changes may be minimal.

If your application imported Meshtastic protobuf types directly, this is a source-compatible break and your imports
must be updated.

## Build Model Change

Maintainers and contributors must now initialize the protobuf submodule before building the repository:

```bash
git submodule update --init --recursive
```

This does not change how consumers use the published Maven Central artifact. It only changes how this repository is
built and how the exposed protobuf classes are sourced.

## Common Source Changes

Before:

```java
import build.buf.gen.meshtastic.AdminMessage;
import build.buf.gen.meshtastic.Config;
import build.buf.gen.meshtastic.MeshPacket;
```

After:

```java
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.MeshProtos.MeshPacket;
```

Other common examples:

- `build.buf.gen.meshtastic.ModuleConfig` -> `org.meshtastic.proto.ModuleConfigProtos.ModuleConfig`
- `build.buf.gen.meshtastic.Channel` -> `org.meshtastic.proto.ChannelProtos.Channel`
- `build.buf.gen.meshtastic.User` -> `org.meshtastic.proto.MeshProtos.User`
- `build.buf.gen.meshtastic.Position` -> `org.meshtastic.proto.MeshProtos.Position`
- `build.buf.gen.meshtastic.Telemetry` -> `org.meshtastic.proto.TelemetryProtos.Telemetry`
- `build.buf.gen.meshtastic.PortNum` -> `org.meshtastic.proto.Portnums.PortNum`

## Recommended Upgrade Pattern

1. update direct protobuf imports from `build.buf.gen.meshtastic...` to `org.meshtastic.proto...`
2. rebuild and fix any nested-type import changes such as `MeshProtos.*`, `ConfigProtos.*`, and `AdminProtos.*`
3. if you build this repository from source, initialize submodules before your first build
4. rerun your integration tests against your target radios and firmware

## 1.x to 2.0.0

`2.0.0` includes a breaking cleanup of `AdminService`.

The goal of the change is to make the admin/config API smaller, more consistent, and easier to reason about:

- broad mutable snapshot access was removed
- grouped refresh helpers were removed
- typed config/module convenience wrappers were removed
- boolean-based write methods were replaced with canonical write methods that use `AdminWriteMode`
- admin writes now return `AdminWriteResult` instead of a mix of `Boolean`, `Void`, and separate `...Result(...)` methods

## High-Level Changes

### Snapshot access

Removed:

- `getSnapshot()`

Use instead:

- `getNodeId()`
- `getOwnerSnapshot()`
- `getMetadataSnapshot()`
- `getDeviceUiSnapshot()`
- `getConfigSnapshot(...)`
- `getModuleConfigSnapshot(...)`
- `getChannelSnapshot(...)`
- `getChannelSnapshots()`

### Grouped refresh helpers

Removed:

- `refreshCore()`
- `refreshChannelsAndSecurity()`
- `refreshAllChannelsAndSecurity()`
- `refreshModules()`

Callers should now explicitly refresh the sections they need.

### Typed refresh helpers

Removed config wrappers such as:

- `refreshSecurityConfig()`
- `refreshDeviceConfig()`
- `refreshPositionConfig()`
- `refreshPowerConfig()`
- `refreshNetworkConfig()`
- `refreshDisplayConfig()`
- `refreshLoraConfig()`
- `refreshBluetoothConfig()`
- `refreshSessionKeyConfig()`

Use instead:

- `refreshConfig(AdminMessage.ConfigType type)`

Removed module wrappers such as:

- `refreshMqttConfig()`
- `refreshSerialModuleConfig()`
- `refreshTelemetryModuleConfig()`
- `refreshAudioModuleConfig()`
- `refreshTakModuleConfig()`

Use instead:

- `refreshModuleConfig(AdminMessage.ModuleConfigType type)`

### Write API cleanup

Removed:

- boolean-based write methods such as `setConfig(config, true)`
- structured companion methods such as `setConfigResult(...)`
- typed config/module write wrappers such as `setDeviceConfig(...)` and `setMqttConfig(...)`

Use instead:

- `setConfig(Config config, AdminWriteMode mode)`
- `setModuleConfig(ModuleConfig moduleConfig, AdminWriteMode mode)`
- `setChannel(int index, Channel updatedChannel, AdminWriteMode mode)`
- `setDeviceUiConfig(DeviceUIConfig deviceUiConfig, AdminWriteMode mode)`
- `setOwner(int targetNodeId, String longName, String shortName, AdminWriteMode mode)`

Common modes:

- `AdminWriteMode.ACCEPT_ONLY`
- `AdminWriteMode.VERIFY_APPLIED`

## Before and After

### Read cached owner

Before:

```java
User owner = adminService.getSnapshot().getOwner();
```

After:

```java
User owner = adminService.getOwnerSnapshot().orElse(User.getDefaultInstance());
```

### Read local node id

Before:

```java
int nodeId = adminService.getSnapshot().getNodeId();
```

After:

```java
int nodeId = adminService.getNodeId();
```

### Refresh a config section

Before:

```java
Config.DeviceConfig device = adminService.refreshDeviceConfig().join();
```

After:

```java
Config config = adminService.refreshConfig(AdminMessage.ConfigType.DEVICE_CONFIG).join();
Config.DeviceConfig device = config.getDevice();
```

### Refresh a module config section

Before:

```java
ModuleConfig.TelemetryConfig telemetry =
        adminService.refreshTelemetryModuleConfig().join();
```

After:

```java
ModuleConfig moduleConfig =
        adminService.refreshModuleConfig(AdminMessage.ModuleConfigType.TELEMETRY_CONFIG).join();
ModuleConfig.TelemetryConfig telemetry = moduleConfig.getTelemetry();
```

### Write a config section

Before:

```java
adminService.setConfig(config, true).join();
```

After:

```java
adminService.setConfig(config, AdminWriteMode.VERIFY_APPLIED).join();
```

### Write a module config section

Before:

```java
adminService.setTelemetryModuleConfig(telemetryConfig, true).join();
```

After:

```java
ModuleConfig moduleConfig = ModuleConfig.newBuilder()
        .setTelemetry(telemetryConfig)
        .build();

adminService.setModuleConfig(moduleConfig, AdminWriteMode.VERIFY_APPLIED).join();
```

### Write owner settings

Before:

```java
adminService.setOwnerAndVerify(nodeId, "Radio", "RDIO").join();
```

After:

```java
adminService.setOwner(nodeId, "Radio", "RDIO", AdminWriteMode.VERIFY_APPLIED).join();
```

### Write a channel

Before:

```java
adminService.setChannel(index, channel, true).join();
```

After:

```java
adminService.setChannel(index, channel, AdminWriteMode.VERIFY_APPLIED).join();
```

## Return Type Changes

Many admin write methods now return:

```java
CompletableFuture<AdminWriteResult>
```

instead of:

- `CompletableFuture<Boolean>`
- `CompletableFuture<Void>`

This gives callers a consistent result model for:

- accepted-only writes
- verify-applied writes
- timeouts
- rejections
- verification failures

## Recommended Upgrade Pattern

For most applications, the upgrade path is:

1. replace `getSnapshot()` reads with typed snapshot accessors
2. replace typed refresh helpers with `refreshConfig(...)` or `refreshModuleConfig(...)`
3. replace boolean write methods with `AdminWriteMode`
4. replace boolean success checks with `AdminWriteResult` handling
5. build `Config` and `ModuleConfig` wrappers explicitly at the call site when writing nested sections

## Notes

- `MeshtasticClient` messaging and event APIs were not broadly reworked in this release.
- The main source compatibility break in `2.0.0` is the `AdminService` API cleanup.

