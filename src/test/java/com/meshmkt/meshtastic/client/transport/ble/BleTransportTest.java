package com.meshmkt.meshtastic.client.transport.ble;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.meshmkt.meshtastic.client.transport.TransportConnectionListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Verifies BLE transport skeleton behavior with an in-memory backend SPI double.
 */
class BleTransportTest {

    /**
     * Verifies transport writes framed bytes to backend and forwards incoming bytes to parsed consumer.
     */
    @Test
    void bleTransportWritesAndReceivesViaBackendSpi() throws Exception {
        RecordingBleBackend backend = new RecordingBleBackend();
        BleTransport transport = new BleTransport(defaultConfig(), backend);

        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);
        transport.addConnectionListener(new TransportConnectionListener() {
            @Override
            public void onConnected() {
                connected.countDown();
            }

            @Override
            public void onDisconnected() {
                // No-op for this test.
            }

            @Override
            public void onError(Throwable error) {
                // No-op for this test.
            }
        });
        transport.addParsedPacketConsumer(bytes -> received.countDown());

        transport.start();
        assertTrue(connected.await(2, TimeUnit.SECONDS));

        byte[] protobufPayload = new byte[] {0x01, 0x02, 0x03};
        transport.write(protobufPayload);

        // Verify framing header [0x94,0xC3,length_msb,length_lsb] + payload.
        assertTrue(waitFor(() -> !backend.writes.isEmpty(), Duration.ofSeconds(2)));
        byte[] framed = backend.writes.get(0);
        assertTrue(framed.length >= 7);
        assertArrayEquals(new byte[] {(byte) 0x94, (byte) 0xC3, 0x00, 0x03, 0x01, 0x02, 0x03}, framed);

        // Feed one valid framed packet inbound and ensure consumer receives it.
        backend.emitIncoming(new byte[] {(byte) 0x94, (byte) 0xC3, 0x00, 0x01, 0x7F});
        assertTrue(received.await(2, TimeUnit.SECONDS));

        transport.stop();
    }

    private static BleConfig defaultConfig() {
        return new BleConfig(
                "AA:BB:CC:DD:EE:FF",
                "6ba1b218-15a8-461f-9fa8-5dcae273eafd",
                "f75c76d2-129e-4dad-a1dd-7866124401e7",
                "2c55e69e-4993-11ed-b878-0242ac120002",
                Duration.ofSeconds(10),
                Duration.ofMillis(200),
                true);
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }

    /**
     * In-memory BLE backend double used by transport tests.
     */
    private static final class RecordingBleBackend implements BleLinkBackend {
        private final List<byte[]> writes = new ArrayList<>();
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private volatile Consumer<byte[]> receiveListener = bytes -> {};
        private volatile Runnable disconnectListener = () -> {};
        private volatile Consumer<Throwable> errorListener = throwable -> {};

        @Override
        public void connect(BleConfig config) {
            connected.set(true);
        }

        @Override
        public void disconnect() {
            connected.set(false);
        }

        @Override
        public void write(byte[] framedData) {
            writes.add(framedData.clone());
        }

        @Override
        public boolean isConnected() {
            return connected.get();
        }

        @Override
        public void setReceiveListener(Consumer<byte[]> receiver) {
            receiveListener = receiver == null ? bytes -> {} : receiver;
        }

        @Override
        public void setDisconnectListener(Runnable callback) {
            disconnectListener = callback == null ? () -> {} : callback;
        }

        @Override
        public void setErrorListener(Consumer<Throwable> callback) {
            errorListener = callback == null ? throwable -> {} : callback;
        }

        void emitIncoming(byte[] bytes) {
            receiveListener.accept(bytes);
        }

        @SuppressWarnings("unused")
        void emitDisconnect() {
            connected.set(false);
            disconnectListener.run();
        }

        @SuppressWarnings("unused")
        void emitError(Throwable t) {
            errorListener.accept(t);
        }
    }
}
