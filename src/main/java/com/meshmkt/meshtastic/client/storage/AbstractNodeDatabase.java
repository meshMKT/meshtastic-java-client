package com.meshmkt.meshtastic.client.storage;

import build.buf.gen.meshtastic.Position;
import com.meshmkt.meshtastic.client.MeshConstants;
import com.meshmkt.meshtastic.client.MeshUtils;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * Base node-database implementation that provides shared observer, self-node, and stale-node cleanup behavior.
 */
public abstract class AbstractNodeDatabase implements NodeDatabase {
    private static final String NODE_CLEANUP_THREAD_NAME = "Mesh-NodeCleanup";

    /**
     * Local node id once startup sync has identified the radio's owner identity.
     */
    protected int localNodeId;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, NODE_CLEANUP_THREAD_NAME);
        t.setDaemon(true);
        return t;
    });
    private final Object cleanupTaskLock = new Object();
    private ScheduledFuture<?> cleanupTask;

    /**
     * Registered observers that want push-style updates when nodes change or are purged.
     */
    protected final List<NodeDatabaseObserver> observers = new CopyOnWriteArrayList<>();

    /**
     * Stores the local node id and emits an update for the existing self snapshot when present.
     *
     * @param id local node id.
     */
    @Override
    public void setSelfNodeId(int id) {
        this.localNodeId = id;
        getNode(id).ifPresent(this::notifyNodeUpdated);
    }

    /**
     * Returns the current local node id.
     *
     * @return local node id, or {@code 0} when unknown.
     */
    @Override
    public int getSelfNodeId() {
        return localNodeId;
    }

    /**
     * Returns whether the supplied node id matches the local node id.
     *
     * @param id node id to compare.
     * @return {@code true} when {@code id} is the local node.
     */
    @Override
    public boolean isSelfNode(int id) {
        return id != 0 && id == localNodeId;
    }

    /**
     * Returns the current local node snapshot when available.
     *
     * @return local node snapshot.
     */
    @Override
    public Optional<MeshNode> getSelfNode() {
        return getNode(localNodeId);
    }

    /**
     * Registers an observer.
     *
     * @param o observer to register.
     */
    @Override
    public void addObserver(NodeDatabaseObserver o) {
        observers.add(o);
    }

    /**
     * Removes a previously registered observer.
     *
     * @param o observer to remove.
     */
    @Override
    public void removeObserver(NodeDatabaseObserver o) {
        observers.remove(o);
    }

    /**
     * Notifies observers that one node has changed.
     *
     * @param n updated node snapshot.
     */
    protected void notifyNodeUpdated(MeshNode n) {
        observers.forEach(o -> o.onNodeUpdated(n));
    }

    /**
     * Notifies observers that a purge operation removed one or more nodes.
     */
    protected void notifyNodesPurged() {
        observers.forEach(o -> o.onNodesPurged());
    }

    /**
     * Starts scheduled stale-node cleanup using the supplied policy.
     *
     * @param policy cleanup schedule and retention policy.
     */
    @Override
    public void startCleanupTask(NodeCleanupPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        synchronized (cleanupTaskLock) {
            cancelCleanupTaskLocked();
            cleanupTask = scheduler.scheduleAtFixedRate(
                    () -> purgeStaleNodes(policy.getStaleAfter()),
                    policy.getInitialDelay().toMillis(),
                    policy.getInterval().toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stopCleanupTask() {
        synchronized (cleanupTaskLock) {
            cancelCleanupTaskLocked();
        }
    }

    @Override
    public void purgeStaleNodes(Duration staleAfter) {
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must be greater than zero");
        }
        long cutoff = System.currentTimeMillis() - staleAfter.toMillis();
        performPurge(cutoff);
    }

    @Override
    public boolean isCleanupTaskRunning() {
        synchronized (cleanupTaskLock) {
            return cleanupTask != null && !cleanupTask.isCancelled() && !cleanupTask.isDone();
        }
    }

    /**
     * Stops internal cleanup scheduler threads.
     */
    @Override
    public void shutdown() {
        stopCleanupTask();
        scheduler.shutdownNow();
    }

    private void cancelCleanupTaskLocked() {
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
            cleanupTask = null;
        }
    }

    /**
     * Recalculates relative distance metadata after the local node's position changes.
     *
     * @param selfId local node id whose position was updated.
     */
    protected void handleSelfLocationUpdate(int selfId) {
        // We call this to let the implementation (In-Memory or SQL)
        // provide the list of nodes to refresh.
        refreshDistancesRelativeto(selfId);
    }

    /**
     * Shared logic for calculating node-to-self distance, including sentinel handling for MQTT and self nodes.
     *
     * @param remoteId remote node id.
     * @param remotePos remote node position payload.
     * @param ctx packet context for the update.
     * @return distance in kilometers or one of the negative sentinel constants from {@link MeshConstants}.
     */
    protected double calculateDistance(int remoteId, Position remotePos, PacketContext ctx) {
        if (ctx != null && ctx.isViaMqtt()) {
            return MeshConstants.DISTANCE_MQTT;
        }
        if (isSelfNode(remoteId)) {
            return MeshConstants.DISTANCE_SELF;
        }

        return getSelfNode()
                .filter(MeshNode::hasGpsFix)
                .filter(self -> remotePos != null && remotePos.getLatitudeI() != 0)
                .map(self -> MeshUtils.calculateDistance(
                        MeshUtils.toDecimal(self.getPosition().getLatitudeI()),
                        MeshUtils.toDecimal(self.getPosition().getLongitudeI()),
                        MeshUtils.toDecimal(remotePos.getLatitudeI()),
                        MeshUtils.toDecimal(remotePos.getLongitudeI())))
                .orElse(MeshConstants.DISTANCE_UNKNOWN);
    }

    /**
     * Removes nodes older than the supplied cutoff.
     *
     * @param cutoff epoch-millisecond cutoff timestamp.
     */
    protected abstract void performPurge(long cutoff);

    /**
     * Recalculates distance fields relative to the supplied local node id.
     *
     * @param selfId local node id.
     */
    protected abstract void refreshDistancesRelativeto(int selfId);
}
