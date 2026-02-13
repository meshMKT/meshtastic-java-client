package com.meshmkt.meshtastic.client.storage;

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
     *
     * @param timeoutMins
     */
    void startCleanupTask(int timeoutMins);
}
