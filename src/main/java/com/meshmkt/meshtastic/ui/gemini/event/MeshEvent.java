package com.meshmkt.meshtastic.ui.gemini.event;

import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import lombok.Getter;
import org.meshtastic.proto.MeshProtos;
import java.time.Instant;

/**
 * The "Smart Envelope" for all mesh traffic. Centralizes the extraction of
 * radio metrics, network routing data, and timing information for both live
 * mesh packets and local radio handshakes.
 */
@Getter
public abstract class MeshEvent {

    /**
     * The 32-bit unsigned Mesh ID of the sender.
     */
    private int nodeId;

    /**
     * The 32-bit unsigned Mesh ID of the recipient (Broadcast is 0xFFFFFFFF).
     */
    private int destinationId;

    /**
     * The channel index (0=Primary, 1-7=Secondary).
     */
    private int channel;

    /**
     * Estimated distance in hops from the sender to this node.
     */
    private int hopsAway;

    /**
     * Signal strength (Received Signal Strength Indicator) in dBm.
     */
    private float rssi;

    /**
     * Signal-to-noise ratio in dB. Higher is better.
     */
    private float snr;

    /**
     * Flag indicating if the packet originated from an MQTT gateway.
     */
    private boolean isViaMqtt;

    /**
     * The raw Protobuf packet for deep inspection of specific flags or headers.
     */
    private MeshProtos.MeshPacket rawPacket;

    /**
     * System timestamp of when this event was instantiated in the application.
     */
    private final Instant timestamp = Instant.now();

    /**
     * Extracts and populates shared metadata from available sources. handles
     * cases where the MeshPacket might be null (e.g., local serial sync).
     *
     * @param p The raw MeshPacket (may be null for local handshake).
     * @param ctx The context containing decoded radio metrics.
     * @param selfId The local node's ID for distance/self-check logic.
     * @param <T> The specific subclass type.
     * @return The current instance for fluent chaining.
     */
    @SuppressWarnings("unchecked")
    protected <T extends MeshEvent> T applyMetadata(MeshProtos.MeshPacket p, PacketContext ctx, int selfId) {
        // Fallback logic: Use PacketContext if the raw MeshPacket is missing
        this.nodeId = (p != null) ? p.getFrom() : ctx.getFrom();
        this.destinationId = (p != null) ? p.getTo() : ctx.getTo();
        this.channel = (p != null) ? p.getChannel() : ctx.getChannel();
        this.rssi = (p != null) ? p.getRxRssi() : ctx.getRssi();
        this.snr = (p != null) ? p.getRxSnr() : ctx.getSnr();
        this.rawPacket = p;
        this.hopsAway = (ctx != null) ? ctx.getHopsAway() : 0;
        this.isViaMqtt = (ctx != null) && ctx.isViaMqtt();

        return (T) this;
    }
}
