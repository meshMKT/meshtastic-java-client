package com.meshmkt.meshtastic.ui.gemini.storage;

import com.meshmkt.meshtastic.ui.gemini.MeshUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class InMemoryNodeDatabase extends AbstractNodeDatabase {

    @Data
    private static class NodeRecord {

        private MeshProtos.User user;
        private MeshProtos.Position position;
        private TelemetryProtos.DeviceMetrics metrics;
        private TelemetryProtos.EnvironmentMetrics envMetrics;
        private PacketContext lastContext;
        private double distanceKm = -1.0;
        private long lastSeenRemote;
        private long lastSeenLocal;
        private boolean online = true;
    }

    private final ConcurrentHashMap<Integer, NodeRecord> nodes = new ConcurrentHashMap<>();

    /**
     * THE SHARED CORE: Standardizes the Fetch-Update-Notify workflow. This
     * eliminates redundant code and ensures metadata is always updated.
     */
    private void updateNodeRecord(PacketContext ctx, Consumer<NodeRecord> updater) {
        int id = (ctx != null && ctx.getFrom() != 0) ? ctx.getFrom() : getSelfNodeId();
        if (id == 0) {
            return;
        }

        NodeRecord r = nodes.computeIfAbsent(id, k -> new NodeRecord());

        if (ctx != null) {
            r.setLastContext(ctx);
            r.setLastSeenRemote(ctx.getTimestamp());
            r.setLastSeenLocal(System.currentTimeMillis());
        }

        updater.accept(r);

        // If this update was for US, trigger the global refresh in the base class
        if (isSelfNode(id)) {
            handleSelfLocationUpdate(id);
        }

        notifyNodeUpdated(mapToDto(id, r));
    }

    @Override
    protected void refreshDistancesRelativeto(int selfId) {
        nodes.forEach((id, record) -> {
            if (id != selfId && record.getPosition() != null) {
                // Perform the math
                double newDist = calculateDistance(id, record.getPosition(), record.getLastContext());
                record.setDistanceKm(newDist);

                // Notify the UI for every node that just "moved" relative to us
                notifyNodeUpdated(mapToDto(id, record));
            }
        });
    }

    @Override
    public void updateUser(MeshProtos.User u, PacketContext ctx) {
        updateNodeRecord(ctx, r -> r.setUser(u));
    }

    @Override
    public void updatePosition(MeshProtos.Position pos, PacketContext ctx) {
        updateNodeRecord(ctx, r -> {
            r.setPosition(pos);
            r.setDistanceKm(calculateDistance(ctx.getFrom(), pos, ctx));
        });
    }

    @Override
    public void updateSignal(PacketContext ctx) {
        updateNodeRecord(ctx, r -> {
        }); // Just updates metadata/online status
    }

    @Override
    public void updateMetrics(TelemetryProtos.DeviceMetrics m, PacketContext ctx) {
        updateNodeRecord(ctx, r -> r.setMetrics(m));
    }

    @Override
    public void updateEnvMetrics(TelemetryProtos.EnvironmentMetrics e, PacketContext ctx) {
        updateNodeRecord(ctx, r -> r.setEnvMetrics(e));
    }

    @Override
    public void clear() {
        nodes.clear();
        notifyNodesPurged();
    }

    @Override
    protected void performPurge(long cutoff) {
        nodes.entrySet().removeIf(entry -> entry.getValue().getLastSeenLocal() < cutoff);
        notifyNodesPurged();
    }

    @Override
    public Collection<MeshNode> getAllNodes() {
        return nodes.entrySet().stream()
                .map(e -> mapToDto(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<MeshNode> getNode(int id) {
        NodeRecord r = nodes.get(id);
        return r != null ? Optional.of(mapToDto(id, r)) : Optional.empty();
    }

    private MeshNode mapToDto(int id, NodeRecord r) {
        // Delegate naming logic to MeshUtils for consistency
        String bestName = MeshUtils.resolveName(id, r.getUser());
        PacketContext ctx = r.getLastContext();

        return MeshNode.builder()
                .nodeId(id)
                .longName(bestName)
                .shortName(r.getUser() != null ? r.getUser().getShortName() : null)
                .hwModel(r.getUser() != null ? r.getUser().getHwModel() : null)
                .role(r.getUser() != null ? r.getUser().getRole() : null)
                .position(r.getPosition())
                .deviceMetrics(r.getMetrics())
                .envMetrics(r.getEnvMetrics())
                .snr(ctx != null ? ctx.getSnr() : 0)
                .rssi(ctx != null ? ctx.getRssi() : 0)
                .hopsAway(ctx != null ? ctx.getHopsAway() : 0)
                .lastChannel(ctx != null ? ctx.getChannel() : 0)
                .isMqtt(ctx != null && ctx.isViaMqtt())
                .distanceKm(r.getDistanceKm())
                .lastSeen(r.getLastSeenRemote())
                .lastSeenLocal(r.getLastSeenLocal())
                .self(isSelfNode(id))
                .online(r.isOnline())
                .build();
    }
}
