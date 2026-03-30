package com.meshmkt.meshtastic.client.transport.stream.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.meshmkt.meshtastic.client.transport.stream.StreamTransport;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * <h2>Serial Transport</h2>
 * <p>
 * Hardware implementation for USB and UART serial connections. Refactored to
 * fulfill the physical layer requirements of StreamTransport.
 * </p>
 */
@Slf4j
public class SerialTransport extends StreamTransport {

    private final SerialConfig config;
    /**
     * Ensures only one reconnect loop can run at a time even if multiple error events arrive.
     */
    private final AtomicBoolean retryLoopActive = new AtomicBoolean(false);
    private SerialPort port;
    /**
     * Tracks the most recently successful descriptor to prefer stable reconnect behavior.
     * This may differ from the originally configured descriptor after USB re-enumeration.
     */
    private volatile String activePortDescriptor;
    /**
     * Tracks human-readable metadata from the last successful port to assist fuzzy matching.
     */
    private volatile String activePortDescription;
    /**
     * Tracks descriptive OS port label from the last successful connection.
     */
    private volatile String activeDescriptiveName;

    /**
     *
     * @param config
     */
    public SerialTransport(SerialConfig config) {
        this.config = config;
        this.activePortDescriptor = config.getPortName();
        this.activePortDescription = "";
        this.activeDescriptiveName = "";
        setOutboundPacingDelay(config.getOutboundPacingDelayMs());
    }

