package com.meshmkt.meshtastic.client.handlers;

import build.buf.gen.meshtastic.FromRadio;
import build.buf.gen.meshtastic.MeshPacket;
import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;

/**
 * The traffic controller for all incoming radio data. It distinguishes between
 * "Live" mesh packets and "Local" status messages.
 * <p>
 * Every handler receives both a {@link NodeDatabase} and {@link MeshEventDispatcher} for a uniform constructor
 * contract. Some handlers are state-only and might not publish events directly, but they still share this base type.
 * </p>
 */
public abstract class BaseMeshHandler implements MeshtasticMessageHandler {
    /**
     * No-op dispatcher used by pure logging/diagnostic handlers that do not emit events.
     */
    private static final MeshEventDispatcher NO_OP_DISPATCHER = new MeshEventDispatcher() {
        /**
         * Dispatches chat-message events to registered listeners.
         *
         * @param event event payload.
         */
        @Override
        public void onChatMessage(com.meshmkt.meshtastic.client.event.ChatMessageEvent event) {}

        /**
         * Dispatches position-update events to registered listeners.
         *
         * @param event event payload.
         */
        @Override
        public void onPositionUpdate(com.meshmkt.meshtastic.client.event.PositionUpdateEvent event) {}

        /**
         * Dispatches telemetry-update events to registered listeners.
         *
         * @param event event payload.
         */
        @Override
        public void onTelemetryUpdate(com.meshmkt.meshtastic.client.event.TelemetryUpdateEvent event) {}

        /**
         * Dispatches node-discovery events to registered listeners.
         *
         * @param event event payload.
         */
        @Override
        public void onNodeDiscovery(com.meshmkt.meshtastic.client.event.NodeDiscoveryEvent event) {}

        /**
         * Dispatches message-status events to registered listeners.
         *
         * @param event event payload.
         */
        @Override
        public void onMessageStatusUpdate(com.meshmkt.meshtastic.client.event.MessageStatusEvent event) {}

        /**
         * Dispatches admin-model update events to registered listeners.
         *
         * @param event event payload.
         */
        @Override
        public void onAdminModelUpdate(com.meshmkt.meshtastic.client.event.AdminModelUpdateEvent event) {}
    };

    /**
     * Shared node database used to resolve names and update signal/state data.
     */
    protected final NodeDatabase nodeDb;

    /**
     * Event dispatcher used by handlers that publish mesh events.
     * State-only handlers may not emit through this field.
     */
    protected final MeshEventDispatcher dispatcher;

    /**
     * @param nodeDb shared node database.
     * @param dispatcher shared event dispatcher. Null is accepted and normalized to a no-op dispatcher.
     */
    protected BaseMeshHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        this.nodeDb = nodeDb;
        this.dispatcher = dispatcher != null ? dispatcher : NO_OP_DISPATCHER;
    }

    /**
     * Routes one FromRadio message through this handler's dispatch flow.
     *
     * @param message inbound message.
     * @return {@code true} when this handler consumed the message.
     */
    @Override
    public final boolean handle(FromRadio message) {
        // SCENARIO A: LOCAL HANDSHAKE / STATUS
        // If the message does NOT have a packet, it is the local radio
        // talking about itself or syncing its internal memory with us.
        if (!message.hasPacket()) {
            return handleNonPacketMessage(message);
        }

        // SCENARIO B: LIVE MESH PACKET
        // If it has a packet, it was heard on the mesh. It has signal
        // quality metadata (RSSI/SNR) which we extract into the PacketContext.
        MeshPacket packet = message.getPacket();
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
     * @param message incoming local-only radio message.
     * @return {@code true} if handled.
     */
    protected boolean handleNonPacketMessage(FromRadio message) {
        return false;
    }

    /**
     * Override this to handle live data heard over the mesh frequency. These
     * ALWAYS contain signal metadata.
     * @param packet decoded mesh packet.
     * @param ctx derived packet context (signal, relay, timestamps).
     * @return {@code true} if handled.
     */
    protected boolean handlePacket(MeshPacket packet, PacketContext ctx) {
        return false;
    }

    /**
     * Resolves a display name for a node if known, else formatted hex id.
     *
     * @param nodeId node number.
     * @return display-friendly name.
     */
    protected String resolveName(int nodeId) {
        return nodeDb.getNode(nodeId).map(MeshUtils::resolveName).orElse(MeshUtils.formatId(nodeId));
    }
}
