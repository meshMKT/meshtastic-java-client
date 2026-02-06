package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intercepts every MeshPacket to update signal strength metadata (SNR and RSSI)
 * in the NodeDatabase.
 */
public class SignalHandler implements MeshtasticMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalHandler.class);
    private final NodeDatabase nodeDb;

    public SignalHandler(NodeDatabase nodeDb) {
        this.nodeDb = nodeDb;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        // Every MeshPacket contains signal metadata
        return message.hasPacket();
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        MeshProtos.MeshPacket packet = message.getPacket();
        int fromId = packet.getFrom();

        // Only update if the packet actually contains signal data (not a local loopback)
        if (packet.getRxSnr() != 0 || packet.getRxRssi() != 0) {
            float snr = packet.getRxSnr();
            int rssi = packet.getRxRssi();

            // MATCH THIS to the interface method name: updateSignal
            nodeDb.updateSignal(fromId, snr, rssi);

            log.debug("Signal from {}: SNR {}dB, RSSI {}dBm",
                    nodeDb.getDisplayName(fromId), snr, rssi);
        }

        // Return false so the packet continues to Text/Position/etc handlers
        return false;
    }
}
