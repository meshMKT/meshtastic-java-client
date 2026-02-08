package com.meshmkt.meshtastic.ui.gemini.storage;

import com.meshmkt.meshtastic.ui.gemini.GeoUtils;
import lombok.Data;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

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
        private boolean online = true; // New status field
    }

    private final ConcurrentHashMap<Integer, NodeRecord> nodes = new ConcurrentHashMap<>();

    private NodeRecord getOrUpdate(int id, MeshProtos.MeshPacket p, PacketContext ctx, long localTime) {
        NodeRecord r = nodes.computeIfAbsent(id, k -> {
            log.debug("New node discovered: !{}", Integer.toHexString(id));
            return new NodeRecord();
        });

        // RESURRECTION: If a node was offline and we just heard from it, mark it online
        if (!r.isOnline()) {
            r.setOnline(true);
            log.info("Node !{} is back ONLINE", Integer.toHexString(id));
        }

        if (ctx != null) {
            r.setLastContext(ctx);
        }

        if (p != null && p.getRxTime() != 0) {
            r.setLastSeenRemote(p.getRxTime() * 1000L);
        }

        if (localTime > 0) {
            r.setLastSeenLocal(localTime);
        }

        return r;
    }

    /**
     * SOFT PURGE: Instead of removing from the map, we mark as offline. This
     * preserves Name/HW info so UI stays pretty even when nodes are quiet.
     */
    @Override
    protected void performPurge(long cutoff) {
        nodes.forEach((id, record) -> {
            long lastSeen = Math.max(record.getLastSeenLocal(), record.getLastSeenRemote());

            if (lastSeen < cutoff && record.isOnline()) {
                record.setOnline(false);
                log.info("Node !{} marked OFFLINE due to inactivity", Integer.toHexString(id));
                notifyNodeUpdated(mapToDto(id, record));
            }
        });

        // We still call this to let UI refresh any global 'count' labels
        notifyNodesPurged();
    }

    @Override
    protected void storePosition(MeshProtos.Position pos, MeshProtos.MeshPacket p, PacketContext ctx, long time) {
        int senderId = p.getFrom();
        NodeRecord r = getOrUpdate(senderId, p, ctx, time);
        r.setPosition(pos);

        // Use the PARENT'S logic to calculate this specific node's distance
        r.setDistanceKm(calculateDistance(senderId, pos, ctx));

        // Notify UI of this specific node update
        notifyNodeUpdated(mapToDto(senderId, r));

        // 2. CRITICAL: If WE are the one who just moved, 
        // we must update the distance for everyone else in the list.
        if (isSelfNode(senderId)) {
            log.debug("Self-Position updated. Recalculating distances for all nodes.");
            nodes.forEach((id, record) -> {
                if (id != senderId) {
                    // Calculate relative to our new position
                    record.setDistanceKm(calculateDistance(id, record.getPosition(), record.getLastContext()));
                    // If you want the UI to refresh immediately for all nodes:
                    notifyNodeUpdated(mapToDto(id, record));
                }
            });
        }
    }

    @Override
    protected void storeUser(MeshProtos.User u, MeshProtos.MeshPacket p, PacketContext ctx, long time) {
        int id = (p != null) ? p.getFrom() : (u != null ? localNodeId : 0);
        if (id == 0) {
            return;
        }

        NodeRecord r = getOrUpdate(id, p, ctx, time);
        r.setUser(u);
        notifyNodeUpdated(mapToDto(id, r));
    }

    @Override
    protected void storeMetrics(TelemetryProtos.DeviceMetrics m, MeshProtos.MeshPacket p, PacketContext ctx, long time) {
        NodeRecord r = getOrUpdate(p.getFrom(), p, ctx, time);
        r.setMetrics(m);
        notifyNodeUpdated(mapToDto(p.getFrom(), r));
    }

    @Override
    protected void storeEnvMetrics(TelemetryProtos.EnvironmentMetrics e, MeshProtos.MeshPacket p, PacketContext ctx, long time) {
        NodeRecord r = getOrUpdate(p.getFrom(), p, ctx, time);
        r.setEnvMetrics(e);
        notifyNodeUpdated(mapToDto(p.getFrom(), r));
    }

    @Override
    protected void storeSignal(int nodeId, PacketContext ctx, long time) {
        NodeRecord r = getOrUpdate(nodeId, null, ctx, time);
        notifyNodeUpdated(mapToDto(nodeId, r));
    }

    private MeshNode mapToDto(int id, NodeRecord r) {
        String bestName = (r.getUser() != null && r.getUser().getLongName() != null && !r.getUser().getLongName().isEmpty())
                ? r.getUser().getLongName()
                : String.format("!%08x", id);

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
                .online(r.isOnline()) // Added to DTO
                .build();
    }

    @Override
    public MeshNode getNode(int id) {
        NodeRecord r = nodes.get(id);
        return r != null ? mapToDto(id, r) : null;
    }

    @Override
    public Collection<MeshNode> getAllNodes() {
        return nodes.entrySet().stream()
                .map(e -> mapToDto(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }
}
