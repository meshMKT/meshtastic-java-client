package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshConstants;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.NodeDiscoveryEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Handles identity discovery. Bridges the gap between "Nodes I'm learning about
 * from local memory" (Non-Packet) and "Nodes I just heard shouting their name"
 * (Packet).
 */
@Slf4j
public class NodeInfoHandler extends BaseMeshHandler {

    /**
     *
     * @param nodeDb
     * @param dispatcher
     */
    public NodeInfoHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        super(nodeDb, dispatcher);
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasNodeInfo() || (message.hasPacket() && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP);
    }

    /**
     * LOCAL HANDSHAKE (Non-Packet): The radio is dumping its internal list of
     * nodes to us via Serial/Bluetooth. These represent "Cached" nodes and do
     * not have current signal (SNR/RSSI) data.
     * @param message
     * @return 
     */
    @Override
    protected boolean handleNonPacketMessage(MeshProtos.FromRadio message) {
        if (message.hasNodeInfo()) {
            MeshProtos.NodeInfo info = message.getNodeInfo();
            MeshProtos.User user = info.getUser();

            // We are just updating the 'Device' timestamp from the sync.
            // The MeshNode's getCalculatedStatus() will see this and 
            // return CACHED or OFFLINE based on how old that 'lastHeard' is.
            PacketContext localCtx = PacketContext.builder()
                    .from(info.getNum())
                    .timestamp(info.getLastHeard() * 1000L)
                    .live(false)
                    .build();

            // 1. Update User Identity (Names/Hardware)
            nodeDb.updateUser(user, localCtx);

            // 2. Update cached Position if available
            if (info.hasPosition()) {
                nodeDb.updatePosition(info.getPosition(), localCtx);
            }

            // 3. Update cached Device Metrics (Battery/Voltage)
            // This is the part I missed - the radio often caches how much battery 
            // your friends had the last time it heard from them.
            if (info.hasDeviceMetrics()) {
                nodeDb.updateMetrics(info.getDeviceMetrics(), localCtx);
            }

            // Fire discovery event to populate the UI list
            dispatcher.onNodeDiscovery(NodeDiscoveryEvent.of(null, localCtx, nodeDb.getSelfNodeId(), user));
            return true;
        }
        return false;
    }

    /**
     * OVER-THE-AIR BROADCAST (Packet): A node just broadcast its User info to
     * the whole mesh. This IS a live radio event and includes signal metadata
     * (SNR/RSSI).
     * @param packet
     * @param ctx
     * @return 
     */
    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        try {
            MeshProtos.User user = MeshProtos.User.parseFrom(packet.getDecoded().getPayload());

            log.info("[OTA DISCOVERY] Learned about !{} ({}) | Signal: {}dB",
                    Integer.toHexString(packet.getFrom()), user.getLongName(), ctx.getSnr());

            // Simple update: We pass the User and the PacketContext (which contains SNR/RSSI)
            nodeDb.updateUser(user, ctx);

            dispatcher.onNodeDiscovery(NodeDiscoveryEvent.of(packet, ctx, nodeDb.getSelfNodeId(), user));
            return true;
        } catch (Exception e) {
            log.error("Failed to parse live NodeInfo payload", e);
            return false;
        }
    }
}
