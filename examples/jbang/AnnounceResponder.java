//JAVA 21
//DEPS com.meshmkt.meshtastic:meshtastic-java-client:RELEASE_VERSION_HERE
//DEPS org.slf4j:slf4j-simple:2.0.17
//
// Run with serial:
// jbang AnnounceResponder.java --port /dev/ttyUSB0 --channel-index 1
//
// Run with TCP:
// jbang AnnounceResponder.java --host 192.168.1.40 --channel-index 1

import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.ProtocolConstraints;
import com.meshmkt.meshtastic.client.event.ChatMessageEvent;
import com.meshmkt.meshtastic.client.event.MeshtasticEventListener;
import com.meshmkt.meshtastic.client.event.StartupState;
import com.meshmkt.meshtastic.client.storage.InMemoryNodeDatabase;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.transport.MeshtasticTransport;
import com.meshmkt.meshtastic.client.transport.stream.serial.SerialConfig;
import com.meshmkt.meshtastic.client.transport.stream.serial.SerialTransport;
import com.meshmkt.meshtastic.client.transport.stream.tcp.TcpConfig;
import com.meshmkt.meshtastic.client.transport.stream.tcp.TcpTransport;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small responder example that listens for a keyword on one channel slot and replies by direct message.
 * <p>
 * This is intentionally a very small "bot" example. It shows the parts most application developers care about first:
 * connect, wait for startup sync, watch text events, and send a response back to the originating node.
 * </p>
 * <p>
 * The example accepts either a serial radio connection or a TCP radio connection so developers can reuse the same
 * sample in both common setups.
 * </p>
 */
public class AnnounceResponder {
    static {
        configureSimpleLoggerDefaults();
    }

    private static final Logger log = LoggerFactory.getLogger(AnnounceResponder.class);

    /**
     * How long we are willing to wait for the startup synchronizer to reach {@link StartupState#READY}.
     */
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Starts the responder.
     * <p>
     * Expected arguments:
     * </p>
     * <ul>
     * <li>{@code --channel-index <0..7>} is required.</li>
     * <li>Either {@code --port <serial-device>} or {@code --host <ip-or-name>} is required.</li>
     * <li>{@code --tcp-port <port>} is optional when TCP is used. Default: {@code 4403}.</li>
     * <li>{@code --keyword <text>} is optional. Default: {@code /announce}.</li>
     * </ul>
     *
     * @param args command-line arguments.
     * @throws Exception if startup, argument validation, or transport setup fails.
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        int channelIndex = parseChannelIndex(options);
        String keyword = options.getOrDefault("--keyword", "/announce");

        MeshtasticTransport transport = createTransport(options);
        NodeDatabase nodeDatabase = new InMemoryNodeDatabase();
        MeshtasticClient client = new MeshtasticClient(nodeDatabase);
        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch keepAlive = new CountDownLatch(1);

        client.addEventListener(new MeshtasticEventListener() {
            /**
             * The example waits for the client to finish its normal startup sync before it begins reacting to traffic.
             */
            @Override
            public void onStartupStateChanged(StartupState previousState, StartupState newState) {
                log.info("Startup state: {} -> {}", previousState, newState);
                if (newState == StartupState.READY) {
                    readyLatch.countDown();
                }
            }

            /**
             * Reacts only to broadcast traffic on the configured channel slot.
             * <p>
             * We ignore direct messages and self-authored messages so the example stays focused on the simple
             * "/announce on a shared channel -> DM reply" flow.
             * </p>
             */
            @Override
            public void onTextMessage(ChatMessageEvent event) {
                if (event.isDirect() || event.getNodeId() == client.getSelfNodeId()) {
                    return;
                }
                if (event.getChannel() != channelIndex) {
                    return;
                }

                String trimmed = event.getText() == null ? "" : event.getText().trim();
                if (!trimmed.equalsIgnoreCase(keyword)) {
                    return;
                }

                log.info(
                        "Matched {} from {} on channel {}. Sending DM reply.",
                        keyword,
                        MeshUtils.formatId(event.getNodeId()),
                        channelIndex);

                client.sendDirectText(
                                event.getNodeId(),
                                "Hello from the Meshtastic Java Client. I heard your /announce.",
                                false)
                        .whenComplete((accepted, error) -> {
                            if (error != null) {
                                log.error("Direct reply failed", error);
                            } else {
                                log.info("Direct reply accepted={}", accepted);
                            }
                        });
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down responder...");
            client.disconnect();
        }, "Mesh-AnnounceResponder-Shutdown"));

