package com.meshmkt.meshtastic.client.storage;

import com.meshmkt.meshtastic.client.MeshConstants;
import java.time.Duration;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * Default threshold-based status policy for {@link MeshNode} freshness/state calculation.
 */
@Getter
public final class NodeStatusPolicy implements NodeStatusCalculator {

    /**
     * Default status policy used by {@link MeshNode#getCalculatedStatus()}.
     */
    public static final NodeStatusPolicy DEFAULT = builder().build();

    private final Duration liveThreshold;
    private final Duration nonLiveThreshold;

    @Builder
    private NodeStatusPolicy(Duration liveThreshold, Duration nonLiveThreshold) {
        this.liveThreshold =
                requirePositive(liveThreshold != null ? liveThreshold : MeshConstants.LIVE_THRESHOLD, "liveThreshold");
        this.nonLiveThreshold = requirePositive(
                nonLiveThreshold != null ? nonLiveThreshold : MeshConstants.NON_LIVE_NODE_THRESHOLD,
                "nonLiveThreshold");
        if (this.liveThreshold.compareTo(this.nonLiveThreshold) > 0) {
            throw new IllegalArgumentException("liveThreshold must be less than or equal to nonLiveThreshold");
        }
    }

    @Override
    public MeshNode.NodeStatus calculate(MeshNode node, Instant now) {
        if (node.isSelf()) {
            return MeshNode.NodeStatus.SELF;
        }

        Duration ageLocal = null;
        Duration ageRadio = null;

        if (node.getLastSeenLocal() > 0) {
            ageLocal = Duration.between(Instant.ofEpochMilli(node.getLastSeenLocal()), now);
            if (ageLocal.compareTo(liveThreshold) < 0) {
                return MeshNode.NodeStatus.LIVE;
            }
        }

        if (node.getLastSeen() > 0) {
            ageRadio = Duration.between(Instant.ofEpochSecond(node.getLastSeen()), now);
        }

        if (ageLocal != null && ageLocal.compareTo(nonLiveThreshold) < 0) {
            return MeshNode.NodeStatus.IDLE;
        }

        if (ageLocal == null && ageRadio != null && ageRadio.compareTo(nonLiveThreshold) < 0) {
            return MeshNode.NodeStatus.CACHED;
        }

        return MeshNode.NodeStatus.OFFLINE;
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return duration;
    }
}
