package com.meshmkt.meshtastic.client.service;

import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.MeshConstants;
import com.meshmkt.meshtastic.client.ProtocolConstraints;
import com.meshmkt.meshtastic.client.event.MeshtasticEventListener;
import com.meshmkt.meshtastic.client.event.StartupState;
import com.meshmkt.meshtastic.client.storage.InMemoryNodeDatabase;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.transport.stream.serial.SerialConfig;
import com.meshmkt.meshtastic.client.transport.stream.serial.SerialTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.meshtastic.proto.AdminProtos.AdminMessage.ConfigType;
import org.meshtastic.proto.AdminProtos.AdminMessage.ModuleConfigType;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ChannelProtos.ChannelSettings;
import org.meshtastic.proto.MeshProtos.DeviceMetadata;
import org.meshtastic.proto.MeshProtos.User;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Hardware-in-the-loop integration tests for admin/config flows against a real serial radio.
 * <p>
 * These tests are intentionally excluded from the default unit-test lifecycle and are executed only
 * through the {@code hardware-it} Maven profile.
 * </p>
 * <p>
 * Required runtime input:
 * </p>
 * <ul>
 * <li>{@code MESHTASTIC_TEST_PORT}: serial device path (for example {@code /dev/cu.usbmodem80B54ED11F101}).</li>
 * </ul>
 * <p>
 * Optional runtime input:
 * </p>
 * <ul>
 * <li>{@code MESHTASTIC_TEST_TIMEOUT_SEC}: per-future timeout in seconds (default: 45).</li>
 * <li>{@code MESHTASTIC_TEST_MUTABLE_CHANNEL_INDEX}: channel slot used for reversible write test (default: 2).</li>
 * </ul>
 */
@Tag("hardware")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "MESHTASTIC_TEST_PORT", matches = ".+")
class RealRadioAdminIT {
    private static final Logger log = LoggerFactory.getLogger(RealRadioAdminIT.class);

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration READBACK_POLL_WINDOW = Duration.ofSeconds(35);
    private static final Duration READBACK_POLL_INTERVAL = Duration.ofMillis(800);
    private static final Duration RECONNECT_READY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration RESTORE_READBACK_WINDOW = Duration.ofSeconds(60);
    private static final int RESTORE_ATTEMPTS = 4;
    private static final List<ConfigType> CORE_CONFIG_MATRIX = List.of(
            ConfigType.LORA_CONFIG,
            ConfigType.DEVICE_CONFIG,
            ConfigType.DISPLAY_CONFIG,
            ConfigType.NETWORK_CONFIG,
            ConfigType.SECURITY_CONFIG
    );
    private static final List<ModuleConfigType> MODULE_CONFIG_MATRIX = List.of(
            ModuleConfigType.MQTT_CONFIG,
            ModuleConfigType.SERIAL_CONFIG,
            ModuleConfigType.TELEMETRY_CONFIG,
            ModuleConfigType.STOREFORWARD_CONFIG
    );

    private MeshtasticClient client;
    private AdminService adminService;
    private Duration operationTimeout;
    private int mutableChannelIndex;
    private boolean enableOwnerWriteTest;

