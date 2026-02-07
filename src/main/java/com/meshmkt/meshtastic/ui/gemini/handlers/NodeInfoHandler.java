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
 * broadcasts.
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
                handleHandshake(message.getNodeInfo());
            } else {
                handleOverTheAir(message.getPacket());
            }
        } catch (Exception e) {
            log.error("Failed to process NodeInfo", e);
        }
        return false;
    }

    private void handleHandshake(MeshProtos.NodeInfo info) {
        // Construct a synthetic packet for the database to maintain consistency
        MeshProtos.MeshPacket dummy = MeshProtos.MeshPacket.newBuilder()
                .setFrom(info.getNum())
                .setRxTime((int) (System.currentTimeMillis() / 1000))
                .build();

        nodeDb.updateUser(dummy, info.getUser());
        dispatchDiscovery(info.getNum(), info.getUser());
    }

    private void handleOverTheAir(MeshProtos.MeshPacket packet) throws Exception {
        MeshProtos.User user = MeshProtos.User.parseFrom(packet.getDecoded().getPayload());
        nodeDb.updateUser(packet, user);
        dispatchDiscovery(packet.getFrom(), user);
    }

    private void dispatchDiscovery(int nodeId, MeshProtos.User user) {
        dispatcher.onNodeDiscovery(NodeDiscoveryEvent.builder()
                .nodeId(nodeId)
                .longName(user.getLongName())
                .shortName(user.getShortName())
                .hwModel(user.getHwModel())
                .role(user.getRole())
                .build());
    }
}
