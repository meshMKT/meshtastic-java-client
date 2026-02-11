package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabaseObserver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.swing.AbstractListModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * <h2>MeshNodeListModel</h2>
 * <p>
 * Advanced Swing model that handles surgical updates for data changes and
 * background sorting for status changes. Utilizing identity-based equality to
 * maintain selection stability.
 * </p>
 */
public class MeshNodeListModel extends AbstractListModel<MeshNode> implements NodeDatabaseObserver {

    private List<MeshNode> displayList = new ArrayList<>();
    private final Map<Integer, MeshNode> nodeLookup = new ConcurrentHashMap<>();
    private final Timer janitorTimer;
    private Runnable onRefreshComplete;

    public MeshNodeListModel() {
        // Run every 5 seconds to re-evaluate "Live" vs "Cached" status.
        this.janitorTimer = new Timer(5000, e -> {
            if (!nodeLookup.isEmpty()) {
                reSort();
            }
        });
        this.janitorTimer.start();
    }

    public void stopJanitor() {
        janitorTimer.stop();
    }

    public void setOnRefreshComplete(Runnable onRefreshComplete) {
        this.onRefreshComplete = onRefreshComplete;
    }
    
    // --- NodeDatabaseObserver Implementation ---
    @Override
    public void onNodeUpdated(MeshNode node) {
        // Ensure UI updates happen on the Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> upsertNode(node));
    }

    @Override
    public void onNodesPurged() {
        SwingUtilities.invokeLater(this::purgeAll);
    }

    // --- Internal Logic ---
    private void upsertNode(MeshNode newNode) {
        MeshNode oldNode = nodeLookup.get(newNode.getNodeId());
        nodeLookup.put(newNode.getNodeId(), newNode);

        if (oldNode == null) {
            // New node discovery requires a full resort to place it correctly
            reSort();
        } else {
            // Check if identity-based status changed (e.g., LIVE -> CACHED)
            // If status changed, we need to resort to maintain the list hierarchy.
            if (oldNode.getCalculatedStatus() != newNode.getCalculatedStatus()) {
                reSort();
            } else {
                // SURGICAL UPDATE:
                // Identity and Status are same, but vitals (Battery/SNR) changed.
                // We update in-place to avoid list jumping/flickering.
                int index = displayList.indexOf(oldNode); // Uses Refactored Identity Equals
                if (index != -1) {
                    displayList.set(index, newNode);
                    fireContentsChanged(this, index, index);
                } else {
                    reSort();
                }
            }
        }
    }

    private void purgeAll() {
        int oldSize = displayList.size();
        nodeLookup.clear();
        displayList.clear();
        if (oldSize > 0) {
            fireIntervalRemoved(this, 0, oldSize - 1);
        }
    }

    /**
     * Performs a heavy-lifting sort on a background thread to prevent UI
     * hitches.
     */
    public synchronized void reSort() {
        new SwingWorker<List<MeshNode>, Void>() {
            @Override
            protected List<MeshNode> doInBackground() {
                // Sort by: 1. Status Weight, 2. Recency (Last Seen), 3. Name
                return nodeLookup.values().stream()
                        .sorted(Comparator.comparingInt((MeshNode n) -> getStatusWeight(n.getCalculatedStatus()))
                                .thenComparing((n1, n2) -> {
                                    // Use the latest available timestamp for recency sorting
                                    long t1 = Math.max(n1.getLastSeenLocal(), n1.getLastSeen() * 1000L);
                                    long t2 = Math.max(n2.getLastSeenLocal(), n2.getLastSeen() * 1000L);
                                    return Long.compare(t2, t1); // Newest first
                                })
                                .thenComparing(n -> n.getLongName() != null ? n.getLongName().toLowerCase() : "zz" + n.getHexId()))
                        .collect(Collectors.toList());
            }

            @Override
            protected void done() {
                try {
                    List<MeshNode> newList = get();
                    displayList = newList;
                    if (!displayList.isEmpty()) {
                        fireContentsChanged(MeshNodeListModel.this, 0, displayList.size() - 1);
                        
                        if (onRefreshComplete != null) {
                            onRefreshComplete.run();
                        }
                    }
                } catch (Exception e) {
                    // Silently fail or log (usually InterruptedException during shutdown)
                }
            }
        }.execute();
    }

    private int getStatusWeight(MeshNode.NodeStatus status) {
        return switch (status) {
            case SELF ->
                0;
            case LIVE ->
                1;
            case CACHED ->
                2;
            case OFFLINE ->
                3;
        };
    }

    @Override
    public int getSize() {
        return displayList.size();
    }

    @Override
    public MeshNode getElementAt(int i) {
        return (i >= 0 && i < displayList.size()) ? displayList.get(i) : null;
    }
}
