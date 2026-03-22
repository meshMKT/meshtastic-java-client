package com.meshmkt.meshtastic.client.storage;

import java.time.Instant;

/**
 * Strategy interface for computing a {@link MeshNode.NodeStatus} from a node snapshot.
 */
@FunctionalInterface
public interface NodeStatusCalculator {

    /**
     * Calculates the effective status for the provided node snapshot at the provided instant.
     *
     * @param node node snapshot to evaluate.
     * @param now current evaluation time.
     * @return calculated status.
     */
    MeshNode.NodeStatus calculate(MeshNode node, Instant now);
}
