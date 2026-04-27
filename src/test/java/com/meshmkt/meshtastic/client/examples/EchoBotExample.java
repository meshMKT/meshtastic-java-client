package com.meshmkt.meshtastic.client.examples;

import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.event.*;
import com.meshmkt.meshtastic.client.storage.InMemoryNodeDatabase;
import com.meshmkt.meshtastic.client.storage.MeshNode;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.transport.stream.tcp.TcpConfig;
import com.meshmkt.meshtastic.client.transport.stream.tcp.TcpTransport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sample code how to show an example of an greeting/echo bot
 * <p>
 * This class lives under test sources so it stays out of the published library artifact while remaining
 * easy to run from an IDE during local development.
 * </p>
 * <p>
 * Supported configuration sources:
 * </p>
 * <ul>
 * <li>System properties: {@code -DMESHTASTIC_TCP_HOST=...}</li>
 * <li>Environment variables: {@code MESHTASTIC_TCP_HOST=...}</li>
 * </ul>
 */
public final class EchoBotExample {
    static {
        configureSimpleLoggerDefaults();
    }

    private static final Logger log = LoggerFactory.getLogger(EchoBotExample.class);

    private static final String LISTEN_CHANNEL = "LISTEN_CHANNEL";
    private static final String HOST_KEY = "MESHTASTIC_TCP_HOST";
    private static final String PORT_KEY = "MESHTASTIC_TCP_PORT";
    private static final String CONNECT_TIMEOUT_KEY = "MESHTASTIC_TCP_TIMEOUT_MS";
    private static final String PACING_KEY = "MESHTASTIC_TCP_PACING_MS";
    private static final String READY_TIMEOUT_KEY = "MESHTASTIC_TCP_READY_TIMEOUT_SEC";

    private EchoBotExample() {}

    /**
     * Runs the TCP smoke example until the process is interrupted.
     *
     * @param args unused.
     * @throws Exception when startup or shutdown fails.
     */
    public static void main(String[] args) throws Exception {
        String host = requiredSetting(HOST_KEY);
        int port = intSetting(PORT_KEY, 4403);
        int connectTimeoutMs = intSetting(CONNECT_TIMEOUT_KEY, 5000);
        long pacingMs = longSetting(PACING_KEY, 200L);
        long readyTimeoutSec = longSetting(READY_TIMEOUT_KEY, 45L);
        int channel = intSetting(LISTEN_CHANNEL, 1);

        NodeDatabase db = new InMemoryNodeDatabase();
        MeshtasticClient client = new MeshtasticClient(db);
        CountDownLatch readyLatch = new CountDownLatch(1);

        client.addEventListener(new MeshtasticEventListener() {
            @Override
            public void onStartupStateChanged(StartupState previousState, StartupState newState) {
                log.info("STARTUP {} -> {}", previousState, newState);
                if (newState == StartupState.READY) {
                    readyLatch.countDown();
                }
            }

            @Override
            public void onTextMessage(ChatMessageEvent event) {
                log.info(
                        "INCOMING_TEXT packetId={} from={} to={} channel={} direct={} text={}",
                        event.getRawPacket().getId(),
                        MeshUtils.formatId(event.getNodeId()),
                        MeshUtils.formatId(event.getDestinationId()),
                        event.getChannel(),
                        event.isDirect(),
                        event.getText());

                if (event.getNodeId() == db.getSelfNodeId()) {
                    return;
                }
                if (event.isDirect()) {
                    return;
                }
                if (event.getChannel() != channel) {
                    return;
                }

                log.info("TEXT from={} text={}", MeshUtils.formatId(event.getNodeId()), event.getText());

                String longName =
                        db.getNode(event.getNodeId()).map(MeshNode::getLongName).orElse("N/A");

                // Say a nice greeting to the user who texted to the channel.
                String reply = """
                        Hi! %s (%s), nice to meet you!
                        """
                        .formatted(longName, MeshUtils.formatId(event.getNodeId()));

                client.sendDirectText(event.getNodeId(), reply);
            }
        });

        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            try {
                                client.shutdown();
                            } catch (Exception ex) {
                                log.error("SHUTDOWN failed", ex);
                            }
                        },
                        "Mesh-EchoBot-Shutdown"));

        TcpConfig config = TcpConfig.builder()
                .host(host)
                .port(port)
                .connectionTimeoutMs(connectTimeoutMs)
                .outboundPacingDelayMs(pacingMs)
                .build();

        log.info("TCP connecting to {}:{} (timeout={}ms pacing={}ms)", host, port, connectTimeoutMs, pacingMs);
        client.connect(new TcpTransport(config));

        boolean ready = readyLatch.await(readyTimeoutSec, TimeUnit.SECONDS);
        if (ready) {
            log.info("TCP client reached READY within {}s.", readyTimeoutSec);
        } else {
            log.warn("TCP client did not reach READY within {}s. Check startup/sync logs.", readyTimeoutSec);
        }

        log.info("Echo bot example is running. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }

    /**
     * Applies development-friendly defaults for {@code slf4j-simple} when the caller has not provided
     * explicit logging properties. This keeps the smoke example self-explanatory without forcing a
     * particular logging setup on real applications.
     */
    private static void configureSimpleLoggerDefaults() {
        setIfMissing("org.slf4j.simpleLogger.showDateTime", "true");
        setIfMissing("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS");
        setIfMissing("org.slf4j.simpleLogger.showThreadName", "true");
        setIfMissing("org.slf4j.simpleLogger.levelInBrackets", "true");
        setIfMissing("org.slf4j.simpleLogger.showShortLogName", "true");
        setIfMissing("org.slf4j.simpleLogger.showLogName", "false");
        setIfMissing("org.slf4j.simpleLogger.defaultLogLevel", "info");
        setIfMissing("org.slf4j.simpleLogger.log.com.meshmkt.meshtastic.client", "error");
        setIfMissing(
                "org.slf4j.simpleLogger.log.com.meshmkt.meshtastic.client.transport.stream.tcp.TcpTransport", "warn");
    }

    /**
     * Resolves one required string setting from system properties or environment variables.
     *
     * @param key property/env key.
     * @return resolved value.
     */
    private static String requiredSetting(String key) {
        String value = setting(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required setting: " + key);
        }
        return value;
    }

    /**
     * Resolves one optional integer setting from system properties or environment variables.
     *
     * @param key property/env key.
     * @param fallback fallback value.
     * @return parsed integer or fallback.
     */
    private static int intSetting(String key, int fallback) {
        String value = setting(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    /**
     * Resolves one optional long setting from system properties or environment variables.
     *
     * @param key property/env key.
     * @param fallback fallback value.
     * @return parsed long or fallback.
     */
    private static long longSetting(String key, long fallback) {
        String value = setting(key);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value);
    }

    /**
     * Resolves one setting from system properties first, then environment variables.
     *
     * @param key property/env key.
     * @return resolved value or {@code null}.
     */
    private static String setting(String key) {
        String propertyValue = System.getProperty(key);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        return System.getenv(key);
    }

    /**
     * Sets one system property only when the caller has not already configured it.
     *
     * @param key property name.
     * @param value default value to apply.
     */
    private static void setIfMissing(String key, String value) {
        if (System.getProperty(key) == null || System.getProperty(key).isBlank()) {
            System.setProperty(key, value);
        }
    }
}
