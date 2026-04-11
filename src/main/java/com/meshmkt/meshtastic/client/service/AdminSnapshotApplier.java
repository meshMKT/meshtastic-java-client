package com.meshmkt.meshtastic.client.service;

import build.buf.gen.meshtastic.*;
import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.MeshConstants;
import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.model.RadioModel;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

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
        for (AdminMessage.ConfigType type : extractConfigTypes(cfg)) {
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
        AdminMessage.ModuleConfigType type = toModuleConfigType(moduleConfig);
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
    List<AdminMessage.ConfigType> extractConfigTypes(Config cfg) {
        if (cfg == null) {
            return List.of();
        }

        AdminMessage.ConfigType type =
                switch (cfg.getPayloadVariantCase()) {
                    case DEVICE -> AdminMessage.ConfigType.DEVICE_CONFIG;
                    case POSITION -> AdminMessage.ConfigType.POSITION_CONFIG;
                    case POWER -> AdminMessage.ConfigType.POWER_CONFIG;
                    case NETWORK -> AdminMessage.ConfigType.NETWORK_CONFIG;
                    case DISPLAY -> AdminMessage.ConfigType.DISPLAY_CONFIG;
                    case LORA -> AdminMessage.ConfigType.LORA_CONFIG;
                    case BLUETOOTH -> AdminMessage.ConfigType.BLUETOOTH_CONFIG;
                    case SECURITY -> AdminMessage.ConfigType.SECURITY_CONFIG;
                    case SESSIONKEY -> AdminMessage.ConfigType.SESSIONKEY_CONFIG;
                    case DEVICE_UI -> AdminMessage.ConfigType.DEVICEUI_CONFIG;
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
    AdminMessage.ModuleConfigType toModuleConfigType(ModuleConfig moduleConfig) {
        if (moduleConfig == null) {
            return null;
        }

        return switch (moduleConfig.getPayloadVariantCase()) {
            case MQTT -> AdminMessage.ModuleConfigType.MQTT_CONFIG;
            case SERIAL -> AdminMessage.ModuleConfigType.SERIAL_CONFIG;
            case EXTERNAL_NOTIFICATION -> AdminMessage.ModuleConfigType.EXTNOTIF_CONFIG;
            case STORE_FORWARD -> AdminMessage.ModuleConfigType.STOREFORWARD_CONFIG;
            case RANGE_TEST -> AdminMessage.ModuleConfigType.RANGETEST_CONFIG;
            case TELEMETRY -> AdminMessage.ModuleConfigType.TELEMETRY_CONFIG;
            case CANNED_MESSAGE -> AdminMessage.ModuleConfigType.CANNEDMSG_CONFIG;
            case AUDIO -> AdminMessage.ModuleConfigType.AUDIO_CONFIG;
            case REMOTE_HARDWARE -> AdminMessage.ModuleConfigType.REMOTEHARDWARE_CONFIG;
            case NEIGHBOR_INFO -> AdminMessage.ModuleConfigType.NEIGHBORINFO_CONFIG;
            case AMBIENT_LIGHTING -> AdminMessage.ModuleConfigType.AMBIENTLIGHTING_CONFIG;
            case DETECTION_SENSOR -> AdminMessage.ModuleConfigType.DETECTIONSENSOR_CONFIG;
            case PAXCOUNTER -> AdminMessage.ModuleConfigType.PAXCOUNTER_CONFIG;
            case STATUSMESSAGE -> AdminMessage.ModuleConfigType.STATUSMESSAGE_CONFIG;
            case TRAFFIC_MANAGEMENT -> AdminMessage.ModuleConfigType.TRAFFICMANAGEMENT_CONFIG;
            case TAK -> AdminMessage.ModuleConfigType.TAK_CONFIG;
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
