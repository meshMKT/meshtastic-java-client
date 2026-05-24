package com.meshmkt.meshtastic.client.service;

/**
 * Controls how admin write operations should complete.
 */
public enum AdminWriteMode {

    /**
     * Complete once the radio accepts the request at the routing/transport level.
     */
    ACCEPT_ONLY,

    /**
     * Complete only after a follow-up read-back confirms the requested state was applied.
     */
    VERIFY_APPLIED
}
