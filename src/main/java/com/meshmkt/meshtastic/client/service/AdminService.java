package com.meshmkt.meshtastic.client.service;

import build.buf.gen.meshtastic.*;
import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.ProtocolConstraints;
import com.meshmkt.meshtastic.client.model.RadioModel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin/config facade for Meshtastic settings operations.
 * <p>
 * Public API shape:
 * </p>
 * <ul>
 * <li>Typed snapshot accessors for the cached radio model.</li>
 * <li>Explicit async refresh operations keyed by config/module/channel type.</li>
 * <li>Canonical write operations returning {@link AdminWriteResult} and controlled by
 * {@link AdminWriteMode}.</li>
 * </ul>
 * <p>
 * Snapshot mutation/session-key handling and verify-applied policy logic are delegated to focused
 * helpers so this class can stay oriented around request orchestration and public API semantics.
 * </p>
 */
@Slf4j
public class AdminService {
    private static final Duration OWNER_VERIFY_TIMEOUT = Duration.ofSeconds(10);
    private static final int PRIMARY_CHANNEL_INDEX = 0;
    private static final List<AdminMessage.ConfigType> CORE_CONFIG_TYPES = List.of(
            AdminMessage.ConfigType.LORA_CONFIG,
            AdminMessage.ConfigType.DEVICE_CONFIG,
            AdminMessage.ConfigType.DISPLAY_CONFIG,
            AdminMessage.ConfigType.NETWORK_CONFIG);
    private static final List<AdminMessage.ModuleConfigType> SUPPORTED_MODULE_CONFIG_TYPES = List.of(
            AdminMessage.ModuleConfigType.MQTT_CONFIG,
            AdminMessage.ModuleConfigType.SERIAL_CONFIG,
            AdminMessage.ModuleConfigType.EXTNOTIF_CONFIG,
            AdminMessage.ModuleConfigType.STOREFORWARD_CONFIG,
            AdminMessage.ModuleConfigType.RANGETEST_CONFIG,
            AdminMessage.ModuleConfigType.TELEMETRY_CONFIG,
            AdminMessage.ModuleConfigType.CANNEDMSG_CONFIG,
            AdminMessage.ModuleConfigType.AUDIO_CONFIG,
            AdminMessage.ModuleConfigType.REMOTEHARDWARE_CONFIG,
            AdminMessage.ModuleConfigType.NEIGHBORINFO_CONFIG,
            AdminMessage.ModuleConfigType.AMBIENTLIGHTING_CONFIG,
            AdminMessage.ModuleConfigType.DETECTIONSENSOR_CONFIG,
            AdminMessage.ModuleConfigType.PAXCOUNTER_CONFIG,
            AdminMessage.ModuleConfigType.STATUSMESSAGE_CONFIG,
            AdminMessage.ModuleConfigType.TRAFFICMANAGEMENT_CONFIG,
            AdminMessage.ModuleConfigType.TAK_CONFIG);

    private final AdminClientAccess clientAccess;
    private final RadioModel radioModel = new RadioModel();
    private final AdminSnapshotApplier snapshotApplier = new AdminSnapshotApplier(radioModel);
    private final AdminVerificationEngine verificationEngine = new AdminVerificationEngine();

    /**
     * Creates an admin service bound to a client access interface.
     *
     * @param clientAccess interface used for admin request/response operations.
     */
    public AdminService(AdminClientAccess clientAccess) {
        this.clientAccess = clientAccess;
    }

    /**
     * Returns the current admin write verification policy.
     *
     * @return active verification policy.
     */
    public AdminVerificationPolicy getVerificationPolicy() {
        return verificationEngine.getVerificationPolicy();
    }

    /**
     * Sets the admin write verification policy.
     * <p>
     * This controls delayed read-back retries for eventual-consistency firmwares.
     * </p>
     *
     * @param verificationPolicy new verification policy.
     */
    public void setVerificationPolicy(AdminVerificationPolicy verificationPolicy) {
        verificationEngine.setVerificationPolicy(verificationPolicy);
    }

    /**
     * Returns the latest known local node id from the cached admin model.
     *
     * @return cached local node id, or {@code 0} when unknown.
     */
    public int getNodeId() {
        return radioModel.getNodeId();
    }

    /**
     * Returns the cached owner snapshot.
     *
     * @return optional cached owner payload.
     */
    public Optional<User> getOwnerSnapshot() {
        return Optional.ofNullable(radioModel.getOwner());
    }

    /**
     * Returns the cached device metadata snapshot.
     *
     * @return optional cached metadata payload.
     */
    public Optional<DeviceMetadata> getMetadataSnapshot() {
        return Optional.ofNullable(radioModel.getDeviceMetadata());
    }

    /**
     * Returns the cached device UI config snapshot.
     *
     * @return optional cached device UI payload.
     */
    public Optional<DeviceUIConfig> getDeviceUiSnapshot() {
        return getConfigSnapshot(AdminMessage.ConfigType.DEVICEUI_CONFIG)
                .filter(Config::hasDeviceUi)
                .map(Config::getDeviceUi);
    }

    /**
     * Clears all cached admin snapshot state.
     * <p>
     * This is intended for disconnect/reboot lifecycle handling inside the client runtime.
     * </p>
     */
    public void resetSnapshotState() {
        radioModel.reset();
    }

    /**
     * Refreshes the core config group used by most settings UIs.
     *
     * @return future completing with immutable map of refreshed config payloads keyed by type.
     */
    public CompletableFuture<Map<AdminMessage.ConfigType, Config>> refreshCoreConfigs() {
        return refreshConfigs(CORE_CONFIG_TYPES);
    }

