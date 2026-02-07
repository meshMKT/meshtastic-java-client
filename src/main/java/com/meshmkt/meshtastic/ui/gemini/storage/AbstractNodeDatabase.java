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
 * This class eliminates the need for arbitrary timers by using a logical
 * {@code syncComplete} flag. It distinguishes between historical radio memory
 * (Cached) and real-time mesh traffic (Live) by applying local system
 * timestamps only after the initial handshake is finished.
 * </p>
 */
public abstract class AbstractNodeDatabase implements NodeDatabase {

    /**
     * * Indicates if the initial radio memory dump has finished. When false,
     * incoming data is treated as historical (Cached).
     */
    private boolean syncComplete = false;

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

    @Override
    public void setSyncComplete(boolean complete) {
        this.syncComplete = complete;
        // Trigger a notification so UI can transition nodes from CACHED to LIVE status
        notifyNodesPurged();
    }

    protected void notifyNodeUpdated(MeshNode node) {
        observers.forEach(o -> o.onNodeUpdated(node));
    }

    protected void notifyNodesPurged() {
        observers.forEach(NodeDatabaseObserver::onNodesPurged);
    }

    /**
     * Standardized packet processor that extracts common metadata and applies
     * timing.
     *
     * @param packet The raw mesh packet received from the radio.
     * @param storageAction A consumer that receives the full packet and a local
     * arrival timestamp. The local timestamp is 0 if the radio is still
     * syncing.
     */
    protected void process(MeshProtos.MeshPacket packet, BiConsumer<MeshProtos.MeshPacket, Long> storageAction) {
        int id = packet.getFrom();

        // 1. Determine if this arrival counts as "Live" for this session
        long localArrival = isSyncComplete() ? System.currentTimeMillis() : 0;

        // 2. Update hardware signal stats WITH the local arrival time
        // We call a protected internal method so the implementation can save the time
        storeSignal(id, packet.getRxSnr(), packet.getRxRssi(), localArrival);

        // 3. Perform the specific storage (User, Position, etc.)
        storageAction.accept(packet, localArrival);
    }

    @Override
    public final void updateUser(MeshProtos.MeshPacket p, MeshProtos.User u) {
        process(p, (packet, localTime) -> storeUser(u, packet, localTime));
    }

    @Override
    public final void updatePosition(MeshProtos.MeshPacket p, MeshProtos.Position pos) {
        process(p, (packet, localTime) -> storePosition(pos, packet, localTime));
    }

    @Override
    public final void updateMetrics(MeshProtos.MeshPacket p, TelemetryProtos.DeviceMetrics m) {
        process(p, (packet, localTime) -> storeMetrics(m, packet, localTime));
    }

    @Override
    public final void updateEnvMetrics(MeshProtos.MeshPacket p, TelemetryProtos.EnvironmentMetrics e) {
        process(p, (packet, localTime) -> storeEnvMetrics(e, packet, localTime));
    }

    @Override
    public void startCleanupTask(int timeoutMins) {
        scheduler.scheduleAtFixedRate(() -> {
            long cutoff = System.currentTimeMillis() - ((long) timeoutMins * 60 * 1000);
            performPurge(cutoff);
        }, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public boolean isSyncComplete() {
        return syncComplete;
    }

    @Override
    public final void updateSignal(int nodeId, float snr, int rssi) {
        // If a handler calls this manually, we still apply the Live/Sync logic
        long localTime = isSyncComplete() ? System.currentTimeMillis() : 0;
        storeSignal(nodeId, snr, rssi, localTime);
    }

// Add this to your Abstract Subclass Methods at the bottom
    protected abstract void storeSignal(int nodeId, float snr, int rssi, long localTime);

    // --- Subclass Storage Methods ---
    protected abstract void storeUser(MeshProtos.User u, MeshProtos.MeshPacket p, long localTime);

    protected abstract void storePosition(MeshProtos.Position pos, MeshProtos.MeshPacket p, long localTime);

    protected abstract void storeMetrics(TelemetryProtos.DeviceMetrics m, MeshProtos.MeshPacket p, long localTime);

    protected abstract void storeEnvMetrics(TelemetryProtos.EnvironmentMetrics e, MeshProtos.MeshPacket p, long localTime);

    protected abstract void performPurge(long cutoff);
}
