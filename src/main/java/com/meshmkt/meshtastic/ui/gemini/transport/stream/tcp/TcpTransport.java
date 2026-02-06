package com.meshmkt.meshtastic.ui.gemini.transport.stream.tcp;

import com.meshmkt.meshtastic.ui.gemini.transport.stream.StreamTransport;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * <h2>TCP Transport</h2>
 * <p>
 * Network implementation for radios connected via WiFi or Ethernet. Manages a
 * persistent socket connection and a dedicated reader thread.
 * </p>
 */
public class TcpTransport extends StreamTransport {

    private final TcpConfig config;
    private Socket socket;
    private OutputStream outputStream;
    private volatile boolean connected = false;

    public TcpTransport(TcpConfig config) {
        this.config = config;
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
     * Blocking read loop that feeds the shared data queue.
     */
    private void readLoop() {
        while (running) {
            try (BufferedInputStream bis = new BufferedInputStream(socket.getInputStream())) {
                byte[] buffer = new byte[1024];
                int read;
                while (running && (read = bis.read(buffer)) != -1) {
                    if (read > 0) {
                        byte[] data = new byte[read];
                        System.arraycopy(buffer, 0, data, 0, read);
                        enqueueData(data);
                    }
                }
            } catch (IOException e) {
                // This is expected when the socket is closed or network fails
            } finally {
                if (running && connected) {
                    handleTransportError(new IOException("TCP Connection Lost"));
                }
            }
        }
    }

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
        // 1. Clean up the current failed session
        try {
            disconnect();
        } catch (Exception ignored) {
        }

        // 2. Alert the client (which will likely reset its UI or sync state)
        notifyDisconnected(e.getMessage());

        // 3. Launch the background recovery loop
        if (running) {
            startRetryLoop();
        }
    }

    private void startRetryLoop() {
        Thread retryThread = new Thread(() -> {
            System.out.println(">>> TCP Link lost. Retrying " + config.getHost() + "...");
            while (running && !isConnected()) {
                try {
                    Thread.sleep(5000); // 5-second backoff
                    attemptConnection();
                    if (isConnected()) {
                        System.out.println(">>> TCP Link Restored!");
                        notifyConnected(); // This triggers the MeshtasticClient 'WantConfig'
                        break;
                    }
                } catch (Exception e) {
                    // Fail silently and keep retrying
                }
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
