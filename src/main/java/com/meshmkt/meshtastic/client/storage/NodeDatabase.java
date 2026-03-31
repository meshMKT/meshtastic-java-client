package com.meshmkt.meshtastic.client.storage;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;

/**
 * The central authority for Meshtastic node state. This interface standardizes
 * data entry via PacketContext to ensure signal metadata and identity are
 * always coupled.
 */
public interface NodeDatabase {

    /**
     * Updates user identity (Names/Hardware).
     * @param user
     * @param ctx
     */
    void updateUser(MeshProtos.User user, PacketContext ctx);

    /**
     * Updates geographic coordinates and triggers distance recalculation.
     * @param position
     * @param ctx
     */
    void updatePosition(MeshProtos.Position position, PacketContext ctx);

    /**
     * Updates device battery and health vitals.
     * @param metrics
     * @param ctx
     */
    void updateMetrics(TelemetryProtos.DeviceMetrics metrics, PacketContext ctx);

    /**
     * Updates sensor data (Temp/Humidity/Pressure).
     * @param env
     * @param ctx
     */
    void updateEnvMetrics(TelemetryProtos.EnvironmentMetrics env, PacketContext ctx);

    /**
     * Updates signal metadata (SNR/RSSI) without changing payload data.
     * @param ctx
     */
    void updateSignal(PacketContext ctx);

    /**
     *
     * @param nodeId
     */
    void setSelfNodeId(int nodeId);

    /**
     *
     * @return
     */
    int getSelfNodeId();

    /**
     *
     * @param nodeId
     * @return
     */
    boolean isSelfNode(int nodeId);

    /**
     *
     * @param nodeId
     * @return
     */
    Optional<MeshNode> getNode(int nodeId);

    /**
     *
     * @return
     */
    Optional<MeshNode> getSelfNode();

    /**
     *
     * @return
     */
    Collection<MeshNode> getAllNodes();

    /**
     *
     */
    void clear();

    /**
     *
     * @param observer
     */
    void addObserver(NodeDatabaseObserver observer);

    /**
     *
     * @param observer
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
