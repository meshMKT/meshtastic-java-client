package com.meshmkt.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.MeshConstants;
import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.model.RadioModel;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.AdminProtos.AdminMessage.ConfigType;
import org.meshtastic.proto.AdminProtos.AdminMessage.ModuleConfigType;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.MeshProtos.DeviceMetadata;
import org.meshtastic.proto.MeshProtos.FromRadio;
import org.meshtastic.proto.MeshProtos.NodeInfo;
import org.meshtastic.proto.MeshProtos.User;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;

/**
 * Applies inbound admin/local snapshot payloads to the shared {@link RadioModel}.
 * <p>
 * This helper centralizes snapshot mutation rules and session-passkey tracking so
 * {@link AdminService} can focus on request orchestration and public API semantics.
 * </p>
 */
@Slf4j
final class AdminSnapshotApplier {

    private final RadioModel radioModel;
    private ByteString lastSessionPasskey = ByteString.EMPTY;

    /**
     * Creates a snapshot applier bound to one radio model.
     *
     * @param radioModel target radio model cache.
     */
    AdminSnapshotApplier(RadioModel radioModel) {
        this.radioModel = radioModel;
    }

    /**
     * Injects the latest known admin session key into a request builder when available.
     *
     * @param builder admin message builder to enrich.
     * @return the same builder instance.
     */
    AdminMessage.Builder withSessionIfPresent(AdminMessage.Builder builder) {
        if (!lastSessionPasskey.isEmpty()) {
            builder.setSessionPasskey(lastSessionPasskey);
        }
        return builder;
    }

    /**
     * Updates the cached session key from an inbound admin message.
     *
     * @param adminMsg inbound admin message.
     */
    void updateSessionKey(AdminMessage adminMsg) {
        if (!adminMsg.getSessionPasskey().isEmpty()) {
            this.lastSessionPasskey = adminMsg.getSessionPasskey();
            log.debug("[ADMIN] Updated Session Key: {}", MeshUtils.bytesToHex(lastSessionPasskey.toByteArray()));
        }
    }

    /**
     * Applies a decoded inbound admin message to the radio model.
     *
     * @param msg decoded admin message.
     */
    void ingestAdminMessage(AdminMessage msg) {
        updateSessionKey(msg);

        if (msg.hasGetOwnerResponse()) {
            applyOwner(msg.getGetOwnerResponse());
            log.debug("[ADMIN] Model Updated: Owner");
        }
        if (msg.hasGetConfigResponse()) {
            applyConfig(msg.getGetConfigResponse());
            log.debug("[ADMIN] Model Updated: Config");
        }
        if (msg.hasGetChannelResponse()) {
            applyChannel(msg.getGetChannelResponse());
            log.debug(
                    "[ADMIN] Model Updated: Channel Slot {}",
                    msg.getGetChannelResponse().getIndex());
        }
        if (msg.hasGetModuleConfigResponse()) {
            applyModuleConfig(msg.getGetModuleConfigResponse());
            log.debug("[ADMIN] Model Updated: Module Config");
        }
        if (msg.hasGetDeviceMetadataResponse()) {
            applyMetadata(msg.getGetDeviceMetadataResponse());
            log.debug("[ADMIN] Model Updated: Device Metadata");
        }
    }

    /**
     * Applies startup snapshot variants carried by top-level {@link FromRadio} fields.
     *
     * @param message startup snapshot message.
     */
    void ingestStartupSnapshot(FromRadio message) {
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
     * Applies local node identity from {@code my_info}.
     *
     * @param nodeId local node id.
     */
    void ingestMyInfo(int nodeId) {
        if (isKnownNodeId(nodeId)) {
            radioModel.setNodeId(nodeId);
        }
    }

    /**
     * Applies owner/node identity information from a node-info snapshot.
     *
     * @param info node info payload.
     */
    void ingestNodeInfo(NodeInfo info) {
        if (info == null) {
            return;
        }
        if (info.hasUser()) {
            applyOwner(info.getUser());
        }
        if (isKnownNodeId(info.getNum())) {
            radioModel.setNodeId(info.getNum());
        }
    }

    /**
     * Applies owner data to the radio model cache.
     *
     * @param owner owner payload.
     */
    void applyOwner(User owner) {
        if (owner != null) {
            radioModel.setOwner(owner);
            String ownerId = owner.getId();
            if (ownerId != null && !ownerId.isBlank()) {
                int parsedId = MeshUtils.parseId(ownerId);
                if (isKnownNodeId(parsedId)) {
                    radioModel.setNodeId(parsedId);
                }
            }
        }
    }

    /**
     * Applies config payload data to the radio model cache.
     *
     * @param cfg config payload.
     */
    void applyConfig(Config cfg) {
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

    /**
     * Applies channel payload data to the radio model cache.
     *
     * @param channel channel payload.
     */
    void applyChannel(Channel channel) {
        if (channel != null) {
            radioModel.setChannel(channel.getIndex(), channel);
        }
    }

    /**
     * Applies module-config payload data to the radio model cache.
     *
     * @param moduleConfig module-config payload.
     */
    void applyModuleConfig(ModuleConfig moduleConfig) {
        ModuleConfigType type = toModuleConfigType(moduleConfig);
        if (type != null) {
            radioModel.putModuleConfig(type, moduleConfig);
        }
    }

    /**
     * Applies device metadata to the radio model cache.
     *
     * @param metadata metadata payload.
     */
    void applyMetadata(DeviceMetadata metadata) {
        if (metadata != null) {
            radioModel.setDeviceMetadata(metadata);
        }
    }

    /**
     * Maps a config payload oneof variant to the corresponding admin config type.
     *
     * @param cfg config payload.
     * @return affected config types, or empty when no variant is set.
     */
    List<ConfigType> extractConfigTypes(Config cfg) {
        if (cfg == null) {
            return List.of();
        }

        ConfigType type =
                switch (cfg.getPayloadVariantCase()) {
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
     * Resolves the module-config payload variant into its admin enum type.
     *
     * @param moduleConfig module-config payload.
     * @return corresponding module config type, or {@code null} when unset.
     */
    ModuleConfigType toModuleConfigType(ModuleConfig moduleConfig) {
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
     * Returns whether a node id is a usable concrete identifier.
     *
     * @param nodeId node id candidate.
     * @return {@code true} when id is neither unknown nor broadcast.
     */
    private boolean isKnownNodeId(int nodeId) {
        return nodeId != MeshConstants.ID_UNKNOWN && nodeId != MeshConstants.ID_BROADCAST;
    }
}
