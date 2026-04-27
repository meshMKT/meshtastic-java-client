package com.meshmkt.meshtastic.client.storage;

/**
 * Observer interface for application components that want push-style node update notifications.
 */
public interface NodeDatabaseObserver {

    /**
     * Called when one node snapshot is created or updated.
     *
     * @param node updated node snapshot.
     */
    void onNodeUpdated(MeshNode node);

    /**
     * Called after the implementation purges stale nodes from local storage.
     */
    void onNodesPurged();
}
