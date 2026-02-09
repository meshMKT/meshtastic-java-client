package com.meshmkt.meshtastic.ui.gemini.storage;

import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.Collection;
import java.util.Optional;

/**
 * The central authority for Meshtastic node state. This interface standardizes
 * data entry via PacketContext to ensure signal metadata and identity are
 * always coupled.
 */
public interface NodeDatabase {

    /**
     * Updates user identity (Names/Hardware).
     */
    void updateUser(MeshProtos.User user, PacketContext ctx);

    /**
     * Updates geographic coordinates and triggers distance recalculation.
     */
    void updatePosition(MeshProtos.Position position, PacketContext ctx);

    /**
     * Updates device battery and health vitals.
     */
    void updateMetrics(TelemetryProtos.DeviceMetrics metrics, PacketContext ctx);

    /**
     * Updates sensor data (Temp/Humidity/Pressure).
     */
    void updateEnvMetrics(TelemetryProtos.EnvironmentMetrics env, PacketContext ctx);

    /**
     * Updates signal metadata (SNR/RSSI) without changing payload data.
     */
    void updateSignal(PacketContext ctx);

    void setSelfNodeId(int nodeId);

    int getSelfNodeId();

    boolean isSelfNode(int nodeId);

    Optional<MeshNode> getNode(int nodeId);

    Optional<MeshNode> getSelfNode();

    Collection<MeshNode> getAllNodes();

    void clear();

    void addObserver(NodeDatabaseObserver observer);

    void removeObserver(NodeDatabaseObserver observer);

    void startCleanupTask(int timeoutMins);
}