    /**
     * Establishes the underlying transport connection.
     *
     */
    @Override
    protected void connect() throws Exception {
        // Resolve against current enumerated ports so reconnect can survive descriptor changes after device reboot.
        port = resolvePortForConnect();
        port.setBaudRate(config.getBaudRate());
        port.setNumDataBits(config.getDataBits());
        port.setNumStopBits(config.getStopBits());
        port.setParity(config.getParity());

        if (!port.openPort()) {
            throw new IOException("Failed to open serial port: " + descriptorFor(port));
        }

        activePortDescriptor = descriptorFor(port);
        activePortDescription = normalize(port.getPortDescription());
        activeDescriptiveName = normalize(port.getDescriptivePortName());

        port.flushIOBuffers();
        port.flushDataListener();
        dataQueue.clear();

        port.addDataListener(new SerialPortDataListener() {
            /**
             * Returns the serial event mask to subscribe to from the serial library.
             *
             * @return serial event bitmask handled by this listener.
             */
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE
                        | SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
            }

            /**
             * Handles one inbound serial-port event callback from the serial library.
             *
             * @param event event payload.
             */
            @Override
            public void serialEvent(SerialPortEvent event) {
                if (event.getEventType() == SerialPort.LISTENING_EVENT_PORT_DISCONNECTED) {
                    handleTransportError(new IOException("Serial Device disconnected"));
                    return;
                }

                if (event.getEventType() == SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
                    int available = port.bytesAvailable();
                    if (available > 0) {
                        byte[] buffer = new byte[available];
                        int read = port.readBytes(buffer, buffer.length);
                        if (read > 0) {
                            // This triggers the async RX notification in AbstractFramedTransport
                            enqueueData(buffer);
                        }
                    }
                }
            }
        });
    }
    
    /**
     * Physical implementation of the framed write. Magic bytes and length are
     * already applied by the StreamTransport layer.
     * @param framedData
     * @throws java.io.IOException
     */
    @Override
    protected void writeToPhysicalLayer(byte[] framedData) throws IOException {
        if (port == null || !port.isOpen()) {
            throw new IOException("Port is closed");
        }

        log.trace("PHYSICAL WRITE: {} bytes to port {}", framedData.length, descriptorFor(port));
        int written = port.writeBytes(framedData, framedData.length);
        if (written < 0) {
            throw new IOException("Serial write failed.");
        }
    }

    /**
     * Handles transport failures and triggers reconnect flow when configured.
     *
     * @param e error or event payload, depending on callback context.
     */
    @Override
    protected void handleTransportError(Exception e) {
        try {
            disconnect();
        } catch (Exception ignored) {
        }

        // Notify UI/Client (Async via base class)
        notifyError(e);

        if (running) {
            startRetryLoop();
        }
    }

    /**
     * Starts the reconnect retry loop after unexpected link loss.
     *
     */
    private void startRetryLoop() {
        if (!retryLoopActive.compareAndSet(false, true)) {
            return;
        }

        Thread retryThread = new Thread(() -> {
            try {
                log.trace(">>> Serial link lost. Searching for radio (preferred: {})...", activePortDescriptor);
                while (running && !isConnected()) {
                    try {
                        Thread.sleep(5000);
                        log.trace("Serial reconnect attempt (preferred: {})", activePortDescriptor);
                        connect();
                        if (isConnected()) {
                            log.trace(">>> Radio Found! Link Restored on {}.", activePortDescriptor);
                            notifyConnected();
                            break;
                        }
                    } catch (Exception e) {
                        // Keep retrying and emit reason to simplify field debugging for descriptor churn cases.
                        log.trace("Serial reconnect attempt failed: {}", e.getMessage());
                    }
                }
            } finally {
                retryLoopActive.set(false);
            }
        }, "SerialRetryThread");
        retryThread.setDaemon(true);
        retryThread.start();
    }

    /**
     * Closes the underlying transport connection and releases resources.
     *
     */
    @Override
    protected void disconnect() {
        if (port != null) {
            port.removeDataListener();
            if (port.isOpen()) {
                port.closePort();
            }
        }
    }

    /**
     * Reports whether the transport currently has an active connection.
     *
     * @return {@code true} when the serial port is open.
     */
    @Override
    public boolean isConnected() {
        return port != null && port.isOpen();
    }

    /**
     * Resolves the best serial port candidate for connection.
     * <p>
     * Strategy:
     * </p>
     * <ul>
     * <li>Prefer exact descriptor match (current active descriptor).</li>
     * <li>Fallback to metadata match against last-known description/descriptive name.</li>
     * <li>Fallback to same descriptor family (prefix before trailing numeric churn).</li>
     * <li>Fail with a detailed "available ports" list for observability.</li>
     * </ul>
     */
    private SerialPort resolvePortForConnect() throws IOException {
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length == 0) {
            throw new IOException("No serial ports available");
        }

        var candidates = Arrays.stream(ports)
                .map(p -> new SerialPortSelector.Candidate<>(
                descriptorFor(p),
                p.getPortDescription(),
                p.getDescriptivePortName(),
                p))
                .toList();

        Optional<SerialPortSelector.Candidate<SerialPort>> selected = SerialPortSelector.select(
                activePortDescriptor,
                activePortDescription,
                activeDescriptiveName,
                candidates
        );
        if (selected.isPresent()) {
            SerialPort chosen = selected.get().getPayload();
            String chosenDescriptor = descriptorFor(chosen);
            if (!SerialPortSelector.canonicalDescriptor(chosenDescriptor)
                    .equals(SerialPortSelector.canonicalDescriptor(activePortDescriptor))) {
                log.warn("Configured serial descriptor {} unavailable, switching to {}",
                        activePortDescriptor, chosenDescriptor);
            }
            return chosen;
        }

        String available = Arrays.stream(ports)
                .map(p -> descriptorFor(p) + " [" + p.getPortDescription() + "]")
                .collect(Collectors.joining(", "));
        throw new IOException("Serial descriptor not found. Preferred: " + activePortDescriptor + " | Available: " + available);
    }

    /**
     * Produces a stable descriptor string for logs and matching.
     */
    private String descriptorFor(SerialPort candidate) {
        if (candidate == null) {
            return "";
        }
        String systemName = candidate.getSystemPortName();
        if (systemName == null || systemName.isBlank()) {
            return "";
        }
        if (systemName.toUpperCase(Locale.ROOT).startsWith("COM")) {
            return systemName;
        }
        if (systemName.startsWith("/dev/")) {
            return systemName;
        }
        return "/dev/" + systemName;
    }

    /**
     * Normalizes nullable strings for case-insensitive matching.
     */
    private String normalize(String value) {
        return SerialPortSelector.normalize(value);
    }
}
