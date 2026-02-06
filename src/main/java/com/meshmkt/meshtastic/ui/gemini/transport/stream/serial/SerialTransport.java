package com.meshmkt.meshtastic.ui.gemini.transport.stream.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.meshmkt.meshtastic.ui.gemini.transport.stream.StreamTransport;
import java.io.IOException;

/**
 * <h2>Serial Transport</h2>
 * <p>
 * Hardware implementation for USB and UART serial connections. Utilizes the
 * {@code jSerialComm} library to interact with system COM ports.
 * </p>
 */
public class SerialTransport extends StreamTransport {

    private final SerialConfig config;
    private SerialPort port;

    /**
     * @param config The hardware parameters (Port Name, Baud, etc.)
     */
    public SerialTransport(SerialConfig config) {
        this.config = config;
    }

    /**
     * Opens the serial port and registers the asynchronous data listener.
     */
    @Override
    protected void connect() throws Exception {
        port = SerialPort.getCommPort(config.getPortName());
        port.setBaudRate(config.getBaudRate());
        port.setNumDataBits(config.getDataBits());
        port.setNumStopBits(config.getStopBits());
        port.setParity(config.getParity());

        if (!port.openPort()) {
            throw new IOException("Failed to open serial port: " + config.getPortName());
        }

        port.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                // We listen for BOTH new data and the physical removal of the device
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE
                        | SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
            }

            @Override
            public void serialEvent(SerialPortEvent event) {

                // Handle Physical Unplug
                if (event.getEventType() == SerialPort.LISTENING_EVENT_PORT_DISCONNECTED) {
                    handleTransportError(new IOException("Serial Device disconnected"));
                }

                if (event.getEventType() == SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
                    byte[] buffer = new byte[port.bytesAvailable()];
                    int read = port.readBytes(buffer, buffer.length);
                    if (read > 0) {
                        enqueueData(buffer);
                    }
                }

            }
        });
    }

    @Override
    protected void writeToPhysicalLayer(byte[] framedData) throws IOException {
        if (port == null || !port.isOpen()) throw new IOException("Port is closed");
        int written = port.writeBytes(framedData, framedData.length);
        if (written < 0) {
            throw new IOException("Serial write failed.");
        }
    }

    @Override
    protected void handleTransportError(Exception e) {
        // 1. Critical: Tear down everything to stop the "Event Storm"
        try {
            disconnect();
        } catch (Exception ignored) {
        }

        // 2. Notify the UI/Client that we are currently down
        notifyDisconnected(e.getMessage());

        // 3. Start a background thread to "fish" for the device
        if (running) {
            startRetryLoop();
        }
    }

    private void startRetryLoop() {
        Thread retryThread = new Thread(() -> {
            System.out.println(">>> Serial link lost. Searching for radio on " + config.getPortName() + "...");
            while (running && !isConnected()) {
                try {
                    Thread.sleep(5000); // Wait 5s between "fishing" attempts
                    connect();
                    if (isConnected()) {
                        System.out.println(">>> Radio Found! Link Restored.");
                        notifyConnected();
                        break;
                    }
                } catch (Exception e) {
                    // Fail silently and keep looking
                }
            }
        }, "SerialRetryThread");
        retryThread.setDaemon(true);
        retryThread.start();
    }

    @Override
    protected void disconnect() {
        if (port != null && port.isOpen()) {
            port.removeDataListener();
            port.closePort();
        }
    }

    @Override
    public boolean isConnected() {
        return port != null && port.isOpen();
    }
}
