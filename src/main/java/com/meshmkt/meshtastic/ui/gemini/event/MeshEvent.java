package com.meshmkt.meshtastic.ui.gemini.event;

import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import lombok.Getter;
import org.meshtastic.proto.MeshProtos;
import java.time.Instant;

/**
 * The "Smart Envelope" for all mesh traffic. Centralizes the extraction of
 * radio metrics and routing data.
 */
@Getter
public abstract class MeshEvent {

    /**
     * The Mesh ID of the sender (e.g., !a1b2c3d4).
     */
    private int nodeId;

    /**
     * The Mesh ID of the recipient (could be Broadcast ID 0xFFFFFFFF).
     */
    private int destinationId;

    /**
     * The channel index this packet arrived on (0=Primary, 1-7=Secondary).
     */
    private int channel;

    /**
     * Number of hops the packet took to reach this node.
     */
    private int hopsAway;

    /**
     * Signal strength (Received Signal Strength Indicator) in dBm.
     */
    private float rssi;

    /**
     * Signal-to-noise ratio in dB.
     */
    private float snr;

    /**
     * Indicates if the packet was received via an MQTT gateway.
     */
    private boolean isViaMqtt;

    /**
     * The original Protobuf packet for deep data inspection if needed.
     */
    private MeshProtos.MeshPacket rawPacket;

    /**
     * Local timestamp of when the event was processed by the app.
     */
    private final Instant timestamp = Instant.now();

    /**
     * Fluent "Stamper" method to populate shared metadata.
     *
     * * @param p The raw MeshPacket from the radio.
     * @param ctx The packet context containing hop info.
     * @param selfId Our own Node ID to detect MQTT origin.
     * @return This instance, cast to the specific subclass type.
     */
    @SuppressWarnings("unchecked")
    protected <T extends MeshEvent> T applyMetadata(MeshProtos.MeshPacket p, PacketContext ctx, int selfId) {
        this.nodeId = p.getFrom();
        this.destinationId = p.getTo();
        this.channel = p.getChannel();
        this.rssi = p.getRxRssi();
        this.snr = p.getRxSnr();
        this.rawPacket = p;
        this.hopsAway = (ctx != null) ? ctx.getHopsAway() : 0;

        // MQTT Detection: No hops remaining and not sent by ourselves.
        this.isViaMqtt = (p.getHopLimit() == 0 && p.getFrom() != selfId);

        return (T) this;
    }
}
