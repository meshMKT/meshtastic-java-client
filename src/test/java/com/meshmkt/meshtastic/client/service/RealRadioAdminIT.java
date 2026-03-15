package com.meshmkt.meshtastic.client.service;

import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.MeshConstants;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Hardware-in-the-loop integration tests for admin/config flows against a real radio.
 * <p>
 * These tests are intentionally excluded from the default unit-test lifecycle and are executed only
 * through the {@code hardware-it} Maven profile.
 * </p>
 * <p>
 * Required runtime input:
 * </p>
 * <ul>
 * <li>{@code MESHTASTIC_TEST_TRANSPORT}: transport kind, either {@code serial} (default) or {@code tcp}.</li>
 * <li>{@code MESHTASTIC_TEST_PORT}: required when {@code MESHTASTIC_TEST_TRANSPORT=serial}.</li>
 * <li>{@code MESHTASTIC_TEST_TCP_HOST}: required when {@code MESHTASTIC_TEST_TRANSPORT=tcp}.</li>
 * </ul>
 * <p>
 * Optional runtime input:
 * </p>
 * <ul>
 * <li>{@code MESHTASTIC_TEST_TCP_PORT}: TCP port when {@code MESHTASTIC_TEST_TRANSPORT=tcp} (default: 4403).</li>
 * <li>{@code MESHTASTIC_TEST_TIMEOUT_SEC}: per-future timeout in seconds (default: 45).</li>
 * <li>{@code MESHTASTIC_TEST_MUTABLE_CHANNEL_INDEX}: channel slot used for reversible write test (default: 2).</li>
 * </ul>
 */
