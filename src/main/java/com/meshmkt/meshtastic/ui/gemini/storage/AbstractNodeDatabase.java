package com.meshmkt.meshtastic.ui.gemini.storage;

import com.meshmkt.meshtastic.ui.gemini.MeshConstants;
import com.meshmkt.meshtastic.ui.gemini.MeshUtils;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.meshtastic.proto.MeshProtos;

public abstract class AbstractNodeDatabase implements NodeDatabase {

    protected int localNodeId;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    protected final List<NodeDatabaseObserver> observers = new CopyOnWriteArrayList<>();

    @Override
    public void setSelfNodeId(int id) {
        this.localNodeId = id;
        getNode(id).ifPresent(this::notifyNodeUpdated);
    }

    @Override
    public int getSelfNodeId() {
        return localNodeId;
    }

    @Override
    public boolean isSelfNode(int id) {
        return id != 0 && id == localNodeId;
    }

    @Override
    public Optional<MeshNode> getSelfNode() {
        return getNode(localNodeId);
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
        observers.forEach(o -> o.onNodesPurged());
    }

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
     */
    protected void handleSelfLocationUpdate(int selfId) {
        // We call this to let the implementation (In-Memory or SQL) 
        // provide the list of nodes to refresh.
        refreshDistancesRelativeto(selfId);
    }

    /**
     * Shared logic to calculate distance. Uses MeshUtils to handle the
     * Integer-to-Double conversion safely.
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

    protected abstract void performPurge(long cutoff);

    /**
     * * Implementations must provide a way to iterate and update.
     */
    protected abstract void refreshDistancesRelativeto(int selfId);
}
