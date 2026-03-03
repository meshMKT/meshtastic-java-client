package com.meshmkt.meshtastic.client.service.ota;

import com.meshmkt.meshtastic.client.MeshtasticClient;
import org.meshtastic.proto.XmodemProtos.XModem;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Bridges {@link SerialXmodemUploadStrategy} to {@link MeshtasticClient} OTA primitives.
 * <p>
 * This adapter keeps OTA upload code transport-agnostic by routing all XMODEM traffic through:
 * </p>
 * <ul>
 * <li>{@link MeshtasticClient#sendXmodemPacket(XModem)} for outbound frames.</li>
 * <li>{@link MeshtasticClient#awaitXmodemControl(Duration)} for inbound control events.</li>
 * </ul>
 * <p>
 * Call {@link #reset()} before starting a new upload session to clear stale control events.
 * </p>
 */
public final class MeshtasticXmodemDuplex implements SerialXmodemUploadStrategy.XmodemDuplex {

    private final MeshtasticClient client;

    /**
     * Creates a client-backed XMODEM duplex adapter.
     *
     * @param client meshtastic client instance.
     */
    public MeshtasticXmodemDuplex(MeshtasticClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    /**
     * Clears any buffered control values from previous OTA attempts.
     */
    public void reset() {
        client.clearXmodemControlBuffer();
    }

    /**
     * Sends one XMODEM frame through the active client transport.
     *
     * @param frame frame to send.
     * @return completion future.
     */
    @Override
    public CompletableFuture<Void> send(XModem frame) {
        return client.sendXmodemPacket(frame);
    }

    /**
     * Waits for the next inbound XMODEM control code.
     *
     * @param timeout maximum wait timeout.
     * @return completion future yielding next control.
     */
    @Override
    public CompletableFuture<XModem.Control> nextControl(Duration timeout) {
        return client.awaitXmodemControl(timeout);
    }
}
