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
public class AdminHandler extends BaseMeshHandler {

    private final AdminService adminService;

    public AdminHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher, AdminService adminService) {
        super(nodeDb, dispatcher);
        this.adminService = adminService;
    }

    @Override
    protected boolean handlePacket(MeshPacket packet, PacketContext ctx) {
        int portNum = packet.getDecoded().getPortnumValue();

        // Log EVERY packet's port to see the sequence
        log.info("[DIAGNOSTIC] Observed Packet | Port: {} | From: {} | ID: {}",
                packet.getDecoded().getPortnum(),
                MeshUtils.formatId(ctx.getFrom()),
                MeshUtils.formatId(packet.getId()));

        if (portNum == PortNum.ADMIN_APP_VALUE) {
            try {
                AdminMessage msg = AdminMessage.parseFrom(packet.getDecoded().getPayload());

                log.info("[DIAGNOSTIC] >>> ADMIN PAYLOAD DETECTED <<<");
                log.info("[DIAGNOSTIC] Variant: {}", msg.getPayloadVariantCase());
                log.info("[DIAGNOSTIC] Has Session Key: {}", !msg.getSessionPasskey().isEmpty());
                log.info("[DIAGNOSTIC] Payload: {}", msg);

                // Feed it to the service to see if the model finally populates
                adminService.updateFromRadio(msg);

                return true;
            } catch (Exception e) {
                log.error("[DIAGNOSTIC] Failed to parse AdminMessage payload", e);
            }
        }
        return false;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() && message.getPacket().getDecoded().getPortnum() == ADMIN_APP;
    }
    
    
}
