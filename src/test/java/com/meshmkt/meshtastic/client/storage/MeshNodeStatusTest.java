package com.meshmkt.meshtastic.client.storage;

import com.meshmkt.meshtastic.client.MeshConstants;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

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
        long justOutsideLiveMs = MeshConstants.LIVE_THRESHOLD.toMillis() + 5_000L;
        MeshNode node = baseNodeBuilder()
                .lastSeenLocal(nowMs - justOutsideLiveMs)
                .lastSeen((nowMs / 1000L) - MeshConstants.LIVE_THRESHOLD.plusSeconds(10).toSeconds())
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
                .lastSeen(nowSec - MeshConstants.NON_LIVE_NODE_THRESHOLD.plusSeconds(60).toSeconds())
                .build();

        assertEquals(MeshNode.NodeStatus.OFFLINE, node.getCalculatedStatus());
    }

    @Test
    void returnsOfflineWhenLocalAndSnapshotAgesAreStale() {
        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000L;
        long staleMs = MeshConstants.NON_LIVE_NODE_THRESHOLD.toMillis() + 5_000L;
        MeshNode node = baseNodeBuilder()
                .lastSeenLocal(nowMs - staleMs)
                .lastSeen(nowSec - MeshConstants.NON_LIVE_NODE_THRESHOLD.plusSeconds(10).toSeconds())
                .build();

        assertEquals(MeshNode.NodeStatus.OFFLINE, node.getCalculatedStatus());
    }

    @Test
    void supportsCustomStatusPolicyWithoutChangingNodeDatabaseImplementation() {
        Instant now = Instant.now();
        MeshNode node = baseNodeBuilder()
                .lastSeenLocal(now.minusSeconds(45).toEpochMilli())
                .lastSeen(now.minusSeconds(45).getEpochSecond())
                .build();

        NodeStatusPolicy strictPolicy = NodeStatusPolicy.builder()
                .liveThreshold(Duration.ofSeconds(30))
                .nonLiveThreshold(Duration.ofMinutes(10))
                .build();

        assertEquals(MeshNode.NodeStatus.IDLE, node.getCalculatedStatus(strictPolicy, now));
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
