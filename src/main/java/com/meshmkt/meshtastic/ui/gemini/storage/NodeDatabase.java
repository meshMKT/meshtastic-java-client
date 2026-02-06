package com.meshmkt.meshtastic.ui.gemini.storage;

import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.Collection;

/**
 * Defines the contract for storing and retrieving Meshtastic node information.
 * <p>
 * This interface decouples the high-level application logic from the underlying
 * storage mechanism. Implementations are expected to handle the translation
 * between raw Protobuf messages and the internal system state.
 * </p>
 */
public interface NodeDatabase {

    // --- Write Methods ---
    /**
     * Updates or initializes the user identification for a node.
     *
     * * @param nodeId The 32-bit unsigned integer ID of the node.
     * @param user The {@link MeshProtos.User} protobuf containing names and
     * hardware info.
     */
    void updateUser(int nodeId, MeshProtos.User user);

    /**
     * Updates the geographic coordinates and altitude for a node.
     *
     * * @param nodeId The unique ID of the node.
     * @param position The {@link MeshProtos.Position} protobuf containing GPS
     * data.
     */
    void updatePosition(int nodeId, MeshProtos.Position position);

    /**
     * Updates device-specific health data such as battery level and voltage.
     *
     * * @param nodeId The unique ID of the node.
     * @param metrics The {@link TelemetryProtos.DeviceMetrics} protobuf.
     */
    void updateMetrics(int nodeId, TelemetryProtos.DeviceMetrics metrics);

    /**
     * Records the radio signal quality for the last packet received from this
     * node.
     * <p>
     * Typically called by a PacketHandler or Dispatcher to track link quality.
     * </p>
     *
     * * @param nodeId The unique ID of the node.
     * @param snr The Signal-to-Noise Ratio (dB).
     * @param rssi The Received Signal Strength Indicator (dBm).
     */
    void updateSignal(int nodeId, float snr, int rssi);

    /**
     * Sets the ID of the local node (the Pi-connected device).
     *
     * * @param nodeId The local node's numeric ID.
     */
    void setLocalNodeId(int nodeId);

    // --- Read Methods ---
    /**
     * Resolves a node ID to a human-readable name. Should fall back to the
     * "!hexid" format if the name is unknown.
     *
     * * @param nodeId The unique ID of the node.
     * @return A string representing the node's Long Name or its Hex ID.
     */
    String getDisplayName(int nodeId);

    /**
     * Checks if the provided ID belongs to the local radio node.
     *
     * * @param nodeId The ID to check.
     * @return {@code true} if the ID matches the local node; {@code false}
     * otherwise.
     */
    boolean isLocalNode(int nodeId);

    /**
     * Retrieves a complete snapshot of a node's known data.
     *
     * * @param nodeId The unique ID of the node.
     * @return A {@link MeshNode} DTO, or {@code null} if the node has not been
     * discovered.
     */
    MeshNode getNode(int nodeId);

    /**
     * Retrieves all known nodes currently stored in the database.
     *
     * * @return A collection of {@link MeshNode} DTOs.
     */
    Collection<MeshNode> getAllNodes();
}
