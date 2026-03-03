package com.meshmkt.meshtastic.client.transport.stream.tcp;

import com.meshmkt.meshtastic.client.transport.stream.StreamTransport;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * <h2>TCP Transport</h2>
 * <p>
 * Network implementation for radios connected via WiFi or Ethernet. Refactored
 * to use the Template Method pattern for framing and notifications.
 * </p>
 */
@Slf4j
public class TcpTransport extends StreamTransport {

    private final TcpConfig config;
    private Socket socket;
    private OutputStream outputStream;
    private volatile boolean connected = false;
    /**
     * Guards reconnect thread creation so repeated faults do not spawn overlapping retry loops.
     */
    private final AtomicBoolean retryLoopActive = new AtomicBoolean(false);

    /**
     *
     * @param config
     */
    public TcpTransport(TcpConfig config) {
        this.config = config;
        setOutboundPacingDelay(config.getOutboundPacingDelayMs());
    }

    @Override
    protected void connect() throws Exception {
        attemptConnection();
    }

    /**
     * Establishes the socket and starts the listener thread.
     */
    private void attemptConnection() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(config.getHost(), config.getPort()), config.getConnectionTimeoutMs());
        outputStream = socket.getOutputStream();
        connected = true;

        Thread reader = new Thread(this::readLoop, "TcpReader-" + config.getHost());
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Blocking read loop that feeds the shared data queue. Triggers async RX
     * notifications via AbstractFramedTransport.
     */
    private void readLoop() {
        try (BufferedInputStream bis = new BufferedInputStream(socket.getInputStream())) {
            byte[] buffer = new byte[1024];
            int read;
            while (connected && (read = bis.read(buffer)) != -1) {
                if (read > 0) {
                    byte[] data = new byte[read];
                    System.arraycopy(buffer, 0, data, 0, read);
                    // Base class handles queueing and async RX blinker notification
                    enqueueData(data);
                }
            }
        } catch (IOException e) {
            // Socket closed or network error
        } finally {
            if (running && connected) {
                handleTransportError(new IOException("TCP Connection Lost"));
            }
        }
    }

    /**
     * Physical implementation of the framed write.
     * @param framedData
     * @throws java.io.IOException
     */
    @Override
    protected void writeToPhysicalLayer(byte[] framedData) throws IOException {
        if (!isConnected()) {
            throw new IOException("Socket not connected");
        }
        outputStream.write(framedData);
        outputStream.flush();
    }

    @Override
    protected void handleTransportError(Exception e) {
        try {
            disconnect();
        } catch (Exception ignored) {
        }

        // Notify client/listeners asynchronously
        notifyError(e);

        if (running) {
            startRetryLoop();
        }
    }

    private void startRetryLoop() {
        if (!retryLoopActive.compareAndSet(false, true)) {
            return;
        }

        Thread retryThread = new Thread(() -> {
            try {
                log.trace(">>> TCP Link lost. Retrying {}...", config.getHost());
                while (running && !isConnected()) {
                    try {
                        Thread.sleep(5000); // 5-second backoff
                        attemptConnection();
                        if (isConnected()) {
                            log.trace(">>> TCP Link Restored!");
                            notifyConnected();
                            break;
                        }
                    } catch (Exception e) {
                        // Stay in loop until connection is restored or transport stopped.
                    }
                }
            } finally {
                retryLoopActive.set(false);
            }
        }, "TcpRetryThread");
        retryThread.setDaemon(true);
        retryThread.start();
    }

    @Override
    protected void disconnect() throws Exception {
        connected = false;
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }
}
