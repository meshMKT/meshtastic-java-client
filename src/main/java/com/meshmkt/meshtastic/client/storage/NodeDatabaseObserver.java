package com.meshmkt.meshtastic.client.storage;

/**
 *
 * @author tmulle
 */
public interface NodeDatabaseObserver {

    /**
     *
     * @param node
     */
    void onNodeUpdated(MeshNode node);

    /**
     *
     */
    void onNodesPurged();
}