package com.meshmkt.meshtastic.client;

import build.buf.gen.meshtastic.FromRadio;
import com.meshmkt.meshtastic.client.handlers.MeshtasticMessageHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;

/**
 * Routes incoming packets to registered handlers asynchronously.
 * Maintains message order while freeing up the serial transport thread.
 */
@Slf4j
final class MeshtasticDispatcher {
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
    public void enqueue(FromRadio message) {
        if (!worker.isShutdown()) {
            worker.execute(() -> dispatch(message));
        }
    }

    /**
     * Dispatches one inbound message to the first matching registered handler.
     *
     * @param message inbound message.
     */
    private void dispatch(FromRadio message) {
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
                log.error(
                        "Handler {} failed while dispatching message",
                        handler.getClass().getSimpleName(),
                        e);
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
