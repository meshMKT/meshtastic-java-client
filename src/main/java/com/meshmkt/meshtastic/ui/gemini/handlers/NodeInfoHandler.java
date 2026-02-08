package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.NodeDiscoveryEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Handles the identification of users and nodes on the network.
 * Processes both Over-The-Air (OTA) broadcasts and local handshakes.
 */
@Slf4j
public class NodeInfoHandler extends BaseMeshHandler {

    public NodeInfoHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        super(nodeDb, dispatcher);
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasNodeInfo() || (message.hasPacket() && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP);
    }

    /**
     * Handles initial handshake NodeInfo (Local/Serial connection).
     * Since this isn't a "Packet" from the mesh, we generate a synthetic event.
     */
    @Override
    protected boolean handleNonPacketMessage(MeshProtos.FromRadio message) {
        if (message.hasNodeInfo()) {
            MeshProtos.NodeInfo info = message.getNodeInfo();
            MeshProtos.User user = info.getUser();

            log.info("[LOCAL] Handshake from !{} ({})", 
                    Integer.toHexString(info.getNum()), user.getLongName());

            // Create a synthetic context and dummy packet for local discovery
            PacketContext localCtx = PacketContext.builder().hopStart(0).hopLimit(0).build();
            MeshProtos.MeshPacket dummy = MeshProtos.MeshPacket.newBuilder().setFrom(info.getNum()).build();

            // Store in DB and notify UI
            nodeDb.updateUser(dummy, user, localCtx);
            dispatcher.onNodeDiscovery(NodeDiscoveryEvent.of(dummy, localCtx, nodeDb.getSelfNodeId(), user));
            
            return true;
        }
        return false;
    }

    /**
     * Handles Over-The-Air (OTA) discovery broadcasts.
     */
    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        try {
            MeshProtos.User user = MeshProtos.User.parseFrom(packet.getDecoded().getPayload());
            
            // Fluent factory handles shared radio metadata
            NodeDiscoveryEvent event = NodeDiscoveryEvent.of(packet, ctx, nodeDb.getSelfNodeId(), user);

            log.info("[DISCOVERY] Found !{} ({}) via {} hops", 
                    Integer.toHexString(event.getNodeId()), event.getLongName(), event.getHopsAway());

            // Update database with specific User profile data
            nodeDb.updateUser(packet, user, ctx);
            
            dispatcher.onNodeDiscovery(event);
            return true;
        } catch (Exception e) {
            log.error("Failed to parse NodeInfo packet", e);
            return false;
        }
    }
}