package com.meshmkt.meshtastic.client.event;

/**
 * High-level startup lifecycle states for a client connection.
 */
public enum StartupState {

    /**
     * No active transport session.
     */
    DISCONNECTED,

    /**
     * Connected and running startup sync phase 1 (local identity bootstrap).
     */
    SYNC_LOCAL_CONFIG,

    /**
     * Connected and running startup sync phase 2 (full config/node bootstrap).
     */
    SYNC_MESH_CONFIG,

    /**
     * Startup sync is complete and normal operations are ready.
     */
    READY
}
