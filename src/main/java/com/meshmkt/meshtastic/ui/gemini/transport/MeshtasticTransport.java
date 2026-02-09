package com.meshmkt.meshtastic.ui.gemini.transport;

import java.util.function.Consumer;

/**
 * <h2>Meshtastic Transport Interface</h2>
 * <p>
 * The common contract for all communication methods (Serial, TCP, BLE).
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
     * @param data
     */
    void write(byte[] data);

    /**
     * Returns true if the physical link is currently active.
     * @return 
     */
    boolean isConnected();

    /**
     * * Registers a callback for fully parsed (deframed) packets.
     *
     * @param consumer A callback receiving raw Protobuf bytes.
     */
    void addParsedPacketConsumer(Consumer<byte[]> consumer);

    /**
     * * Registers a listener for connectivity state changes.
     *
     * @param listener The listener to be notified of connect/disconnect events.
     */
    void addConnectionListener(TransportConnectionListener listener);

    /**
     * * Removes a previously registered connection listener.
     *
     * @param listener The listener to remove.
     */
    void removeConnectionListener(TransportConnectionListener listener);
}
