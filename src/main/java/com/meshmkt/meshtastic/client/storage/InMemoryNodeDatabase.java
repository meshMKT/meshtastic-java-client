package com.meshmkt.meshtastic.client.storage;

import build.buf.gen.meshtastic.*;
import com.meshmkt.meshtastic.client.MeshConstants;
import com.meshmkt.meshtastic.client.MeshUtils;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author tmulle
 */
@Slf4j
public class InMemoryNodeDatabase extends AbstractNodeDatabase {

    /**
     * Internal storage entry. We've removed the 'online' boolean and
     * 'PacketContext' object in favor of raw fields to ensure "Sticky" signal
     * data.
     */
    @Data
    private static class NodeRecord {

        private User user;
        private Position position;
        private DeviceMetrics metrics;
        private EnvironmentMetrics envMetrics;

        // Signal Vitals (Last Known Good from a LIVE packet)
        private float snr;
        private int rssi;
        private int hopsAway;
        private int lastChannel;
        private boolean viaMqtt;

        private double distanceKm = -1.0;

        // Timestamps for status calculation
        private long lastSeenRemote; // Radio Hardware Time (seconds)
        private long lastSeenLocal; // PC System Time (ms)
    }

    private final ConcurrentHashMap<Integer, NodeRecord> nodes = new ConcurrentHashMap<>();

    /**
     * Standardizes the Fetch-Update-Notify workflow. Decisions on "Live" vs
     * "Cached" happen here to ensure data integrity.
     */
    private void updateNodeRecord(PacketContext ctx, Consumer<NodeRecord> updater) {
        int id = (ctx != null && ctx.getFrom() != 0) ? ctx.getFrom() : getSelfNodeId();
        if (id == 0) {
            return;
        }

        NodeRecord r = nodes.computeIfAbsent(id, k -> new NodeRecord());

        if (ctx != null) {
            // 1. Always update the Radio's internal timestamp (Remote)
            if (ctx.getTimestamp() != 0) {
                r.setLastSeenRemote(ctx.getTimestamp() / 1000);
            }

            // 2. Promotion Logic: Only update signal vitals if it's a real mesh packet.
            // This prevents a "Node Dump" from zeroing out your SNR/RSSI.
            if (ctx.isLive()) {
                r.setLastSeenLocal(System.currentTimeMillis());
                r.setSnr(ctx.getSnr());
                r.setRssi(ctx.getRssi());
                r.setHopsAway(ctx.getHopsAway());
                r.setLastChannel(ctx.getChannel());
                r.setViaMqtt(ctx.isViaMqtt());
            }
        }

        // Apply the specific update (User, Position, etc.)
        updater.accept(r);

        // Map to DTO and notify listeners
        notifyNodeUpdated(mapToDto(id, r));
    }

    /**
     * Recomputes cached node distances relative to the provided self node id.
     *
     * @param selfId local node id used as distance reference.
     */
    @Override
    protected void refreshDistancesRelativeto(int selfId) {
        nodes.forEach((id, record) -> {
            if (id != selfId && record.getPosition() != null) {
                /*
                 * Preserve MQTT semantic distance when recomputing after self-position updates.
                 * We do not have a PacketContext here, so use sticky per-node transport metadata.
                 */
                double newDist = record.isViaMqtt()
                        ? MeshConstants.DISTANCE_MQTT
                        : calculateDistance(id, record.getPosition(), null);
                record.setDistanceKm(newDist);
                notifyNodeUpdated(mapToDto(id, record));
            }
        });
    }

    /**
     * Applies user identity fields from an inbound packet context into the node entry.
     *
     * @param u user payload from node info.
     * @param ctx packet context metadata.
     */
    @Override
    public void updateUser(User u, PacketContext ctx) {
        updateNodeRecord(ctx, r -> r.setUser(u));
    }

