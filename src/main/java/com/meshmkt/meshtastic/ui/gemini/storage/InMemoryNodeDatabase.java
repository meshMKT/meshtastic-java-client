package com.meshmkt.meshtastic.ui.gemini.storage;

import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.Data;

/**
 * Thread-safe, in-memory implementation of the {@link NodeDatabase}.
 * <p>
 * This class serves as the central state repository for all discovered nodes in
 * the mesh. It encapsulates raw Protobuf models within internal
 * {@code NodeRecord} objects and exposes data via clean {@link MeshNode} DTOs
 * to prevent Protobuf leakage into the UI/Logic layers.
 * </p>
 */
public class InMemoryNodeDatabase implements NodeDatabase {

    /**
     * Internal mutable container for node state. Use {@code @Data} for internal
     * boilerplate, but keep it private to maintain encapsulation.
     */
    @Data
    private static class NodeRecord {

        private MeshProtos.User user;
        private MeshProtos.Position position;
        private TelemetryProtos.DeviceMetrics metrics;
        private float snr;
        private int rssi;
        private long lastSeen = System.currentTimeMillis();
    }

    private final ConcurrentHashMap<Integer, NodeRecord> nodes = new ConcurrentHashMap<>();
    private int localNodeId;

    /**
     * Updates or creates a user record for a specific node.
     *
     * @param nodeId The unique numeric ID of the node.
     * @param user The Protobuf User message containing names and hardware info.
     */
    @Override
    public void updateUser(int nodeId, MeshProtos.User user) {
        nodes.computeIfAbsent(nodeId, k -> new NodeRecord()).setUser(user);
        nodes.get(nodeId).setLastSeen(System.currentTimeMillis());
    }

    /**
     * Updates the GPS/Position data for a node.
     *
     * @param nodeId The unique numeric ID of the node.
     * @param position The Protobuf Position message.
     */
    @Override
    public void updatePosition(int nodeId, MeshProtos.Position position) {
        nodes.computeIfAbsent(nodeId, k -> new NodeRecord()).setPosition(position);
        nodes.get(nodeId).setLastSeen(System.currentTimeMillis());
    }

    /**
     * Updates environmental or device health metrics (battery, voltage, etc.).
     *
     * @param nodeId The unique numeric ID of the node.
     * @param metrics The Protobuf DeviceMetrics message.
     */
    @Override
    public void updateMetrics(int nodeId, TelemetryProtos.DeviceMetrics metrics) {
        nodes.computeIfAbsent(nodeId, k -> new NodeRecord()).setMetrics(metrics);
        nodes.get(nodeId).setLastSeen(System.currentTimeMillis());
    }

    /**
     * Updates the last known signal quality for a node.
     *
     * @param nodeId The unique numeric ID of the node.
     * @param snr Signal-to-Noise Ratio.
     * @param rssi Received Signal Strength Indicator.
     */
    @Override
    public void updateSignal(int nodeId, float snr, int rssi) {
        NodeRecord record = nodes.computeIfAbsent(nodeId, k -> new NodeRecord());
        record.setSnr(snr);
        record.setRssi(rssi);
        record.setLastSeen(System.currentTimeMillis());
    }

    /**
     * Resolves a human-readable name for a node, falling back to Hex ID if
     * unknown.
     *
     * @param nodeId The unique numeric ID of the node.
     * @return The Long Name (e.g., "MightyNode") or formatted Hex (e.g.,
     * "!a1b2c3d4").
     */
    @Override
    public String getDisplayName(int nodeId) {
        NodeRecord record = nodes.get(nodeId);
        if (record != null && record.getUser() != null) {
            String name = record.getUser().getLongName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return String.format("!%08x", nodeId);
    }

    @Override
    public void setLocalNodeId(int nodeId) {
        this.localNodeId = nodeId;
    }

    @Override
    public boolean isLocalNode(int nodeId) {
        return nodeId == localNodeId;
    }

    /**
     * Retrieves a single node's data mapped to a developer-friendly DTO.
     *
     * @param nodeId The unique numeric ID.
     * @return A {@link MeshNode} DTO or null if not found.
     */
    @Override
    public MeshNode getNode(int nodeId) {
        NodeRecord record = nodes.get(nodeId);
        return (record == null) ? null : mapToDto(nodeId, record);
    }

    /**
     * Returns a snapshot of all known nodes.
     *
     * * @return An unmodifiable collection of {@link MeshNode} DTOs.
     * @return 
     */
    @Override
    public Collection<MeshNode> getAllNodes() {
        return nodes.entrySet().stream()
                .map(e -> mapToDto(e.getKey(), e.getValue()))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Internal mapper to keep the public DTO separate from internal state. This
     * ensures that changes to the internal storage or Protobuf structure do not
     * break external consumers.
     */
    private MeshNode mapToDto(int id, NodeRecord record) {
        return new MeshNode(
                id,
                record.getUser() != null ? record.getUser().getLongName() : null,
                record.getUser() != null ? record.getUser().getShortName() : null,
                record.getPosition(),
                record.getMetrics(),
                record.getSnr(),
                record.getRssi(),
                record.getLastSeen()
        );
    }
}
