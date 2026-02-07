package com.meshmkt.meshtastic.ui.gemini.storage;

import java.util.List;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Base implementation of the NodeDatabase that centralizes packet processing
 * logic.
 * <p>
 * This class ensures that every update automatically captures signal metadata
 * and applies a consistent local timestamp to the "Last Seen" field.
 * </p>
 */
public abstract class AbstractNodeDatabase implements NodeDatabase {

    /**
     * * The time the application session started. Used to differentiate between
     * "Sync Data" and "Live Data".
     */
    private final long sessionStartTime = System.currentTimeMillis();

    /**
     * * The grace period (in ms) where incoming packets are treated as CACHED.
     * Meshtastic serial sync can be slow, so 60s is a safe default.
     */
    private static final long SYNC_GRACE_PERIOD_MS = 60000;

    protected int localNodeId;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    protected final List<NodeDatabaseObserver> observers = new CopyOnWriteArrayList<>();

    @Override
    public void addObserver(NodeDatabaseObserver observer) {
        if (!observers.contains(observer)) {
            observers.add(observer);
        }
    }

    @Override
    public void removeObserver(NodeDatabaseObserver observer) {
        observers.remove(observer);
    }

    // Helper methods for subclasses to trigger notifications
    protected void notifyNodeUpdated(MeshNode node) {
        observers.forEach(o -> o.onNodeUpdated(node));
    }

    protected void notifyNodesPurged() {
        observers.forEach(NodeDatabaseObserver::onNodesPurged);
    }

    /**
     * Extracts common metadata from a packet and executes the specific storage
     * action.
     *
     * @param packet The raw mesh packet received from the radio.
     * @param storageAction A consumer that receives the sender ID and current
     * timestamp.
     */
    protected void process(MeshProtos.MeshPacket packet, BiConsumer<Integer, Long> storageAction) {
        int id = packet.getFrom();
        long now = System.currentTimeMillis();

        updateSignal(id, packet.getRxSnr(), packet.getRxRssi());
        storageAction.accept(id, now);
    }

    @Override
    public final void updateUser(MeshProtos.MeshPacket p, MeshProtos.User u) {
        process(p, (id, time) -> storeUser(id, u, time));
    }

    @Override
    public final void updatePosition(MeshProtos.MeshPacket p, MeshProtos.Position pos) {
        process(p, (id, time) -> storePosition(id, pos, time));
    }

    @Override
    public final void updateMetrics(MeshProtos.MeshPacket p, TelemetryProtos.DeviceMetrics m) {
        process(p, (id, time) -> storeMetrics(id, m, time));
    }

    @Override
    public final void updateEnvMetrics(MeshProtos.MeshPacket p, TelemetryProtos.EnvironmentMetrics e) {
        process(p, (id, time) -> storeEnvMetrics(id, e, time));
    }

    @Override
    public void startCleanupTask(int timeoutMins) {
        scheduler.scheduleAtFixedRate(() -> {
            long cutoff = System.currentTimeMillis() - ((long) timeoutMins * 60 * 1000);
            performPurge(cutoff);
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Normalizes the lastSeen timestamp for the UI. If the packet was heard
     * during the initial sync period, it returns 0 to indicate it is "Cached"
     * data.
     */
    /**
     * Normalizes the lastSeen timestamp for the UI. Now detects if a packet is
     * "Live" (happening now) to bypass the sync window.
     */
    protected long getNormalizedLastSeen(long rawLastSeen) {
        long now = System.currentTimeMillis();

        // LIVE BYPASS: If the packet was heard within the last 3 seconds, 
        // it's real-time traffic. We trust it immediately.
        if (Math.abs(now - rawLastSeen) < 3000) {
            return rawLastSeen;
        }

        // SYNC WINDOW: If we are still in the first 60s of the app running, 
        // treat older timestamps (radio memory dump) as "Cached".
        if (rawLastSeen < sessionStartTime + SYNC_GRACE_PERIOD_MS) {
            return 0;
        }

        return rawLastSeen;
    }

    protected abstract void storeUser(int id, MeshProtos.User user, long time);

    protected abstract void storePosition(int id, MeshProtos.Position pos, long time);

    protected abstract void storeMetrics(int id, TelemetryProtos.DeviceMetrics m, long time);

    protected abstract void storeEnvMetrics(int id, TelemetryProtos.EnvironmentMetrics e, long time);

    protected abstract void performPurge(long cutoff);
}
