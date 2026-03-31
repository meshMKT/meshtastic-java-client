package com.meshmkt.meshtastic.client.support;

import com.meshmkt.meshtastic.client.transport.MeshtasticTransport;
import com.meshmkt.meshtastic.client.transport.TransportConnectionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory transport test double for {@link MeshtasticTransport}.
 * <p>
 * This fake captures outbound writes and allows tests to inject parsed packet bytes and
 * connection state transitions without using a physical radio.
 * </p>
 */
public class FakeTransport implements MeshtasticTransport {

    private final List<byte[]> writes = new CopyOnWriteArrayList<>();
    private final List<TransportConnectionListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Consumer<byte[]> parsedPacketConsumer = data -> {};
    private volatile boolean connected;

    @Override
    public void start() {
        connected = true;
        listeners.forEach(TransportConnectionListener::onConnected);
    }

    @Override
    public void stop() {
        connected = false;
        listeners.forEach(TransportConnectionListener::onDisconnected);
    }

    @Override
    public void write(byte[] data) {
        if (data != null) {
            writes.add(data.clone());
            listeners.forEach(TransportConnectionListener::onTrafficTransmitted);
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void addParsedPacketConsumer(Consumer<byte[]> consumer) {
        parsedPacketConsumer = (consumer != null) ? consumer : data -> {};
    }

    @Override
    public void addConnectionListener(TransportConnectionListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeConnectionListener(TransportConnectionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Injects already-decoded packet bytes into the client's parsed-packet callback pipeline.
     *
     * @param parsedPacketBytes serialized {@code FromRadio} bytes.
     */
    public void emitParsedPacket(byte[] parsedPacketBytes) {
        parsedPacketConsumer.accept(parsedPacketBytes);
        listeners.forEach(TransportConnectionListener::onTrafficReceived);
    }

    /**
     * Emits a transport error event and marks this transport disconnected.
     *
     * @param error error to deliver to connection listeners.
     */
    public void emitError(Throwable error) {
        connected = false;
        listeners.forEach(l -> l.onError(error));
    }

    /**
     * Returns a snapshot of outbound raw protobuf writes.
     *
     * @return copied list of captured writes in original order.
     */
    public List<byte[]> getWritesSnapshot() {
        List<byte[]> copy = new ArrayList<>(writes.size());
        for (byte[] write : writes) {
            copy.add(write.clone());
        }
        return copy;
    }
}
