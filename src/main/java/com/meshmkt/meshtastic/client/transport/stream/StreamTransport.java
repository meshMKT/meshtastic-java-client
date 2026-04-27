package com.meshmkt.meshtastic.client.transport.stream;

import com.meshmkt.meshtastic.client.MeshtasticFrameDecoder;
import com.meshmkt.meshtastic.client.transport.AbstractFramedTransport;
import java.io.IOException;

/**
 * <h2>Stream Transport</h2>
 * <p>
 * Refactored to fit the Template Method Pattern. This layer now specifically
 * handles the Meshtastic Serial Protocol framing (0x94 0xC3).
 * </p>
 */
public abstract class StreamTransport extends AbstractFramedTransport {

    /**
     * Stateful decoder that reconstructs Meshtastic framed packets from raw stream bytes.
     */
    protected final MeshtasticFrameDecoder frameDecoder;

    /**
     * Creates a stream transport using the default stalled-frame timeout.
     */
    protected StreamTransport() {
        this(200L);
    }

    /**
     * Creates a stream transport with a custom stalled-frame timeout.
     *
     * @param stalledFrameTimeoutMs maximum inter-byte gap tolerated while decoding a frame.
     */
    protected StreamTransport(long stalledFrameTimeoutMs) {
        this.frameDecoder = new MeshtasticFrameDecoder(this::dispatchToConsumer, stalledFrameTimeoutMs);
    }

    /**
     * Feeds raw data chunks into the byte-by-byte frame decoder.
     *
     * @param data raw bytes from the transport stream.
     */
    @Override
    protected void handleIncomingRawData(byte[] data) {
        for (byte b : data) {
            frameDecoder.processByte(b);
        }
    }

    /**
     * Frames one protobuf payload using the Meshtastic serial framing envelope before writing it to the
     * concrete physical transport.
     *
     * @param protobufData outbound protobuf payload.
     * @throws Exception when the physical write fails.
     */
    @Override
    protected synchronized void sendRawBytes(byte[] protobufData) throws Exception {
        // 1. Apply Meshtastic Protocol Framing
        // Header: [Magic1: 0x94][Magic2: 0xC3][LengthMSB][LengthLSB]
        byte[] framed = new byte[protobufData.length + 4];
        framed[0] = (byte) 0x94;
        framed[1] = (byte) 0xC3;
        framed[2] = (byte) ((protobufData.length >> 8) & 0xFF);
        framed[3] = (byte) (protobufData.length & 0xFF);
        System.arraycopy(protobufData, 0, framed, 4, protobufData.length);

        // 2. Pass to the physical layer (Serial/TCP)
        try {
            writeToPhysicalLayer(framed);
        } catch (IOException e) {
            // Delegate error handling back to the concrete implementation
            handleTransportError(e);
            throw e; // Re-throw to ensure the Template Method knows it failed
        }
    }

    /**
     * Subclasses (Serial/TCP) implement this to transmit the framed packet.
     *
     * @param framedData transport frame ready for physical transmission.
     * @throws IOException when the underlying transport write fails.
     */
    protected abstract void writeToPhysicalLayer(byte[] framedData) throws IOException;

    /**
     * Subclasses implement recovery logic (e.g., retry loops).
     *
     * @param e transport failure to recover from.
     */
    @Override
    protected abstract void handleTransportError(Exception e);
}
