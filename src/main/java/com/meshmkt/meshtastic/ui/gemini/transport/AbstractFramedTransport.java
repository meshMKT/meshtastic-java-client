package com.meshmkt.meshtastic.ui.gemini.transport;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * <h2>Abstract Framed Transport</h2>
 * <p>
 * Refactored version using the Template Method Pattern and asynchronous event
 * dispatching for low-power hardware.
 * </p>
 */
public abstract class AbstractFramedTransport implements MeshtasticTransport {

    protected final BlockingQueue<byte[]> dataQueue = new LinkedBlockingQueue<>(1000);
    private final List<TransportConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    private ExecutorService consumerExecutor;
    private ExecutorService eventExecutor; // Decouples IO from Listeners

    protected Consumer<byte[]> packetConsumer = (data) -> {
    };
    protected volatile boolean running = false;

    // --- Template Methods for Subclasses ---
    /**
     * Subclasses implement physical connection logic.
     */
    protected abstract void connect() throws Exception;

    /**
     * Subclasses implement physical disconnection.
     */
    protected abstract void disconnect() throws Exception;

    /**
     * Subclasses implement the physical write (e.g., serial.writeBytes).
     */
    protected abstract void sendRawBytes(byte[] data) throws Exception;

    /**
     * Subclasses define how raw bytes are processed into packets.
     */
    protected abstract void handleIncomingRawData(byte[] data);

    // --- Public Template API (Final) ---
    @Override
    public final void write(byte[] protobufData) {
        if (!isConnected() || protobufData == null) {
            return;
        }

        try {
            // 1. Perform the work first
            sendRawBytes(protobufData);

            // 2. Notify asynchronously
            asyncNotify(TransportConnectionListener::onTrafficTransmitted);
        } catch (Exception e) {
            handleTransportError(e);
        }
    }

    /**
     * Entry point for hardware threads to feed the system.
     */
    protected final void enqueueData(byte[] data) {
        if (data != null && data.length > 0) {
            try {
                // 1. Queue data immediately to free up hardware thread
                dataQueue.put(data);

                // 2. Notify asynchronously
                asyncNotify(TransportConnectionListener::onTrafficReceived);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // --- Event Dispatching Logic ---
    /**
     * Safely dispatches events to listeners on a background thread.
     */
    private void asyncNotify(Consumer<TransportConnectionListener> action) {
        if (eventExecutor == null || eventExecutor.isShutdown()) {
            return;
        }

        eventExecutor.submit(() -> {
            for (TransportConnectionListener listener : connectionListeners) {
                try {
                    action.accept(listener);
                } catch (Exception e) {
                    System.err.println("Listener Error [" + listener.getClass().getSimpleName() + "]: " + e.getMessage());
                }
            }
        });
    }

    protected void notifyConnected() {
        asyncNotify(TransportConnectionListener::onConnected);
    }

    protected void notifyDisconnected() {
        asyncNotify(TransportConnectionListener::onDisconnected);
    }

    protected void notifyError(Throwable t) {
        asyncNotify(l -> l.onError(t));
    }

    // --- Lifecycle Management ---
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        try {
            // Setup Event Executor first so we can notify connection success
            eventExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "TransportEvent-" + getClass().getSimpleName());
                t.setDaemon(true);
                return t;
            });

            connect();
            running = true;

            consumerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "TransportWorker-" + getClass().getSimpleName());
                t.setDaemon(true);
                return t;
            });

            consumerExecutor.submit(this::processQueue);
            notifyConnected();

        } catch (Exception e) {
            stop();
            notifyError(e);
            throw new RuntimeException("Failed to start transport: " + e.getMessage(), e);
        }
    }

    private void processQueue() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                byte[] data = dataQueue.take();
                handleIncomingRawData(data);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;

        try {
            disconnect();

            if (consumerExecutor != null) {
                consumerExecutor.shutdownNow();
            }

            dataQueue.clear();
            notifyDisconnected();

            // Shutdown events last to ensure 'onDisconnected' is sent
            if (eventExecutor != null) {
                eventExecutor.shutdown();
            }

        } catch (Exception e) {
            notifyError(e);
        } finally {
            consumerExecutor = null;
            eventExecutor = null;
        }
    }

    // --- Implementation Helpers ---
    @Override
    public void addConnectionListener(TransportConnectionListener l) {
        if (l != null) {
            connectionListeners.add(l);
        }
    }

    @Override
    public void removeConnectionListener(TransportConnectionListener l) {
        connectionListeners.remove(l);
    }

    @Override
    public void addParsedPacketConsumer(Consumer<byte[]> c) {
        this.packetConsumer = (c != null) ? c : (d) -> {
        };
    }

    protected void dispatchToConsumer(byte[] packet) {
        packetConsumer.accept(packet);
    }

    /**
     * Logic for handling errors and triggering retries (implemented in concrete
     * class)
     */
    protected abstract void handleTransportError(Exception e);
}
