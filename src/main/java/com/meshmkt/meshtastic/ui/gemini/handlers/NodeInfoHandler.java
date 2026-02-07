package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.NodeDiscoveryEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles NODEINFO data from both the initial radio handshake and over-the-air
 * broadcasts. This version extracts mesh topology (Hops) to identify direct
 * neighbors immediately upon discovery.
 */
public class NodeInfoHandler implements MeshtasticMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(NodeInfoHandler.class);
    private final NodeDatabase nodeDb;
    private final MeshEventDispatcher dispatcher;

    public NodeInfoHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        this.nodeDb = nodeDb;
        this.dispatcher = dispatcher;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasNodeInfo() || (message.hasPacket() && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.NODEINFO_APP_VALUE);
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        try {
            if (message.hasNodeInfo()) {
                // Initial connection to local radio (USB/BLE)
                handleHandshake(message.getNodeInfo());
            } else {
                // Received a broadcast from another node in the mesh
                handleOverTheAir(message.getPacket());
            }
        } catch (Exception e) {
            log.error("Failed to process NodeInfo", e);
        }
        return false;
    }

    private void handleHandshake(MeshProtos.NodeInfo info) {
        // Local hardware info is always 0 hops away
        int hops = 0;

        // Construct a synthetic packet so nodeDb can treat it as a standard update
        MeshProtos.MeshPacket dummy = MeshProtos.MeshPacket.newBuilder()
                .setFrom(info.getNum())
                .setRxTime((int) (System.currentTimeMillis() / 1000))
                .setHopStart(3)
                .setHopLimit(3) // Start == Limit means 0 hops
                .build();

        nodeDb.updateUser(dummy, info.getUser());

        dispatchDiscovery(info.getNum(), info.getUser(), hops);
    }

    private void handleOverTheAir(MeshProtos.MeshPacket packet) throws Exception {
        MeshProtos.User user = MeshProtos.User.parseFrom(packet.getDecoded().getPayload());

        // Calculate Hops: How many repeaters did this pass through?
        int hops = Math.max(0, packet.getHopStart() - packet.getHopLimit());

        // Update DB with signal stats and user data
        nodeDb.updateUser(packet, user);

        dispatchDiscovery(packet.getFrom(), user, hops);
    }

    private void dispatchDiscovery(int nodeId, MeshProtos.User user, int hops) {
        // NOTE: Ensure your NodeDiscoveryEvent.builder() accepts .hopsAway(hops)
        dispatcher.onNodeDiscovery(NodeDiscoveryEvent.builder()
                .nodeId(nodeId)
                .longName(user.getLongName())
                .shortName(user.getShortName())
                .hwModel(user.getHwModel())
                .role(user.getRole())
                .hopsAway(hops)
                .build());
    }
}
