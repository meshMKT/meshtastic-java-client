package com.meshmkt.meshtastic.client.transport;

import java.util.function.Consumer;

/**
 * Common transport contract for Meshtastic link implementations.
 * <p>
 * Implementations are responsible for physical connectivity (for example serial, TCP, BLE)
 * while the client layer handles protocol orchestration and request correlation.
 * </p>
 */
public interface MeshtasticTransport {

    /**
     * Starts the transport and any associated background threads.
     */
    void start();

    /**
     * Stops the transport and cleans up all resources/threads.
     */
    void stop();

    /**
     * Sends a raw Protobuf payload to the radio.
     *
     * @param data serialized protobuf payload bytes.
     */
    void write(byte[] data);

    /**
     * Returns whether the physical link is currently active.
     *
     * @return {@code true} when connected.
     */
    boolean isConnected();

    /**
     * Registers a callback for fully parsed (deframed) packets.
     *
     * @param consumer callback receiving parsed protobuf payload bytes.
     */
    void addParsedPacketConsumer(Consumer<byte[]> consumer);

    /**
     * Registers a listener for connectivity state changes.
     *
     * @param listener listener notified of connect/disconnect/error events.
     */
    void addConnectionListener(TransportConnectionListener listener);

    /**
     * Removes a previously registered connection listener.
     *
     * @param listener listener to remove.
     */
    void removeConnectionListener(TransportConnectionListener listener);
}
