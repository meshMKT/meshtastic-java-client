package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.service.AdminService;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.MeshProtos.MeshPacket;
import org.meshtastic.proto.Portnums.PortNum;
import static org.meshtastic.proto.Portnums.PortNum.ADMIN_APP;

@Slf4j
/**
 * Handles packet-based {@code ADMIN_APP} payloads and forwards decoded admin messages into {@link AdminService}.
 * <p>
 * This handler currently acts as a state-ingest bridge and does not publish dispatcher events directly.
 * </p>
 */
public class AdminHandler extends BaseMeshHandler {

    private final AdminService adminService;

    public AdminHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher, AdminService adminService) {
        super(nodeDb, dispatcher);
        this.adminService = adminService;
    }

    @Override
    protected boolean handlePacket(MeshPacket packet, PacketContext ctx) {
        int portNum = packet.getDecoded().getPortnumValue();

        if (portNum == PortNum.ADMIN_APP_VALUE) {
            try {
                AdminMessage msg = AdminMessage.parseFrom(packet.getDecoded().getPayload());

                // Admin payload flow is useful during integration but too noisy for INFO.
                log.debug("[ADMIN-RX] from={} variant={} session_key_present={}",
                        MeshUtils.formatId(ctx.getFrom()),
                        msg.getPayloadVariantCase(),
                        !msg.getSessionPasskey().isEmpty());
                log.trace("[ADMIN-RX] payload={}", msg);

                // Feed it to the service to see if the model finally populates
                adminService.ingestAdminMessage(msg);

                return true;
            } catch (Exception e) {
                log.error("[ADMIN-RX] Failed to parse AdminMessage payload from={} packet_id={}",
                        MeshUtils.formatId(ctx.getFrom()),
                        packet.getId(),
                        e);
            }
        }
        return false;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() && message.getPacket().getDecoded().getPortnum() == ADMIN_APP;
    }
    
    
}
