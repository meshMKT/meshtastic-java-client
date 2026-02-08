package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;

/**
 * Abstract base for all Mesh handlers. Standardizes radio metadata extraction
 * and centralized signal health updates.
 */
public abstract class BaseMeshHandler implements MeshtasticMessageHandler {

    protected final NodeDatabase nodeDb;
    protected final MeshEventDispatcher dispatcher;

    protected BaseMeshHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        this.nodeDb = nodeDb;
        this.dispatcher = dispatcher;
    }

    @Override
    public final boolean handle(MeshProtos.FromRadio message) {
        // 1. Handle messages without packets (Handshakes, MyInfo)
        if (!message.hasPacket()) {
            return handleNonPacketMessage(message);
        }

        // 2. Extract packet and radio-layer context
        MeshProtos.MeshPacket packet = message.getPacket();
        PacketContext ctx = PacketContext.from(message);

        // 3. Centralized Signal Update: Every packet processed updates the sender's health
        if (packet != null && packet.getFrom() != 0) {
            nodeDb.updateSignal(packet.getFrom(), ctx);
        }

        return handlePacket(packet, ctx);
    }

    protected boolean handleNonPacketMessage(MeshProtos.FromRadio message) {
        return false;
    }

    protected abstract boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx);

    /**
     * Helper to resolve display names for logging across all handlers.
     */
    protected String resolveName(int nodeId) {
        var node = nodeDb.getNode(nodeId);
        if (node == null) {
            return "!" + Integer.toHexString(nodeId);
        }

        if (node.getLongName() != null && !node.getLongName().isEmpty()) {
            return node.getLongName();
        }
        if (node.getShortName() != null && !node.getShortName().isEmpty()) {
            return node.getShortName();
        }

        return "!" + Integer.toHexString(nodeId);
    }
}
