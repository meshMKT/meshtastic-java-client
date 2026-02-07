package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A "Catch-All" handler that ensures signal metadata and node activity are 
 * tracked for every single packet seen on the mesh, regardless of the 
 * application type (Text, Position, Telemetry, etc).
 * <p>
 * This handler is designed to be transparent (returns false), allowing 
 * subsequent specialized handlers to process the packet payload.
 * </p>
 */
public class SignalHandler implements MeshtasticMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalHandler.class);
    private final NodeDatabase nodeDb;

    public SignalHandler(NodeDatabase nodeDb) {
        this.nodeDb = nodeDb;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        // We want to see every packet that has a sender ID and signal data
        return message.hasPacket();
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        MeshProtos.MeshPacket packet = message.getPacket();
        
        // Update the signal strength and signal-to-noise ratio.
        // The NodeDatabase implementation will handle the dual-timestamping
        // based on the current syncComplete state.
        nodeDb.updateSignal(packet.getFrom(), packet.getRxSnr(), packet.getRxRssi());
        
        // Return false so the Dispatcher continues to the next handler in the chain
        return false; 
    }
}