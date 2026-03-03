package com.meshmkt.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.ProtocolConstraints;
import com.meshmkt.meshtastic.client.model.RadioModel;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.AdminProtos.AdminMessage.ConfigType;
import org.meshtastic.proto.AdminProtos.AdminMessage.ModuleConfigType;
import org.meshtastic.proto.AdminProtos.OTAMode;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ChannelProtos.ChannelSettings;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.DeviceUIProtos.DeviceUIConfig;
import org.meshtastic.proto.MeshProtos.DeviceMetadata;
import org.meshtastic.proto.MeshProtos.FromRadio;
import org.meshtastic.proto.MeshProtos.MeshPacket;
import org.meshtastic.proto.MeshProtos.NodeInfo;
import org.meshtastic.proto.MeshProtos.User;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Admin/config facade for Meshtastic settings operations.
 * <p>
 * Public API is intentionally compact:
 * </p>
 * <ul>
 * <li>Immediate model reads via {@link #getSnapshot()}.</li>
 * <li>Explicit async refresh operations that return typed futures.</li>
 * <li>Write operations for owner/config/channel plus lifecycle commands.</li>
 * </ul>
 * <p>
 * All incoming radio data is funneled through ingest methods and applied by a single set of private
 * {@code apply...} mutators to keep model updates consistent.
 * </p>
 * <p>
 * Write-operation completion semantics:
 * </p>
 * <ul>
 * <li>{@link #setChannel(int, Channel)}: accepted + read-back verified (default behavior).</li>
 * <li>{@link #setChannel(int, Channel, boolean)}: caller chooses accepted-only vs verified-applied.</li>
 * <li>{@link #setConfig(Config)}: accepted-only.</li>
 * <li>{@link #setConfig(Config, boolean)} and {@link #setConfigAndVerify(Config)}: caller chooses or forces verification.</li>
 * <li>{@link #setModuleConfig(ModuleConfig)}: accepted-only.</li>
 * <li>{@link #setModuleConfig(ModuleConfig, boolean)} and {@link #setModuleConfigAndVerify(ModuleConfig)}:
 * caller chooses or forces verification.</li>
 * <li>{@link #setOwner(int, String, String)}: accepted-only.</li>
 * <li>{@link #setOwner(int, String, String, boolean)} and {@link #setOwnerAndVerify(int, String, String)}:
 * accepted-only or verified-applied.</li>
 * </ul>
 */
@Slf4j
public class AdminService {
    private static final Duration OWNER_VERIFY_TIMEOUT = Duration.ofSeconds(10);
    private static final int PRIMARY_CHANNEL_INDEX = 0;
    private static final List<ConfigType> CORE_CONFIG_TYPES = List.of(
            ConfigType.LORA_CONFIG,
            ConfigType.DEVICE_CONFIG,
            ConfigType.DISPLAY_CONFIG,
            ConfigType.NETWORK_CONFIG
    );
    private static final List<ModuleConfigType> SUPPORTED_MODULE_CONFIG_TYPES = List.of(
            ModuleConfigType.MQTT_CONFIG,
            ModuleConfigType.SERIAL_CONFIG,
            ModuleConfigType.EXTNOTIF_CONFIG,
            ModuleConfigType.STOREFORWARD_CONFIG,
            ModuleConfigType.RANGETEST_CONFIG,
            ModuleConfigType.TELEMETRY_CONFIG,
            ModuleConfigType.CANNEDMSG_CONFIG,
            ModuleConfigType.AUDIO_CONFIG,
            ModuleConfigType.REMOTEHARDWARE_CONFIG,
            ModuleConfigType.NEIGHBORINFO_CONFIG,
            ModuleConfigType.AMBIENTLIGHTING_CONFIG,
            ModuleConfigType.DETECTIONSENSOR_CONFIG,
            ModuleConfigType.PAXCOUNTER_CONFIG,
            ModuleConfigType.STATUSMESSAGE_CONFIG
    );

    private final AdminRequestGateway gateway;
    private final RadioModel radioModel = new RadioModel();
    private ByteString lastSessionPasskey = ByteString.EMPTY;

    /**
     * Creates a new admin service bound to one request gateway instance.
     *
     * @param gateway admin request gateway runtime.
     */
    public AdminService(AdminRequestGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * Returns the live in-memory radio model snapshot.
     *
     * @return current radio model cache.
     */
    public RadioModel getSnapshot() {
        return radioModel;
    }

    /**
     * Refreshes core local-node state used by most settings screens.
     * <p>
     * Includes metadata, owner, and key config blocks (LoRa, Device, Display, Network).
     * </p>
     *
     * @return future completing with the updated model.
     */
    public CompletableFuture<RadioModel> refreshCore() {
        return refreshMetadata()
                .thenCompose(metadata -> refreshOwner())
                .thenCompose(owner -> refreshCoreConfigs())
                .thenApply(configs -> radioModel);
    }

    /**
     * Refreshes settings usually needed by a channels/security page.
     * <p>
     * Includes likely-active channel slots, security config, and MQTT module config.
     * This avoids blocking settings UIs on a full 8-slot sweep for radios that only use a subset of slots.
     * </p>
     *
     * @return future completing with the updated model.
     */
    public CompletableFuture<RadioModel> refreshChannelsAndSecurity() {
        return refreshLikelyActiveChannels()
                .thenCompose(channels -> refreshSecurityConfig())
                .thenCompose(config -> refreshMqttConfig())
                .thenApply(moduleConfig -> radioModel);
    }

    /**
     * Refreshes settings needed by channels/security pages using a full all-slot channel sweep.
     * <p>
     * This is the explicit deep-refresh variant when callers must force refresh of all {@code 0..7} slots.
     * </p>
     *
     * @return future completing with the updated model.
     */
    public CompletableFuture<RadioModel> refreshAllChannelsAndSecurity() {
        return refreshChannels()
                .thenCompose(channels -> refreshSecurityConfig())
                .thenCompose(config -> refreshMqttConfig())
                .thenApply(moduleConfig -> radioModel);
    }

    /**
     * Refreshes all supported module config sections.
     *
     * @return future completing with the updated model.
     */
    public CompletableFuture<RadioModel> refreshModules() {
        return refreshAllModuleConfigs().thenApply(moduleConfigs -> radioModel);
    }

    /**
     * Refreshes the core config group used by most settings UIs.
     *
     * @return future completing with immutable map of refreshed config payloads keyed by type.
     */
    public CompletableFuture<Map<ConfigType, Config>> refreshCoreConfigs() {
        return refreshConfigs(CORE_CONFIG_TYPES);
    }

    /**
     * Refreshes device metadata from the radio.
     *
     * @return future completing with parsed metadata.
     */
    public CompletableFuture<DeviceMetadata> refreshMetadata() {
        AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setGetDeviceMetadataRequest(true)).build();
        return executeAndParse(request, msg -> {
            if (!msg.hasGetDeviceMetadataResponse()) {
                throw new IllegalStateException("Missing get_device_metadata_response");
            }
            DeviceMetadata metadata = msg.getGetDeviceMetadataResponse();
            applyMetadata(metadata);
            return metadata;
        });
    }

    /**
     * Refreshes owner/user data from the radio.
     *
     * @return future completing with parsed owner response.
     */
    public CompletableFuture<User> refreshOwner() {
        AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setGetOwnerRequest(true)).build();
        return executeAndParse(request, msg -> {
            if (!msg.hasGetOwnerResponse()) {
                throw new IllegalStateException("Missing get_owner_response");
            }
            User owner = msg.getGetOwnerResponse();
            applyOwner(owner);
            return owner;
        });
    }

    /**
     * Refreshes a specific config block from the radio.
     * <p>
     * For {@link ConfigType#SESSIONKEY_CONFIG}, metadata is refreshed first to match behavior seen in official clients.
     * </p>
     *
     * @param type config type to request.
     * @return future completing with parsed config response.
     */
    public CompletableFuture<Config> refreshConfig(ConfigType type) {
        CompletableFuture<Void> preflight = type == ConfigType.SESSIONKEY_CONFIG
                ? refreshMetadata().thenApply(metadata -> null)
                : CompletableFuture.completedFuture(null);

        return preflight.thenCompose(unused -> {
            AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setGetConfigRequest(type)).build();
            return executeAndParse(request, msg -> {
                if (!msg.hasGetConfigResponse()) {
                    throw new IllegalStateException("Missing get_config_response");
                }
                Config config = msg.getGetConfigResponse();
                applyConfig(config);
                return config;
            });
        });
    }

    /**
     * Refreshes the security config section.
     *
     * @return future completing with parsed security config payload.
     */
    public CompletableFuture<Config> refreshSecurityConfig() {
        return refreshConfig(ConfigType.SECURITY_CONFIG);
    }

    /**
     * Refreshes the device config section.
     *
     * @return future completing with parsed device config section.
     */
    public CompletableFuture<Config.DeviceConfig> refreshDeviceConfig() {
        return refreshConfig(ConfigType.DEVICE_CONFIG).thenApply(Config::getDevice);
    }

    /**
     * Refreshes the position config section.
     *
     * @return future completing with parsed position config section.
     */
    public CompletableFuture<Config.PositionConfig> refreshPositionConfig() {
        return refreshConfig(ConfigType.POSITION_CONFIG).thenApply(Config::getPosition);
    }

    /**
     * Refreshes the power config section.
     *
     * @return future completing with parsed power config section.
     */
    public CompletableFuture<Config.PowerConfig> refreshPowerConfig() {
        return refreshConfig(ConfigType.POWER_CONFIG).thenApply(Config::getPower);
    }

    /**
     * Refreshes the network config section.
     *
     * @return future completing with parsed network config section.
     */
    public CompletableFuture<Config.NetworkConfig> refreshNetworkConfig() {
        return refreshConfig(ConfigType.NETWORK_CONFIG).thenApply(Config::getNetwork);
    }

    /**
     * Refreshes the display config section.
     *
     * @return future completing with parsed display config section.
     */
    public CompletableFuture<Config.DisplayConfig> refreshDisplayConfig() {
        return refreshConfig(ConfigType.DISPLAY_CONFIG).thenApply(Config::getDisplay);
    }

    /**
     * Refreshes the LoRa config section.
     *
     * @return future completing with parsed LoRa config section.
     */
    public CompletableFuture<Config.LoRaConfig> refreshLoraConfig() {
        return refreshConfig(ConfigType.LORA_CONFIG).thenApply(Config::getLora);
    }

    /**
     * Refreshes the Bluetooth config section.
     *
     * @return future completing with parsed Bluetooth config section.
     */
    public CompletableFuture<Config.BluetoothConfig> refreshBluetoothConfig() {
        return refreshConfig(ConfigType.BLUETOOTH_CONFIG).thenApply(Config::getBluetooth);
    }

    /**
     * Refreshes the session key config section.
     *
     * @return future completing with parsed session key config section.
     */
    public CompletableFuture<Config.SessionkeyConfig> refreshSessionKeyConfig() {
        return refreshConfig(ConfigType.SESSIONKEY_CONFIG).thenApply(Config::getSessionkey);
    }

    /**
     * Refreshes the device UI config section.
     *
     * @return future completing with parsed device UI config section.
     */
    public CompletableFuture<DeviceUIConfig> refreshDeviceUiConfig() {
        return refreshConfig(ConfigType.DEVICEUI_CONFIG).thenApply(Config::getDeviceUi);
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
    public CompletableFuture<Map<ConfigType, Config>> refreshConfigs(List<ConfigType> types) {
        Set<ConfigType> uniqueTypes = normalizeDistinct(types);
        CompletableFuture<Map<ConfigType, Config>> chain
                = CompletableFuture.completedFuture(new EnumMap<>(ConfigType.class));

        for (ConfigType type : uniqueTypes) {
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
        AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setGetChannelRequest(index + 1)).build();
        return executeAndParse(request, msg -> {
            if (!msg.hasGetChannelResponse()) {
                throw new IllegalStateException("Missing get_channel_response");
            }
            Channel channel = msg.getGetChannelResponse();
            applyChannel(channel);
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
        CompletableFuture<List<Channel>> chain
                = CompletableFuture.completedFuture(new ArrayList<>(normalizedIndexes.size()));

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
    public CompletableFuture<ModuleConfig> refreshModuleConfig(ModuleConfigType type) {
        AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setGetModuleConfigRequest(type)).build();
        return executeAndParse(request, msg -> {
            if (!msg.hasGetModuleConfigResponse()) {
                throw new IllegalStateException("Missing get_module_config_response");
            }
            ModuleConfig moduleConfig = msg.getGetModuleConfigResponse();
            applyModuleConfig(moduleConfig);
            return moduleConfig;
        });
    }

    /**
     * Refreshes MQTT module settings.
     *
     * @return future completing with parsed MQTT module config payload.
     */
    public CompletableFuture<ModuleConfig> refreshMqttConfig() {
        return refreshModuleConfig(ModuleConfigType.MQTT_CONFIG);
    }

    /**
     * Refreshes serial module settings.
     *
     * @return future completing with parsed serial module settings.
     */
    public CompletableFuture<ModuleConfig.SerialConfig> refreshSerialModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.SERIAL_CONFIG).thenApply(ModuleConfig::getSerial);
    }

    /**
     * Refreshes external-notification module settings.
     *
     * @return future completing with parsed external-notification module settings.
     */
    public CompletableFuture<ModuleConfig.ExternalNotificationConfig> refreshExternalNotificationModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.EXTNOTIF_CONFIG).thenApply(ModuleConfig::getExternalNotification);
    }

    /**
     * Refreshes store-forward module settings.
     *
     * @return future completing with parsed store-forward module settings.
     */
    public CompletableFuture<ModuleConfig.StoreForwardConfig> refreshStoreForwardModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.STOREFORWARD_CONFIG).thenApply(ModuleConfig::getStoreForward);
    }

    /**
     * Refreshes range-test module settings.
     *
     * @return future completing with parsed range-test module settings.
     */
    public CompletableFuture<ModuleConfig.RangeTestConfig> refreshRangeTestModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.RANGETEST_CONFIG).thenApply(ModuleConfig::getRangeTest);
    }

    /**
     * Refreshes telemetry module settings.
     *
     * @return future completing with parsed telemetry module settings.
     */
    public CompletableFuture<ModuleConfig.TelemetryConfig> refreshTelemetryModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.TELEMETRY_CONFIG).thenApply(ModuleConfig::getTelemetry);
    }

    /**
     * Refreshes canned-message module settings.
     *
     * @return future completing with parsed canned-message module settings.
     */
    public CompletableFuture<ModuleConfig.CannedMessageConfig> refreshCannedMessageModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.CANNEDMSG_CONFIG).thenApply(ModuleConfig::getCannedMessage);
    }

    /**
     * Refreshes audio module settings.
     *
     * @return future completing with parsed audio module settings.
     */
    public CompletableFuture<ModuleConfig.AudioConfig> refreshAudioModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.AUDIO_CONFIG).thenApply(ModuleConfig::getAudio);
    }

    /**
     * Refreshes remote-hardware module settings.
     *
     * @return future completing with parsed remote-hardware module settings.
     */
    public CompletableFuture<ModuleConfig.RemoteHardwareConfig> refreshRemoteHardwareModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.REMOTEHARDWARE_CONFIG).thenApply(ModuleConfig::getRemoteHardware);
    }

    /**
     * Refreshes neighbor-info module settings.
     *
     * @return future completing with parsed neighbor-info module settings.
     */
    public CompletableFuture<ModuleConfig.NeighborInfoConfig> refreshNeighborInfoModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.NEIGHBORINFO_CONFIG).thenApply(ModuleConfig::getNeighborInfo);
    }

    /**
     * Refreshes ambient-lighting module settings.
     *
     * @return future completing with parsed ambient-lighting module settings.
     */
    public CompletableFuture<ModuleConfig.AmbientLightingConfig> refreshAmbientLightingModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.AMBIENTLIGHTING_CONFIG).thenApply(ModuleConfig::getAmbientLighting);
    }

    /**
     * Refreshes detection-sensor module settings.
     *
     * @return future completing with parsed detection-sensor module settings.
     */
    public CompletableFuture<ModuleConfig.DetectionSensorConfig> refreshDetectionSensorModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.DETECTIONSENSOR_CONFIG).thenApply(ModuleConfig::getDetectionSensor);
    }

    /**
     * Refreshes paxcounter module settings.
     *
     * @return future completing with parsed paxcounter module settings.
     */
    public CompletableFuture<ModuleConfig.PaxcounterConfig> refreshPaxcounterModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.PAXCOUNTER_CONFIG).thenApply(ModuleConfig::getPaxcounter);
    }

    /**
     * Refreshes status-message module settings.
     *
     * @return future completing with parsed status-message module settings.
     */
    public CompletableFuture<ModuleConfig.StatusMessageConfig> refreshStatusMessageModuleConfig() {
        return refreshModuleConfig(ModuleConfigType.STATUSMESSAGE_CONFIG).thenApply(ModuleConfig::getStatusmessage);
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
    public CompletableFuture<Map<ModuleConfigType, ModuleConfig>> refreshModuleConfigs(List<ModuleConfigType> types) {
        Set<ModuleConfigType> uniqueTypes = normalizeDistinct(types);
        CompletableFuture<Map<ModuleConfigType, ModuleConfig>> chain
                = CompletableFuture.completedFuture(new EnumMap<>(ModuleConfigType.class));

        for (ModuleConfigType type : uniqueTypes) {
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
    public CompletableFuture<Map<ModuleConfigType, ModuleConfig>> refreshAllModuleConfigs() {
        return refreshModuleConfigs(SUPPORTED_MODULE_CONFIG_TYPES);
    }

    /**
     * Writes an updated channel to a slot and verifies by read-back.
     * <p>
     * This method preserves the legacy/default behavior where channel writes are considered successful only when
     * read-back role/settings match the requested payload.
     * </p>
     *
     * @param index channel slot index ({@code 0..7}).
     * @param updatedChannel channel payload.
     * @return future completing with {@code true} when accepted and verified-applied.
     */
    public CompletableFuture<Boolean> setChannel(int index, Channel updatedChannel) {
        return setChannel(index, updatedChannel, true, true);
    }

    /**
     * Writes an updated channel to a slot and optionally verifies by read-back.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param updatedChannel channel payload.
     * @param verifyApplied when {@code true}, performs read-back verification.
     * @return future completing with {@code true} when accepted and verification passed (if enabled).
     */
    public CompletableFuture<Boolean> setChannel(int index, Channel updatedChannel, boolean verifyApplied) {
        return setChannel(index, updatedChannel, verifyApplied, verifyApplied);
    }

    /**
     * Writes an updated channel to a slot and returns structured completion status.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param updatedChannel channel payload.
     * @param verifyApplied when {@code true}, performs read-back verification.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setChannelResult(int index, Channel updatedChannel, boolean verifyApplied) {
        String operation = "setChannel(" + index + ")";
        return setChannel(index, updatedChannel, verifyApplied)
                .handle((applied, ex) -> toWriteResult(operation, verifyApplied, Boolean.TRUE.equals(applied), ex));
    }

    private CompletableFuture<Boolean> setChannel(int index,
                                                  Channel updatedChannel,
                                                  boolean verifyApplied,
                                                  boolean allowRetry) {
        validateChannelIndex(index);
        Channel requestedChannel = updatedChannel.toBuilder().setIndex(index).build();
        ProtocolConstraints.validateChannel(requestedChannel);
        CompletableFuture<Void> preflight = refreshMetadata().thenApply(metadata -> null);

        return preflight.thenCompose(unused -> hydrateChannelFromCurrentSlotIfSparse(index, requestedChannel))
                .thenCompose(channelWithIndex -> {
                    if (log.isDebugEnabled()) {
                        log.debug("[ADMIN] setChannel tx payload -> {}", describeChannelForLog(channelWithIndex));
                    }
                    AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setSetChannel(channelWithIndex)).build();
                    // Channel write is a mutating operation: routing NONE is terminal success, routing errors fail fast.
                    return gateway.executeAdminRequest(gateway.getSelfNodeId(), request, false)
                            .thenCompose(packet -> {
                                if (!verifyApplied) {
                                    applyChannel(channelWithIndex);
                                    log.info("[ADMIN] Channel {} request accepted by radio", index);
                                    return CompletableFuture.completedFuture(true);
                                }

                                return verifyChannelApplied(
                                        index,
                                        channelWithIndex,
                                        allowRetry,
                                        "Channel write not reflected on radio; retrying once");
                            })
                            .exceptionallyCompose(ex -> {
                                if (verifyApplied && isRoutingNoResponse(ex)) {
                                    log.warn("[ADMIN] Channel {} set returned ROUTING NO_RESPONSE; verifying by read-back", index);
                                    return verifyChannelApplied(
                                            index,
                                            channelWithIndex,
                                            allowRetry,
                                            "Channel write returned ROUTING NO_RESPONSE and read-back did not match; retrying once")
                                            .exceptionallyCompose(verifyEx -> {
                                                if (allowRetry) {
                                                    log.warn("[ADMIN] Channel {} verification after ROUTING NO_RESPONSE failed; " +
                                                                    "refreshing session key and retrying once: {}",
                                                            index, verifyEx.getMessage());
                                                    return setChannel(index, updatedChannel, verifyApplied, false);
                                                }
                                                return CompletableFuture.completedFuture(false);
                                            });
                                }
                                if (allowRetry) {
                                    log.warn("[ADMIN] Channel {} set failed/verification failed; refreshing session key and retrying once: {}",
                                            index, ex.getMessage());
                                    return setChannel(index, updatedChannel, verifyApplied, false);
                                }
                                return CompletableFuture.completedFuture(false);
                            });
                });
    }

    /**
     * Writes a config block to the radio.
     *
     * @param config config payload.
     * @return future completing when accepted by the radio.
     */
    public CompletableFuture<Void> setConfig(Config config) {
        return setConfig(config, false).thenAccept(applied -> {
        });
    }

    /**
     * Writes one config payload and forces read-back verification.
     *
     * @param config config payload.
     * @return future completing with {@code true} when accepted and verified-applied.
     */
    public CompletableFuture<Boolean> setConfigAndVerify(Config config) {
        return setConfig(config, true);
    }

    /**
     * Writes one config payload and returns structured completion status.
     *
     * @param config config payload.
     * @param verifyApplied when {@code true}, performs read-back verification before completing.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setConfigResult(Config config, boolean verifyApplied) {
        return setConfig(config, verifyApplied)
                .handle((applied, ex) -> toWriteResult("setConfig", verifyApplied, Boolean.TRUE.equals(applied), ex));
    }

    /**
     * Writes one config payload to the radio, optionally verifying by read-back.
     * <p>
     * Verification is performed by re-requesting the affected config type(s) and comparing section payloads.
     * </p>
     *
     * @param config config payload.
     * @param verifyApplied when {@code true}, performs read-back verification before completing.
     * @return future completing with {@code true} if request was accepted and verification passed (when enabled).
     */
    public CompletableFuture<Boolean> setConfig(Config config, boolean verifyApplied) {
        Objects.requireNonNull(config, "config must not be null");

        AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setSetConfig(config)).build();
        return gateway.executeAdminRequest(gateway.getSelfNodeId(), request, false)
                .thenCompose(packet -> {
                    applyConfig(config);
                    if (!verifyApplied) {
                        return CompletableFuture.completedFuture(true);
                    }

                    List<ConfigType> impactedTypes = extractConfigTypes(config);
                    if (impactedTypes.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }

                    return refreshConfigs(impactedTypes)
                            .thenApply(observed -> isConfigApplied(config, observed));
                })
                .exceptionallyCompose(ex -> {
                    if (!verifyApplied || !isRoutingNoResponse(ex)) {
                        return CompletableFuture.failedFuture(ex);
                    }

                    log.warn("[ADMIN] setConfig returned ROUTING NO_RESPONSE; verifying by read-back");
                    List<ConfigType> impactedTypes = extractConfigTypes(config);
                    if (impactedTypes.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return refreshConfigs(impactedTypes)
                            .thenApply(observed -> isConfigApplied(config, observed));
                });
    }

    /**
     * Writes one module config payload to the radio.
     *
     * @param moduleConfig module config payload.
     * @return future completing when accepted by the radio.
     */
    public CompletableFuture<Void> setModuleConfig(ModuleConfig moduleConfig) {
        return setModuleConfig(moduleConfig, false).thenAccept(applied -> {
        });
    }

    /**
     * Writes one module config payload and forces read-back verification.
     *
     * @param moduleConfig module config payload.
     * @return future completing with {@code true} when accepted and verified-applied.
     */
    public CompletableFuture<Boolean> setModuleConfigAndVerify(ModuleConfig moduleConfig) {
        return setModuleConfig(moduleConfig, true);
    }

    /**
     * Writes one module config payload and returns structured completion status.
     *
     * @param moduleConfig module config payload.
     * @param verifyApplied when {@code true}, performs read-back verification before completing.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setModuleConfigResult(ModuleConfig moduleConfig, boolean verifyApplied) {
        return setModuleConfig(moduleConfig, verifyApplied)
                .handle((applied, ex) -> toWriteResult("setModuleConfig", verifyApplied, Boolean.TRUE.equals(applied), ex));
    }

    /**
     * Writes one module config payload to the radio, optionally verifying by read-back.
     *
     * @param moduleConfig module config payload.
     * @param verifyApplied when {@code true}, performs read-back verification before completing.
     * @return future completing with {@code true} if request was accepted and verification passed (when enabled).
     */
    public CompletableFuture<Boolean> setModuleConfig(ModuleConfig moduleConfig, boolean verifyApplied) {
        Objects.requireNonNull(moduleConfig, "moduleConfig must not be null");

        ModuleConfigType moduleType = toModuleConfigType(moduleConfig);
        if (moduleType == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("moduleConfig must include a concrete payload variant"));
        }

        AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setSetModuleConfig(moduleConfig)).build();
        return gateway.executeAdminRequest(gateway.getSelfNodeId(), request, false)
                .thenCompose(packet -> {
                    applyModuleConfig(moduleConfig);
                    if (!verifyApplied) {
                        return CompletableFuture.completedFuture(true);
                    }

                    return refreshModuleConfig(moduleType)
                            .thenApply(observed -> isModuleConfigApplied(moduleConfig, observed));
                })
                .exceptionallyCompose(ex -> {
                    if (!verifyApplied || !isRoutingNoResponse(ex)) {
                        return CompletableFuture.failedFuture(ex);
                    }

                    log.warn("[ADMIN] setModuleConfig returned ROUTING NO_RESPONSE; verifying by read-back for {}", moduleType);
                    return refreshModuleConfig(moduleType)
                            .thenApply(observed -> isModuleConfigApplied(moduleConfig, observed));
                });
    }

    /**
     * Writes MQTT module settings to the local radio.
     *
     * @param mqttConfig MQTT module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setMqttConfig(ModuleConfig.MQTTConfig mqttConfig, boolean verifyApplied) {
        Objects.requireNonNull(mqttConfig, "mqttConfig must not be null");
        ModuleConfig moduleConfig = ModuleConfig.newBuilder().setMqtt(mqttConfig).build();
        return setModuleConfig(moduleConfig, verifyApplied);
    }

    /**
     * Writes MQTT module settings using accepted-only completion semantics.
     *
     * @param mqttConfig MQTT module config payload.
     * @return future completing when accepted by the radio.
     */
    public CompletableFuture<Void> setMqttConfig(ModuleConfig.MQTTConfig mqttConfig) {
        return setMqttConfig(mqttConfig, false).thenAccept(applied -> {
        });
    }

    /**
     * Writes MQTT module settings and forces read-back verification.
     *
     * @param mqttConfig MQTT module config payload.
     * @return future completing with {@code true} when accepted and verified-applied.
     */
    public CompletableFuture<Boolean> setMqttConfigAndVerify(ModuleConfig.MQTTConfig mqttConfig) {
        return setMqttConfig(mqttConfig, true);
    }

    /**
     * Writes serial module settings.
     *
     * @param serialConfig serial module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setSerialModuleConfig(ModuleConfig.SerialConfig serialConfig, boolean verifyApplied) {
        Objects.requireNonNull(serialConfig, "serialConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setSerial(serialConfig).build(), verifyApplied);
    }

    /**
     * Writes external-notification module settings.
     *
     * @param config external-notification module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setExternalNotificationModuleConfig(ModuleConfig.ExternalNotificationConfig config,
                                                                          boolean verifyApplied) {
        Objects.requireNonNull(config, "externalNotificationConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setExternalNotification(config).build(), verifyApplied);
    }

    /**
     * Writes store-forward module settings.
     *
     * @param config store-forward module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setStoreForwardModuleConfig(ModuleConfig.StoreForwardConfig config,
                                                                  boolean verifyApplied) {
        Objects.requireNonNull(config, "storeForwardConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setStoreForward(config).build(), verifyApplied);
    }

    /**
     * Writes range-test module settings.
     *
     * @param config range-test module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setRangeTestModuleConfig(ModuleConfig.RangeTestConfig config, boolean verifyApplied) {
        Objects.requireNonNull(config, "rangeTestConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setRangeTest(config).build(), verifyApplied);
    }

    /**
     * Writes telemetry module settings.
     *
     * @param config telemetry module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setTelemetryModuleConfig(ModuleConfig.TelemetryConfig config, boolean verifyApplied) {
        Objects.requireNonNull(config, "telemetryConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setTelemetry(config).build(), verifyApplied);
    }

    /**
     * Writes canned-message module settings.
     *
     * @param config canned-message module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setCannedMessageModuleConfig(ModuleConfig.CannedMessageConfig config,
                                                                   boolean verifyApplied) {
        Objects.requireNonNull(config, "cannedMessageConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setCannedMessage(config).build(), verifyApplied);
    }

    /**
     * Writes audio module settings.
     *
     * @param config audio module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setAudioModuleConfig(ModuleConfig.AudioConfig config, boolean verifyApplied) {
        Objects.requireNonNull(config, "audioConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setAudio(config).build(), verifyApplied);
    }

    /**
     * Writes remote-hardware module settings.
     *
     * @param config remote-hardware module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setRemoteHardwareModuleConfig(ModuleConfig.RemoteHardwareConfig config,
                                                                    boolean verifyApplied) {
        Objects.requireNonNull(config, "remoteHardwareConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setRemoteHardware(config).build(), verifyApplied);
    }

    /**
     * Writes neighbor-info module settings.
     *
     * @param config neighbor-info module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setNeighborInfoModuleConfig(ModuleConfig.NeighborInfoConfig config,
                                                                  boolean verifyApplied) {
        Objects.requireNonNull(config, "neighborInfoConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setNeighborInfo(config).build(), verifyApplied);
    }

    /**
     * Writes ambient-lighting module settings.
     *
     * @param config ambient-lighting module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setAmbientLightingModuleConfig(ModuleConfig.AmbientLightingConfig config,
                                                                     boolean verifyApplied) {
        Objects.requireNonNull(config, "ambientLightingConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setAmbientLighting(config).build(), verifyApplied);
    }

    /**
     * Writes detection-sensor module settings.
     *
     * @param config detection-sensor module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setDetectionSensorModuleConfig(ModuleConfig.DetectionSensorConfig config,
                                                                     boolean verifyApplied) {
        Objects.requireNonNull(config, "detectionSensorConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setDetectionSensor(config).build(), verifyApplied);
    }

    /**
     * Writes paxcounter module settings.
     *
     * @param config paxcounter module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setPaxcounterModuleConfig(ModuleConfig.PaxcounterConfig config,
                                                                boolean verifyApplied) {
        Objects.requireNonNull(config, "paxcounterConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setPaxcounter(config).build(), verifyApplied);
    }

    /**
     * Writes status-message module settings.
     *
     * @param config status-message module config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setStatusMessageModuleConfig(ModuleConfig.StatusMessageConfig config,
                                                                   boolean verifyApplied) {
        Objects.requireNonNull(config, "statusMessageConfig must not be null");
        return setModuleConfig(ModuleConfig.newBuilder().setStatusmessage(config).build(), verifyApplied);
    }

    /**
     * Writes security config to the local radio.
     *
     * @param securityConfig security config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setSecurityConfig(Config.SecurityConfig securityConfig, boolean verifyApplied) {
        Objects.requireNonNull(securityConfig, "securityConfig must not be null");
        Config config = Config.newBuilder().setSecurity(securityConfig).build();
        return setConfig(config, verifyApplied);
    }

    /**
     * Writes device config to the local radio.
     *
     * @param deviceConfig device config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setDeviceConfig(Config.DeviceConfig deviceConfig, boolean verifyApplied) {
        Objects.requireNonNull(deviceConfig, "deviceConfig must not be null");
        return setConfig(Config.newBuilder().setDevice(deviceConfig).build(), verifyApplied);
    }

    /**
     * Writes position config to the local radio.
     *
     * @param positionConfig position config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setPositionConfig(Config.PositionConfig positionConfig, boolean verifyApplied) {
        Objects.requireNonNull(positionConfig, "positionConfig must not be null");
        return setConfig(Config.newBuilder().setPosition(positionConfig).build(), verifyApplied);
    }

    /**
     * Writes power config to the local radio.
     *
     * @param powerConfig power config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setPowerConfig(Config.PowerConfig powerConfig, boolean verifyApplied) {
        Objects.requireNonNull(powerConfig, "powerConfig must not be null");
        return setConfig(Config.newBuilder().setPower(powerConfig).build(), verifyApplied);
    }

    /**
     * Writes network config to the local radio.
     *
     * @param networkConfig network config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setNetworkConfig(Config.NetworkConfig networkConfig, boolean verifyApplied) {
        Objects.requireNonNull(networkConfig, "networkConfig must not be null");
        return setConfig(Config.newBuilder().setNetwork(networkConfig).build(), verifyApplied);
    }

    /**
     * Writes display config to the local radio.
     *
     * @param displayConfig display config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setDisplayConfig(Config.DisplayConfig displayConfig, boolean verifyApplied) {
        Objects.requireNonNull(displayConfig, "displayConfig must not be null");
        return setConfig(Config.newBuilder().setDisplay(displayConfig).build(), verifyApplied);
    }

    /**
     * Writes LoRa config to the local radio.
     *
     * @param loraConfig LoRa config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setLoraConfig(Config.LoRaConfig loraConfig, boolean verifyApplied) {
        Objects.requireNonNull(loraConfig, "loraConfig must not be null");
        return setConfig(Config.newBuilder().setLora(loraConfig).build(), verifyApplied);
    }

    /**
     * Writes Bluetooth config to the local radio.
     *
     * @param bluetoothConfig Bluetooth config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setBluetoothConfig(Config.BluetoothConfig bluetoothConfig, boolean verifyApplied) {
        Objects.requireNonNull(bluetoothConfig, "bluetoothConfig must not be null");
        return setConfig(Config.newBuilder().setBluetooth(bluetoothConfig).build(), verifyApplied);
    }

    /**
     * Writes session key config to the local radio.
     *
     * @param sessionKeyConfig session key config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setSessionKeyConfig(Config.SessionkeyConfig sessionKeyConfig, boolean verifyApplied) {
        Objects.requireNonNull(sessionKeyConfig, "sessionKeyConfig must not be null");
        return setConfig(Config.newBuilder().setSessionkey(sessionKeyConfig).build(), verifyApplied);
    }

    /**
     * Writes device UI config to the local radio.
     *
     * @param deviceUiConfig device UI config payload.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setDeviceUiConfig(DeviceUIConfig deviceUiConfig, boolean verifyApplied) {
        Objects.requireNonNull(deviceUiConfig, "deviceUiConfig must not be null");
        return setConfig(Config.newBuilder().setDeviceUi(deviceUiConfig).build(), verifyApplied);
    }

    /**
     * Writes security config using accepted-only completion semantics.
     *
     * @param securityConfig security config payload.
     * @return future completing when accepted by the radio.
     */
    public CompletableFuture<Void> setSecurityConfig(Config.SecurityConfig securityConfig) {
        return setSecurityConfig(securityConfig, false).thenAccept(applied -> {
        });
    }

    /**
     * Writes security config to the local radio and forces read-back verification.
     *
     * @param securityConfig security config payload.
     * @return future completing with {@code true} when accepted and verified-applied.
     */
    public CompletableFuture<Boolean> setSecurityConfigAndVerify(Config.SecurityConfig securityConfig) {
        return setSecurityConfig(securityConfig, true);
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
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setChannelName(int index, String channelName, boolean verifyApplied) {
        Objects.requireNonNull(channelName, "channelName must not be null");
        return resolveChannelForUpdate(index)
                .thenCompose(baseChannel -> {
                    ChannelSettings settings = baseChannel.getSettings().toBuilder().setName(channelName).build();
                    Channel updated = baseChannel.toBuilder().setSettings(settings).build();
                    return setChannel(index, updated, verifyApplied);
                });
    }

    /**
     * Updates only the channel display name and verifies by read-back.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param channelName new channel name.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setChannelName(int index, String channelName) {
        return setChannelName(index, channelName, true);
    }

    /**
     * Updates only the channel PSK for a slot.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param psk channel PSK bytes.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setChannelPsk(int index, ByteString psk, boolean verifyApplied) {
        ProtocolConstraints.validateChannelPsk(psk);
        return resolveChannelForUpdate(index)
                .thenCompose(baseChannel -> {
                    ChannelSettings settings = baseChannel.getSettings().toBuilder().setPsk(psk).build();
                    Channel updated = baseChannel.toBuilder().setSettings(settings).build();
                    return setChannel(index, updated, verifyApplied);
                });
    }

    /**
     * Updates only the channel PSK and verifies by read-back.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param psk channel PSK bytes.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setChannelPsk(int index, ByteString psk) {
        return setChannelPsk(index, psk, true);
    }

    /**
     * Updates only the channel PSK for a slot.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param psk channel PSK bytes.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setChannelPsk(int index, byte[] psk, boolean verifyApplied) {
        Objects.requireNonNull(psk, "psk must not be null");
        return setChannelPsk(index, ByteString.copyFrom(psk), verifyApplied);
    }

    /**
     * Updates only the channel PSK from a UTF-8 password string.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param password UTF-8 password value to store as PSK bytes.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setChannelPassword(int index, String password, boolean verifyApplied) {
        Objects.requireNonNull(password, "password must not be null");
        return setChannelPsk(index, ByteString.copyFrom(password, StandardCharsets.UTF_8), verifyApplied);
    }

    /**
     * Updates only the channel PSK from a UTF-8 password string and verifies by read-back.
     *
     * @param index channel slot index ({@code 0..7}).
     * @param password UTF-8 password value to store as PSK bytes.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setChannelPassword(int index, String password) {
        return setChannelPassword(index, password, true);
    }

    /**
     * Updates the primary channel PSK.
     *
     * @param psk channel PSK bytes.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setPrimaryChannelPsk(ByteString psk, boolean verifyApplied) {
        return setChannelPsk(0, psk, verifyApplied);
    }

    /**
     * Updates the primary channel PSK and verifies by read-back.
     *
     * @param psk channel PSK bytes.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setPrimaryChannelPsk(ByteString psk) {
        return setPrimaryChannelPsk(psk, true);
    }

    /**
     * Updates the primary channel PSK from a UTF-8 password string.
     *
     * @param password UTF-8 password value to store as PSK bytes.
     * @param verifyApplied when {@code true}, verifies by read-back.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setPrimaryChannelPassword(String password, boolean verifyApplied) {
        return setChannelPassword(0, password, verifyApplied);
    }

    /**
     * Updates the primary channel PSK from a UTF-8 password string and verifies by read-back.
     *
     * @param password UTF-8 password value to store as PSK bytes.
     * @return future completing with write/verification result.
     */
    public CompletableFuture<Boolean> setPrimaryChannelPassword(String password) {
        return setPrimaryChannelPassword(password, true);
    }

    /**
     * Updates node identity values.
     *
     * @param targetNodeId target node id.
     * @param longName new long name.
     * @param shortName new short name.
     * @return future completing with {@code true} when accepted by the radio.
     */
    public CompletableFuture<Boolean> setOwner(int targetNodeId, String longName, String shortName) {
        return setOwner(targetNodeId, longName, shortName, false);
    }

    /**
     * Updates node identity values and forces verification.
     *
     * @param targetNodeId target node id.
     * @param longName new long name.
     * @param shortName new short name.
     * @return future completing with {@code true} when accepted and verified-applied.
     */
    public CompletableFuture<Boolean> setOwnerAndVerify(int targetNodeId, String longName, String shortName) {
        return setOwner(targetNodeId, longName, shortName, true);
    }

    /**
     * Updates node identity values and returns structured completion status.
     *
     * @param targetNodeId target node id.
     * @param longName new long name.
     * @param shortName new short name.
     * @param verifyApplied when {@code true}, performs read-back verification before completing.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> setOwnerResult(int targetNodeId,
                                                              String longName,
                                                              String shortName,
                                                              boolean verifyApplied) {
        String operation = "setOwner(" + MeshUtils.formatId(targetNodeId) + ")";
        return setOwner(targetNodeId, longName, shortName, verifyApplied)
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
    public CompletableFuture<Boolean> setOwner(int targetNodeId,
                                               String longName,
                                               String shortName,
                                               boolean verifyApplied) {
        User updatedUser = User.newBuilder()
                .setLongName(longName)
                .setShortName(shortName)
                .build();

        AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setSetOwner(updatedUser)).build();

        log.info("[ADMIN] Requesting rename for !{} to {}", Integer.toHexString(targetNodeId), longName);
        return gateway.executeAdminRequest(targetNodeId, request, false)
                .thenCompose(packet -> {
                    applyOwner(updatedUser);
                    log.info("[ADMIN] Rename accepted by radio for !{}", Integer.toHexString(targetNodeId));
                    if (!verifyApplied) {
                        return CompletableFuture.completedFuture(true);
                    }
                    return verifyOwnerApplied(targetNodeId, longName, shortName);
                })
                .exceptionallyCompose(ex -> {
                    if (!verifyApplied || !isRoutingNoResponse(ex)) {
                        return CompletableFuture.failedFuture(ex);
                    }

                    log.warn("[ADMIN] setOwner returned ROUTING NO_RESPONSE for !{}; verifying by read-back",
                            Integer.toHexString(targetNodeId));
                    return verifyOwnerApplied(targetNodeId, longName, shortName);
                });
    }

    /**
     * Performs a full factory reset.
     *
     * @return future completing when accepted by the radio.
     */
    public CompletableFuture<Void> factoryReset() {
        AdminMessage request = AdminMessage.newBuilder()
                .setFactoryResetConfig(0)
                .setFactoryResetDevice(0)
                .build();
        log.warn("[ADMIN] Sending Full Factory Reset command!");
        return gateway.executeAdminRequest(gateway.getSelfNodeId(), request, false).thenAccept(packet -> {
        });
    }

    /**
     * Requests radio reboot.
     *
     * @param seconds reboot delay; {@code 0} is normalized to {@code 1}.
     * @return future completing with {@code true} when accepted by the radio.
     */
    public CompletableFuture<Boolean> reboot(int seconds) {
        AdminMessage request = withSessionIfPresent(
                AdminMessage.newBuilder().setRebootSeconds(seconds == 0 ? 1 : seconds)).build();

        log.info("[ADMIN] Requesting radio reboot in {} seconds...", seconds);
        return gateway.executeAdminRequest(gateway.getSelfNodeId(), request, false).thenApply(packet -> true);
    }

    /**
     * Requests the target node to enter OTA mode with an optional firmware hash.
     * <p>
     * This call only requests OTA loader mode. Actual firmware upload is transport/tool specific and should be
     * handled by an OTA uploader strategy.
     * </p>
     *
     * @param targetNodeId target node id.
     * @param mode OTA reboot mode.
     * @param firmwareSha256 optional 32-byte SHA-256 firmware hash; pass empty to omit.
     * @return future completing with structured write result status.
     */
    public CompletableFuture<AdminWriteResult> requestOtaModeResult(int targetNodeId,
                                                                    OTAMode mode,
                                                                    ByteString firmwareSha256) {
        Objects.requireNonNull(mode, "mode must not be null");
        ByteString hash = firmwareSha256 == null ? ByteString.EMPTY : firmwareSha256;

        AdminMessage.OTAEvent otaEvent = AdminMessage.OTAEvent.newBuilder()
                .setRebootOtaMode(mode)
                .setOtaHash(hash)
                .build();
        AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setOtaRequest(otaEvent)).build();
        String operation = "requestOtaMode(" + MeshUtils.formatId(targetNodeId) + ")";

        return gateway.executeAdminRequest(targetNodeId, request, false)
                .handle((packet, ex) -> toWriteResult(operation, false, ex == null, ex));
    }

    /**
     * Ingests decoded admin message payload from {@code ADMIN_APP} packets.
     *
     * @param msg decoded admin message.
     */
    public void ingestAdminMessage(AdminMessage msg) {
        updateSessionKey(msg);

        if (msg.hasGetOwnerResponse()) {
            applyOwner(msg.getGetOwnerResponse());
            log.info("[ADMIN] Model Updated: Owner");
        }
        if (msg.hasGetConfigResponse()) {
            applyConfig(msg.getGetConfigResponse());
            log.info("[ADMIN] Model Updated: Config");
        }
        if (msg.hasGetChannelResponse()) {
            applyChannel(msg.getGetChannelResponse());
            log.info("[ADMIN] Model Updated: Channel Slot {}", msg.getGetChannelResponse().getIndex());
        }
        if (msg.hasGetModuleConfigResponse()) {
            applyModuleConfig(msg.getGetModuleConfigResponse());
            log.info("[ADMIN] Model Updated: Module Config");
        }
        if (msg.hasGetDeviceMetadataResponse()) {
            applyMetadata(msg.getGetDeviceMetadataResponse());
            log.info("[ADMIN] Model Updated: Device Metadata");
        }
    }

    /**
     * Returns a cached config snapshot for one type.
     *
     * @param type config type key.
     * @return optional cached config payload.
     */
    public Optional<Config> getConfigSnapshot(ConfigType type) {
        return radioModel.getConfig(type);
    }

    /**
     * Returns all cached config snapshots keyed by config type.
     *
     * @return immutable config snapshot map.
     */
    public Map<ConfigType, Config> getConfigSnapshots() {
        return radioModel.getConfigs();
    }

    /**
     * Returns a cached module config snapshot for one type.
     *
     * @param type module config type key.
     * @return optional cached module config payload.
     */
    public Optional<ModuleConfig> getModuleConfigSnapshot(ModuleConfigType type) {
        return radioModel.getModuleConfig(type);
    }

    /**
     * Returns all cached module config snapshots keyed by module config type.
     *
     * @return immutable module config snapshot map.
     */
    public Map<ModuleConfigType, ModuleConfig> getModuleConfigSnapshots() {
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
     * Ingests startup snapshot variants from {@link FromRadio} messages.
     *
     * @param message startup snapshot message.
     */
    public void ingestStartupSnapshot(FromRadio message) {
        if (message.hasMyInfo()) {
            ingestMyInfo(message.getMyInfo().getMyNodeNum());
        }
        if (message.hasNodeInfo()) {
            ingestNodeInfo(message.getNodeInfo());
        }
        if (message.hasConfig()) {
            applyConfig(message.getConfig());
        }
        if (message.hasChannel()) {
            applyChannel(message.getChannel());
        }
        if (message.hasModuleConfig()) {
            applyModuleConfig(message.getModuleConfig());
        }
        if (message.hasMetadata()) {
            applyMetadata(message.getMetadata());
        }
    }

    /**
     * Ingests node id from {@code my_info} startup message.
     *
     * @param nodeId local node id.
     */
    public void ingestMyInfo(int nodeId) {
        if (nodeId > 0) {
            radioModel.setNodeId(nodeId);
        }
    }

    /**
     * Ingests owner/node identity from node info payload.
     *
     * @param info node info payload.
     */
    public void ingestNodeInfo(NodeInfo info) {
        if (info == null) {
            return;
        }
        if (info.hasUser()) {
            applyOwner(info.getUser());
        }
        if (info.getNum() > 0) {
            radioModel.setNodeId(info.getNum());
        }
    }

    private void applyOwner(User owner) {
        if (owner != null) {
            radioModel.setOwner(owner);
        }
    }

    private void applyConfig(Config cfg) {
        if (cfg == null) {
            return;
        }
        for (ConfigType type : extractConfigTypes(cfg)) {
            radioModel.putConfig(type, cfg);
        }
        if (cfg.hasLora()) {
            radioModel.setLoraConfig(cfg.getLora());
        }
        if (cfg.hasDevice()) {
            radioModel.setDeviceConfig(cfg.getDevice());
        }
        if (cfg.hasDisplay()) {
            radioModel.setDisplayConfig(cfg.getDisplay());
        }
        if (cfg.hasNetwork()) {
            radioModel.setNetworkConfig(cfg.getNetwork());
        }
    }

    private void applyChannel(Channel channel) {
        if (channel != null) {
            radioModel.setChannel(channel.getIndex(), channel);
        }
    }

    private void applyModuleConfig(ModuleConfig moduleConfig) {
        ModuleConfigType type = toModuleConfigType(moduleConfig);
        if (type != null) {
            radioModel.putModuleConfig(type, moduleConfig);
        }
    }

    private void applyMetadata(DeviceMetadata metadata) {
        if (metadata != null) {
            radioModel.setDeviceMetadata(metadata);
        }
    }

    private ModuleConfigType toModuleConfigType(ModuleConfig moduleConfig) {
        if (moduleConfig == null) {
            return null;
        }

        return switch (moduleConfig.getPayloadVariantCase()) {
            case MQTT -> ModuleConfigType.MQTT_CONFIG;
            case SERIAL -> ModuleConfigType.SERIAL_CONFIG;
            case EXTERNAL_NOTIFICATION -> ModuleConfigType.EXTNOTIF_CONFIG;
            case STORE_FORWARD -> ModuleConfigType.STOREFORWARD_CONFIG;
            case RANGE_TEST -> ModuleConfigType.RANGETEST_CONFIG;
            case TELEMETRY -> ModuleConfigType.TELEMETRY_CONFIG;
            case CANNED_MESSAGE -> ModuleConfigType.CANNEDMSG_CONFIG;
            case AUDIO -> ModuleConfigType.AUDIO_CONFIG;
            case REMOTE_HARDWARE -> ModuleConfigType.REMOTEHARDWARE_CONFIG;
            case NEIGHBOR_INFO -> ModuleConfigType.NEIGHBORINFO_CONFIG;
            case AMBIENT_LIGHTING -> ModuleConfigType.AMBIENTLIGHTING_CONFIG;
            case DETECTION_SENSOR -> ModuleConfigType.DETECTIONSENSOR_CONFIG;
            case PAXCOUNTER -> ModuleConfigType.PAXCOUNTER_CONFIG;
            case STATUSMESSAGE -> ModuleConfigType.STATUSMESSAGE_CONFIG;
            case PAYLOADVARIANT_NOT_SET -> null;
        };
    }

    /**
     * Maps a config payload oneof variant to its admin config type key.
     *
     * @param cfg config payload.
     * @return list containing the affected config type, or empty when payload has no variant.
     */
    private List<ConfigType> extractConfigTypes(Config cfg) {
        if (cfg == null) {
            return List.of();
        }

        ConfigType type = switch (cfg.getPayloadVariantCase()) {
            case DEVICE -> ConfigType.DEVICE_CONFIG;
            case POSITION -> ConfigType.POSITION_CONFIG;
            case POWER -> ConfigType.POWER_CONFIG;
            case NETWORK -> ConfigType.NETWORK_CONFIG;
            case DISPLAY -> ConfigType.DISPLAY_CONFIG;
            case LORA -> ConfigType.LORA_CONFIG;
            case BLUETOOTH -> ConfigType.BLUETOOTH_CONFIG;
            case SECURITY -> ConfigType.SECURITY_CONFIG;
            case SESSIONKEY -> ConfigType.SESSIONKEY_CONFIG;
            case DEVICE_UI -> ConfigType.DEVICEUI_CONFIG;
            case PAYLOADVARIANT_NOT_SET -> null;
        };
        return type == null ? List.of() : List.of(type);
    }

    /**
     * Verifies whether requested config section payloads match read-back payloads.
     *
     * @param requested requested config payload.
     * @param observedByType refreshed config payloads keyed by type.
     * @return {@code true} when all affected sections match.
     */
    private boolean isConfigApplied(Config requested, Map<ConfigType, Config> observedByType) {
        List<ConfigType> impactedTypes = extractConfigTypes(requested);
        if (impactedTypes.isEmpty()) {
            return false;
        }

        for (ConfigType type : impactedTypes) {
            Config observed = observedByType.get(type);
            if (observed == null) {
                return false;
            }
            boolean sectionMatches = switch (type) {
                case DEVICE_CONFIG -> requested.hasDevice() && observed.hasDevice()
                        && requested.getDevice().equals(observed.getDevice());
                case POSITION_CONFIG -> requested.hasPosition() && observed.hasPosition()
                        && requested.getPosition().equals(observed.getPosition());
                case POWER_CONFIG -> requested.hasPower() && observed.hasPower()
                        && requested.getPower().equals(observed.getPower());
                case NETWORK_CONFIG -> requested.hasNetwork() && observed.hasNetwork()
                        && requested.getNetwork().equals(observed.getNetwork());
                case DISPLAY_CONFIG -> requested.hasDisplay() && observed.hasDisplay()
                        && requested.getDisplay().equals(observed.getDisplay());
                case LORA_CONFIG -> requested.hasLora() && observed.hasLora()
                        && requested.getLora().equals(observed.getLora());
                case BLUETOOTH_CONFIG -> requested.hasBluetooth() && observed.hasBluetooth()
                        && requested.getBluetooth().equals(observed.getBluetooth());
                case SECURITY_CONFIG -> requested.hasSecurity() && observed.hasSecurity()
                        && requested.getSecurity().equals(observed.getSecurity());
                case SESSIONKEY_CONFIG -> requested.hasSessionkey() && observed.hasSessionkey()
                        && requested.getSessionkey().equals(observed.getSessionkey());
                case DEVICEUI_CONFIG -> requested.hasDeviceUi() && observed.hasDeviceUi()
                        && requested.getDeviceUi().equals(observed.getDeviceUi());
                case UNRECOGNIZED -> false;
            };
            if (!sectionMatches) {
                return false;
            }
        }

        return true;
    }

    /**
     * Verifies whether requested module config payload matches read-back payload.
     *
     * @param requested requested module config payload.
     * @param observed observed module config payload.
     * @return {@code true} when the requested payload variant and value match.
     */
    private boolean isModuleConfigApplied(ModuleConfig requested, ModuleConfig observed) {
        if (requested == null || observed == null) {
            return false;
        }

        if (requested.getPayloadVariantCase() != observed.getPayloadVariantCase()) {
            return false;
        }

        return switch (requested.getPayloadVariantCase()) {
            case MQTT -> requested.getMqtt().equals(observed.getMqtt());
            case SERIAL -> requested.getSerial().equals(observed.getSerial());
            case EXTERNAL_NOTIFICATION -> requested.getExternalNotification().equals(observed.getExternalNotification());
            case STORE_FORWARD -> requested.getStoreForward().equals(observed.getStoreForward());
            case RANGE_TEST -> requested.getRangeTest().equals(observed.getRangeTest());
            case TELEMETRY -> requested.getTelemetry().equals(observed.getTelemetry());
            case CANNED_MESSAGE -> requested.getCannedMessage().equals(observed.getCannedMessage());
            case AUDIO -> requested.getAudio().equals(observed.getAudio());
            case REMOTE_HARDWARE -> requested.getRemoteHardware().equals(observed.getRemoteHardware());
            case NEIGHBOR_INFO -> requested.getNeighborInfo().equals(observed.getNeighborInfo());
            case AMBIENT_LIGHTING -> requested.getAmbientLighting().equals(observed.getAmbientLighting());
            case DETECTION_SENSOR -> requested.getDetectionSensor().equals(observed.getDetectionSensor());
            case PAXCOUNTER -> requested.getPaxcounter().equals(observed.getPaxcounter());
            case STATUSMESSAGE -> requested.getStatusmessage().equals(observed.getStatusmessage());
            case PAYLOADVARIANT_NOT_SET -> false;
        };
    }

    /**
     * Verifies owner/name write visibility using the best available read-back path.
     *
     * @param targetNodeId target node id.
     * @param expectedLongName expected long name.
     * @param expectedShortName expected short name.
     * @return future completing with {@code true} when read-back values match.
     */
    private CompletableFuture<Boolean> verifyOwnerApplied(int targetNodeId,
                                                          String expectedLongName,
                                                          String expectedShortName) {
        if (targetNodeId == gateway.getSelfNodeId()) {
            return refreshOwner().thenApply(owner -> owner != null
                    && Objects.equals(expectedLongName, owner.getLongName())
                    && Objects.equals(expectedShortName, owner.getShortName()));
        }

        return gateway.requestNodeInfoAwaitPayloadOrSnapshot(targetNodeId, OWNER_VERIFY_TIMEOUT)
                .thenApply(node -> node != null
                        && Objects.equals(expectedLongName, node.getLongName())
                        && Objects.equals(expectedShortName, node.getShortName()))
                .exceptionally(ex -> false);
    }

    private AdminMessage.Builder withSessionIfPresent(AdminMessage.Builder builder) {
        if (!lastSessionPasskey.isEmpty()) {
            builder.setSessionPasskey(lastSessionPasskey);
        }
        return builder;
    }

    private <T> CompletableFuture<T> executeAndParse(AdminMessage request, Function<AdminMessage, T> extractor) {
        // Read operations must wait for correlated ADMIN_APP payloads, not just ROUTING NONE status.
        return gateway.executeAdminRequest(gateway.getSelfNodeId(), request, true)
                .thenApply(this::parseAdminMessage)
                .thenApply(msg -> {
                    updateSessionKey(msg);
                    return extractor.apply(msg);
                });
    }

    private AdminMessage parseAdminMessage(MeshPacket packet) {
        try {
            return AdminMessage.parseFrom(packet.getDecoded().getPayload());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse admin payload from response packet", ex);
        }
    }

    private void updateSessionKey(AdminMessage adminMsg) {
        if (!adminMsg.getSessionPasskey().isEmpty()) {
            this.lastSessionPasskey = adminMsg.getSessionPasskey();
            log.info("[ADMIN] Updated Session Key: {}", MeshUtils.bytesToHex(lastSessionPasskey.toByteArray()));
        }
    }

    private void validateChannelIndex(int index) {
        ProtocolConstraints.validateChannelIndex(index);
    }

    /**
     * Verifies channel role/settings by read-back.
     *
     * @param index channel index.
     * @param expected expected role/settings payload.
     * @param allowRetry whether mismatch should throw to trigger a retry.
     * @param retryMessage message used when throwing retry trigger exception.
     * @return future completing with {@code true} when expected channel state is observed.
     */
    private CompletableFuture<Boolean> verifyChannelApplied(int index,
                                                            Channel expected,
                                                            boolean allowRetry,
                                                            String retryMessage) {
        return refreshChannel(index).thenApply(appliedChannel -> {
            if (log.isDebugEnabled()) {
                log.debug("[ADMIN] setChannel verify expected -> {}", describeChannelForLog(expected));
                log.debug("[ADMIN] setChannel verify observed -> {}", describeChannelForLog(appliedChannel));
            }
            boolean applied = expected.getRole() == appliedChannel.getRole()
                    && expected.getSettings().equals(appliedChannel.getSettings());

            if (applied) {
                log.info("[ADMIN] Channel {} updated successfully", index);
            } else if (allowRetry) {
                throw new IllegalStateException(retryMessage);
            } else {
                log.warn("[ADMIN] Channel {} update did not apply on radio (requested role={}, observed role={})",
                        index, expected.getRole(), appliedChannel.getRole());
            }
            return applied;
        });
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
                        log.debug("[ADMIN] setChannel sparse payload detected for slot {}. " +
                                        "Hydrating with cached slot settings before write.", index);
                        log.debug("[ADMIN] setChannel cached base -> {}", describeChannelForLog(current));
                        log.debug("[ADMIN] setChannel requested sparse -> {}", describeChannelForLog(requested));
                        log.debug("[ADMIN] setChannel hydrated payload -> {}",
                                describeChannelForLog(requested.toBuilder().setSettings(merged).build()));
                    }
                    return CompletableFuture.completedFuture(requested.toBuilder().setSettings(merged).build());
                })
                .orElseGet(() -> {
                    if (log.isDebugEnabled()) {
                        log.debug("[ADMIN] setChannel sparse payload detected for slot {} but no cached slot found. " +
                                "Sending payload as-is.", index);
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
        return getChannelSnapshot(index)
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> refreshChannel(index));
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
     */
    private List<Integer> normalizeChannelIndexes(List<Integer> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            return IntStream.range(0, ProtocolConstraints.MAX_CHANNEL_SLOTS).boxed().toList();
        }
        Set<Integer> distinct = new LinkedHashSet<>();
        indexes.stream()
                .filter(Objects::nonNull)
                .forEach(index -> {
                    validateChannelIndex(index);
                    distinct.add(index);
                });
        if (distinct.isEmpty()) {
            return IntStream.range(0, ProtocolConstraints.MAX_CHANNEL_SLOTS).boxed().toList();
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
     * Maps boolean/exception write outcomes into canonical admin write statuses.
     *
     * @param operation operation identifier.
     * @param verificationRequested whether caller requested read-back verification.
     * @param applied boolean applied flag from write path.
     * @param error optional failure throwable from write path.
     * @return canonical write result payload.
     */
    private AdminWriteResult toWriteResult(String operation,
                                           boolean verificationRequested,
                                           boolean applied,
                                           Throwable error) {
        if (error == null) {
            if (verificationRequested) {
                if (applied) {
                    return new AdminWriteResult(AdminWriteStatus.VERIFIED_APPLIED, operation,
                            "Write accepted and verified by read-back.");
                }
                return new AdminWriteResult(AdminWriteStatus.VERIFICATION_FAILED, operation,
                        "Write accepted but verification read-back did not match requested state.");
            }

            if (applied) {
                return new AdminWriteResult(AdminWriteStatus.ACCEPTED, operation,
                        "Write accepted by radio.");
            }

            return new AdminWriteResult(AdminWriteStatus.FAILED, operation,
                    "Write did not report acceptance.");
        }

        Throwable root = unwrap(error);
        if (root instanceof TimeoutException) {
            return new AdminWriteResult(AdminWriteStatus.TIMEOUT, operation,
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
