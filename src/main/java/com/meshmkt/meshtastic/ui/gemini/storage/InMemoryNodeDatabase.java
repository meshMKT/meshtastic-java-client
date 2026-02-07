package com.meshmkt.meshtastic.ui.gemini.storage;

import lombok.Data;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * A thread-safe, memory-backed implementation of the NodeDatabase.
 * <p>
 * This implementation stores internal {@code NodeRecord} objects and maps them
 * to immutable {@link MeshNode} snapshots. It maintains the distinction between
 * remote radio time and local system arrival time.
 * </p>
 */
public class InMemoryNodeDatabase extends AbstractNodeDatabase {

    

    /**
     * Internal mutable container for node data.
     */
    @Data
    private static class NodeRecord {

        private MeshProtos.User user;
        private MeshProtos.Position position;
        private TelemetryProtos.DeviceMetrics metrics;
        private TelemetryProtos.EnvironmentMetrics envMetrics;
        private float snr;
        private int rssi;
        private long lastSeenRemote;
        private long lastSeenLocal;
    }

    private final ConcurrentHashMap<Integer, NodeRecord> nodes = new ConcurrentHashMap<>();

    @Override
    public MeshNode getSelfNode() {
        return getNode(localNodeId);
    }

    @Override
    public String getDisplayName(int nodeId) {
        NodeRecord r = nodes.get(nodeId);
        if (r != null && r.getUser() != null) {
            String name = r.getUser().getLongName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        }
        // Return the standardized Hex ID so the core is never "Empty"
        return String.format("!%08x", nodeId);
    }

    @Override
    public MeshNode getNode(int nodeId) {
        NodeRecord r = nodes.get(nodeId);
        return (r != null) ? mapToDto(nodeId, r) : null;
    }

    @Override
    public Collection<MeshNode> getAllNodes() {
        return nodes.entrySet().stream()
                .map(e -> mapToDto(e.getKey(), e.getValue()))
                .collect(Collectors.toUnmodifiableList());
    }

    private NodeRecord getOrUpdate(int id, MeshProtos.MeshPacket p, long localTime) {
        NodeRecord r = nodes.computeIfAbsent(id, k -> new NodeRecord());

        // Only update Remote Time if the packet has a valid timestamp
        if (p.getRxTime() != 0) {
            r.setLastSeenRemote(p.getRxTime() * 1000L);
        }

        // CRITICAL: Only update local time if the NEW arrival is Live.
        // Never overwrite a 'Live' timestamp with a '0' (Sync) timestamp.
        if (localTime > 0) {
            r.setLastSeenLocal(localTime);
        }

        return r;
    }

    @Override
    protected void storeUser(MeshProtos.User u, MeshProtos.MeshPacket p, long localTime) {
        NodeRecord r = getOrUpdate(p.getFrom(), p, localTime);
        r.setUser(u);
        notifyNodeUpdated(mapToDto(p.getFrom(), r));
    }

    @Override
    protected void storePosition(MeshProtos.Position pos, MeshProtos.MeshPacket p, long localTime) {
        NodeRecord r = getOrUpdate(p.getFrom(), p, localTime);
        r.setPosition(pos);
        notifyNodeUpdated(mapToDto(p.getFrom(), r));
    }

    @Override
    protected void storeMetrics(TelemetryProtos.DeviceMetrics m, MeshProtos.MeshPacket p, long localTime) {
        NodeRecord r = getOrUpdate(p.getFrom(), p, localTime);
        r.setMetrics(m);
        notifyNodeUpdated(mapToDto(p.getFrom(), r));
    }

    @Override
    protected void storeEnvMetrics(TelemetryProtos.EnvironmentMetrics e, MeshProtos.MeshPacket p, long localTime) {
        NodeRecord r = getOrUpdate(p.getFrom(), p, localTime);
        r.setEnvMetrics(e);
        notifyNodeUpdated(mapToDto(p.getFrom(), r));
    }

    @Override
    protected void storeSignal(int nodeId, float snr, int rssi, long localTime) {
        NodeRecord r = nodes.computeIfAbsent(nodeId, k -> new NodeRecord());
        r.setSnr(snr);
        r.setRssi(rssi);

        // Only update local time if the incoming packet is LIVE.
        // This prevents the T-Deck from flipping back to RECENT during a sync.
        if (localTime > 0) {
            r.setLastSeenLocal(localTime);
        }

        notifyNodeUpdated(mapToDto(nodeId, r));
    }

    @Override
    protected void performPurge(long cutoff) {
        // Use the most recent available timestamp for purge checks
        boolean removed = nodes.entrySet().removeIf(e -> {
            long lastSeen = Math.max(e.getValue().getLastSeenRemote(), e.getValue().getLastSeenLocal());
            return lastSeen > 0 && lastSeen < cutoff;
        });

        if (removed) {
            notifyNodesPurged();
        }
    }

    /**
     * Maps the internal mutable record to an immutable DTO for the Core API.
     */
    private MeshNode mapToDto(int id, NodeRecord r) {
        return MeshNode.builder()
                .nodeId(id)
                .longName(r.getUser() != null ? r.getUser().getLongName() : null)
                .shortName(r.getUser() != null ? r.getUser().getShortName() : null)
                .hwModel(r.getUser() != null ? r.getUser().getHwModel() : null)
                .role(r.getUser() != null ? r.getUser().getRole() : null)
                .position(r.getPosition())
                .deviceMetrics(r.getMetrics())
                .envMetrics(r.getEnvMetrics())
                .snr(r.getSnr())
                .rssi(r.getRssi())
                .lastSeen(r.getLastSeenRemote())
                .lastSeenLocal(r.getLastSeenLocal())
                .isSelf(isSelfNode(id))
                .build();
    }
}