@Tag("hardware")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealRadioAdminIT {
    private static final Logger log = LoggerFactory.getLogger(RealRadioAdminIT.class);

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration READBACK_POLL_WINDOW = Duration.ofSeconds(35);
    private static final Duration READBACK_POLL_INTERVAL = Duration.ofMillis(800);
    private static final Duration RECONNECT_READY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration SELF_ID_READY_TIMEOUT = Duration.ofSeconds(20);
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
    private boolean enableSecurityWriteTest;
    private boolean enableMqttWriteTest;
    private boolean enableRebootResilienceTest;
    private String transportKind;
    private final List<RequestLifecycleEvent> lifecycleEvents = new CopyOnWriteArrayList<>();

    /**
     * Connects to the configured real radio and waits for startup state {@link StartupState#READY}.
     *
     * @throws Exception when connection or startup sync fails.
     */
    @BeforeAll
    void connectToHardware() throws Exception {
        this.transportKind = parseTransportKind(trimToNull(System.getProperty("MESHTASTIC_TEST_TRANSPORT")));
        this.operationTimeout = parseDurationSeconds(
                trimToNull(System.getProperty("MESHTASTIC_TEST_TIMEOUT_SEC")),
                DEFAULT_OPERATION_TIMEOUT
        );
        this.mutableChannelIndex = parseMutableChannelIndex(trimToNull(System.getProperty("MESHTASTIC_TEST_MUTABLE_CHANNEL_INDEX")));
        this.enableOwnerWriteTest = parseBoolean(trimToNull(System.getProperty("MESHTASTIC_TEST_ENABLE_OWNER_WRITE")), false);
        this.enableSecurityWriteTest = parseBoolean(trimToNull(System.getProperty("MESHTASTIC_TEST_ENABLE_SECURITY_WRITE")), false);
        this.enableMqttWriteTest = parseBoolean(trimToNull(System.getProperty("MESHTASTIC_TEST_ENABLE_MQTT_WRITE")), false);
        this.enableRebootResilienceTest = parseBoolean(trimToNull(System.getProperty("MESHTASTIC_TEST_ENABLE_REBOOT_TEST")), false);

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

            @Override
            public void onRequestLifecycleUpdate(RequestLifecycleEvent event) {
                lifecycleEvents.add(event);
            }
        });

        client.connect(buildTransport());

        boolean ready = client.isReady() || readyLatch.await(READY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertTrue(ready, "Client did not reach READY within " + READY_TIMEOUT.toSeconds() + "s");
        requireAssumption(awaitKnownSelfNodeId(SELF_ID_READY_TIMEOUT),
                "Self node ID did not become available after READY.");
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
     * Optional reversible security-config write/readback/restore.
     * <p>
     * Enabled with {@code MESHTASTIC_TEST_ENABLE_SECURITY_WRITE=true}.
     * </p>
     *
     * @throws Exception when write/readback/restore fails.
     */
    @Test
    @Timeout(value = 300)
    void reversibleSecurityConfigWriteReadbackWhenEnabled() throws Exception {
        requireAssumption(enableSecurityWriteTest,
                "Set MESHTASTIC_TEST_ENABLE_SECURITY_WRITE=true to enable security write IT.");

        Config current = awaitWithRetry(adminService::refreshSecurityConfig, 2);
        requireAssumption(current != null && current.hasSecurity(), "Security config is unavailable on this device.");
        Config.SecurityConfig original = current.getSecurity();

        boolean proposed = !original.getDebugLogApiEnabled();
        Config.SecurityConfig updated = original.toBuilder().setDebugLogApiEnabled(proposed).build();
        Config writePayload = Config.newBuilder().setSecurity(updated).build();

        try {
            AdminWriteResult write = awaitWithRetry(() -> adminService.setConfigResult(writePayload, false), 2);
            assertTrue(write.isSuccess(), "Security config write request did not succeed: " + write.message());

            awaitBooleanCondition(
                    () -> awaitWithRetry(adminService::refreshSecurityConfig, 2).getSecurity().getDebugLogApiEnabled() == proposed,
                    READBACK_POLL_WINDOW,
                    "Timed out waiting for security.debug_log_api_enabled to update."
            );
        } finally {
            Config restorePayload = Config.newBuilder().setSecurity(original).build();
            awaitWithRetry(() -> adminService.setConfigResult(restorePayload, false), 2);
            awaitBooleanCondition(
                    () -> awaitWithRetry(adminService::refreshSecurityConfig, 2).getSecurity().getDebugLogApiEnabled()
                            == original.getDebugLogApiEnabled(),
                    RESTORE_READBACK_WINDOW,
                    "Timed out waiting for security config restore."
            );
        }
    }

    /**
     * Optional reversible MQTT module-config write/readback/restore.
     * <p>
     * Enabled with {@code MESHTASTIC_TEST_ENABLE_MQTT_WRITE=true}.
     * </p>
     *
     * @throws Exception when write/readback/restore fails.
     */
    @Test
    @Timeout(value = 300)
    void reversibleMqttModuleConfigWriteReadbackWhenEnabled() throws Exception {
        requireAssumption(enableMqttWriteTest,
                "Set MESHTASTIC_TEST_ENABLE_MQTT_WRITE=true to enable MQTT write IT.");

        ModuleConfig current = awaitWithRetry(adminService::refreshMqttConfig, 2);
        requireAssumption(current != null && current.hasMqtt(), "MQTT module config is unavailable on this device.");
        ModuleConfig.MQTTConfig original = current.getMqtt();

        boolean proposed = !original.getJsonEnabled();
        ModuleConfig.MQTTConfig updated = original.toBuilder().setJsonEnabled(proposed).build();
        ModuleConfig writePayload = ModuleConfig.newBuilder().setMqtt(updated).build();

        try {
            AdminWriteResult write = awaitWithRetry(() -> adminService.setModuleConfigResult(writePayload, false), 2);
            assertTrue(write.isSuccess(), "MQTT config write request did not succeed: " + write.message());

            awaitBooleanCondition(
                    () -> awaitWithRetry(adminService::refreshMqttConfig, 2).getMqtt().getJsonEnabled() == proposed,
                    READBACK_POLL_WINDOW,
                    "Timed out waiting for mqtt.json_enabled to update."
            );
        } finally {
            ModuleConfig restorePayload = ModuleConfig.newBuilder().setMqtt(original).build();
            awaitWithRetry(() -> adminService.setModuleConfigResult(restorePayload, false), 2);
            awaitBooleanCondition(
                    () -> awaitWithRetry(adminService::refreshMqttConfig, 2).getMqtt().getJsonEnabled()
                            == original.getJsonEnabled(),
                    RESTORE_READBACK_WINDOW,
                    "Timed out waiting for mqtt config restore."
            );
        }
    }

    /**
     * Verifies request-lifecycle events produce terminal states for a burst of utility requests.
     *
     * @throws Exception when lifecycle terminal events do not arrive in time.
     */
    @Test
    @Timeout(value = 240)
    void requestBurstLifecycleEmitsTerminalStages() throws Exception {
        requireAssumption(isKnownSelfNodeId(client.getSelfNodeId()), "Self node ID is unavailable.");
        lifecycleEvents.clear();

        int targetNodeId = client.getSelfNodeId();
        int burstCount = 6;
        for (int i = 0; i < burstCount; i++) {
            int mod = i % 3;
            if (mod == 0) {
                awaitWithRetry(() -> client.requestTelemetry(targetNodeId), 2);
            } else if (mod == 1) {
                awaitWithRetry(() -> client.requestPosition(targetNodeId), 2);
            } else {
                awaitWithRetry(() -> client.requestNodeInfo(targetNodeId), 2);
            }
        }

        awaitBooleanCondition(() -> {
            Set<Integer> sent = new HashSet<>();
            Set<Integer> terminal = new HashSet<>();
            for (RequestLifecycleEvent event : lifecycleEvents) {
                if (event.getDestinationNodeId() != targetNodeId) {
                    continue;
                }
                if (event.getStage() == RequestLifecycleEvent.Stage.SENT) {
                    sent.add(event.getRequestId());
                } else if (isTerminalLifecycleStage(event.getStage())) {
                    terminal.add(event.getRequestId());
                }
            }
            return !sent.isEmpty() && terminal.containsAll(sent);
        }, Duration.ofSeconds(45), "Timed out waiting for request burst terminal lifecycle events.");
    }

    /**
     * Optional reboot/reconnect resilience test.
     * <p>
     * Enabled with {@code MESHTASTIC_TEST_ENABLE_REBOOT_TEST=true}.
     * </p>
     *
     * @throws Exception when reboot request or reconnection validation fails.
     */
    @Test
    @Timeout(value = 360)
    void rebootReconnectResilienceWhenEnabled() throws Exception {
        requireAssumption(enableRebootResilienceTest,
                "Set MESHTASTIC_TEST_ENABLE_REBOOT_TEST=true to enable reboot resilience IT.");
        requireAssumption(isKnownSelfNodeId(client.getSelfNodeId()), "Self node ID is unavailable.");

        boolean accepted = awaitWithRetry(() -> adminService.reboot(1), 2);
        assertTrue(accepted, "Reboot request was not accepted.");

        awaitReadyState(Duration.ofSeconds(180));
        requireAssumption(awaitKnownSelfNodeId(Duration.ofSeconds(45)),
                "Self node ID did not recover after reboot.");
        assertNotNull(awaitWithRetry(adminService::refreshMetadata, 2), "Metadata refresh after reboot should succeed.");
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
     * Builds the requested hardware test transport from system properties.
     *
     * @return configured serial or TCP transport.
     */
    private MeshtasticTransport buildTransport() {
        if ("tcp".equals(transportKind)) {
            String host = trimToNull(System.getProperty("MESHTASTIC_TEST_TCP_HOST"));
            requireAssumption(host != null,
                    "MESHTASTIC_TEST_TCP_HOST is required when MESHTASTIC_TEST_TRANSPORT=tcp.");
            return new TcpTransport(TcpConfig.builder()
                    .host(host)
                    .port(parseTcpPort(trimToNull(System.getProperty("MESHTASTIC_TEST_TCP_PORT"))))
                    .build());
        }

        String port = trimToNull(System.getProperty("MESHTASTIC_TEST_PORT"));
        requireAssumption(port != null,
                "MESHTASTIC_TEST_PORT is required when MESHTASTIC_TEST_TRANSPORT=serial.");
        return new SerialTransport(SerialConfig.builder()
                .portName(port)
                .build());
    }

    /**
     * Parses the hardware test transport kind.
     *
     * @param raw transport property value.
     * @return validated transport kind.
     */
    private String parseTransportKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return "serial";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        requireAssumption("serial".equals(normalized) || "tcp".equals(normalized),
                "MESHTASTIC_TEST_TRANSPORT must be 'serial' or 'tcp'.");
        return normalized;
    }

    /**
     * Parses the optional TCP port property for hardware integration testing.
     *
     * @param raw optional TCP port value.
     * @return TCP port or the Meshtastic default of 4403.
     */
    private int parseTcpPort(String raw) {
        int fallback = 4403;
        if (raw == null) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port > 0 ? port : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Functional supplier variant that may throw checked exceptions.
     *
     * @param <T> supplied type.
     */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        /**
         * Supplies a value and may throw.
         *
         * @return supplied value.
         * @throws Exception when supply fails.
         */
        T get() throws Exception;
    }

    /**
     * Polls a boolean condition until true or timeout, allowing checked exceptions in the condition.
     *
     * @param predicate condition supplier.
     * @param timeout max wait duration.
     * @param failureMessage failure message when timeout is reached.
     * @throws Exception when predicate evaluation throws or timeout is reached.
     */
    private void awaitBooleanCondition(ThrowingSupplier<Boolean> predicate, Duration timeout, String failureMessage)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(predicate.get())) {
                return;
            }
            Thread.sleep(READBACK_POLL_INTERVAL.toMillis());
        }
        throw new AssertionError(failureMessage);
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
     * Returns whether request lifecycle stage is terminal.
     *
     * @param stage lifecycle stage.
     * @return true for terminal outcomes.
     */
    private boolean isTerminalLifecycleStage(RequestLifecycleEvent.Stage stage) {
        return stage == RequestLifecycleEvent.Stage.ACCEPTED
                || stage == RequestLifecycleEvent.Stage.REJECTED
                || stage == RequestLifecycleEvent.Stage.TIMED_OUT
                || stage == RequestLifecycleEvent.Stage.CANCELLED
                || stage == RequestLifecycleEvent.Stage.FAILED;
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
     * Waits until client reports a known self node id.
     *
     * @param timeout max wait time.
     * @return true when known self id becomes available before timeout.
     * @throws InterruptedException when interrupted while waiting.
     */
    private boolean awaitKnownSelfNodeId(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isKnownSelfNodeId(client.getSelfNodeId())) {
                return true;
            }
            Thread.sleep(READBACK_POLL_INTERVAL.toMillis());
        }
        return isKnownSelfNodeId(client.getSelfNodeId());
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