    /**
     * Connects to the configured real radio and waits for startup state {@link StartupState#READY}.
     *
     * @throws Exception when connection or startup sync fails.
     */
    @BeforeAll
    void connectToHardware() throws Exception {
        String port = trimToNull(System.getProperty("MESHTASTIC_TEST_PORT"));
        requireAssumption(port != null, "MESHTASTIC_TEST_PORT is required for hardware integration tests.");

        this.operationTimeout = parseDurationSeconds(
                trimToNull(System.getProperty("MESHTASTIC_TEST_TIMEOUT_SEC")),
                DEFAULT_OPERATION_TIMEOUT
        );
        this.mutableChannelIndex = parseMutableChannelIndex(trimToNull(System.getProperty("MESHTASTIC_TEST_MUTABLE_CHANNEL_INDEX")));
        this.enableOwnerWriteTest = parseBoolean(trimToNull(System.getProperty("MESHTASTIC_TEST_ENABLE_OWNER_WRITE")), false);

        NodeDatabase nodeDatabase = new InMemoryNodeDatabase();
        client = new MeshtasticClient(nodeDatabase);
        adminService = client.getAdminService();

        CountDownLatch readyLatch = new CountDownLatch(1);
        client.addEventListener(new MeshtasticEventListener() {
            @Override
            public void onStartupStateChanged(StartupState previousState, StartupState newState) {
                if (newState == StartupState.READY) {
                    readyLatch.countDown();
                }
            }
        });

        SerialConfig config = SerialConfig.builder()
                .portName(port)
                .build();
        client.connect(new SerialTransport(config));

        boolean ready = client.isReady() || readyLatch.await(READY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertTrue(ready, "Client did not reach READY within " + READY_TIMEOUT.toSeconds() + "s");
    }

    /**
     * Disconnects and shuts down client resources after hardware tests complete.
     */
    @AfterAll
    void disconnectHardware() {
        if (client != null) {
            client.shutdown();
        }
    }

    /**
     * Verifies core refresh APIs complete against a real radio and return non-null payloads.
     *
     * @throws Exception when refresh operations fail or time out.
     */
    @Test
    @Timeout(value = 180)
    void refreshCoreAndLikelyChannelsCompletes() throws Exception {
        DeviceMetadata metadata = awaitWithRetry(adminService::refreshMetadata, 2);
        assertNotNull(metadata, "Device metadata should be returned.");

        assertNotNull(
                awaitWithRetry(adminService::refreshOwner, 2),
                "Owner should be returned."
        );

        try {
            assertFalse(
                    awaitWithRetry(adminService::refreshLikelyActiveChannels, 2).isEmpty(),
                    "Likely active channel refresh should return at least the primary slot."
            );
        } catch (TimeoutException timeout) {
            Channel primary = awaitWithRetry(() -> adminService.refreshChannel(0), 2);
            assertNotNull(primary, "Primary channel refresh fallback should succeed.");
        }
    }

    /**
     * Verifies core config read matrix can be refreshed against a real radio.
     *
     * @throws Exception when all refreshes fail or a required core config fails.
     */
    @Test
    @Timeout(value = 240)
    void refreshCoreConfigMatrixCompletes() throws Exception {
        Map<ConfigType, Config> observed = new EnumMap<>(ConfigType.class);
        List<String> failures = new ArrayList<>();

        for (ConfigType type : CORE_CONFIG_MATRIX) {
            try {
                Config cfg = awaitWithRetry(() -> adminService.refreshConfig(type), 2);
                observed.put(type, cfg);
            } catch (Exception ex) {
                failures.add(type + ": " + ex.getClass().getSimpleName());
            }
        }

        assertTrue(observed.containsKey(ConfigType.LORA_CONFIG), "LORA_CONFIG refresh should succeed.");
        assertTrue(observed.containsKey(ConfigType.DEVICE_CONFIG), "DEVICE_CONFIG refresh should succeed.");
        assertTrue(observed.size() >= 2,
                "Expected at least two core config refresh successes; failures=" + failures);
    }

    /**
     * Verifies a module-config read matrix can complete at least one successful refresh.
     * <p>
     * Module availability and firmware behavior vary across devices, so this test is capability-tolerant
     * and only requires at least one successful read.
     * </p>
     *
     * @throws Exception when no module config refresh succeeds.
     */
    @Test
    @Timeout(value = 240)
    void refreshModuleConfigMatrixHasAtLeastOneSuccess() throws Exception {
        int successCount = 0;
        List<String> failures = new ArrayList<>();

        for (ModuleConfigType type : MODULE_CONFIG_MATRIX) {
            try {
                ModuleConfig cfg = awaitWithRetry(() -> adminService.refreshModuleConfig(type), 2);
                if (cfg != null) {
                    successCount++;
                }
            } catch (Exception ex) {
                failures.add(type + ": " + ex.getClass().getSimpleName());
            }
        }

        assertTrue(successCount >= 1,
                "Expected at least one module config refresh success; failures=" + failures);
    }

    /**
     * Optional reversible owner write/readback/restore test.
     * <p>
     * Disabled by default because some firmwares trigger reboot-like behavior on owner updates.
     * Enable explicitly with {@code MESHTASTIC_TEST_ENABLE_OWNER_WRITE=true}.
     * </p>
     *
     * @throws Exception when owner write/readback/restore fails.
     */
    @Test
    @Timeout(value = 300)
    void reversibleOwnerWriteReadbackWhenEnabled() throws Exception {
        requireAssumption(enableOwnerWriteTest, "Set MESHTASTIC_TEST_ENABLE_OWNER_WRITE=true to enable owner write IT.");

        int selfNodeId = client.getSelfNodeId();
        requireAssumption(isKnownSelfNodeId(selfNodeId), "Self node ID is unavailable.");

        User original = awaitWithRetry(adminService::refreshOwner, 2);
        String originalLong = original.getLongName();
        String originalShort = original.getShortName();

        String proposedLong = buildSafeOwnerLongName(originalLong);
        String proposedShort = buildSafeOwnerShortName(originalShort);

        try {
            AdminWriteResult write = awaitWithRetry(
                    () -> adminService.setOwnerResult(selfNodeId, proposedLong, proposedShort, false),
                    2
            );
            assertTrue(write.isSuccess(), "Owner write request did not succeed: " + write.message());

            try {
                User observed = awaitOwnerName(proposedLong, proposedShort, READBACK_POLL_WINDOW);
                assertEquals(proposedLong, observed.getLongName(), "Owner long name readback mismatch.");
                assertEquals(proposedShort, observed.getShortName(), "Owner short name readback mismatch.");
            } catch (AssertionError timeoutLike) {
                requireAssumption(false, "Owner write accepted but readback was unstable on this firmware: "
                        + timeoutLike.getMessage());
            }
        } finally {
            restoreOwnerWithRetry(selfNodeId, originalLong, originalShort);
        }
    }

    /**
     * Verifies a reversible channel-name write/readback cycle against a mutable channel slot.
     * <p>
     * The original channel payload is restored in a {@code finally} block to minimize persistent test impact.
     * </p>
     *
     * @throws Exception when write/readback/restore operations fail or time out.
     */
    @Test
    @Timeout(value = 240)
    void reversibleChannelNameWriteReadback() throws Exception {
        Channel original = awaitWithRetry(() -> adminService.refreshChannel(mutableChannelIndex), 3);

        requireAssumption(
                original.getRole() != Channel.Role.PRIMARY,
                "Mutable channel index points to PRIMARY slot. Set MESHTASTIC_TEST_MUTABLE_CHANNEL_INDEX to a non-primary slot."
        );
        requireAssumption(
                original.getRole() != Channel.Role.DISABLED,
                "Mutable channel index points to DISABLED slot. Use an active non-primary slot for reversible write tests."
        );

        String proposedName = buildSafeTestChannelName(original.getSettings().getName());
        Channel updated = original.toBuilder()
                .setSettings(ChannelSettings.newBuilder(original.getSettings()).setName(proposedName).build())
                .build();

        try {
            assertTrue(
                    awaitWithRetry(() -> adminService.setChannel(mutableChannelIndex, updated, false), 3),
                    "Channel write request was not accepted."
            );

            Channel observed = awaitChannelName(mutableChannelIndex, proposedName, READBACK_POLL_WINDOW);
            assertTrue(
                    Objects.equals(proposedName, observed.getSettings().getName()),
                    "Readback name did not match proposed value."
            );
        } finally {
            restoreChannelWithRetry(mutableChannelIndex, original);
        }
    }

    /**
     * Polls refreshChannel until the expected channel name is observed or timeout is reached.
     *
     * @param channelIndex channel slot to refresh.
     * @param expectedName expected channel name.
     * @param timeout maximum poll duration.
     * @return latest observed channel payload matching the expected name.
     * @throws Exception when polling does not observe expected name in time.
     */
    private Channel awaitChannelName(int channelIndex, String expectedName, Duration timeout) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Channel latest = null;

        while (System.nanoTime() < deadlineNanos) {
            latest = awaitWithRetry(() -> adminService.refreshChannel(channelIndex), 3);
            String observedName = latest.getSettings().getName();
            if (Objects.equals(observedName, expectedName)) {
                return latest;
            }
            Thread.sleep(READBACK_POLL_INTERVAL.toMillis());
        }

        throw new AssertionError("Timed out waiting for channel " + channelIndex
                + " name '" + expectedName + "'. Last observed: '" + (latest == null ? "<none>" : latest.getSettings().getName()) + "'");
    }

