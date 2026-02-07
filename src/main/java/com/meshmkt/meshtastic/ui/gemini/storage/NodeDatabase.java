package com.meshmkt.meshtastic.ui.gemini.storage;

import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.Collection;

/**
 * Core interface for managing the state of discovered mesh nodes.
 * <p>
 * Implementations handle the extraction of metadata from
 * {@link MeshProtos.MeshPacket} and provide thread-safe access to node
 * snapshots.
 * </p>
 */
public interface NodeDatabase {

    /**
     * Updates node identity using the provided User packet and packet context.
     */
    void updateUser(MeshProtos.MeshPacket packet, MeshProtos.User user);

    /**
     * Updates node location using the provided Position packet and packet
     * context.
     */
    void updatePosition(MeshProtos.MeshPacket packet, MeshProtos.Position position);

    /**
     * Updates node health using the provided DeviceMetrics and packet context.
     */
    void updateMetrics(MeshProtos.MeshPacket packet, TelemetryProtos.DeviceMetrics metrics);

    /**
     * Updates node sensor data using the provided EnvironmentMetrics and packet
     * context.
     */
    void updateEnvMetrics(MeshProtos.MeshPacket packet, TelemetryProtos.EnvironmentMetrics env);

    /**
     * Manually updates signal metadata for a specific node ID.
     */
    void updateSignal(int nodeId, float snr, int rssi);

    /**
     * Registers the ID of the local radio node.
     */
    void setLocalNodeId(int nodeId);

    /**
     * Returns the display name of a node, or a hex string if unknown.
     */
    String getDisplayName(int nodeId);

    /**
     * Returns true if the ID matches the local radio.
     */
    boolean isLocalNode(int nodeId);

    /**
     * Retrieves a snapshot of a specific node.
     */
    MeshNode getNode(int nodeId);

    /**
     * Retrieves a collection of all known nodes.
     */
    Collection<MeshNode> getAllNodes();

    /**
     * Starts a background task to remove nodes not seen within the timeout
     * period.
     */
    void startCleanupTask(int timeoutMins);

    
    void addObserver(NodeDatabaseObserver observer);
    void removeObserver(NodeDatabaseObserver observer);
    
    void setSyncComplete(boolean complete);

}