    /**
     * Applies node position fields from an inbound packet context into the node entry.
     *
     * @param pos position payload.
     * @param ctx packet context metadata.
     */
    @Override
    public void updatePosition(Position pos, PacketContext ctx) {
        // GATEKEEPER: Don't overwrite good data with "no fix" (0,0)
        if (pos.getLatitudeI() == 0 && pos.getLongitudeI() == 0) {
            log.debug("Skipping position update: No valid GPS fix from !{}", Integer.toHexString(ctx.getFrom()));
            return;
        }

        updateNodeRecord(ctx, r -> {
            r.setPosition(pos);
            if (isSelfNode(ctx.getFrom())) {
                handleSelfLocationUpdate(getSelfNodeId());
            } else {
                r.setDistanceKm(calculateDistance(ctx.getFrom(), pos, ctx));
            }
        });
    }

    /**
     * Applies RF signal metrics from packet context into the node entry.
     *
     * @param ctx packet context metadata.
     */
    @Override
    public void updateSignal(PacketContext ctx) {
        updateNodeRecord(ctx, r -> {
            /* metadata updated in updateNodeRecord */
        });
    }

    /**
     * Applies device telemetry metrics into the node entry.
     *
     * @param m device metrics payload.
     * @param ctx packet context metadata.
     */
    @Override
    public void updateMetrics(DeviceMetrics m, PacketContext ctx) {
        updateNodeRecord(ctx, r -> r.setMetrics(m));
    }

    /**
     * Applies environment telemetry metrics into the node entry.
     *
     * @param e error or event payload, depending on callback context.
     * @param ctx packet context metadata.
     */
    @Override
    public void updateEnvMetrics(EnvironmentMetrics e, PacketContext ctx) {
        updateNodeRecord(ctx, r -> r.setEnvMetrics(e));
    }

    /**
     *
     */
    @Override
    public void clear() {
        nodes.clear();
        notifyNodesPurged();
    }

    /**
     *
     * @param cutoffMs
     */
    @Override
    protected void performPurge(long cutoffMs) {
        long cutoffSecs = cutoffMs / 1000;
        nodes.entrySet().removeIf(entry -> {
            NodeRecord r = entry.getValue();
            // Keep if seen locally recently OR if the radio has seen it recently
            boolean recentLocal = r.getLastSeenLocal() > cutoffMs;
            boolean recentRemote = r.getLastSeenRemote() > cutoffSecs;
            return !recentLocal && !recentRemote;
        });
        notifyNodesPurged();
    }

    /**
     *
     * @return
     */
    @Override
    public Collection<MeshNode> getAllNodes() {
        return nodes.entrySet().stream()
                .map(e -> mapToDto(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    /**
     *
     * @param id
     * @return
     */
    @Override
    public Optional<MeshNode> getNode(int id) {
        NodeRecord r = nodes.get(id);
        return r != null ? Optional.of(mapToDto(id, r)) : Optional.empty();
    }

    /**
     * Maps the internal Historian entry to a clean, immutable DTO. The DTO's
     * getCalculatedStatus() will handle the "Online/Offline" logic.
     */
    private MeshNode mapToDto(int id, NodeRecord r) {
        String bestName = MeshUtils.resolveName(id, r.getUser());

        return MeshNode.builder()
                .nodeId(id)
                .longName(bestName)
                .shortName(r.getUser() != null ? r.getUser().getShortName() : null)
                .hwModel(r.getUser() != null ? r.getUser().getHwModel() : null)
                .role(r.getUser() != null ? r.getUser().getRole() : null)
                .position(r.getPosition())
                .deviceMetrics(r.getMetrics())
                .envMetrics(r.getEnvMetrics())
                // Use the sticky signal data
                .snr(r.getSnr())
                .rssi(r.getRssi())
                .hopsAway(r.getHopsAway())
                .lastChannel(r.getLastChannel())
                .isMqtt(r.isViaMqtt())
                .distanceKm(r.getDistanceKm())
                // Timestamps passed directly to the DTO
                .lastSeen(r.getLastSeenRemote())
                .lastSeenLocal(r.getLastSeenLocal())
                .self(isSelfNode(id))
                .build();
    }
}
