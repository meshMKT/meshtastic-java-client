package com.meshmkt.meshtastic.ui.gemini.transport.stream;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticFrameDecoder;
import com.meshmkt.meshtastic.ui.gemini.transport.AbstractFramedTransport;
import java.io.IOException;

/**
 * <h2>Stream Transport</h2>
 * <p>
 * Refactored to fit the Template Method Pattern. This layer now specifically
 * handles the Meshtastic Serial Protocol framing (0x94 0xC3).
 * </p>
 */
public abstract class StreamTransport extends AbstractFramedTransport {

    protected final MeshtasticFrameDecoder frameDecoder;

    protected StreamTransport() {
        this.frameDecoder = new MeshtasticFrameDecoder(this::dispatchToConsumer);
    }

    /**
     * Feeds raw data chunks into the byte-by-byte frame decoder.
     */
    @Override
    protected void handleIncomingRawData(byte[] data) {
        for (byte b : data) {
            frameDecoder.processByte(b);
        }
    }

    /**
     * Implementation of the Template Method from AbstractFramedTransport. This
     * method is called after the base class has validated connectivity and is
     * responsible for framing the data before the physical write.
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
     */
    protected abstract void writeToPhysicalLayer(byte[] framedData) throws IOException;

    /**
     * Subclasses implement recovery logic (e.g., retry loops).
     */
    @Override
    protected abstract void handleTransportError(Exception e);
}