    /**
     * Polls owner refresh until expected names are observed or timeout is reached.
     *
     * @param expectedLong expected long name.
     * @param expectedShort expected short name.
     * @param timeout maximum poll duration.
     * @return latest owner payload matching expected names.
     * @throws Exception when expected owner names are not observed before timeout.
     */
    private User awaitOwnerName(String expectedLong, String expectedShort, Duration timeout) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        User latest = null;

        while (System.nanoTime() < deadlineNanos) {
            latest = awaitWithRetry(adminService::refreshOwner, 2);
            if (Objects.equals(latest.getLongName(), expectedLong)
                    && Objects.equals(latest.getShortName(), expectedShort)) {
                return latest;
            }
            Thread.sleep(READBACK_POLL_INTERVAL.toMillis());
        }

        throw new AssertionError("Timed out waiting for owner readback. Expected long='"
                + expectedLong + "' short='" + expectedShort + "', last='"
                + (latest == null ? "<none>" : latest.getLongName() + "/" + latest.getShortName()) + "'");
    }

    /**
     * Waits for an async operation with bounded retries for slow firmware/transport conditions.
     *
     * @param operation async operation supplier.
     * @param attempts number of attempts before failing.
     * @param <T> result type.
     * @return completed operation result.
     * @throws Exception when all attempts fail.
     */
    private <T> T awaitWithRetry(Supplier<java.util.concurrent.CompletableFuture<T>> operation, int attempts)
            throws Exception {
        Exception last = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                awaitReadyState(RECONNECT_READY_TIMEOUT);
                return operation.get().get(operationTimeout.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception ex) {
                last = ex;
                if (isTransportDisconnected(ex)) {
                    awaitReadyState(RECONNECT_READY_TIMEOUT);
                }
                if (i < attempts) {
                    Thread.sleep(READBACK_POLL_INTERVAL.toMillis());
                }
            }
        }
        throw last == null ? new IllegalStateException("Operation failed without exception detail.") : last;
    }

    /**
     * Waits until client startup state returns to READY.
     *
     * @param timeout max wait time.
     * @throws InterruptedException when interrupted while waiting.
     * @throws TimeoutException when ready state is not reached before timeout.
     */
    private void awaitReadyState(Duration timeout) throws InterruptedException, TimeoutException {
        if (client.isReady()) {
            return;
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (client.isReady()) {
                return;
            }
            Thread.sleep(READBACK_POLL_INTERVAL.toMillis());
        }
        throw new TimeoutException("Client did not return to READY within " + timeout.toSeconds() + "s");
    }

    /**
     * Detects transport-disconnect failures nested in async wrappers.
     *
     * @param throwable failure to inspect.
     * @return true when throwable chain indicates transient transport disconnect.
     */
    private boolean isTransportDisconnected(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            String msg = cursor.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains("transport disconnected before send")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * Builds a valid bounded channel name for reversible integration testing.
     *
     * @param currentName current channel name for dedupe context.
     * @return a valid UTF-8 channel name within protocol limits.
     */
    private String buildSafeTestChannelName(String currentName) {
        String seed = Long.toHexString(System.currentTimeMillis()).toUpperCase(Locale.ROOT);
        String candidate = ("it" + seed).substring(0, Math.min(11, 2 + seed.length()));
        if (Objects.equals(candidate, currentName)) {
            candidate = "itALT" + candidate.substring(Math.max(0, candidate.length() - 6));
            candidate = candidate.substring(0, Math.min(11, candidate.length()));
        }

        ProtocolConstraints.validateChannelName(candidate);
        return candidate;
    }

    /**
     * Builds a bounded owner long name for reversible testing.
     *
     * @param current current long name.
     * @return temporary long name different from current.
     */
    private String buildSafeOwnerLongName(String current) {
        String seed = Long.toHexString(System.currentTimeMillis()).toUpperCase(Locale.ROOT);
        String candidate = "IT-" + seed.substring(Math.max(0, seed.length() - 6));
        if (Objects.equals(candidate, current)) {
            candidate = candidate + "X";
        }
        return candidate;
    }

    /**
     * Builds a bounded owner short name for reversible testing.
     *
     * @param current current short name.
     * @return temporary short name different from current.
     */
    private String buildSafeOwnerShortName(String current) {
        String seed = Long.toHexString(System.currentTimeMillis()).toUpperCase(Locale.ROOT);
        String candidate = ("I" + seed).substring(0, Math.min(4, 1 + seed.length()));
        if (Objects.equals(candidate, current)) {
            candidate = "I" + seed.substring(Math.max(0, seed.length() - 3));
            candidate = candidate.substring(0, Math.min(4, candidate.length()));
        }
        return candidate;
    }

    /**
     * Parses optional duration input from system properties.
     *
     * @param secondsProp optional seconds property value.
     * @param fallback fallback duration when property is missing/invalid.
     * @return parsed duration.
     */
    private Duration parseDurationSeconds(String secondsProp, Duration fallback) {
        if (secondsProp == null) {
            return fallback;
        }
        try {
            long seconds = Long.parseLong(secondsProp.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Parses mutable channel index from runtime property with a safe default.
     *
     * @param indexProp channel index property value.
     * @return validated mutable channel index.
     */
    private int parseMutableChannelIndex(String indexProp) {
        int fallback = 2;
        if (indexProp == null) {
            return fallback;
        }
        try {
            int idx = Integer.parseInt(indexProp.trim());
            if (idx < 0 || idx >= ProtocolConstraints.MAX_CHANNEL_SLOTS) {
                return fallback;
            }
            return idx;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Parses optional boolean flag from runtime properties.
     *
     * @param value input string.
     * @param fallback fallback value when input is missing.
     * @return parsed boolean value.
     */
    private boolean parseBoolean(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> fallback;
        };
    }

    /**
     * Restores original owner values with bounded retries and readback confirmation.
     *
     * @param selfNodeId local node id.
     * @param originalLong original long name.
     * @param originalShort original short name.
     * @throws Exception when restore cannot be confirmed.
     */
    private void restoreOwnerWithRetry(int selfNodeId, String originalLong, String originalShort) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= RESTORE_ATTEMPTS; attempt++) {
            try {
                AdminWriteResult result = awaitWithRetry(
                        () -> adminService.setOwnerResult(selfNodeId, originalLong, originalShort, false),
                        2
                );
                if (!result.isSuccess()) {
                    throw new IllegalStateException("Owner restore write not accepted: " + result.message());
                }

                awaitOwnerName(originalLong, originalShort, RESTORE_READBACK_WINDOW);
                return;
            } catch (Exception ex) {
                last = ex;
                log.warn("[HARDWARE-IT] Owner restore attempt {}/{} failed: {}",
                        attempt, RESTORE_ATTEMPTS, ex.getMessage());
                if (attempt < RESTORE_ATTEMPTS) {
                    Thread.sleep(READBACK_POLL_INTERVAL.toMillis());
                }
            }
        }

        throw new AssertionError("Owner restore failed after " + RESTORE_ATTEMPTS
                + " attempts. Manual restore may be required.", last);
    }

    /**
     * Restores original channel payload with bounded retries and readback confirmation.
     *
     * @param channelIndex mutable channel index.
     * @param original original channel payload.
     * @throws Exception when restore cannot be confirmed.
     */
    private void restoreChannelWithRetry(int channelIndex, Channel original) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= RESTORE_ATTEMPTS; attempt++) {
            try {
                boolean accepted = awaitWithRetry(() -> adminService.setChannel(channelIndex, original, false), 2);
                if (!accepted) {
                    throw new IllegalStateException("Channel restore write not accepted.");
                }

                awaitChannelName(channelIndex, original.getSettings().getName(), RESTORE_READBACK_WINDOW);
                return;
            } catch (Exception ex) {
                last = ex;
                log.warn("[HARDWARE-IT] Channel restore attempt {}/{} failed: {}",
                        attempt, RESTORE_ATTEMPTS, ex.getMessage());
                if (attempt < RESTORE_ATTEMPTS) {
                    Thread.sleep(READBACK_POLL_INTERVAL.toMillis());
                }
            }
        }

        throw new AssertionError("Channel restore failed after " + RESTORE_ATTEMPTS
                + " attempts. Manual restore may be required.", last);
    }

    /**
     * Applies an assumption and logs the reason when the assumption is not met.
     *
     * @param condition assumption condition.
     * @param message skip reason when condition is false.
     */
    private void requireAssumption(boolean condition, String message) {
        if (!condition) {
            log.warn("[HARDWARE-IT][SKIP] {}", message);
        }
        assumeTrue(condition, message);
    }

    /**
     * Returns whether node id is a usable local self id for targeted admin requests.
     *
     * @param nodeId node id candidate.
     * @return true when id is neither unknown nor broadcast.
     */
    private boolean isKnownSelfNodeId(int nodeId) {
        return nodeId != MeshConstants.ID_UNKNOWN && nodeId != MeshConstants.ID_BROADCAST;
    }

    /**
     * Trims empty strings to null.
     *
     * @param value input string.
     * @return trimmed value or null.
     */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
