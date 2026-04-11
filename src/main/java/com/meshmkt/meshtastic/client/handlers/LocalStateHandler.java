package com.meshmkt.meshtastic.client.handlers;

import build.buf.gen.meshtastic.*;
import build.buf.gen.meshtastic.AdminMessage.ModuleConfigType;
import com.meshmkt.meshtastic.client.event.AdminModelUpdateEvent;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.service.AdminService;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;

/**
 * Handles local (non-packet) state updates from the attached radio transport.
 * <p>
 * This includes both identity bootstrap ({@code my_info}) and settings snapshot variants
 * ({@code config/channel/module_config/metadata}) that arrive as top-level {@link FromRadio}
 * fields rather than mesh packets.
 * </p>
 * <p>
 * This handler intentionally does not process packet-based traffic. Packet-based admin and mesh
 * updates are handled by {@link AdminHandler}, {@link NodeInfoHandler}, and other domain handlers.
 * </p>
 */
public class LocalStateHandler extends BaseMeshHandler {

    private final AdminService adminService;

    /**
     * Creates a local-state handler.
     *
     * @param nodeDb node database used for self-id and signal updates.
     * @param dispatcher shared dispatcher from the base contract (unused here).
     * @param adminService admin service model ingest target.
     */
    public LocalStateHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher, AdminService adminService) {
        super(nodeDb, dispatcher);
        this.adminService = adminService;
    }

    /**
     * Matches only local, non-packet state variants that originate from the attached radio link.
     *
     * @param message inbound {@link FromRadio} message.
     * @return {@code true} when this message carries local bootstrap/snapshot state.
     */
    @Override
    public boolean canHandle(FromRadio message) {
        return message.hasMyInfo()
                || message.hasConfig()
                || message.hasChannel()
                || message.hasModuleConfig()
                || message.hasMetadata();
    }

    /**
     * Applies local state updates into the node database and admin model cache.
     * <p>
     * {@code my_info} establishes self identity and live-local signal context, while
     * config/channel/module-config/metadata snapshots hydrate the admin model.
     * </p>
     *
     * @param message local non-packet message from the radio link.
     * @return always {@code true} once message classes for this handler are processed.
     */
    @Override
    protected boolean handleNonPacketMessage(FromRadio message) {
        // my_info establishes the local node identity and should be reflected immediately in the DB.
        if (message.hasMyInfo()) {
            int selfId = message.getMyInfo().getMyNodeNum();
            nodeDb.setSelfNodeId(selfId);
            adminService.ingestMyInfo(selfId);
            dispatcher.onAdminModelUpdate(AdminModelUpdateEvent.builder()
                    .section(AdminModelUpdateEvent.Section.LOCAL_NODE)
                    .nodeId(selfId)
                    .source("FROM_RADIO")
                    .build());

            PacketContext selfCtx = PacketContext.builder()
                    .from(selfId)
                    .live(true)
                    .timestamp(System.currentTimeMillis())
                    .build();
            nodeDb.updateSignal(selfCtx);
        }

        // Remaining local snapshots hydrate the AdminService/RadioModel cache.
        if (message.hasConfig() || message.hasChannel() || message.hasModuleConfig() || message.hasMetadata()) {
            adminService.ingestStartupSnapshot(message);
            if (message.hasConfig()) {
                dispatcher.onAdminModelUpdate(AdminModelUpdateEvent.builder()
                        .section(AdminModelUpdateEvent.Section.CONFIG)
                        .configType(toConfigType(message.getConfig()))
                        .source("FROM_RADIO")
                        .build());
            }
            if (message.hasChannel()) {
                dispatcher.onAdminModelUpdate(AdminModelUpdateEvent.builder()
                        .section(AdminModelUpdateEvent.Section.CHANNEL)
                        .channelIndex(message.getChannel().getIndex())
                        .source("FROM_RADIO")
                        .build());
            }
            if (message.hasModuleConfig()) {
                dispatcher.onAdminModelUpdate(AdminModelUpdateEvent.builder()
                        .section(AdminModelUpdateEvent.Section.MODULE_CONFIG)
                        .moduleConfigType(toModuleConfigType(message.getModuleConfig()))
                        .source("FROM_RADIO")
                        .build());
            }
            if (message.hasMetadata()) {
                dispatcher.onAdminModelUpdate(AdminModelUpdateEvent.builder()
                        .section(AdminModelUpdateEvent.Section.DEVICE_METADATA)
                        .source("FROM_RADIO")
                        .build());
            }
        }

        return true;
    }

    /**
     * This handler is local-state only and does not process mesh packets.
     *
     * @param packet mesh packet (ignored).
     * @param ctx packet context (ignored).
     * @return always {@code false}.
     */
    @Override
    protected boolean handlePacket(MeshPacket packet, PacketContext ctx) {
        return false;
    }

    /**
     * Maps config payload oneof variant to admin config type key.
     */
    private static AdminMessage.ConfigType toConfigType(Config config) {
        if (config == null) {
            return null;
        }
        return switch (config.getPayloadVariantCase()) {
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
    }

    /**
     * Maps module-config payload oneof variant to admin module config type key.
     */
    private static ModuleConfigType toModuleConfigType(ModuleConfig moduleConfig) {
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
            case TRAFFIC_MANAGEMENT -> ModuleConfigType.TRAFFICMANAGEMENT_CONFIG;
            case TAK -> ModuleConfigType.TAK_CONFIG;
            case PAYLOADVARIANT_NOT_SET -> null;
        };
    }
}
