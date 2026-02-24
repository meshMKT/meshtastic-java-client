package com.meshmkt.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.model.RadioModel;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.AdminProtos.AdminMessage.ConfigType;
import org.meshtastic.proto.AdminProtos.AdminMessage.ModuleConfigType;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.MeshProtos.DeviceMetadata;
import org.meshtastic.proto.MeshProtos.FromRadio;
import org.meshtastic.proto.MeshProtos.MeshPacket;
import org.meshtastic.proto.MeshProtos.NodeInfo;
import org.meshtastic.proto.MeshProtos.User;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
 */
@Slf4j
public class AdminService {

    private static final int MAX_CHANNEL_SLOTS = 8;

    private final MeshtasticClient client;
    private final RadioModel radioModel = new RadioModel();
    private ByteString lastSessionPasskey = ByteString.EMPTY;

    /**
     * Creates a new admin service bound to one client instance.
     *
     * @param client meshtastic client runtime.
     */
    public AdminService(MeshtasticClient client) {
        this.client = client;
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
     * Backward-compatible alias for {@link #getSnapshot()}.
     *
     * @return current radio model cache.
     */
    @Deprecated
    public RadioModel getRadioModel() {
        return getSnapshot();
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
                .thenCompose(owner -> refreshConfig(ConfigType.LORA_CONFIG))
                .thenCompose(cfg -> refreshConfig(ConfigType.DEVICE_CONFIG))
                .thenCompose(cfg -> refreshConfig(ConfigType.DISPLAY_CONFIG))
                .thenCompose(cfg -> refreshConfig(ConfigType.NETWORK_CONFIG))
                .thenApply(cfg -> radioModel);
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
                ? refreshMetadata().thenAccept(metadata -> {
                })
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
        List<CompletableFuture<Channel>> requests = IntStream.range(0, MAX_CHANNEL_SLOTS)
                .mapToObj(this::refreshChannel)
                .toList();

        return sequence(requests)
                .thenApply(channels -> channels.stream()
                        .sorted(Comparator.comparingInt(Channel::getIndex))
                        .toList());
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
     * Writes an updated channel to a slot.
     * <p>
     * Success is verified by reading the slot back from the radio and comparing role/settings.
     * This reflects protocol state, which can be ahead of what the device's local UI currently redraws.
     * </p>
     *
     * @param index channel slot index ({@code 0..7}).
     * @param updatedChannel channel payload.
     * @return future completing with {@code true} when accepted by the radio.
     */
    public CompletableFuture<Boolean> setChannel(int index, Channel updatedChannel) {
        return setChannel(index, updatedChannel, true);
    }

    private CompletableFuture<Boolean> setChannel(int index, Channel updatedChannel, boolean allowRetry) {
        validateChannelIndex(index);
        Channel channelWithIndex = updatedChannel.toBuilder().setIndex(index).build();
        CompletableFuture<Void> preflight = refreshMetadata().thenAccept(metadata -> {
        });

        return preflight.thenCompose(unused -> {
            AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setSetChannel(channelWithIndex)).build();
            return client.executeAdminRequest(client.getSelfNodeId(), request);
        })
                .thenCompose(packet -> refreshChannel(index).thenApply(appliedChannel -> {
                    boolean applied = channelWithIndex.getRole() == appliedChannel.getRole()
                            && channelWithIndex.getSettings().equals(appliedChannel.getSettings());

                    if (applied) {
                        log.info("[ADMIN] Channel {} updated successfully", index);
                    } else if (allowRetry) {
                        throw new IllegalStateException("Channel write not reflected on radio; retrying once");
                    } else {
                        log.warn("[ADMIN] Channel {} update did not apply on radio (requested role={}, observed role={})",
                                index, channelWithIndex.getRole(), appliedChannel.getRole());
                    }
                    return applied;
                }))
                .exceptionallyCompose(ex -> {
                    if (allowRetry) {
                        log.warn("[ADMIN] Channel {} set failed/verification failed; refreshing session key and retrying once: {}",
                                index, ex.getMessage());
                        return setChannel(index, updatedChannel, false);
                    }
                    return CompletableFuture.completedFuture(false);
                });
    }

    /**
     * Writes a config block to the radio.
     *
     * @param config config payload.
     * @return future completing when accepted by the radio.
     */
    public CompletableFuture<Void> setConfig(Config config) {
        AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setSetConfig(config)).build();
        return client.executeAdminRequest(client.getSelfNodeId(), request).thenAccept(packet -> applyConfig(config));
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
        User updatedUser = User.newBuilder()
                .setLongName(longName)
                .setShortName(shortName)
                .build();

        AdminMessage request = withSessionIfPresent(AdminMessage.newBuilder().setSetOwner(updatedUser)).build();

        log.info("[ADMIN] Requesting rename for !{} to {}", Integer.toHexString(targetNodeId), longName);
        return client.executeAdminRequest(targetNodeId, request)
                .thenApply(packet -> {
                    applyOwner(updatedUser);
                    log.info("[ADMIN] Rename accepted by radio for !{}", Integer.toHexString(targetNodeId));
                    return true;
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
        return client.executeAdminRequest(client.getSelfNodeId(), request).thenAccept(packet -> {
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
        return client.executeAdminRequest(client.getSelfNodeId(), request).thenApply(packet -> true);
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
            radioModel.getModuleConfigs().put(type, moduleConfig);
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

    private AdminMessage.Builder withSessionIfPresent(AdminMessage.Builder builder) {
        if (!lastSessionPasskey.isEmpty()) {
            builder.setSessionPasskey(lastSessionPasskey);
        }
        return builder;
    }

    private <T> CompletableFuture<T> executeAndParse(AdminMessage request, Function<AdminMessage, T> extractor) {
        return client.executeAdminRequest(client.getSelfNodeId(), request)
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
        if (index < 0 || index >= MAX_CHANNEL_SLOTS) {
            throw new IllegalArgumentException("Channel index out of range (expected 0-7): " + index);
        }
    }

    private static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        CompletableFuture<List<T>> seed = CompletableFuture.completedFuture(new ArrayList<>(futures.size()));
        return futures.stream().reduce(
                seed,
                (acc, next) -> acc.thenCompose(list -> next.thenApply(value -> {
                    list.add(value);
                    return list;
                })),
                (left, right) -> left.thenCombine(right, (l, r) -> {
                    l.addAll(r);
                    return l;
                }));
    }
}
