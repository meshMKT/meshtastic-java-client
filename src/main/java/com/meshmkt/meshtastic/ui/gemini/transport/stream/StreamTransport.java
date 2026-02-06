package com.meshmkt.meshtastic.ui.gemini.transport.stream;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticFrameDecoder;
import com.meshmkt.meshtastic.ui.gemini.transport.AbstractFramedTransport;
import java.io.IOException;

/**
 * <h2>Stream Transport</h2>
 * <p>
 * A specialized transport layer for communication methods that utilize a
 * continuous byte stream (UART, USB, TCP). This class handles the <b>Meshtastic
 * Serial Protocol</b>
 * framing requirements.
 * </p>
 * * <h3>Responsibilities:</h3>
 * <ul>
 * <li><b>Deframing:</b> Uses {@link MeshtasticFrameDecoder} to extract packets
 * from an incoming stream using the {@code 0x94 0xC3} magic bytes.</li>
 * <li><b>Framing:</b> Wraps outgoing Protobuf payloads in the required 4-byte
 * header (Magic Bytes + Length) before physical transmission.</li>
 * </ul>
 *
 * * @author Gemini
 * @version 2.0
 */
public abstract class StreamTransport extends AbstractFramedTransport {

    /**
     * Internal state machine used to synchronize and decode incoming serial
     * frames.
     */
    protected final MeshtasticFrameDecoder frameDecoder;

    /**
     * Protected constructor to ensure this class is only instantiated via
     * concrete implementations like {@code SerialTransport} or
     * {@code TcpTransport}.
     */
    protected StreamTransport() {
        // Initializes the decoder with a hand-off to the base class dispatcher
        this.frameDecoder = new MeshtasticFrameDecoder(this::dispatchToConsumer);
    }

    /**
     * Implementation of the base processing logic. Feeds raw data chunks into
     * the byte-by-byte frame decoder.
     *
     * * @param data The raw byte array pulled from the background processing
     * queue.
     */
    @Override
    protected void handleIncomingRawData(byte[] data) {
        for (byte b : data) {
            frameDecoder.processByte(b);
        }
    }

    /**
     * Orchestrates the framing of a Protobuf message. Adds the
     * {@code 0x94 0xC3} header and 16-bit length prefix before calling the
     * physical write implementation.
     *
     * @param protobufData The raw bytes of the Meshtastic Protobuf message.
     */
    @Override
    public void write(byte[] protobufData) {
        if (!isConnected() || protobufData == null) {
            return;
        }

        // Protocol Header: [Magic1: 0x94][Magic2: 0xC3][LengthMSB][LengthLSB]
        byte[] framed = new byte[protobufData.length + 4];
        framed[0] = (byte) 0x94;
        framed[1] = (byte) 0xC3;
        framed[2] = (byte) ((protobufData.length >> 8) & 0xFF);
        framed[3] = (byte) (protobufData.length & 0xFF);
        System.arraycopy(protobufData, 0, framed, 4, protobufData.length);

        try {
            writeToPhysicalLayer(framed);
        } catch (IOException e) {
            handleTransportError(e);
        }
    }

    /**
     * Subclasses must implement this to transmit the fully-framed packet to the
     * hardware interface.
     *
     * @param framedData The complete 0x94 0xC3 framed packet.
     * @throws IOException If a physical write error occurs.
     */
    protected abstract void writeToPhysicalLayer(byte[] framedData) throws IOException;

    /**
     * Subclasses must implement this to define behavior when a stream
     * interruption is detected (e.g., reconnect logic).
     *
     * @param e The exception that triggered the error state.
     */
    protected abstract void handleTransportError(Exception e);
}
