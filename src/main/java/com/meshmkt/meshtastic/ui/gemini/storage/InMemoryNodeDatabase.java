package com.meshmkt.meshtastic.ui.gemini.storage;

import lombok.Data;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * A thread-safe, memory-backed implementation of the NodeDatabase.
 * * Uses internal NodeRecord objects for storage and produces immutable 
 * MeshNode snapshots for the UI.
 */
public class InMemoryNodeDatabase extends AbstractNodeDatabase {

    @Override
    public void setSyncComplete(boolean complete) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Data
    private static class NodeRecord {
        private MeshProtos.User user;
        private MeshProtos.Position position;
        private TelemetryProtos.DeviceMetrics metrics;
        private TelemetryProtos.EnvironmentMetrics envMetrics;
        private float snr;
        private int rssi;
        private long lastSeen;
    }

    private final ConcurrentHashMap<Integer, NodeRecord> nodes = new ConcurrentHashMap<>();
    
    // Capture when the app actually started
    private final long sessionStartTime = System.currentTimeMillis();
    
    private int localNodeId = -1;

    // --- Internal Logic ---

    /**
     * Internal helper to update the record and notify observers.
     */
    private NodeRecord getOrUpdate(int id, long t) {
        NodeRecord r = nodes.computeIfAbsent(id, k -> new NodeRecord());
        r.setLastSeen(t);
        return r;
    }

    // --- Storage Overrides (Called by Handlers) ---

    @Override
    protected void storeUser(int id, MeshProtos.User u, long t) {
        NodeRecord r = getOrUpdate(id, t);
        r.setUser(u);
        notifyNodeUpdated(mapToDto(id, r));
    }

    @Override
    protected void storePosition(int id, MeshProtos.Position p, long t) {
        NodeRecord r = getOrUpdate(id, t);
        r.setPosition(p);
        notifyNodeUpdated(mapToDto(id, r));
    }

    @Override
    protected void storeMetrics(int id, TelemetryProtos.DeviceMetrics m, long t) {
        NodeRecord r = getOrUpdate(id, t);
        r.setMetrics(m);
        notifyNodeUpdated(mapToDto(id, r));
    }

    @Override
    protected void storeEnvMetrics(int id, TelemetryProtos.EnvironmentMetrics e, long t) {
        NodeRecord r = getOrUpdate(id, t);
        r.setEnvMetrics(e);
        notifyNodeUpdated(mapToDto(id, r));
    }

    @Override
    public void updateSignal(int nodeId, float snr, int rssi) {
        long now = System.currentTimeMillis();
        NodeRecord r = getOrUpdate(nodeId, now);
        r.setSnr(snr);
        r.setRssi(rssi);
        notifyNodeUpdated(mapToDto(nodeId, r));
    }

    // --- Identity & Meta ---

    @Override
    public void setLocalNodeId(int id) {
        this.localNodeId = id;
    }

    @Override
    public boolean isLocalNode(int id) {
        return id != -1 && id == localNodeId;
    }

    @Override
    public String getDisplayName(int id) {
        NodeRecord r = nodes.get(id);
        if (r != null && r.getUser() != null && !r.getUser().getLongName().isEmpty()) {
            return r.getUser().getLongName();
        }
        return String.format("!%08x", id);
    }

    // --- Retrieval (For UI) ---

    @Override
    public MeshNode getNode(int id) {
        NodeRecord r = nodes.get(id);
        return (r == null) ? null : mapToDto(id, r);
    }

    @Override
    public Collection<MeshNode> getAllNodes() {
        return nodes.entrySet().stream()
                .map(e -> mapToDto(e.getKey(), e.getValue()))
                .collect(Collectors.toUnmodifiableList());
    }

    // --- Purge Logic ---

    @Override
    protected void performPurge(long cutoff) {
        boolean removed = nodes.entrySet().removeIf(e -> e.getValue().getLastSeen() < cutoff);
        if (removed) {
            notifyNodesPurged(); 
        }
    }

    // --- Mapping ---

    private MeshNode mapToDto(int id, NodeRecord r) {
        
        long rawLastSeen = r.getLastSeen();
    boolean isLocal = isLocalNode(id);

    // If it's US, we use the real timestamp immediately.
    // If it's a remote node, we apply the 60s sync grace period.
    long uiLastSeen = (isLocal) ? rawLastSeen : getNormalizedLastSeen(rawLastSeen);
    
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
                .lastSeen(uiLastSeen)
                .isSelf(isLocalNode(id))
                .build();
    }
}