package com.meshmkt.meshtastic.client;

import com.meshmkt.meshtastic.client.handlers.MeshtasticMessageHandler;
import org.meshtastic.proto.MeshProtos;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Routes incoming packets to registered handlers asynchronously.
 * Maintains message order while freeing up the serial transport thread.
 */
public class MeshtasticDispatcher {
    private final List<MeshtasticMessageHandler> handlers = new CopyOnWriteArrayList<>();
    
    // Single thread worker ensures messages are processed in the order they were received.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Meshtastic-Dispatcher");
        t.setDaemon(true);
        return t;
    });

    /**
     *
     * @param handler
     */
    public void registerHandler(MeshtasticMessageHandler handler) {
        handlers.add(handler);
    }

    /**
     * Hands off the message to the worker queue immediately.
     * @param message
     */
    public void enqueue(MeshProtos.FromRadio message) {
        if (!worker.isShutdown()) {
            worker.execute(() -> dispatch(message));
        }
    }

    private void dispatch(MeshProtos.FromRadio message) {
        for (MeshtasticMessageHandler handler : handlers) {
            try {
                if (handler.canHandle(message)) {
                    // If handler returns true, we stop processing the chain for this message.
                    if (handler.handle(message)) {
                        break;
                    }
                }
            } catch (Exception e) {
                // Log error but keep the worker thread alive for the next message.
                System.err.printf("Handler %s failed: %s%n", 
                    handler.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     *
     */
    public void shutdown() {
        worker.shutdown();
    }
}