package com.meshmkt.meshtastic.client.transport;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * <h2>Abstract Framed Transport</h2>
 * <p>
 * Refactored version using the Template Method Pattern and asynchronous event
 * dispatching for low-power hardware.
 * </p>
 */
@Slf4j
public abstract class AbstractFramedTransport implements MeshtasticTransport {

    /**
     *
     */
    protected final BlockingQueue<byte[]> dataQueue = new LinkedBlockingQueue<>(1000);

    private final List<TransportConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    /**
     *
     */
    protected final BlockingQueue<byte[]> txQueue = new LinkedBlockingQueue<>(100);

    private ExecutorService txExecutor;

    private ExecutorService consumerExecutor;
    private ExecutorService eventExecutor; // Decouples IO from Listeners

    /**
     *
     */
    protected Consumer<byte[]> packetConsumer = (data) -> {};

    /**
     *
     */
    protected volatile boolean running = false;

    // Default to 1000ms, which is a safe "middle ground"
    private long outboundPacingDelay = 200;

    // --- Template Methods for Subclasses ---
    /**
     * Subclasses implement physical connection logic.
     * @throws java.lang.Exception
     */
    protected abstract void connect() throws Exception;

    /**
     * Subclasses implement physical disconnection.
     * @throws java.lang.Exception
     */
    protected abstract void disconnect() throws Exception;

    /**
     * Subclasses implement the physical write (e.g., serial.writeBytes).
     * @param data
     * @throws java.lang.Exception
     */
    protected abstract void sendRawBytes(byte[] data) throws Exception;

    /**
     * Subclasses define how raw bytes are processed into packets.
     * @param data
     */
    protected abstract void handleIncomingRawData(byte[] data);

    // --- Public Template API (Final) ---

    /**
     *
     * @param millis
     */
    public void setOutboundPacingDelay(long millis) {
        this.outboundPacingDelay = millis;
    }

    /**
     * Enqueues outbound protobuf bytes for framing and physical transmit.
     *
     * @param protobufData outbound protobuf bytes before framing.
     */
    @Override
    public final void write(byte[] protobufData) {
        if (!isConnected() || protobufData == null) {
            return;
        }

        // Apply bounded backpressure instead of silently dropping writes when queue is full.
        // If enqueue cannot complete quickly, we log and notify so callers can observe congestion behavior.
        log.trace("write - adding bytes to txQueue");
        try {
            boolean enqueued = txQueue.offer(protobufData, 1, TimeUnit.SECONDS);
            if (!enqueued) {
                log.warn("write - txQueue full, dropping outbound frame ({} bytes)", protobufData.length);
                notifyError(new IllegalStateException("Outbound queue saturated; frame dropped"));
            } else {
                log.trace("write - bytes added to txQueue");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            notifyError(new IllegalStateException("Interrupted while queueing outbound frame", ex));
        }
    }

    /**
     * Entry point for hardware threads to feed the system.
     * @param data
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
     * Look in the outbox to see if we have packets to send to the radio
     */
    private void processTxQueue() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                log.trace("processTxQueue - Checking for txQueue data");
                byte[] protobufData = txQueue.take(); // Wait for a packet
                log.trace("processTxQeueu - Found data to process");

                log.trace("processTxQueue - Sending bytes to radio");
                sendRawBytes(protobufData);
                log.trace("processTxQueue - Bytes sent to radio");

                log.trace("processTxQueue - Sending activity event");
                asyncNotify(TransportConnectionListener::onTrafficTransmitted);
                log.trace("processTxQueue - Activity event sent");

                /// Use the configurable delay
                if (outboundPacingDelay > 0) {
                    log.trace("processTxQueue - Sleeping for {}ms", outboundPacingDelay);
                    Thread.sleep(outboundPacingDelay);
                    log.trace("processTxQueue - Sleep finished");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                handleTransportError(e);
            }
        }
    }

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
                    log.warn("Listener Error [{}]: {}", listener.getClass().getSimpleName(), e.getMessage());
                }
            }
        });
    }

    /**
     *
     */
    protected void notifyConnected() {
        asyncNotify(TransportConnectionListener::onConnected);
    }

    /**
     *
     */
    protected void notifyDisconnected() {
        asyncNotify(TransportConnectionListener::onDisconnected);
    }

    /**
     *
     * @param t
     */
    protected void notifyError(Throwable t) {
        asyncNotify(l -> l.onError(t));
    }

    // --- Lifecycle Management ---
    /**
     * Starts transport workers and opens the underlying physical link.
     *
     */
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

            consumerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "TransportWorker-" + getClass().getSimpleName());
                t.setDaemon(true);
                return t;
            });

            txExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "TransportTX-" + getClass().getSimpleName());
                t.setDaemon(true);
                return t;
            });

            connect();
            running = true;

            // Start AFTER the running flag is set
            consumerExecutor.submit(this::processIncomingDataQueue);
            txExecutor.submit(this::processTxQueue);

            notifyConnected();

        } catch (Exception e) {
            stop();
            notifyError(e);
            throw new RuntimeException("Failed to start transport: " + e.getMessage(), e);
        }
    }

    /**
     * Process the incoming raw data queue so we don't block the IO thread
     */
    private void processIncomingDataQueue() {
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

    /**
     * Stops transport workers and closes the underlying physical link.
     *
     */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;

        try {
            disconnect();
            notifyDisconnected();

            dataQueue.clear();
            txQueue.clear();

        } catch (Exception e) {
            notifyError(e);
        } finally {

            if (consumerExecutor != null) {
                consumerExecutor.shutdownNow();
            }

            // Shutdown events last to ensure 'onDisconnected' is sent
            if (eventExecutor != null) {
                eventExecutor.shutdown();
            }

            if (txExecutor != null) {
                txExecutor.shutdownNow();
            }

            consumerExecutor = null;
            eventExecutor = null;
            txExecutor = null;
        }
    }

    // --- Implementation Helpers ---
    /**
     * Registers a transport-connection listener callback.
     *
     * @param l listener instance to register.
     */
    @Override
    public void addConnectionListener(TransportConnectionListener l) {
        if (l != null) {
            connectionListeners.add(l);
        }
    }

    /**
     * Removes a previously registered transport-connection listener callback.
     *
     * @param l listener instance to remove.
     */
    @Override
    public void removeConnectionListener(TransportConnectionListener l) {
        connectionListeners.remove(l);
    }

    /**
     * Registers a consumer for parsed inbound packet payload bytes.
     *
     * @param c parsed packet payload consumer callback.
     */
    @Override
    public void addParsedPacketConsumer(Consumer<byte[]> c) {
        this.packetConsumer = (c != null) ? c : (d) -> {};
    }

    /**
     *
     * @param packet
     */
    protected void dispatchToConsumer(byte[] packet) {
        packetConsumer.accept(packet);
    }

    /**
     * Logic for handling errors and triggering retries (implemented in concrete
     * class)
     * @param e
     */
    protected abstract void handleTransportError(Exception e);
}
