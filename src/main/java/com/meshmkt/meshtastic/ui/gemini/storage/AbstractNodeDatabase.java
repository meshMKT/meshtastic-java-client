package com.meshmkt.meshtastic.ui.gemini.storage;

import com.meshmkt.meshtastic.ui.gemini.MeshConstants;
import com.meshmkt.meshtastic.ui.gemini.MeshUtils;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.List;
import java.util.Optional;
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
        storeUser(u, p, ctx, getArrivalTimestamp(p));
    }

    @Override
    public final void updatePosition(MeshProtos.MeshPacket p, MeshProtos.Position pos, PacketContext ctx) {
        storePosition(pos, p, ctx, getArrivalTimestamp(p));
    }

    @Override
    public final void updateMetrics(MeshProtos.MeshPacket p, TelemetryProtos.DeviceMetrics m, PacketContext ctx) {
        storeMetrics(m, p, ctx, ctx.getTimestamp());
    }

    @Override
    public final void updateEnvMetrics(MeshProtos.MeshPacket p, TelemetryProtos.EnvironmentMetrics e, PacketContext ctx) {
        storeEnvMetrics(e, p, ctx, ctx.getTimestamp());
    }

    @Override
    public final void updateSignal(int nodeId, PacketContext ctx) {
        storeSignal(nodeId, ctx, ctx.getTimestamp());
    }

    /**
     * Logic for local timestamping.
     *
     * @return Current time if sync is complete, otherwise 0 to flag as
     * 'Historical'.
     */
    private long getArrivalTimestamp(MeshProtos.MeshPacket p) {
        if (p != null && p.getRxTime() != 0) {
            return p.getRxTime() * 1000L; // Convert Protobuf seconds to Java millis
        }
        return System.currentTimeMillis(); // Fallback for local messages
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
    public int getSelfNodeId() {
        return localNodeId;
    }

    @Override
    public Optional<MeshNode> getSelfNode() {
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
        }, 5, 1, TimeUnit.MINUTES);
    }

    /**
     * Calculates distance from the local node to a remote node. Returns -1.0 if
     * distance cannot be calculated, or -2.0 if via MQTT.
     */
    protected double calculateDistance(int remoteId, MeshProtos.Position remotePos, PacketContext ctx) {

        // 1. Check if the message came via the Internet (MQTT)
        if (ctx != null && ctx.isViaMqtt()) {
            return MeshConstants.DISTANCE_MQTT;
        }

        // 2. Check if we are looking at our own node
        if (isSelfNode(remoteId)) {
            return MeshConstants.DISTANCE_SELF;
        }

        // 3. Attempt math using Optional pipeline
        return getSelfNode()
                .filter(self -> self.hasGpsFix())
                .filter(self -> remotePos != null && remotePos.getLatitudeI() != 0)
                .map(self -> MeshUtils.calculateDistance(
                self.getPosition().getLatitudeI() / 1e7, self.getPosition().getLongitudeI() / 1e7,
                remotePos.getLatitudeI() / 1e7, remotePos.getLongitudeI() / 1e7
        ))
                .orElse(MeshConstants.DISTANCE_UNKNOWN);
    }
}
