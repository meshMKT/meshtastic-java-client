package com.meshmkt.meshtastic.ui.gemini.storage;

import com.meshmkt.meshtastic.ui.gemini.GeoUtils;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.List;
import java.util.concurrent.*;

/**
 * Abstract base for NodeDatabase implementations.
 * <p>
 * Handles observer management, cleanup scheduling, and the logic that
 * distinguishes between "Cached" (initial sync) and "Live" (real-time) packets.
 * </p>
 */
public abstract class AbstractNodeDatabase implements NodeDatabase {

    private boolean syncComplete = false;
    protected int localNodeId;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    protected final List<NodeDatabaseObserver> observers = new CopyOnWriteArrayList<>();

    @Override
    public final void updateUser(MeshProtos.MeshPacket p, MeshProtos.User u, PacketContext ctx) {
        storeUser(u, p, ctx, getArrivalTimestamp());
    }

    @Override
    public final void updatePosition(MeshProtos.MeshPacket p, MeshProtos.Position pos, PacketContext ctx) {
        storePosition(pos, p, ctx, getArrivalTimestamp());
    }

    @Override
    public final void updateMetrics(MeshProtos.MeshPacket p, TelemetryProtos.DeviceMetrics m, PacketContext ctx) {
        storeMetrics(m, p, ctx, getArrivalTimestamp());
    }

    @Override
    public final void updateEnvMetrics(MeshProtos.MeshPacket p, TelemetryProtos.EnvironmentMetrics e, PacketContext ctx) {
        storeEnvMetrics(e, p, ctx, getArrivalTimestamp());
    }

    @Override
    public final void updateSignal(int nodeId, PacketContext ctx) {
        storeSignal(nodeId, ctx, getArrivalTimestamp());
    }

    /**
     * Logic for local timestamping.
     *
     * @return Current time if sync is complete, otherwise 0 to flag as
     * 'Historical'.
     */
    private long getArrivalTimestamp() {
        return isSyncComplete() ? System.currentTimeMillis() : 0;
    }

    protected abstract void storeSignal(int nodeId, PacketContext ctx, long time);

    protected abstract void storeUser(MeshProtos.User u, MeshProtos.MeshPacket p, PacketContext ctx, long time);

    protected abstract void storePosition(MeshProtos.Position pos, MeshProtos.MeshPacket p, PacketContext ctx, long time);

    protected abstract void storeMetrics(TelemetryProtos.DeviceMetrics m, MeshProtos.MeshPacket p, PacketContext ctx, long time);

    protected abstract void storeEnvMetrics(TelemetryProtos.EnvironmentMetrics e, MeshProtos.MeshPacket p, PacketContext ctx, long time);

    protected abstract void performPurge(long cutoff);

    @Override
    public void setSelfNodeId(int id) {
        this.localNodeId = id;
    }

    @Override
    public boolean isSelfNode(int id) {
        return id == localNodeId;
    }

    @Override
    public MeshNode getSelfNode() {
        return getNode(localNodeId);
    }

    @Override
    public boolean isSyncComplete() {
        return syncComplete;
    }

    @Override
    public void setSyncComplete(boolean c) {
        this.syncComplete = c;
        notifyNodesPurged();
    }

    @Override
    public void addObserver(NodeDatabaseObserver o) {
        observers.add(o);
    }

    @Override
    public void removeObserver(NodeDatabaseObserver o) {
        observers.remove(o);
    }

    protected void notifyNodeUpdated(MeshNode n) {
        observers.forEach(o -> o.onNodeUpdated(n));
    }

    protected void notifyNodesPurged() {
        observers.forEach(NodeDatabaseObserver::onNodesPurged);
    }

    @Override
    public void startCleanupTask(int timeoutMins) {
        scheduler.scheduleAtFixedRate(() -> {
            // Parent calculates the timestamp cutoff
            long cutoff = System.currentTimeMillis() - (timeoutMins * 60 * 1000L);
            // Parent tells the child: "Clean up anything older than this"
            performPurge(cutoff);
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Calculates distance from the local node to a remote node. Returns -1.0 if
     * distance cannot be calculated, or -2.0 if via MQTT.
     */
    protected double calculateDistance(int remoteId, MeshProtos.Position remotePos, PacketContext ctx) {
        // 1. Check if the message came via the Internet (MQTT)
        if (ctx != null && ctx.isViaMqtt()) {
            return -2.0;
        }

        // 2. Check if we are looking at our own node
        if (isSelfNode(remoteId)) {
            return 0.0;
        }

        // 3. Attempt math if both nodes have GPS fixes
        MeshNode self = getSelfNode();
        if (self != null && self.hasGpsFix() && remotePos != null && remotePos.getLatitudeI() != 0) {
            return GeoUtils.calculateDistance(
                    self.getPosition().getLatitudeI() / 1e7, self.getPosition().getLongitudeI() / 1e7,
                    remotePos.getLatitudeI() / 1e7, remotePos.getLongitudeI() / 1e7
            );
        }

        return -1.0; // Unknown/No Fix
    }
}
