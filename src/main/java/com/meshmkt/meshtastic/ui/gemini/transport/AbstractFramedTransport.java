package com.meshmkt.meshtastic.ui.gemini.transport;

import com.meshmkt.meshtastic.ui.gemini.TransportConnectionListener;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * <h2>Abstract Framed Transport</h2>
 * <p>
 * Provides a thread-safe foundation for Meshtastic data transports, optimized
 * for low-power hardware like the Raspberry Pi Zero 2W.
 * </p>
 * * <p>
 * This class implements a <b>Producer/Consumer</b> pattern:</p>
 * <ul>
 * <li><b>Producer:</b> Subclasses (Serial, TCP, BLE) call
 * {@link #enqueueData(byte[])} immediately upon receiving hardware interrupts
 * or socket data.</li>
 * <li><b>Consumer:</b> A background daemon thread polls the queue and delegates
 * processing to {@link #handleIncomingRawData(byte[])}.</li>
 * </ul>
 *
 * * @author Gemini
 * @version 2.0
 */
public abstract class AbstractFramedTransport implements MeshtasticTransport {

    protected final BlockingQueue<byte[]> dataQueue = new LinkedBlockingQueue<>(1000);
    private ExecutorService consumerExecutor;

    protected Consumer<byte[]> packetConsumer = (data) -> {
    }; // Null-safe default
    protected volatile boolean running = false;

    /**
     * Subclasses must implement this to handle the physical connection logic
     * (e.g., opening a Serial Port or Socket).
     */
    protected abstract void connect() throws Exception;

    /**
     * Subclasses must implement this to close physical resources safely.
     */
    protected abstract void disconnect() throws Exception;

    /**
     * Subclasses must implement this to define how raw bytes are processed.
     * Streaming transports will feed a {@link MeshtasticFrameDecoder}, while
     * Datagram transports (BLE) will dispatch packets directly.
     *
     * @param data The raw bytes pulled from the internal queue.
     */
    protected abstract void handleIncomingRawData(byte[] data);

    /**
     * A thread-safe list of listeners interested in connection state changes.
     * We use CopyOnWriteArrayList so that listeners can be added or removed
     * even while an event is being broadcasted without causing a
     * ConcurrentModificationException.
     */
    private final List<TransportConnectionListener> connectionListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Registers a new listener to receive connectivity updates.
     *
     * @param listener The listener implementation to add.
     */
    public void addConnectionListener(TransportConnectionListener listener) {
        if (listener != null) {
            this.connectionListeners.add(listener);
        }
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener The listener implementation to remove.
     */
    public void removeConnectionListener(TransportConnectionListener listener) {
        this.connectionListeners.remove(listener);
    }

    /**
     * Notifies all registered listeners that the physical link is active. This
     * should be called by subclasses immediately after a successful handshake
     * or socket connection.
     */
    protected void notifyConnected() {
        connectionListeners.forEach(TransportConnectionListener::onConnected);
    }

    /**
     * Notifies all registered listeners that the connection has been lost.
     *
     * @param reason A descriptive string explaining why the disconnect occurred
     * (e.g., "Socket timeout" or "Port closed").
     */
    protected void notifyDisconnected(String reason) {
        connectionListeners.forEach(l -> l.onDisconnected(reason));
    }

    /**
     * Notifies all registered listeners that a transport-level error occurred.
     *
     * @param t The exception that triggered the error state.
     */
    protected void notifyError(Throwable t) {
        connectionListeners.forEach(l -> l.onError(t));
    }

    @Override
    public void addParsedPacketConsumer(Consumer<byte[]> consumer) {
        // Wrap in null-check to maintain safety
        this.packetConsumer = (consumer != null) ? consumer : (data) -> {
        };
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        try {
            connect();
            running = true;

            consumerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "TransportWorker-" + getClass().getSimpleName());
                t.setDaemon(true);
                return t;
            });

            consumerExecutor.submit(this::processQueue);

            // broadcase success
            notifyConnected();

        } catch (Exception e) {
            notifyError(e);
            stop();
            throw new RuntimeException("Failed to start transport: " + e.getMessage(), e);
        }
    }

    /**
     * The Consumer loop: Polls the queue and delegates to the subclass handler.
     * This runs on a dedicated background thread.
     */
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

    /**
     * Called by the hardware/IO thread to hand raw data to the worker thread.
     *
     * * @param data The raw bytes received from the physical link.
     */
    protected void enqueueData(byte[] data) {
        if (data != null && data.length > 0) {
            try {
                // put() will block the IO thread until there is room.
                // This acts as "Backpressure" to prevent data loss.
                dataQueue.put(data);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Helper method for subclasses to safely dispatch a fully assembled
     * Meshtastic packet to the registered consumer.
     *
     * * @param packet The assembled byte array (Protobuf payload).
     */
    protected void dispatchToConsumer(byte[] packet) {
        packetConsumer.accept(packet);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }

        running = false;
        try {
            disconnect();

            // Shutdown threads
            if (consumerExecutor != null) {
                consumerExecutor.shutdownNow();

                // Block for a moment to ensure thread is dead
                if (!consumerExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    System.err.println("Worker thread failed to terminate in time.");
                }
            }

            // clear the queue
            dataQueue.clear();
            notifyDisconnected("Manual stop requested");
        } catch (Exception e) {
            notifyError(e);
        } finally {
            consumerExecutor = null;
        }
    }
}
