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
    private static final int DEFAULT_RX_QUEUE_CAPACITY = 1000;
    private static final int DEFAULT_TX_QUEUE_CAPACITY = 100;
    private static final long DEFAULT_TX_QUEUE_OFFER_TIMEOUT_SECONDS = 1L;
    private static final long DEFAULT_OUTBOUND_PACING_DELAY_MS = 200L;

    /**
     * Buffered raw inbound bytes waiting to be decoded into protobuf payloads.
     * <p>
     * The queue is intentionally bounded so a stalled consumer cannot grow memory without limit
     * on small devices or long-running desktop clients.
     * </p>
     */
    protected final BlockingQueue<byte[]> dataQueue = new LinkedBlockingQueue<>(DEFAULT_RX_QUEUE_CAPACITY);

    /**
     * Registered connection listeners notified on a dedicated event executor.
     */
    private final List<TransportConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    /**
     * Buffered outbound protobuf payloads waiting to be framed and transmitted.
     * <p>
     * This queue is intentionally bounded so callers receive an observable backpressure signal instead
     * of unbounded memory growth during sustained radio or transport congestion.
     * </p>
     */
    protected final BlockingQueue<byte[]> txQueue = new LinkedBlockingQueue<>(DEFAULT_TX_QUEUE_CAPACITY);

    private ExecutorService txExecutor;

    private ExecutorService consumerExecutor;
    private ExecutorService eventExecutor; // Decouples IO from Listeners

    /**
     * Consumer invoked after a full protobuf payload has been decoded from the transport framing.
     */
    protected Consumer<byte[]> packetConsumer = (data) -> {};

    /**
     * /**
     * Transport lifecycle flag shared by worker loops and reconnect logic.
     */
    protected volatile boolean running = false;

    /**
     * Small pacing delay inserted between outbound frames to avoid overrunning slower radios.
     */
    private long outboundPacingDelay = DEFAULT_OUTBOUND_PACING_DELAY_MS;

    // --- Template Methods for Subclasses ---
    /**
     * Subclasses implement physical connection logic.
     *
     * @throws Exception when the physical transport cannot be opened.
     */
    protected abstract void connect() throws Exception;

    /**
     * Subclasses implement physical disconnection.
     *
     * @throws Exception when the physical transport cannot be closed cleanly.
     */
    protected abstract void disconnect() throws Exception;

    /**
     * Subclasses implement the physical write (e.g., serial.writeBytes).
     *
     * @param data outbound protobuf payload or transport-specific frame bytes.
     * @throws Exception when the physical write fails.
     */
    protected abstract void sendRawBytes(byte[] data) throws Exception;

    /**
     * Subclasses define how raw bytes are processed into packets.
     *
     * @param data raw bytes read from the physical transport.
     */
    protected abstract void handleIncomingRawData(byte[] data);

    // --- Public Template API (Final) ---

    /**
     * Sets the inter-frame pacing delay used by the outbound transmit worker.
     *
     * @param millis pacing delay in milliseconds.
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
            boolean enqueued = txQueue.offer(protobufData, DEFAULT_TX_QUEUE_OFFER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
     *
     * @param data raw bytes read from the physical transport.
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
     * Pulls outbound payloads from the transmit queue, writes them to the physical transport,
     * and emits traffic notifications.
     */
    private void processTxQueue() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                log.trace("processTxQueue - Checking for txQueue data");
                byte[] protobufData = txQueue.take();
                log.trace("processTxQueue - Found data to process");

                log.trace("processTxQueue - Sending bytes to radio");
                sendRawBytes(protobufData);
                log.trace("processTxQueue - Bytes sent to radio");

                log.trace("processTxQueue - Sending activity event");
                asyncNotify(TransportConnectionListener::onTrafficTransmitted);
                log.trace("processTxQueue - Activity event sent");

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
     * Notifies listeners that the transport link is connected.
     */
    protected void notifyConnected() {
        asyncNotify(TransportConnectionListener::onConnected);
    }

    /**
     * Notifies listeners that the transport link was disconnected.
     */
    protected void notifyDisconnected() {
        asyncNotify(TransportConnectionListener::onDisconnected);
    }

    /**
     * Notifies listeners about a transport error.
     *
     * @param t transport failure to publish.
     */
    protected void notifyError(Throwable t) {
        asyncNotify(l -> l.onError(t));
    }

    // --- Lifecycle Management ---
    /**
     * Starts transport workers and opens the underlying physical link.
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        try {
            String transportName = getClass().getSimpleName();

            // Setup Event Executor first so we can notify connection success
            eventExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Mesh-TransportEvents-" + transportName);
                t.setDaemon(true);
                return t;
            });

            consumerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Mesh-TransportRx-" + transportName);
                t.setDaemon(true);
                return t;
            });

            txExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Mesh-TransportTx-" + transportName);
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
     * Processes queued inbound raw data so the physical IO callback can return quickly.
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
     * Forwards one decoded protobuf payload to the registered parsed-packet consumer.
     *
     * @param packet decoded protobuf payload.
     */
    protected void dispatchToConsumer(byte[] packet) {
        packetConsumer.accept(packet);
    }

    /**
     * Handles transport errors and optional reconnect logic in concrete implementations.
     *
     * @param e transport error.
     */
    protected abstract void handleTransportError(Exception e);
}
