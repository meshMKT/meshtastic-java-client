package com.meshmkt.meshtastic.client.event;

/**
 * Event listener contract for receiving client lifecycle and mesh data updates.
 * <p>
 * All methods are default no-ops so callers can override only the callbacks they need.
 * </p>
 */
public interface MeshtasticEventListener {

    /**
     * Fired when a text message is received from the mesh.
     *
     * @param event chat message event payload.
     */
    default void onTextMessage(ChatMessageEvent event) {
        // No-op by default. Override only when needed.
    }

    /**
     * Fired when a node reports a new GPS location.
     *
     * @param event position update payload.
     */
    default void onPositionUpdate(PositionUpdateEvent event) {
        // No-op by default. Override only when needed.
    }

    /**
     * Fired when battery or environment data arrives.
     *
     * @param event telemetry update payload.
     */
    default void onTelemetryUpdate(TelemetryUpdateEvent event) {
        // No-op by default. Override only when needed.
    }

    /**
     * Fired when a new node is discovered or updated (names/HW).
     *
     * @param event node discovery/update payload.
     */
    default void onNodeDiscovery(NodeDiscoveryEvent event) {
        // No-op by default. Override only when needed.
    }

    /**
     * Fired when a message acknowledgment (ACK) or error arrives.
     * <p>
     * This allows callers to track whether outbound sends were accepted, rejected, or timed out.
     * </p>
     *
     * @param event message status payload.
     */
    default void onMessageStatusUpdate(MessageStatusEvent event) {
        // No-op by default. Override only when needed.
    }

    /**
     * Fired when the client startup lifecycle state changes.
     *
     * @param previousState previous startup state.
     * @param newState new startup state.
     */
    default void onStartupStateChanged(StartupState previousState, StartupState newState) {
        // No-op by default. Override only when needed.
    }

    /**
     * Fired when an outbound request lifecycle transitions (sent/accepted/rejected/timeout/etc).
     *
     * @param event request lifecycle event.
     */
    default void onRequestLifecycleUpdate(RequestLifecycleEvent event) {
        // No-op by default. Override only when needed.
    }

    /**
     * Fired when local admin model state changes (owner/config/channel/module/metadata).
     *
     * @param event admin model update event.
     */
    default void onAdminModelUpdate(AdminModelUpdateEvent event) {
        // No-op by default. Override only when needed.
    }

}
