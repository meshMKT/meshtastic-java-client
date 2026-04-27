//JAVA 21
//DEPS com.meshmkt.meshtastic:meshtastic-java-client:RELEASE_VERSION_HERE
//DEPS org.slf4j:slf4j-simple:2.0.17
//DEPS com.fasterxml.jackson.core:jackson-databind:2.19.0
//
// Run with:
// jbang WeatherBeacon.java --port /dev/ttyUSB0 --zip 27514 --channel-index 2
// or:
// jbang WeatherBeacon.java --host 192.168.1.40 --zip 27514 --channel-index 2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.ProtocolConstraints;
import com.meshmkt.meshtastic.client.event.MeshtasticEventListener;
import com.meshmkt.meshtastic.client.event.RequestLifecycleEvent;
import com.meshmkt.meshtastic.client.event.StartupState;
import com.meshmkt.meshtastic.client.storage.InMemoryNodeDatabase;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.transport.MeshtasticTransport;
import com.meshmkt.meshtastic.client.transport.stream.serial.SerialConfig;
import com.meshmkt.meshtastic.client.transport.stream.serial.SerialTransport;
import com.meshmkt.meshtastic.client.transport.stream.tcp.TcpConfig;
import com.meshmkt.meshtastic.client.transport.stream.tcp.TcpTransport;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WeatherBeacon {
    static {
        configureSimpleLoggerDefaults();
    }

    private static final Logger log = LoggerFactory.getLogger(WeatherBeacon.class);
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Starts the weather beacon.
     * <p>
     * Expected arguments:
     * </p>
     * <ul>
     * <li>{@code --zip <zipcode>} is required.</li>
     * <li>{@code --channel-index <0..7>} is required.</li>
     * <li>Either {@code --port <serial-device>} or {@code --host <ip-or-name>} is required.</li>
     * <li>{@code --tcp-port <port>} is optional when TCP is used. Default: {@code 4403}.</li>
     * <li>{@code --interval-minutes <minutes>} is optional. Default: {@code 60}.</li>
     * </ul>
     *
     * @param args command-line arguments.
     * @throws Exception if startup, transport setup, or weather retrieval fails.
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        String zip = require(options, "--zip");
        int channelIndex = parseChannelIndex(options);
        int intervalMinutes = Integer.parseInt(options.getOrDefault("--interval-minutes", "60"));

        MeshtasticTransport transport = createTransport(options);
        NodeDatabase nodeDatabase = new InMemoryNodeDatabase();
        MeshtasticClient client = new MeshtasticClient(nodeDatabase);
        CountDownLatch readyLatch = new CountDownLatch(1);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Mesh-WeatherBeacon");
            t.setDaemon(true);
            return t;
        });
        HttpClient httpClient = HttpClient.newHttpClient();

        client.addEventListener(new MeshtasticEventListener() {
            @Override
            public void onStartupStateChanged(StartupState previousState, StartupState newState) {
                log.info("Startup state: {} -> {}", previousState, newState);
                if (newState == StartupState.READY) {
                    readyLatch.countDown();
                }
            }

            @Override
            public void onRequestLifecycleUpdate(RequestLifecycleEvent event) {
                log.info(
                        "REQ id={} dst={} port={} stage={} message={}",
                        event.getRequestId(),
                        MeshUtils.formatId(event.getDestinationNodeId()),
                        event.getPort(),
                        event.getStage(),
                        event.getMessage());
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down weather beacon...");
            scheduler.shutdownNow();
            client.disconnect();
        }, "Mesh-WeatherBeacon-Shutdown"));

        client.connect(transport);
        if (!readyLatch.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Timed out waiting for client startup sync.");
        }

        Runnable publishTask = () -> {
            try {
                String weatherMessage = buildWeatherMessage(httpClient, zip);
                log.info("Sending weather update to channel slot {}: {}", channelIndex, weatherMessage);
                client.sendChannelText(channelIndex, weatherMessage, false)
                        .thenAccept(accepted -> log.info("Weather update accepted={}", accepted))
                        .join();
            } catch (Exception e) {
                log.error("Weather publish failed", e);
            }
        };

        publishTask.run();
        scheduler.scheduleAtFixedRate(publishTask, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);

        log.info("Weather beacon is running for ZIP {} on channel slot {}. Press Ctrl+C to stop.", zip, channelIndex);

        new CountDownLatch(1).await();
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
     * Builds one user-facing weather sentence for the provided ZIP code.
     * <p>
     * The example uses Open-Meteo because it has a straightforward public API and does not require a separate SDK.
     * </p>
     *
     * @param httpClient HTTP client used for the remote API calls.
     * @param zip US ZIP code to resolve.
     * @return concise weather summary ready to send as a mesh text message.
     * @throws Exception if geocoding or weather retrieval fails.
     */
    private static String buildWeatherMessage(HttpClient httpClient, String zip) throws Exception {
        JsonNode geocoding = getJson(
                httpClient,
                "https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(zip, StandardCharsets.UTF_8)
                        + "&count=1&countryCode=US&language=en&format=json");

        JsonNode location = geocoding.path("results").path(0);
        if (location.isMissingNode()) {
            throw new IllegalStateException("No geocoding result for ZIP " + zip);
        }

        double latitude = location.path("latitude").asDouble();
        double longitude = location.path("longitude").asDouble();
        String city = location.path("name").asText(zip);
        String state = location.path("admin1").asText("");

        JsonNode weather = getJson(
                httpClient,
                "https://api.open-meteo.com/v1/forecast?latitude="
                        + latitude
                        + "&longitude="
                        + longitude
                        + "&current=temperature_2m,apparent_temperature,precipitation,wind_speed_10m,weather_code"
                        + "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch&timezone=auto");

        JsonNode current = weather.path("current");
        int temp = Math.round((float) current.path("temperature_2m").asDouble());
        int feels = Math.round((float) current.path("apparent_temperature").asDouble());
        int wind = Math.round((float) current.path("wind_speed_10m").asDouble());
        double precipitation = current.path("precipitation").asDouble();
        int code = current.path("weather_code").asInt();

        return String.format(
                "Weather %s%s%s: %dF, feels like %dF, %s, wind %dmph, precip %.2fin.",
                city,
                state.isBlank() ? "" : ", ",
                state,
                temp,
                feels,
                describeWeatherCode(code),
                wind,
                precipitation);
    }

    /**
     * Fetches one JSON document and parses it with Jackson.
     *
     * @param httpClient HTTP client used for the request.
     * @param url remote URL to fetch.
     * @return parsed JSON tree.
     * @throws Exception if the HTTP call fails or returns an error status.
     */
    private static JsonNode getJson(HttpClient httpClient, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "meshtastic-java-client-weather-beacon")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
        }
        return JSON.readTree(response.body());
    }

    /**
     * Maps Open-Meteo weather codes to short user-facing phrases.
     *
     * @param code Open-Meteo weather code.
     * @return readable weather description.
     */
    private static String describeWeatherCode(int code) {
        return switch (code) {
            case 0 -> "clear skies";
            case 1, 2 -> "partly cloudy";
            case 3 -> "overcast";
            case 45, 48 -> "fog";
            case 51, 53, 55, 56, 57 -> "drizzle";
            case 61, 63, 65, 66, 67 -> "rain";
            case 71, 73, 75, 77 -> "snow";
            case 80, 81, 82 -> "rain showers";
            case 85, 86 -> "snow showers";
            case 95, 96, 99 -> "thunderstorms";
            default -> "mixed conditions";
        };
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
     * PicoCLI would be a fine choice for a larger real tool, but for a documentation sample it adds more moving pieces
     * than value. Keeping the parser here makes the example easier to read top-to-bottom.
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