        log.info("Connecting to radio...");
        client.connect(transport);

        if (!readyLatch.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Timed out waiting for client startup sync.");
        }

        log.info("Watching channel slot {} for keyword {}", channelIndex, keyword);
        log.info("Responder is running. Press Ctrl+C to stop.");

        keepAlive.await();
    }

    /**
     * Creates either a serial transport or a TCP transport from the supplied command-line options.
     *
     * @param options parsed CLI options.
     * @return configured transport instance.
     */
    private static MeshtasticTransport createTransport(Map<String, String> options) {
        String serialPort = options.get("--port");
        String host = options.get("--host");

        if (serialPort != null && host != null) {
            throw new IllegalArgumentException("Choose either --port for serial or --host for TCP, not both.");
        }
        if (serialPort == null && host == null) {
            throw new IllegalArgumentException("Provide either --port for serial or --host for TCP.");
        }

        if (serialPort != null) {
            return new SerialTransport(SerialConfig.builder().portName(serialPort).build());
        }

        int tcpPort = Integer.parseInt(options.getOrDefault("--tcp-port", "4403"));
        return new TcpTransport(TcpConfig.builder().host(host).port(tcpPort).build());
    }

    /**
     * Parses and validates the required channel slot index.
     *
     * @param options parsed CLI options.
     * @return validated Meshtastic channel slot index.
     */
    private static int parseChannelIndex(Map<String, String> options) {
        int channelIndex = Integer.parseInt(require(options, "--channel-index"));
        ProtocolConstraints.validateChannelIndex(channelIndex);
        return channelIndex;
    }

    /**
     * Very small option parser for example purposes.
     * <p>
     * PicoCLI would work fine here, but for a short documentation sample this keeps the example self-contained and
     * avoids pulling in another framework just to explain a few arguments.
     * </p>
     *
     * @param args raw command-line arguments.
     * @return map of {@code --flag -> value}.
     */
    private static Map<String, String> parseArgs(String[] args) {
        java.util.LinkedHashMap<String, String> options = new java.util.LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!key.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + key);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + key);
            }
            options.put(key, args[++i]);
        }
        return options;
    }

    /**
     * Returns a required option value or throws a clear error if it is missing.
     *
     * @param options parsed CLI options.
     * @param key required flag name.
     * @return non-null option value.
     */
    private static String require(Map<String, String> options, String key) {
        return Objects.requireNonNull(options.get(key), () -> "Missing required argument " + key);
    }

    /**
     * Applies readable defaults for {@code slf4j-simple} while still allowing callers to override them.
     */
    private static void configureSimpleLoggerDefaults() {
        setIfMissing("org.slf4j.simpleLogger.showDateTime", "true");
        setIfMissing("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS");
        setIfMissing("org.slf4j.simpleLogger.showThreadName", "true");
        setIfMissing("org.slf4j.simpleLogger.levelInBrackets", "true");
        setIfMissing("org.slf4j.simpleLogger.showShortLogName", "true");
        setIfMissing("org.slf4j.simpleLogger.showLogName", "false");
        setIfMissing("org.slf4j.simpleLogger.defaultLogLevel", "info");
        setIfMissing("org.slf4j.simpleLogger.log.com.meshmkt.meshtastic.client", "info");
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
