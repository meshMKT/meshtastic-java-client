package com.meshmkt.meshtastic.client.storage;

import build.buf.gen.meshtastic.*;
import java.time.Duration;
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
     *
     * @param user user payload from the radio.
     * @param ctx packet context used to associate signal metadata and timestamps.
     */
    void updateUser(User user, PacketContext ctx);

    /**
     * Updates geographic coordinates and triggers distance recalculation.
     *
     * @param position position payload from the radio.
     * @param ctx packet context used to associate signal metadata and timestamps.
     */
    void updatePosition(Position position, PacketContext ctx);

    /**
     * Updates device battery and health vitals.
     *
     * @param metrics device metrics payload from the radio.
     * @param ctx packet context used to associate signal metadata and timestamps.
     */
    void updateMetrics(DeviceMetrics metrics, PacketContext ctx);

    /**
     * Updates sensor data (Temp/Humidity/Pressure).
     *
     * @param env environmental metrics payload from the radio.
     * @param ctx packet context used to associate signal metadata and timestamps.
     */
    void updateEnvMetrics(EnvironmentMetrics env, PacketContext ctx);

    /**
     * Updates signal metadata (SNR/RSSI) without changing payload data.
     *
     * @param ctx packet context containing the latest signal metadata.
     */
    void updateSignal(PacketContext ctx);

    /**
     * Sets the local node id once the client learns its own identity from startup sync.
     *
     * @param nodeId local node id.
     */
    void setSelfNodeId(int nodeId);

    /**
     * Returns the current local node id.
     *
     * @return local node id, or {@code 0} when unknown.
     */
    int getSelfNodeId();

    /**
     * Returns whether the supplied node id refers to the local node.
     *
     * @param nodeId node id to compare.
     * @return {@code true} when {@code nodeId} matches the current local node id.
     */
    boolean isSelfNode(int nodeId);

    /**
     * Looks up one node by id.
     *
     * @param nodeId node id to look up.
     * @return matching node, if present.
     */
    Optional<MeshNode> getNode(int nodeId);

    /**
     * Returns the current local node snapshot, if available.
     *
     * @return local node snapshot.
     */
    Optional<MeshNode> getSelfNode();

    /**
     * Returns all known node snapshots.
     *
     * @return collection view of known nodes.
     */
    Collection<MeshNode> getAllNodes();

    /**
     * Clears all locally cached nodes from the database implementation.
     */
    void clear();

    /**
     * Registers an observer that should receive node update callbacks.
     *
     * @param observer observer to add.
     */
    void addObserver(NodeDatabaseObserver observer);

    /**
     * Removes a previously registered observer.
     *
     * @param observer observer to remove.
     */
    void removeObserver(NodeDatabaseObserver observer);

    /**
     * Starts automatic stale-node cleanup for implementations that support background purging.
     * <p>
     * This behavior is implementation-defined. Implementations may ignore this request, provide their own scheduler,
     * or inherit the default scheduler behavior from {@code AbstractNodeDatabase}. Callers should not assume cleanup
     * starts automatically unless they enable it explicitly.
     * </p>
     *
     * @param policy cleanup scheduling policy.
     */
    default void startCleanupTask(NodeCleanupPolicy policy) {
        // no-op by default
    }

    /**
     * Stops any automatic cleanup task previously started by {@link #startCleanupTask(NodeCleanupPolicy)}.
     * <p>
     * Default implementation is a no-op for implementations that do not support background cleanup.
     * </p>
     */
    default void stopCleanupTask() {
        // no-op by default
    }

    /**
     * Performs an immediate one-shot purge using the provided stale timeout.
     * <p>
     * This is useful for applications that prefer manual cleanup over a background scheduler.
     * Default implementation is a no-op for implementations that do not support stale-node purging.
     * </p>
     *
     * @param staleAfter stale timeout window.
     */
    default void purgeStaleNodes(Duration staleAfter) {
        // no-op by default
    }

    /**
     * Returns whether an automatic cleanup task is currently active.
     * <p>
     * Default implementation returns {@code false} for implementations that do not track cleanup task state.
     * </p>
     *
     * @return {@code true} if automatic cleanup is currently active.
     */
    default boolean isCleanupTaskRunning() {
        return false;
    }

    /**
     * Releases any background resources held by this database implementation.
     * <p>
     * Default implementation is a no-op for implementations that do not allocate background workers.
     * </p>
     */
    default void shutdown() {
        // no-op by default
    }
}
