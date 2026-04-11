package com.meshmkt.meshtastic.client.handlers;

import build.buf.gen.meshtastic.*;
import build.buf.gen.meshtastic.AdminMessage.ModuleConfigType;
import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.event.AdminModelUpdateEvent;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.service.AdminService;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles packet-based {@code ADMIN_APP} payloads and forwards decoded admin messages into {@link AdminService}.
 * <p>
 * In addition to model ingest, this handler emits {@link AdminModelUpdateEvent} notifications so UIs can react
 * to owner/config/channel/module/metadata changes while the link remains connected.
 * </p>
 */
@Slf4j
public class AdminHandler extends BaseMeshHandler {

    private final AdminService adminService;

    /**
     * Creates a new AdminHandler instance.
     *
     * @param nodeDb node database dependency.
     * @param dispatcher event dispatcher dependency.
     * @param adminService admin service dependency.
     */
    public AdminHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher, AdminService adminService) {
        super(nodeDb, dispatcher);
        this.adminService = adminService;
    }

    /**
     * Processes one decoded mesh packet for this handler.
     *
     * @param packet decoded mesh packet.
     * @param ctx packet context metadata.
     * @return {@code true} when packet processing is complete for this handler.
     */
    @Override
    protected boolean handlePacket(MeshPacket packet, PacketContext ctx) {
        int portNum = packet.getDecoded().getPortnumValue();

        if (portNum == PortNum.ADMIN_APP_VALUE) {
            try {
                AdminMessage msg = AdminMessage.parseFrom(packet.getDecoded().getPayload());

                // Admin payload flow is useful during integration but too noisy for INFO.
                log.debug(
                        "[ADMIN-RX] from={} variant={} session_key_present={}",
                        MeshUtils.formatId(ctx.getFrom()),
                        msg.getPayloadVariantCase(),
                        !msg.getSessionPasskey().isEmpty());
                log.trace("[ADMIN-RX] payload={}", msg);

                // Feed it to the service to see if the model finally populates
                adminService.ingestAdminMessage(msg);
                emitAdminModelEvents(msg);

                return true;
            } catch (Exception e) {
                log.error(
                        "[ADMIN-RX] Failed to parse AdminMessage payload from={} packet_id={}",
                        MeshUtils.formatId(ctx.getFrom()),
                        packet.getId(),
                        e);
            }
        }
        return false;
    }

    /**
     * Determines whether this handler can process the incoming message.
     *
     * @param message inbound message.
     * @return {@code true} when this handler should process the message.
     */
    @Override
    public boolean canHandle(FromRadio message) {
        return message.hasPacket() && message.getPacket().getDecoded().getPortnum() == PortNum.ADMIN_APP;
    }

    /**
     * Emits granular admin model update events for UI/cache listeners.
     *
     * @param msg decoded admin payload.
     */
    private void emitAdminModelEvents(AdminMessage msg) {
        if (msg.hasGetOwnerResponse()) {
            dispatcher.onAdminModelUpdate(AdminModelUpdateEvent.builder()
                    .section(AdminModelUpdateEvent.Section.OWNER)
                    .source("ADMIN_APP")
                    .build());
        }
        if (msg.hasGetConfigResponse()) {
            dispatcher.onAdminModelUpdate(AdminModelUpdateEvent.builder()
                    .section(AdminModelUpdateEvent.Section.CONFIG)
                    .configType(toConfigType(msg.getGetConfigResponse()))
                    .source("ADMIN_APP")
                    .build());
        }
        if (msg.hasGetChannelResponse()) {
            dispatcher.onAdminModelUpdate(AdminModelUpdateEvent.builder()
                    .section(AdminModelUpdateEvent.Section.CHANNEL)
                    .channelIndex(msg.getGetChannelResponse().getIndex())
                    .source("ADMIN_APP")
                    .build());
        }
        if (msg.hasGetModuleConfigResponse()) {
            dispatcher.onAdminModelUpdate(AdminModelUpdateEvent.builder()
                    .section(AdminModelUpdateEvent.Section.MODULE_CONFIG)
                    .moduleConfigType(toModuleConfigType(msg.getGetModuleConfigResponse()))
                    .source("ADMIN_APP")
                    .build());
        }
        if (msg.hasGetDeviceMetadataResponse()) {
            dispatcher.onAdminModelUpdate(AdminModelUpdateEvent.builder()
                    .section(AdminModelUpdateEvent.Section.DEVICE_METADATA)
                    .source("ADMIN_APP")
                    .build());
        }
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
