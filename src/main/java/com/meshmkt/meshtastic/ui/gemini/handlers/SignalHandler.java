package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A "Catch-All" handler that updates signal metadata for every incoming packet,
 * regardless of the application type (Text, Position, etc).
 * 
 * Technically optional since the AbstractNodeDatabase updates signals
 */
public class SignalHandler implements MeshtasticMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalHandler.class);
    private final NodeDatabase nodeDb;

    public SignalHandler(NodeDatabase nodeDb) {
        this.nodeDb = nodeDb;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket();
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        MeshProtos.MeshPacket packet = message.getPacket();
        
        // We update the DB for every packet heard. 
        // Our new AbstractNodeDatabase logic will handle the timestamping.
        nodeDb.updateSignal(packet.getFrom(), packet.getRxSnr(), packet.getRxRssi());
        

        return false; // Always return false so specific handlers (Text, etc) can still run
    }
}