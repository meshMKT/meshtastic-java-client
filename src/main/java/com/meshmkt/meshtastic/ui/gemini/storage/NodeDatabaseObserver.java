package com.meshmkt.meshtastic.ui.gemini.storage;

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