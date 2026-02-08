package com.meshmkt.meshtastic.ui.gemini.storage;

import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.Collection;

/**
 * Central authority for managing the state of all discovered Meshtastic nodes.
 * <p>
 * This interface provides a high-level API for updating node information from
 * various protobuf payloads while maintaining consistent radio metadata via
 * {@link PacketContext}.
 * </p>
 */
public interface NodeDatabase {

    /**
     * Updates node identity (Long Name, Short Name, Role) and signal context.
     *
     * @param packet The raw mesh packet.
     * @param user The decoded User payload.
     * @param ctx The radio metadata context.
     */
    void updateUser(MeshProtos.MeshPacket packet, MeshProtos.User user, PacketContext ctx);

    /**
     * Updates node location and triggers relative distance recalculation.
     *
     * @param packet The raw mesh packet.
     * @param position The decoded Position payload.
     * @param ctx The radio metadata context.
     */
    void updatePosition(MeshProtos.MeshPacket packet, MeshProtos.Position position, PacketContext ctx);

    /**
     * Updates device metrics (Battery, Voltage).
     *
     * @param packet The raw mesh packet.
     * @param metrics The decoded DeviceMetrics payload.
     * @param ctx The radio metadata context.
     */
    void updateMetrics(MeshProtos.MeshPacket packet, TelemetryProtos.DeviceMetrics metrics, PacketContext ctx);

    /**
     * Updates environmental metrics (Temp, Humidity).
     *
     * @param packet The raw mesh packet.
     * @param env The decoded EnvironmentMetrics payload.
     * @param ctx The radio metadata context.
     */
    void updateEnvMetrics(MeshProtos.MeshPacket packet, TelemetryProtos.EnvironmentMetrics env, PacketContext ctx);

    /**
     * Updates the signal vitals and 'last seen' timestamps for a node. Useful
     * for packets that don't have specialized storage (e.g. Chat or ACKs).
     *
     * @param nodeId The ID of the sender.
     * @param ctx The radio metadata (SNR, RSSI, Hops).
     */
    void updateSignal(int nodeId, PacketContext ctx);

    /**
     * Registers the NodeID of the local radio to enable "Self" identification.
     *
     * @param nodeId The 32-bit integer NodeID.
     */
    void setSelfNodeId(int nodeId);

    /**
     * @return An immutable snapshot of the local node.
     */
    MeshNode getSelfNode();

    /**
     * @return An immutable snapshot of a specific node, or null if unknown.
     */
    MeshNode getNode(int nodeId);

    /**
     * @return A collection of all known node snapshots in the database.
     */
    Collection<MeshNode> getAllNodes();

    /**
     * Determines if a specific NodeID belongs to the local radio.
     *
     * @param nodeId The ID to check.
     * @return true if it is the local node.
     */
    boolean isSelfNode(int nodeId);

    /**
     * Starts a background task to purge nodes that haven't been seen for a
     * while.
     *
     * @param timeoutMins The inactivity threshold in minutes.
     */
    void startCleanupTask(int timeoutMins);

    /**
     * Indicates if the radio has finished dumping its initial internal memory.
     */
    void setSyncComplete(boolean complete);

    boolean isSyncComplete();

    void addObserver(NodeDatabaseObserver observer);

    void removeObserver(NodeDatabaseObserver observer);
}
