package com.meshmkt.meshtastic.client.storage;

import com.meshmkt.meshtastic.client.MeshConstants;
import com.meshmkt.meshtastic.client.MeshUtils;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.meshtastic.proto.MeshProtos;

/**
 *
 * @author tmulle
 */
public abstract class AbstractNodeDatabase implements NodeDatabase {

    /**
     *
     */
    protected int localNodeId;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     *
     */
    protected final List<NodeDatabaseObserver> observers = new CopyOnWriteArrayList<>();

    /**
     *
     * @param id
     */
    @Override
    public void setSelfNodeId(int id) {
        this.localNodeId = id;
        getNode(id).ifPresent(this::notifyNodeUpdated);
    }

    /**
     *
     * @return
     */
    @Override
    public int getSelfNodeId() {
        return localNodeId;
    }

    /**
     *
     * @param id
     * @return
     */
    @Override
    public boolean isSelfNode(int id) {
        return id != 0 && id == localNodeId;
    }

    /**
     *
     * @return
     */
    @Override
    public Optional<MeshNode> getSelfNode() {
        return getNode(localNodeId);
    }

    /**
     *
     * @param o
     */
    @Override
    public void addObserver(NodeDatabaseObserver o) {
        observers.add(o);
    }

    /**
     *
     * @param o
     */
    @Override
    public void removeObserver(NodeDatabaseObserver o) {
        observers.remove(o);
    }

    /**
     *
     * @param n
     */
    protected void notifyNodeUpdated(MeshNode n) {
        observers.forEach(o -> o.onNodeUpdated(n));
    }

    /**
     *
     */
    protected void notifyNodesPurged() {
        observers.forEach(o -> o.onNodesPurged());
    }

    /**
     *
     * @param timeoutMins
     */
    @Override
    public void startCleanupTask(int timeoutMins) {
        scheduler.scheduleAtFixedRate(() -> {
            long cutoff = System.currentTimeMillis() - (timeoutMins * 60 * 1000L);
            performPurge(cutoff);
        }, 5, 1, TimeUnit.MINUTES);
    }

    /**
     * When the "Self" node moves, we need to iterate through every known node
     * and update how far away they are from our new position.
     * @param selfId
     */
    protected void handleSelfLocationUpdate(int selfId) {
        // We call this to let the implementation (In-Memory or SQL) 
        // provide the list of nodes to refresh.
        refreshDistancesRelativeto(selfId);
    }

    /**
     * Shared logic to calculate distance. Uses MeshUtils to handle the
     * Integer-to-Double conversion safely.
     * @param remoteId
     * @param remotePos
     * @param ctx
     * @return 
     */
    protected double calculateDistance(int remoteId, MeshProtos.Position remotePos, PacketContext ctx) {
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
                MeshUtils.toDecimal(remotePos.getLongitudeI())
        )).orElse(MeshConstants.DISTANCE_UNKNOWN);
    }

    /**
     *
     * @param cutoff
     */
    protected abstract void performPurge(long cutoff);

    /**
     * * Implementations must provide a way to iterate and update.
     * @param selfId
     */
    protected abstract void refreshDistancesRelativeto(int selfId);
}
