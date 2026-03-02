package com.meshmkt.meshtastic.client.storage;

import com.meshmkt.meshtastic.client.MeshConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies node status semantics for live, idle, cached, and offline states.
 * <p>
 * These tests lock in the intended distinction:
 * </p>
 * <ul>
 * <li>CACHED = only snapshot/radio timeline data (no app-local hearing yet).</li>
 * <li>IDLE = app has heard this node, but not within the live window.</li>
 * </ul>
 */
class MeshNodeStatusTest {

    @Test
    void returnsSelfForSelfNode() {
        MeshNode node = baseNodeBuilder()
                .self(true)
                .build();

        assertEquals(MeshNode.NodeStatus.SELF, node.getCalculatedStatus());
    }

    @Test
    void returnsLiveWhenLocalAgeIsWithinLiveThreshold() {
        long nowMs = System.currentTimeMillis();
        MeshNode node = baseNodeBuilder()
                .lastSeenLocal(nowMs - 60_000L)
                .lastSeen((nowMs / 1000L) - 120L)
                .build();

        assertEquals(MeshNode.NodeStatus.LIVE, node.getCalculatedStatus());
    }

    @Test
    void returnsIdleWhenLocallyHeardButOutsideLiveThreshold() {
        long nowMs = System.currentTimeMillis();
        long justOutsideLiveMs = (MeshConstants.LIVE_THRESHOLD_SECONDS * 1000L) + 5_000L;
        MeshNode node = baseNodeBuilder()
                .lastSeenLocal(nowMs - justOutsideLiveMs)
                .lastSeen((nowMs / 1000L) - (MeshConstants.LIVE_THRESHOLD_SECONDS + 10L))
                .build();

        assertEquals(MeshNode.NodeStatus.IDLE, node.getCalculatedStatus());
    }

    @Test
    void returnsCachedWhenOnlySnapshotDataExistsAndIsFreshEnough() {
        long nowSec = System.currentTimeMillis() / 1000L;
        MeshNode node = baseNodeBuilder()
                .lastSeenLocal(0L)
                .lastSeen(nowSec - 600L)
                .build();

        assertEquals(MeshNode.NodeStatus.CACHED, node.getCalculatedStatus());
    }

    @Test
    void returnsOfflineWhenOnlySnapshotDataIsStale() {
        long nowSec = System.currentTimeMillis() / 1000L;
        MeshNode node = baseNodeBuilder()
                .lastSeenLocal(0L)
                .lastSeen(nowSec - (MeshConstants.STALE_NODE_THRESHOLD_SECONDS + 60L))
                .build();

        assertEquals(MeshNode.NodeStatus.OFFLINE, node.getCalculatedStatus());
    }

    @Test
    void returnsOfflineWhenLocalAndSnapshotAgesAreStale() {
        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000L;
        long staleMs = (MeshConstants.STALE_NODE_THRESHOLD_SECONDS * 1000L) + 5_000L;
        MeshNode node = baseNodeBuilder()
                .lastSeenLocal(nowMs - staleMs)
                .lastSeen(nowSec - (MeshConstants.STALE_NODE_THRESHOLD_SECONDS + 10L))
                .build();

        assertEquals(MeshNode.NodeStatus.OFFLINE, node.getCalculatedStatus());
    }

    /**
     * Minimal node builder helper for status tests.
     */
    private MeshNode.MeshNodeBuilder baseNodeBuilder() {
        return MeshNode.builder()
                .nodeId(0x12345678)
                .longName("Test Node")
                .shortName("test")
                .self(false)
                .lastSeen(0L)
                .lastSeenLocal(0L);
    }
}
