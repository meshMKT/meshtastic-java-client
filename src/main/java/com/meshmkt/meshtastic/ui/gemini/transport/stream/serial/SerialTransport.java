package com.meshmkt.meshtastic.ui.gemini.transport.stream.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.meshmkt.meshtastic.ui.gemini.transport.stream.StreamTransport;
import java.io.IOException;
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
    private SerialPort port;

    public SerialTransport(SerialConfig config) {
        this.config = config;
    }

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

        port.flushIOBuffers();
        port.flushDataListener();
        dataQueue.clear();

        port.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE
                        | SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
            }

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
     */
    @Override
    protected void writeToPhysicalLayer(byte[] framedData) throws IOException {
        if (port == null || !port.isOpen()) {
            throw new IOException("Port is closed");
        }

        log.info("PHYSICAL WRITE: {} bytes to port {}", framedData.length, config.getPortName());
        int written = port.writeBytes(framedData, framedData.length);
        if (written < 0) {
            throw new IOException("Serial write failed.");
        }
    }

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

    private void startRetryLoop() {
        Thread retryThread = new Thread(() -> {
            System.out.println(">>> Serial link lost. Searching for radio on " + config.getPortName() + "...");
            while (running && !isConnected()) {
                try {
                    Thread.sleep(5000);
                    connect();
                    if (isConnected()) {
                        System.out.println(">>> Radio Found! Link Restored.");
                        notifyConnected();
                        break;
                    }
                } catch (Exception e) {
                    // Silently continue fishing
                }
            }
        }, "SerialRetryThread");
        retryThread.setDaemon(true);
        retryThread.start();
    }

    @Override
    protected void disconnect() {
        if (port != null) {
            port.removeDataListener();
            if (port.isOpen()) {
                port.closePort();
            }
        }
    }

    @Override
    public boolean isConnected() {
        return port != null && port.isOpen();
    }
}
