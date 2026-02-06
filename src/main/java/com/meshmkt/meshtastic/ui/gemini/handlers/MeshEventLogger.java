package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A generalized logging handler that records significant mesh events (Messages,
 * GPS updates, and Telemetry) to a dedicated SLF4J logger.
 */
public class MeshEventLogger implements MeshtasticMessageHandler {

    // This logger can be routed to its own file via SLF4J configuration
    private static final Logger eventLog = LoggerFactory.getLogger("MeshEvents");
    private final NodeDatabase nodeDb;

    public MeshEventLogger(NodeDatabase nodeDb) {
        this.nodeDb = nodeDb;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        // We handle everything interesting coming from the mesh
        return message.hasPacket() || message.hasNodeInfo();
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        if (message.hasNodeInfo()) {
            eventLog.info("NODE_SYNC: {} joined/updated", nodeDb.getDisplayName(message.getNodeInfo().getNum()));
        } else if (message.hasPacket()) {
            int from = message.getPacket().getFrom();
            int port = message.getPacket().getDecoded().getPortnumValue();
            eventLog.debug("PACKET: From={} Port={}", nodeDb.getDisplayName(from), port);
        }
        return false; // Always allow other handlers to see the data
    }
}
