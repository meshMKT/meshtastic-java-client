package com.meshmkt.meshtastic.ui.gemini.storage;

public interface NodeDatabaseObserver {
    void onNodeUpdated(MeshNode node);
    void onNodesPurged();
}