    /**
     * Refreshes device metadata from the radio.
     *
     * @return future completing with parsed metadata.
     */
    public CompletableFuture<DeviceMetadata> refreshMetadata() {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setGetDeviceMetadataRequest(true))
                .build();
        return executeAndParse(request, msg -> {
            if (!msg.hasGetDeviceMetadataResponse()) {
                throw new IllegalStateException("Missing get_device_metadata_response");
            }
            DeviceMetadata metadata = msg.getGetDeviceMetadataResponse();
            snapshotApplier.applyMetadata(metadata);
            return metadata;
        });
    }

    /**
     * Refreshes owner/user data from the radio.
     *
     * @return future completing with parsed owner response.
     */
    public CompletableFuture<User> refreshOwner() {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setGetOwnerRequest(true))
                .build();
        return executeAndParse(request, msg -> {
            if (!msg.hasGetOwnerResponse()) {
                throw new IllegalStateException("Missing get_owner_response");
            }
            User owner = msg.getGetOwnerResponse();
            snapshotApplier.applyOwner(owner);
            return owner;
        });
    }

    /**
     * Refreshes a specific config block from the radio.
     * <p>
     * For {@link AdminMessage.ConfigType#SESSIONKEY_CONFIG}, metadata is refreshed first to match behavior seen in official clients.
     * </p>
     *
     * @param type config type to request.
     * @return future completing with parsed config response.
     */
    public CompletableFuture<Config> refreshConfig(AdminMessage.ConfigType type) {
        CompletableFuture<Void> preflight = type == AdminMessage.ConfigType.SESSIONKEY_CONFIG
                ? refreshMetadata().thenApply(metadata -> null)
                : CompletableFuture.completedFuture(null);

        return preflight.thenCompose(unused -> {
            AdminMessage request = snapshotApplier
                    .withSessionIfPresent(AdminMessage.newBuilder().setGetConfigRequest(type))
                    .build();
            return executeAndParse(request, msg -> {
                if (!msg.hasGetConfigResponse()) {
                    throw new IllegalStateException("Missing get_config_response");
                }
                Config config = msg.getGetConfigResponse();
                snapshotApplier.applyConfig(config);
                return config;
            });
        });
    }

    /**
     * Refreshes the device UI config section.
     *
     * @return future completing with parsed device UI config section.
     */
    public CompletableFuture<DeviceUIConfig> refreshDeviceUiConfig() {
        return refreshConfig(AdminMessage.ConfigType.DEVICEUI_CONFIG).thenApply(Config::getDeviceUi);
    }

    /**
     * Refreshes an explicit set of config types sequentially.
     * <p>
     * Sequential ordering avoids flooding slower radios with many settings queries at once.
     * </p>
     *
     * @param types config types to refresh. Null and duplicate entries are ignored.
     * @return future completing with immutable map of refreshed config payloads keyed by type.
     */
    public CompletableFuture<Map<AdminMessage.ConfigType, Config>> refreshConfigs(List<AdminMessage.ConfigType> types) {
        Set<AdminMessage.ConfigType> uniqueTypes = normalizeDistinct(types);
        CompletableFuture<Map<AdminMessage.ConfigType, Config>> chain =
                CompletableFuture.completedFuture(new EnumMap<>(AdminMessage.ConfigType.class));

        for (AdminMessage.ConfigType type : uniqueTypes) {
            chain = chain.thenCompose(refreshed -> refreshConfig(type).thenApply(config -> {
                refreshed.put(type, config);
                return refreshed;
            }));
        }

        return chain.thenApply(Map::copyOf);
    }

    /**
     * Refreshes a single channel slot.
     *
     * @param index channel slot index ({@code 0..7}).
     * @return future completing with parsed channel response.
     */
    public CompletableFuture<Channel> refreshChannel(int index) {
        validateChannelIndex(index);
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setGetChannelRequest(index + 1))
                .build();
        return executeAndParse(request, msg -> {
            if (!msg.hasGetChannelResponse()) {
                throw new IllegalStateException("Missing get_channel_response");
            }
            Channel channel = msg.getGetChannelResponse();
            snapshotApplier.applyChannel(channel);
            return channel;
        });
    }

    /**
     * Refreshes all channel slots sequentially.
     *
     * @return future completing with channels sorted by index.
     */
    public CompletableFuture<List<Channel>> refreshChannels() {
        return refreshChannels(IntStream.range(0, ProtocolConstraints.MAX_CHANNEL_SLOTS)
                .boxed()
                .toList());
    }

    /**
     * Refreshes only the provided channel slots sequentially.
     * <p>
     * This is the preferred API for page-scoped refreshes where callers already know the relevant slots.
     * Sequential ordering is preserved to avoid flooding slower radios.
     * </p>
     *
     * @param indexes channel slot indexes to refresh. Null/duplicate entries are ignored.
     *                If null or empty, all slots {@code 0..7} are refreshed.
     * @return future completing with refreshed channels sorted by index.
     */
    public CompletableFuture<List<Channel>> refreshChannels(List<Integer> indexes) {
        List<Integer> normalizedIndexes = normalizeChannelIndexes(indexes);
        CompletableFuture<List<Channel>> chain =
                CompletableFuture.completedFuture(new ArrayList<>(normalizedIndexes.size()));

        for (int index : normalizedIndexes) {
            chain = chain.thenCompose(refreshed -> refreshChannel(index).thenApply(channel -> {
                refreshed.add(channel);
                return refreshed;
            }));
        }

        return chain.thenApply(channels -> channels.stream()
                .sorted(Comparator.comparingInt(Channel::getIndex))
                .toList());
    }

    /**
     * Refreshes likely-active channel slots.
     * <p>
     * Slot {@code 0} is always included. Additional slots are chosen from cached snapshots that look active
     * (for example PRIMARY/SECONDARY roles or non-empty settings).
     * </p>
     *
     * @return future completing with refreshed likely-active channels sorted by index.
     */
    public CompletableFuture<List<Channel>> refreshLikelyActiveChannels() {
        return refreshChannels(getLikelyActiveChannelIndexes());
    }

    /**
     * Returns channel slot indexes that are likely active based on current cached channel snapshots.
     * <p>
     * This helper supports instant settings-page loads: render cached channels immediately, then reconcile
     * these indexes in the background.
     * </p>
     *
     * @return immutable ascending list of likely-active slot indexes.
     */
    public List<Integer> getLikelyActiveChannelIndexes() {
        Set<Integer> indexes = new LinkedHashSet<>();
        indexes.add(PRIMARY_CHANNEL_INDEX);
        radioModel.getChannels().values().stream()
                .filter(AdminService::isLikelyActiveChannel)
                .map(Channel::getIndex)
                .sorted()
                .forEach(indexes::add);
        return List.copyOf(indexes);
    }

    /**
     * Refreshes one module config block.
     *
     * @param type module config type to request.
     * @return future completing with parsed module config response.
     */
    public CompletableFuture<ModuleConfig> refreshModuleConfig(AdminMessage.ModuleConfigType type) {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setGetModuleConfigRequest(type))
                .build();
        return executeAndParse(request, msg -> {
            if (!msg.hasGetModuleConfigResponse()) {
                throw new IllegalStateException("Missing get_module_config_response");
            }
            ModuleConfig moduleConfig = msg.getGetModuleConfigResponse();
            snapshotApplier.applyModuleConfig(moduleConfig);
            return moduleConfig;
        });
    }

    /**
     * Refreshes an explicit set of module config types sequentially.
     * <p>
     * Sequential ordering avoids flooding slower radios with many settings queries at once.
     * </p>
     *
     * @param types module config types to refresh. Null and duplicate entries are ignored.
     * @return future completing with immutable map of refreshed module config payloads keyed by type.
     */
    public CompletableFuture<Map<AdminMessage.ModuleConfigType, ModuleConfig>> refreshModuleConfigs(
            List<AdminMessage.ModuleConfigType> types) {
        Set<AdminMessage.ModuleConfigType> uniqueTypes = normalizeDistinct(types);
        CompletableFuture<Map<AdminMessage.ModuleConfigType, ModuleConfig>> chain =
                CompletableFuture.completedFuture(new EnumMap<>(AdminMessage.ModuleConfigType.class));

        for (AdminMessage.ModuleConfigType type : uniqueTypes) {
            chain = chain.thenCompose(refreshed -> refreshModuleConfig(type).thenApply(config -> {
                refreshed.put(type, config);
                return refreshed;
            }));
        }

        return chain.thenApply(Map::copyOf);
    }

    /**
     * Refreshes all currently supported module config sections.
     *
     * @return future completing with immutable map of refreshed module config payloads keyed by type.
     */
    public CompletableFuture<Map<AdminMessage.ModuleConfigType, ModuleConfig>> refreshAllModuleConfigs() {
        return refreshModuleConfigs(SUPPORTED_MODULE_CONFIG_TYPES);
    }

    /**
     * Writes an updated channel to a slot.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param updatedChannel channel payload.
     * @param mode acceptance-only or verify-applied completion mode.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setChannel(int index, Channel updatedChannel, AdminWriteMode mode) {
        boolean verifyApplied = requiresVerification(mode);
        String operation = "setChannel(" + index + ")";
        return setChannelInternal(index, updatedChannel, verifyApplied, verifyApplied)
                .handle((applied, ex) -> toWriteResult(operation, verifyApplied, Boolean.TRUE.equals(applied), ex));
    }

    private CompletableFuture<Boolean> setChannelInternal(
            int index, Channel updatedChannel, boolean verifyApplied, boolean allowRetry) {
        validateChannelIndex(index);
        Channel requestedChannel = updatedChannel.toBuilder().setIndex(index).build();
        ProtocolConstraints.validateChannel(requestedChannel);
        CompletableFuture<Void> preflight = refreshMetadata().thenApply(metadata -> null);

        return preflight
                .thenCompose(unused -> hydrateChannelFromCurrentSlotIfSparse(index, requestedChannel))
                .thenCompose(channelWithIndex -> {
                    if (log.isDebugEnabled()) {
                        log.debug("[ADMIN] setChannel tx payload -> {}", describeChannelForLog(channelWithIndex));
                    }
                    AdminMessage request = snapshotApplier
                            .withSessionIfPresent(AdminMessage.newBuilder().setSetChannel(channelWithIndex))
                            .build();
                    // Channel write is a mutating operation: routing NONE is terminal success, routing errors fail
                    // fast.
                    return clientAccess
                            .executeAdminRequest(clientAccess.getSelfNodeId(), request, false)
                            .thenCompose(packet -> {
                                if (!verifyApplied) {
                                    snapshotApplier.applyChannel(channelWithIndex);
                                    log.info("[ADMIN] Channel {} request accepted by radio", index);
                                    return CompletableFuture.completedFuture(true);
                                }

                                return verifyChannelApplied(index, channelWithIndex);
                            })
                            .exceptionallyCompose(ex -> {
                                if (verifyApplied && isRoutingNoResponse(ex)) {
                                    log.warn(
                                            "[ADMIN] Channel {} set returned ROUTING NO_RESPONSE; verifying by read-back",
                                            index);
                                    return verifyChannelApplied(index, channelWithIndex);
                                }
                                if (allowRetry && verifyApplied) {
                                    log.warn(
                                            "[ADMIN] Channel {} set failed/verification failed; refreshing session key and retrying once: {}",
                                            index,
                                            ex.getMessage());
                                    return setChannelInternal(index, updatedChannel, verifyApplied, false);
                                }
                                return CompletableFuture.failedFuture(ex);
                            })
                            .thenCompose(applied -> {
                                if (Boolean.TRUE.equals(applied)) {
                                    return CompletableFuture.completedFuture(true);
                                }
                                if (verifyApplied && allowRetry) {
                                    log.warn(
                                            "[ADMIN] Channel {} write accepted but verification did not match; "
                                                    + "refreshing session key and retrying write once.",
                                            index);
                                    return setChannelInternal(index, updatedChannel, true, false);
                                }
                                return CompletableFuture.completedFuture(Boolean.TRUE.equals(applied));
                            });
                });
    }

    /**
     * Writes one config payload to the radio.
     * <p>
     * Verification is performed by re-requesting the affected config type(s) and comparing section payloads
     * when {@link AdminWriteMode#VERIFY_APPLIED} is requested.
     * </p>
     *
     * @param config config payload.
     * @param mode acceptance-only or verify-applied completion mode.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setConfig(Config config, AdminWriteMode mode) {
        boolean verifyApplied = requiresVerification(mode);
        return setConfigInternal(config, verifyApplied)
                .handle((applied, ex) -> toWriteResult("setConfig", verifyApplied, Boolean.TRUE.equals(applied), ex));
    }

    private CompletableFuture<Boolean> setConfigInternal(Config config, boolean verifyApplied) {
        Objects.requireNonNull(config, "config must not be null");

        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setSetConfig(config))
                .build();
        return clientAccess
                .executeAdminRequest(clientAccess.getSelfNodeId(), request, false)
                .thenCompose(packet -> {
                    snapshotApplier.applyConfig(config);
                    if (!verifyApplied) {
                        return CompletableFuture.completedFuture(true);
                    }

                    List<AdminMessage.ConfigType> impactedTypes = snapshotApplier.extractConfigTypes(config);
                    if (impactedTypes.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }

                    return verificationEngine.verifyWithPolicy("setConfig", () -> refreshConfigs(impactedTypes)
                            .thenApply(
                                    observed -> verificationEngine.isConfigApplied(config, observed, impactedTypes)));
                })
                .exceptionallyCompose(ex -> {
                    if (!verifyApplied || !isRoutingNoResponse(ex)) {
                        return CompletableFuture.failedFuture(ex);
                    }

                    log.warn("[ADMIN] setConfig returned ROUTING NO_RESPONSE; verifying by read-back");
                    List<AdminMessage.ConfigType> impactedTypes = snapshotApplier.extractConfigTypes(config);
                    if (impactedTypes.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return verificationEngine.verifyWithPolicy("setConfig", () -> refreshConfigs(impactedTypes)
                            .thenApply(
                                    observed -> verificationEngine.isConfigApplied(config, observed, impactedTypes)));
                });
    }

    /**
     * Writes one module config payload to the radio.
     *
     * @param moduleConfig module config payload.
     * @param mode acceptance-only or verify-applied completion mode.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setModuleConfig(ModuleConfig moduleConfig, AdminWriteMode mode) {
        boolean verifyApplied = requiresVerification(mode);
        return setModuleConfigInternal(moduleConfig, verifyApplied)
                .handle((applied, ex) ->
                        toWriteResult("setModuleConfig", verifyApplied, Boolean.TRUE.equals(applied), ex));
    }

    private CompletableFuture<Boolean> setModuleConfigInternal(ModuleConfig moduleConfig, boolean verifyApplied) {
        Objects.requireNonNull(moduleConfig, "moduleConfig must not be null");

        AdminMessage.ModuleConfigType moduleType = snapshotApplier.toModuleConfigType(moduleConfig);
        if (moduleType == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("moduleConfig must include a concrete payload variant"));
        }

        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setSetModuleConfig(moduleConfig))
                .build();
        return clientAccess
                .executeAdminRequest(clientAccess.getSelfNodeId(), request, false)
                .thenCompose(packet -> {
                    snapshotApplier.applyModuleConfig(moduleConfig);
                    if (!verifyApplied) {
                        return CompletableFuture.completedFuture(true);
                    }

                    return verificationEngine.verifyWithPolicy(
                            "setModuleConfig(" + moduleType + ")", () -> refreshModuleConfig(moduleType)
                                    .thenApply(observed ->
                                            verificationEngine.isModuleConfigApplied(moduleConfig, observed)));
                })
                .exceptionallyCompose(ex -> {
                    if (!verifyApplied || !isRoutingNoResponse(ex)) {
                        return CompletableFuture.failedFuture(ex);
                    }

                    log.warn(
                            "[ADMIN] setModuleConfig returned ROUTING NO_RESPONSE; verifying by read-back for {}",
                            moduleType);
                    return verificationEngine.verifyWithPolicy(
                            "setModuleConfig(" + moduleType + ")", () -> refreshModuleConfig(moduleType)
                                    .thenApply(observed ->
                                            verificationEngine.isModuleConfigApplied(moduleConfig, observed)));
                });
    }

    /**
     * Writes device UI config to the local radio.
     *
     * @param deviceUiConfig device UI config payload.
     * @param mode acceptance-only or verify-applied completion mode.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setDeviceUiConfig(DeviceUIConfig deviceUiConfig, AdminWriteMode mode) {
        Objects.requireNonNull(deviceUiConfig, "deviceUiConfig must not be null");
        return setConfig(Config.newBuilder().setDeviceUi(deviceUiConfig).build(), mode);
    }

    /**
     * Sets fixed position data on the local radio and enables fixed-position mode.
     *
     * @param position fixed position payload to send.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setFixedPosition(Position position) {
        Objects.requireNonNull(position, "position must not be null");
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setSetFixedPosition(position))
                .build();
        log.info("[ADMIN] Setting fixed position on the radio");
        return executeAcceptanceOnly(
                "setFixedPosition", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Sets fixed position data on the local radio using decimal degrees and altitude.
     *
     * @param latitude latitude in decimal degrees.
     * @param longitude longitude in decimal degrees.
     * @param altitude altitude in meters.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setFixedPosition(double latitude, double longitude, int altitude) {
        Position.Builder builder = Position.newBuilder();
        if (latitude != 0.0d) {
            builder.setLatitudeI((int) Math.round(latitude * MeshUtils.COORD_SCALE));
        }
        if (longitude != 0.0d) {
            builder.setLongitudeI((int) Math.round(longitude * MeshUtils.COORD_SCALE));
        }
        if (altitude != 0) {
            builder.setAltitude(altitude);
        }
        return setFixedPosition(builder.build());
    }

    /**
     * Clears fixed position data on the local radio and disables fixed-position mode.
     *
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> removeFixedPosition() {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setRemoveFixedPosition(true))
                .build();
        log.info("[ADMIN] Removing fixed position from the radio");
        return executeAcceptanceOnly(
                "removeFixedPosition", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Updates only the channel display name for a slot.
     * <p>
     * The method reuses cached channel state when available; otherwise it refreshes the slot first so unchanged
     * settings are preserved.
     * </p>
     *
     * @param index channel slot index ({@code 0..7}).
     * @param channelName new channel name.
     * @param mode acceptance-only or verify-applied completion mode.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setChannelName(int index, String channelName, AdminWriteMode mode) {
        Objects.requireNonNull(channelName, "channelName must not be null");
        return resolveChannelForUpdate(index).thenCompose(baseChannel -> {
            ChannelSettings settings =
                    baseChannel.getSettings().toBuilder().setName(channelName).build();
            Channel updated = baseChannel.toBuilder().setSettings(settings).build();
            return setChannel(index, updated, mode);
        });
    }

    /**
     * Updates only the channel PSK for a slot.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param psk channel PSK bytes.
     * @param mode acceptance-only or verify-applied completion mode.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setChannelPsk(int index, ByteString psk, AdminWriteMode mode) {
        ProtocolConstraints.validateChannelPsk(psk);
        return resolveChannelForUpdate(index).thenCompose(baseChannel -> {
            ChannelSettings settings =
                    baseChannel.getSettings().toBuilder().setPsk(psk).build();
            Channel updated = baseChannel.toBuilder().setSettings(settings).build();
            return setChannel(index, updated, mode);
        });
    }

    /**
     * Updates only the channel PSK for a slot.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param psk channel PSK bytes.
     * @param mode acceptance-only or verify-applied completion mode.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setChannelPsk(int index, byte[] psk, AdminWriteMode mode) {
        Objects.requireNonNull(psk, "psk must not be null");
        return setChannelPsk(index, ByteString.copyFrom(psk), mode);
    }

    /**
     * Updates only the channel PSK from a UTF-8 password string.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param password UTF-8 password value to store as PSK bytes.
     * @param mode acceptance-only or verify-applied completion mode.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setChannelPassword(int index, String password, AdminWriteMode mode) {
        Objects.requireNonNull(password, "password must not be null");
        return setChannelPsk(index, ByteString.copyFrom(password, StandardCharsets.UTF_8), mode);
    }

    /**
     * Updates the primary channel PSK.
     *
     * @param psk channel PSK bytes.
     * @param mode acceptance-only or verify-applied completion mode.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setPrimaryChannelPsk(ByteString psk, AdminWriteMode mode) {
        return setChannelPsk(PRIMARY_CHANNEL_INDEX, psk, mode);
    }

    /**
     * Updates the primary channel PSK from a UTF-8 password string.
     *
     * @param password UTF-8 password value to store as PSK bytes.
     * @param mode acceptance-only or verify-applied completion mode.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setPrimaryChannelPassword(String password, AdminWriteMode mode) {
        return setChannelPassword(PRIMARY_CHANNEL_INDEX, password, mode);
    }

    /**
     * Updates node identity values.
     *
     * @param targetNodeId target node id.
     * @param longName new long name.
     * @param shortName new short name.
     * @param mode acceptance-only or verify-applied completion mode.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setOwner(
            int targetNodeId, String longName, String shortName, AdminWriteMode mode) {
        boolean verifyApplied = requiresVerification(mode);
        String operation = "setOwner(" + MeshUtils.formatId(targetNodeId) + ")";
        return setOwnerInternal(targetNodeId, longName, shortName, verifyApplied)
                .handle((applied, ex) -> toWriteResult(operation, verifyApplied, Boolean.TRUE.equals(applied), ex));
    }

    /**
     * Updates node identity values and optionally verifies they were applied.
     * <p>
     * Verification behavior:
     * </p>
     * <ul>
     * <li>For the local node, verification performs {@link #refreshOwner()} and compares returned names.</li>
     * <li>For remote nodes, verification waits for a node-info snapshot and compares names.</li>
     * </ul>
     *
     * @param targetNodeId target node id.
     * @param longName new long name.
     * @param shortName new short name.
     * @param verifyApplied when {@code true}, performs read-back verification before completing.
     * @return future completing with {@code true} when accepted and verification passed (if enabled).
     */
    private CompletableFuture<Boolean> setOwnerInternal(
            int targetNodeId, String longName, String shortName, boolean verifyApplied) {
        User updatedUser =
                User.newBuilder().setLongName(longName).setShortName(shortName).build();

        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setSetOwner(updatedUser))
                .build();

        log.info("[ADMIN] Requesting rename for !{} to {}", Integer.toHexString(targetNodeId), longName);
        return clientAccess
                .executeAdminRequest(targetNodeId, request, false)
                .thenCompose(packet -> {
                    snapshotApplier.applyOwner(updatedUser);
                    log.info("[ADMIN] Rename accepted by radio for !{}", Integer.toHexString(targetNodeId));
                    if (!verifyApplied) {
                        return CompletableFuture.completedFuture(true);
                    }
                    return verificationEngine.verifyWithPolicy(
                            "setOwner(" + MeshUtils.formatId(targetNodeId) + ")",
                            () -> verifyOwnerApplied(targetNodeId, longName, shortName));
                })
                .exceptionallyCompose(ex -> {
                    if (!verifyApplied || !isRoutingNoResponse(ex)) {
                        return CompletableFuture.failedFuture(ex);
                    }

                    log.warn(
                            "[ADMIN] setOwner returned ROUTING NO_RESPONSE for !{}; verifying by read-back",
                            Integer.toHexString(targetNodeId));
                    return verificationEngine.verifyWithPolicy(
                            "setOwner(" + MeshUtils.formatId(targetNodeId) + ")",
                            () -> verifyOwnerApplied(targetNodeId, longName, shortName));
                });
    }

    /**
     * Performs a full factory reset.
     *
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> factoryReset() {
        AdminMessage request = AdminMessage.newBuilder()
                .setFactoryResetConfig(0)
                .setFactoryResetDevice(0)
                .build();
        log.warn("[ADMIN] Sending Full Factory Reset command!");
        return executeAcceptanceOnly(
                "factoryReset", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Requests radio reboot.
     *
     * @param seconds reboot delay; {@code 0} is normalized to {@code 1}.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> reboot(int seconds) {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setRebootSeconds(seconds == 0 ? 1 : seconds))
                .build();

        log.info("[ADMIN] Requesting radio reboot in {} seconds...", seconds);
        return executeAcceptanceOnly(
                "reboot", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Removes one node from the radio's on-device node database.
     *
     * @param nodeId node id to remove from the radio node database.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> removeNodeByNum(int nodeId) {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setRemoveByNodenum(nodeId))
                .build();
        log.info("[ADMIN] Removing node {} from radio node database", MeshUtils.formatId(nodeId));
        return executeAcceptanceOnly(
                "removeNodeByNum", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Adds one shared contact to the radio's on-device node database.
     *
     * @param contact shared contact payload to add.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> addContact(SharedContact contact) {
        Objects.requireNonNull(contact, "contact must not be null");
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setAddContact(contact))
                .build();
        log.info("[ADMIN] Adding contact {} to radio node database", MeshUtils.formatId(contact.getNodeNum()));
        return executeAcceptanceOnly(
                "addContact", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Marks one node as favorite on the radio's on-device node database.
     *
     * @param nodeId node id to favorite.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setFavoriteNode(int nodeId) {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setSetFavoriteNode(nodeId))
                .build();
        log.info("[ADMIN] Marking node {} as favorite on the radio", MeshUtils.formatId(nodeId));
        return executeAcceptanceOnly(
                "setFavoriteNode", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Removes favorite status from one node on the radio's on-device node database.
     *
     * @param nodeId node id to un-favorite.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> removeFavoriteNode(int nodeId) {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setRemoveFavoriteNode(nodeId))
                .build();
        log.info("[ADMIN] Removing favorite flag for node {} on the radio", MeshUtils.formatId(nodeId));
        return executeAcceptanceOnly(
                "removeFavoriteNode", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Marks one node as ignored on the radio's on-device node database.
     *
     * @param nodeId node id to ignore.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setIgnoredNode(int nodeId) {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setSetIgnoredNode(nodeId))
                .build();
        log.info("[ADMIN] Marking node {} as ignored on the radio", MeshUtils.formatId(nodeId));
        return executeAcceptanceOnly(
                "setIgnoredNode", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Removes ignored status from one node on the radio's on-device node database.
     *
     * @param nodeId node id to un-ignore.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> removeIgnoredNode(int nodeId) {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setRemoveIgnoredNode(nodeId))
                .build();
        log.info("[ADMIN] Removing ignored flag for node {} on the radio", MeshUtils.formatId(nodeId));
        return executeAcceptanceOnly(
                "removeIgnoredNode", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Toggles muted state for one node on the radio's on-device node database.
     *
     * @param nodeId node id to toggle mute for.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> toggleMutedNode(int nodeId) {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setToggleMutedNode(nodeId))
                .build();
        log.info("[ADMIN] Toggling muted flag for node {} on the radio", MeshUtils.formatId(nodeId));
        return executeAcceptanceOnly(
                "toggleMutedNode", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Resets the radio's on-device node database.
     *
     * @param preserveFavorites when {@code true}, preserve favorite nodes through reset if firmware supports it.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> resetNodeDb(boolean preserveFavorites) {
        AdminMessage request = snapshotApplier
                .withSessionIfPresent(AdminMessage.newBuilder().setNodedbReset(preserveFavorites))
                .build();
        log.warn("[ADMIN] Requesting radio node database reset (preserveFavorites={})", preserveFavorites);
        return executeAcceptanceOnly(
                "resetNodeDb", clientAccess.executeAdminRequest(clientAccess.getSelfNodeId(), request, false));
    }

    /**
     * Internal pipeline hook that applies decoded {@code ADMIN_APP} payloads to the cached radio model.
     * <p>
     * This method is used by built-in handlers and startup/correlation flows. Application code should
     * normally use refresh APIs and snapshot getters instead of calling ingestion methods directly.
     * </p>
     *
     * @param msg decoded admin message.
     */
    public void ingestAdminMessage(AdminMessage msg) {
        snapshotApplier.ingestAdminMessage(msg);
    }

    /**
     * Returns a cached config snapshot for one type.
     *
     * @param type config type key.
     * @return optional cached config payload.
     */
    public Optional<Config> getConfigSnapshot(AdminMessage.ConfigType type) {
        return radioModel.getConfig(type);
    }

    /**
     * Returns all cached config snapshots keyed by config type.
     *
     * @return immutable config snapshot map.
     */
    public Map<AdminMessage.ConfigType, Config> getConfigSnapshots() {
        return radioModel.getConfigs();
    }

    /**
     * Returns a cached module config snapshot for one type.
     *
     * @param type module config type key.
     * @return optional cached module config payload.
     */
    public Optional<ModuleConfig> getModuleConfigSnapshot(AdminMessage.ModuleConfigType type) {
        return radioModel.getModuleConfig(type);
    }

    /**
     * Returns all cached module config snapshots keyed by module config type.
     *
     * @return immutable module config snapshot map.
     */
    public Map<AdminMessage.ModuleConfigType, ModuleConfig> getModuleConfigSnapshots() {
        return radioModel.getModuleConfigs();
    }

    /**
     * Returns cached channels sorted by channel index.
     *
     * @return immutable channel list sorted ascending by index.
     */
    public List<Channel> getChannelSnapshots() {
        return radioModel.getChannels().values().stream()
                .sorted(Comparator.comparingInt(Channel::getIndex))
                .toList();
    }

    /**
     * Returns one cached channel snapshot by index.
     *
     * @param index channel slot index ({@code 0..7}).
     * @return optional cached channel payload.
     */
    public Optional<Channel> getChannelSnapshot(int index) {
        return radioModel.getChannel(index);
    }

    /**
     * Internal pipeline hook that applies top-level startup snapshot variants from {@link FromRadio}.
     * <p>
     * This is intended for the client's startup-sync path, not for normal application calls.
     * </p>
     *
     * @param message startup snapshot message.
     */
    public void ingestStartupSnapshot(FromRadio message) {
        snapshotApplier.ingestStartupSnapshot(message);
    }

    /**
     * Internal pipeline hook that applies local identity from {@code my_info}.
     *
     * @param nodeId local node id.
     */
    public void ingestMyInfo(int nodeId) {
        snapshotApplier.ingestMyInfo(nodeId);
    }

    /**
     * Internal pipeline hook that applies owner/node identity from a node-info payload.
     *
     * @param info node info payload.
     */
    public void ingestNodeInfo(NodeInfo info) {
        snapshotApplier.ingestNodeInfo(info);
    }

    /**
     * Verifies owner/name write visibility using the best available read-back path.
     *
     * @param targetNodeId target node id.
     * @param expectedLongName expected long name.
     * @param expectedShortName expected short name.
     * @return future completing with {@code true} when read-back values match.
     */
    private CompletableFuture<Boolean> verifyOwnerApplied(
            int targetNodeId, String expectedLongName, String expectedShortName) {
        if (targetNodeId == clientAccess.getSelfNodeId()) {
            return refreshOwner()
                    .thenApply(owner -> owner != null
                            && Objects.equals(expectedLongName, owner.getLongName())
                            && Objects.equals(expectedShortName, owner.getShortName()));
        }

        return clientAccess
                .requestNodeInfoAwaitPayloadOrSnapshot(targetNodeId, OWNER_VERIFY_TIMEOUT)
                .thenApply(node -> node != null
                        && Objects.equals(expectedLongName, node.getLongName())
                        && Objects.equals(expectedShortName, node.getShortName()))
                .exceptionally(ex -> false);
    }

    /**
     * Executes an admin request and maps the correlated admin response payload.
     *
     * @param request outbound admin request.
     * @param extractor function mapping parsed admin payload to target result type.
     * @return future completing with mapped response value.
     */
    private <T> CompletableFuture<T> executeAndParse(AdminMessage request, Function<AdminMessage, T> extractor) {
        // Read operations must wait for correlated ADMIN_APP payloads, not just ROUTING NONE status.
        return clientAccess
                .executeAdminRequest(clientAccess.getSelfNodeId(), request, true)
                .thenApply(this::parseAdminMessage)
                .thenApply(msg -> {
                    snapshotApplier.updateSessionKey(msg);
                    return extractor.apply(msg);
                });
    }

    /**
     * Parses an AdminMessage payload from a correlated mesh packet.
     *
     * @param packet decoded mesh packet.
     * @return parsed admin payload.
     */
    private AdminMessage parseAdminMessage(MeshPacket packet) {
        try {
            return AdminMessage.parseFrom(packet.getDecoded().getPayload());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse admin payload from response packet", ex);
        }
    }

    /**
     * Validates channel slot index bounds for admin channel operations.
     *
     * @param index channel slot index.
     */
    private void validateChannelIndex(int index) {
        ProtocolConstraints.validateChannelIndex(index);
    }

    /**
     * Verifies channel role/settings by read-back.
     *
     * @param index channel index.
     * @param expected expected role/settings payload.
     * @return future completing with {@code true} when expected channel state is observed.
     */
    private CompletableFuture<Boolean> verifyChannelApplied(int index, Channel expected) {
        return verificationEngine.verifyWithPolicy(
                "setChannel(" + index + ")", () -> refreshChannel(index).thenApply(appliedChannel -> {
                    if (log.isDebugEnabled()) {
                        log.debug("[ADMIN] setChannel verify expected -> {}", describeChannelForLog(expected));
                        log.debug("[ADMIN] setChannel verify observed -> {}", describeChannelForLog(appliedChannel));
                    }
                    boolean applied = verificationEngine.isChannelApplied(expected, appliedChannel);

                    if (applied) {
                        log.info("[ADMIN] Channel {} updated successfully", index);
                    } else {
                        log.debug(
                                "[ADMIN] Channel {} update not yet reflected (requested role={}, observed role={})",
                                index,
                                expected.getRole(),
                                appliedChannel.getRole());
                    }
                    return applied;
                }));
    }

    /**
     * Back-fills sparse channel settings from the current slot snapshot before write.
     * <p>
     * Some firmware builds ignore partial channel settings writes (for example, name-only payloads that omit PSK).
     * When a sparse payload is detected, this method merges the current slot settings with the caller-provided
     * settings so unchanged values are preserved in the outgoing write.
     * </p>
     *
     * @param index channel slot index.
     * @param requested caller-provided channel payload with normalized index.
     * @return future completing with a write-safe channel payload.
     */
    private CompletableFuture<Channel> hydrateChannelFromCurrentSlotIfSparse(int index, Channel requested) {
        if (!isLikelySparseChannelSettings(requested.getSettings())) {
            return CompletableFuture.completedFuture(requested);
        }

        return getChannelSnapshot(index)
                .map(current -> {
                    ChannelSettings merged = current.getSettings().toBuilder()
                            .mergeFrom(requested.getSettings())
                            .build();
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "[ADMIN] setChannel sparse payload detected for slot {}. "
                                        + "Hydrating with cached slot settings before write.",
                                index);
                        log.debug("[ADMIN] setChannel cached base -> {}", describeChannelForLog(current));
                        log.debug("[ADMIN] setChannel requested sparse -> {}", describeChannelForLog(requested));
                        log.debug(
                                "[ADMIN] setChannel hydrated payload -> {}",
                                describeChannelForLog(requested.toBuilder()
                                        .setSettings(merged)
                                        .build()));
                    }
                    return CompletableFuture.completedFuture(
                            requested.toBuilder().setSettings(merged).build());
                })
                .orElseGet(() -> {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "[ADMIN] setChannel sparse payload detected for slot {} but no cached slot found. "
                                        + "Sending payload as-is.",
                                index);
                    }
                    return CompletableFuture.completedFuture(requested);
                });
    }

    /**
     * Heuristic for settings payloads that are likely patch-style writes rather than complete channel state.
     * <p>
     * Current trigger: name was provided but PSK is empty. This matches common UI edit flows where only the
     * display name is edited, and we need to preserve existing channel cryptographic fields.
     * </p>
     *
     * @param settings outgoing channel settings payload.
     * @return {@code true} when payload should be hydrated from current slot state before write.
     */
    private static boolean isLikelySparseChannelSettings(ChannelSettings settings) {
        return settings != null
                && !settings.getName().isEmpty()
                && settings.getPsk().isEmpty();
    }

    /**
     * Builds a concise, readable channel summary for debug logs without dumping raw key material.
     *
     * @param channel channel payload.
     * @return formatted summary string.
     */
    private static String describeChannelForLog(Channel channel) {
        if (channel == null) {
            return "channel=null";
        }
        ChannelSettings s = channel.getSettings();
        int pskLen = s.getPsk().size();
        return "slot=" + channel.getIndex()
                + ", role=" + channel.getRole()
                + ", name=\"" + s.getName() + "\""
                + ", psk_len=" + pskLen
                + ", uplink=" + s.getUplinkEnabled()
                + ", downlink=" + s.getDownlinkEnabled()
                + ", module_settings_present=" + s.hasModuleSettings();
    }

    /**
     * Resolves a channel payload to use as the base for patch-style channel updates.
     *
     * @param index channel slot index ({@code 0..7}).
     * @return future completing with cached or freshly read channel payload.
     */
    private CompletableFuture<Channel> resolveChannelForUpdate(int index) {
        validateChannelIndex(index);
        return getChannelSnapshot(index).map(CompletableFuture::completedFuture).orElseGet(() -> refreshChannel(index));
    }

    /**
     * Normalizes an input list by removing nulls and preserving first-seen order.
     *
     * @param input raw type list.
     * @param <T> enum key type.
     * @return insertion-ordered distinct set.
     */
    private static <T> Set<T> normalizeDistinct(List<T> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptySet();
        }
        Set<T> distinct = new LinkedHashSet<>();
        input.stream().filter(Objects::nonNull).forEach(distinct::add);
        return distinct;
    }

    /**
     * Normalizes requested channel indexes by dropping nulls, validating range, removing duplicates,
     * and preserving first-seen order.
     *
     * @param indexes requested indexes.
     * @return normalized immutable index list. Defaults to all slots when input is null/empty.
     * If input is non-empty but all entries are null, returns an empty list.
     */
    private List<Integer> normalizeChannelIndexes(List<Integer> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            return IntStream.range(0, ProtocolConstraints.MAX_CHANNEL_SLOTS)
                    .boxed()
                    .toList();
        }
        Set<Integer> distinct = new LinkedHashSet<>();
        indexes.stream().filter(Objects::nonNull).forEach(index -> {
            validateChannelIndex(index);
            distinct.add(index);
        });
        if (distinct.isEmpty()) {
            return List.of();
        }
        return List.copyOf(distinct);
    }

    /**
     * Heuristic for whether a channel slot should be treated as active for targeted refresh.
     *
     * @param channel cached channel snapshot.
     * @return {@code true} when slot likely represents an active channel entry.
     */
    private static boolean isLikelyActiveChannel(Channel channel) {
        if (channel == null) {
            return false;
        }
        if (channel.getRole() == Channel.Role.PRIMARY || channel.getRole() == Channel.Role.SECONDARY) {
            return true;
        }
        ChannelSettings settings = channel.getSettings();
        return !settings.getName().isEmpty()
                || !settings.getPsk().isEmpty()
                || settings.getUplinkEnabled()
                || settings.getDownlinkEnabled()
                || settings.hasModuleSettings();
    }

    /**
     * Maps a write mode into the legacy verify-applied flag used internally by the orchestration helpers.
     *
     * @param mode caller-selected write mode.
     * @return {@code true} when verification should be performed before completing.
     */
    private static boolean requiresVerification(AdminWriteMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        return mode == AdminWriteMode.VERIFY_APPLIED;
    }

    /**
     * Converts an acceptance-only admin request into the  structured write result shape.
     *
     * @param operation operation identifier for result payloads.
     * @param requestFuture outbound request future.
     * @return future completing with ACCEPTED/REJECTED/FAILED/TIMEOUT status.
     */
    private CompletableFuture<AdminWriteResult> executeAcceptanceOnly(
            String operation, CompletableFuture<MeshPacket> requestFuture) {
        return requestFuture.handle((packet, ex) -> toWriteResult(operation, false, packet != null, ex));
    }

    /**
     * Maps boolean/exception write outcomes into admin write statuses.
     *
     * @param operation operation identifier.
     * @param verificationRequested whether caller requested read-back verification.
     * @param applied boolean applied flag from write path.
     * @param error optional failure throwable from write path.
     * @return canonical write result payload.
     */
    private AdminWriteResult toWriteResult(
            String operation, boolean verificationRequested, boolean applied, Throwable error) {
        if (error == null) {
            if (verificationRequested) {
                if (applied) {
                    return new AdminWriteResult(
                            AdminWriteStatus.VERIFIED_APPLIED, operation, "Write accepted and verified by read-back.");
                }
                return new AdminWriteResult(
                        AdminWriteStatus.VERIFICATION_FAILED,
                        operation,
                        "Write accepted but verification read-back did not match requested state.");
            }

            if (applied) {
                return new AdminWriteResult(AdminWriteStatus.ACCEPTED, operation, "Write accepted by radio.");
            }

            return new AdminWriteResult(AdminWriteStatus.FAILED, operation, "Write did not report acceptance.");
        }

        Throwable root = unwrap(error);
        if (root instanceof TimeoutException) {
            return new AdminWriteResult(
                    AdminWriteStatus.TIMEOUT,
                    operation,
                    root.getMessage() == null ? "Write timed out." : root.getMessage());
        }

        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        if (message.contains("Routing rejected request")) {
            return new AdminWriteResult(AdminWriteStatus.REJECTED, operation, message);
        }

        return new AdminWriteResult(AdminWriteStatus.FAILED, operation, message);
    }

    /**
     * Unwraps common async wrapper exceptions to expose the root cause.
     *
     * @param error wrapped error from completion stages.
     * @return root throwable.
     */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Returns whether the request failed with a routing-level NO_RESPONSE status.
     * <p>
     * Some firmwares can apply writes while still reporting routing NO_RESPONSE, so write paths that requested
     * verification should perform read-back before declaring failure.
     * </p>
     *
     * @param error completion error from request pipeline.
     * @return {@code true} when error payload indicates routing NO_RESPONSE.
     */
    private static boolean isRoutingNoResponse(Throwable error) {
        Throwable root = unwrap(error);
        String message = root.getMessage();
        return message != null
                && message.contains("Routing rejected request")
                && message.toUpperCase().contains("NO_RESPONSE");
    }
}
