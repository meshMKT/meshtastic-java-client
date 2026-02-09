package com.meshmkt.meshtastic.ui.gemini;

import com.google.protobuf.InvalidProtocolBufferException;
import org.meshtastic.proto.MeshProtos;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * <h2>Meshtastic Frame Decoder</h2>
 * <p>
 * A high-performance stream parser for the Meshtastic Serial Protocol. This
 * class identifies valid frames from a raw byte stream, validates the framing
 * envelope, and dispatches a decoded {@link MeshProtos.FromRadio} object.
 * </p>
 *
 * <h3>Protocol Specification:</h3>
 * <ul>
 * <li><b>Magic Bytes:</b> 0x94 0xC3 (Used for frame synchronization)</li>
 * <li><b>Length:</b> 2-byte Big Endian integer (Max 512 bytes)</li>
 * <li><b>Payload:</b> Protobuf-encoded FromRadio message</li>
 * </ul>
 *
 */
@Slf4j
public class MeshtasticFrameDecoder {

    private static final int MAX_PAYLOAD_SIZE = 512;
    private static final int START_1 = 0x94;
    private static final int START_2 = 0xC3;

    private final Consumer<byte[]> packetConsumer;

    /**
     * Internal states for the serial stream parser.
     */
    private enum State {
        LOOKING_FOR_START1,
        LOOKING_FOR_START2,
        READING_LENGTH,
        READING_PAYLOAD
    }

    private State state = State.LOOKING_FOR_START1;
    private final byte[] lengthBytes = new byte[2];
    private final byte[] payloadBuffer = new byte[MAX_PAYLOAD_SIZE];

    private int lengthPos = 0;
    private int payloadPos = 0;
    private int expectedLength = 0;
    private long lastByteTime = 0;

    /**
     * @param packetConsumer Receives a fully validated and decoded FromRadio
     * object.
     */
    public MeshtasticFrameDecoder(Consumer<byte[]> packetConsumer) {
        this.packetConsumer = packetConsumer;
    }

    /**
     * Processes a single byte from the serial stream. This method handles
     * signed-to-unsigned conversion and state transitions.
     *
     * * @param b The raw byte from the hardware interface.
     */
    public void processByte(byte b) {

        long now = System.currentTimeMillis();
        
        // if we were mid-packet but the radio went quite, we are out of sync
        if (state != State.LOOKING_FOR_START1 && (now - lastByteTime > 200)) {
            state = State.LOOKING_FOR_START1;
        }
        lastByteTime = now;
        
        // Convert to unsigned int (0-255) for clean comparison and math
        int ub = Byte.toUnsignedInt(b);

        // Manage state transitions using a Java 17 Switch Expression
        this.state = switch (state) {

            case LOOKING_FOR_START1 -> {
                // Return LOOKING_FOR_START2 if we found the first magic byte
                yield (ub == START_1) ? State.LOOKING_FOR_START2 : State.LOOKING_FOR_START1;
            }

            case LOOKING_FOR_START2 -> {
                if (ub == START_2) {
                    yield State.READING_LENGTH;
                }
                // Handle consecutive START_1 bytes or reset to start
                yield (ub == START_1) ? State.LOOKING_FOR_START2 : State.LOOKING_FOR_START1;
            }

            case READING_LENGTH -> {
                lengthBytes[lengthPos++] = b;
                if (lengthPos < 2) {
                    yield State.READING_LENGTH;
                } else {
                    // Combine the two length bytes (Big Endian)
                    expectedLength = (Byte.toUnsignedInt(lengthBytes[0]) << 8) | Byte.toUnsignedInt(lengthBytes[1]);
                    lengthPos = 0; // Reset for next packet cycle

                    // Sanity check length to prevent buffer overruns
                    if (expectedLength > 0 && expectedLength <= MAX_PAYLOAD_SIZE) {
                        payloadPos = 0;
                        yield State.READING_PAYLOAD;
                    }
                    // Invalid length detected, resyncing
                    yield State.LOOKING_FOR_START1;
                }
            }

            case READING_PAYLOAD -> {
                payloadBuffer[payloadPos++] = b;
                if (payloadPos == expectedLength) {
                    // Packet fully buffered, proceed to decode
                    dispatchPacket();

                    // IMPORTANT: Reset payloadPos to 0 here to prevent 
                    // accidental carry-over if the next packet is weird.
                    payloadPos = 0;
                    
                    yield State.LOOKING_FOR_START1;
                }
                yield State.READING_PAYLOAD;
            }
        };
    }

    /**
     * Extracts the payload from the internal buffer and hands it to the
     * consumer.
     */
    private void dispatchPacket() {
        try {
            byte[] payload = new byte[expectedLength];
            System.arraycopy(payloadBuffer, 0, payload, 0, expectedLength);

            // This is now purely a byte-level handoff.
            packetConsumer.accept(payload);
        } catch (Exception e) {
            // If parsing fails in the handler, we MUST force
            // a new search
            log.error("Desync deteced, resetting decoder", e);
            this.state = State.LOOKING_FOR_START1;
        }
    }
}
