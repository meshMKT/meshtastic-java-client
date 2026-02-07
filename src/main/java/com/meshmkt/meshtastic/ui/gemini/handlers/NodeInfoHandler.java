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
 * Handles NODEINFO data from both the initial radio handshake and 
 * over-the-air broadcasts.
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
        // Case A: Initial handshake NodeInfo (direct from local radio storage)
        if (message.hasNodeInfo()) {
            return true;
        }

        // Case B: Over-the-air MeshPacket NodeInfo
        return message.hasPacket()
                && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.NODEINFO_APP_VALUE;
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        try {
            if (message.hasNodeInfo()) {
                handleHandshake(message.getNodeInfo());
            } else {
                handleOverTheAir(message.getPacket());
            }
        } catch (Exception e) {
            log.error("Failed to process NodeInfo packet", e);
        }
        return false;
    }

    /**
     * Handshake data comes from the local radio's internal DB.
     * There is no MeshPacket context here, so we call a manual update.
     */
    private void handleHandshake(MeshProtos.NodeInfo info) {
        if (info.hasUser()) {
            MeshProtos.User user = info.getUser();
            int nodeId = info.getNum();
            
            // Because there is no packet, we manually trigger the update.
            // We can pass a 'null' packet if your DB implementation handles it,
            // or simply use our manual update methods.
            processUserUpdate(nodeId, user);
        }
    }

    /**
     * OTA data arrives as a MeshPacket. This includes SNR/RSSI.
     */
    private void handleOverTheAir(MeshProtos.MeshPacket packet) throws Exception {
        MeshProtos.User user = MeshProtos.User.parseFrom(packet.getDecoded().getPayload());
        
        // Use the new packet-aware update!
        nodeDb.updateUser(packet, user);
        
        // Also fire the UI event
        dispatchDiscovery(packet.getFrom(), user);
    }

    /**
     * Internal helper for the handshake path where no packet exists.
     */
    private void processUserUpdate(int nodeId, MeshProtos.User user) {
        // We'll use a dummy packet or a direct method for handshakes 
        // since we want to avoid NullPointerExceptions in our Abstract class.
        nodeDb.updateUser(MeshProtos.MeshPacket.newBuilder().setFrom(nodeId).build(), user);
        dispatchDiscovery(nodeId, user);
    }

    private void dispatchDiscovery(int nodeId, MeshProtos.User user) {
        dispatcher.onNodeDiscovery(NodeDiscoveryEvent.builder()
                .nodeId(nodeId)
                .longName(user.getLongName())
                .shortName(user.getShortName())
                .hwModel(user.getHwModel())
                .role(user.getRole()) // Now including the Role in the event!
                .build());
    }
}