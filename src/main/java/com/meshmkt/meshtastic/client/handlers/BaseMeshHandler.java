package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import com.meshmkt.meshtastic.client.MeshUtils;
import org.meshtastic.proto.MeshProtos;

/**
 * The traffic controller for all incoming radio data. It distinguishes between
 * "Live" mesh packets and "Local" status messages.
 */
public abstract class BaseMeshHandler implements MeshtasticMessageHandler {

    /**
     *
     */
    protected final NodeDatabase nodeDb;

    /**
     *
     */
    protected final MeshEventDispatcher dispatcher;

    /**
     *
     * @param nodeDb
     * @param dispatcher
     */
    protected BaseMeshHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        this.nodeDb = nodeDb;
        this.dispatcher = dispatcher;
    }

    @Override
    public final boolean handle(MeshProtos.FromRadio message) {
        // SCENARIO A: LOCAL HANDSHAKE / STATUS
        // If the message does NOT have a packet, it is the local radio 
        // talking about itself or syncing its internal memory with us.
        if (!message.hasPacket()) {
            return handleNonPacketMessage(message);
        }

        // SCENARIO B: OVER-THE-AIR (OTA) PACKET
        // If it has a packet, it was heard on the mesh. It has signal 
        // quality metadata (RSSI/SNR) which we extract into the PacketContext.
        MeshProtos.MeshPacket packet = message.getPacket();
        PacketContext ctx = PacketContext.from(message);
        
                

        // Record the fact that this node is alive and update signal health
        if (packet.getFrom() != 0) {
            nodeDb.updateSignal(ctx);
        }

        return handlePacket(packet, ctx);
    }

    /**
     * Override this to handle local radio events like NodeInfo syncs or MyInfo.
     * These do NOT contain signal metadata (SNR/RSSI).
     * @param message
     * @return 
     */
    protected boolean handleNonPacketMessage(MeshProtos.FromRadio message) {
        return false;
    }

    /**
     * Override this to handle live data heard over the mesh frequency. These
     * ALWAYS contain signal metadata.
     * @param packet
     * @param ctx
     * @return 
     */
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        return false;
    }

    /**
     *
     * @param nodeId
     * @return
     */
    protected String resolveName(int nodeId) {
        return nodeDb.getNode(nodeId)
                .map(MeshUtils::resolveName)
                .orElse(MeshUtils.formatId(nodeId));
    }
}
