package com.meshmkt.meshtastic.client.service;

import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.AdminProtos.AdminMessage.ConfigType;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Encapsulates verify-applied retry policy and payload comparison rules for admin writes.
 * <p>
 * This retry logic is independent of the physical transport. It assumes only that future refresh/write
 * requests can still be issued successfully after the current attempt completes or reconnects.
 * </p>
 */
@Slf4j
final class AdminVerificationEngine {

    /**
     * Internal verification attempt envelope.
     *
     * @param applied whether this attempt verified applied state.
     * @param error optional attempt error.
     */
    private record VerificationAttemptResult(boolean applied, Throwable error) {
    }

    private volatile AdminVerificationPolicy verificationPolicy = AdminVerificationPolicy.builder().build().validated();

    /**
     * Returns the current verification policy.
     *
     * @return active verification policy.
     */
    AdminVerificationPolicy getVerificationPolicy() {
        return verificationPolicy;
    }

    /**
     * Sets the verification policy used by future verify-applied operations.
     *
     * @param verificationPolicy new policy.
     */
    void setVerificationPolicy(AdminVerificationPolicy verificationPolicy) {
        this.verificationPolicy = Objects.requireNonNull(verificationPolicy, "verificationPolicy must not be null")
                .validated();
    }

    /**
     * Executes verify-applied logic with the configured retry/backoff policy.
     *
     * @param operation operation label for diagnostics.
     * @param verifier verification attempt supplier.
     * @return future completing with {@code true} once one attempt verifies applied state.
     */
    CompletableFuture<Boolean> verifyWithPolicy(String operation,
                                                Supplier<CompletableFuture<Boolean>> verifier) {
        return verifyWithPolicy(operation, verifier, 1);
    }

    /**
     * Verifies whether requested config payload sections match observed read-back sections.
     *
     * @param requested requested config payload.
     * @param observedByType observed config payloads keyed by type.
     * @param impactedTypes affected config types.
     * @return {@code true} when all impacted sections match.
     */
    boolean isConfigApplied(Config requested, Map<ConfigType, Config> observedByType, List<ConfigType> impactedTypes) {
        if (impactedTypes == null || impactedTypes.isEmpty()) {
            return false;
        }

        for (ConfigType type : impactedTypes) {
            Config observed = observedByType.get(type);
            if (observed == null) {
                return false;
            }
            boolean sectionMatches = switch (type) {
                case DEVICE_CONFIG -> requested.hasDevice() && observed.hasDevice()
                        && requested.getDevice().equals(observed.getDevice());
                case POSITION_CONFIG -> requested.hasPosition() && observed.hasPosition()
                        && requested.getPosition().equals(observed.getPosition());
                case POWER_CONFIG -> requested.hasPower() && observed.hasPower()
                        && requested.getPower().equals(observed.getPower());
                case NETWORK_CONFIG -> requested.hasNetwork() && observed.hasNetwork()
                        && requested.getNetwork().equals(observed.getNetwork());
                case DISPLAY_CONFIG -> requested.hasDisplay() && observed.hasDisplay()
                        && requested.getDisplay().equals(observed.getDisplay());
                case LORA_CONFIG -> requested.hasLora() && observed.hasLora()
                        && requested.getLora().equals(observed.getLora());
                case BLUETOOTH_CONFIG -> requested.hasBluetooth() && observed.hasBluetooth()
                        && requested.getBluetooth().equals(observed.getBluetooth());
                case SECURITY_CONFIG -> requested.hasSecurity() && observed.hasSecurity()
                        && requested.getSecurity().equals(observed.getSecurity());
                case SESSIONKEY_CONFIG -> requested.hasSessionkey() && observed.hasSessionkey()
                        && requested.getSessionkey().equals(observed.getSessionkey());
                case DEVICEUI_CONFIG -> requested.hasDeviceUi() && observed.hasDeviceUi()
                        && requested.getDeviceUi().equals(observed.getDeviceUi());
                case UNRECOGNIZED -> false;
            };
            if (!sectionMatches) {
                return false;
            }
        }

        return true;
    }

    /**
     * Verifies whether requested module-config payload matches observed read-back payload.
     *
     * @param requested requested module config payload.
     * @param observed observed module config payload.
     * @return {@code true} when the variant and value match.
     */
    boolean isModuleConfigApplied(ModuleConfig requested, ModuleConfig observed) {
        if (requested == null || observed == null) {
            return false;
        }

        if (requested.getPayloadVariantCase() != observed.getPayloadVariantCase()) {
            return false;
        }

        return switch (requested.getPayloadVariantCase()) {
            case MQTT -> requested.getMqtt().equals(observed.getMqtt());
            case SERIAL -> requested.getSerial().equals(observed.getSerial());
            case EXTERNAL_NOTIFICATION -> requested.getExternalNotification().equals(observed.getExternalNotification());
            case STORE_FORWARD -> requested.getStoreForward().equals(observed.getStoreForward());
            case RANGE_TEST -> requested.getRangeTest().equals(observed.getRangeTest());
            case TELEMETRY -> requested.getTelemetry().equals(observed.getTelemetry());
            case CANNED_MESSAGE -> requested.getCannedMessage().equals(observed.getCannedMessage());
            case AUDIO -> requested.getAudio().equals(observed.getAudio());
            case REMOTE_HARDWARE -> requested.getRemoteHardware().equals(observed.getRemoteHardware());
            case NEIGHBOR_INFO -> requested.getNeighborInfo().equals(observed.getNeighborInfo());
            case AMBIENT_LIGHTING -> requested.getAmbientLighting().equals(observed.getAmbientLighting());
            case DETECTION_SENSOR -> requested.getDetectionSensor().equals(observed.getDetectionSensor());
            case PAXCOUNTER -> requested.getPaxcounter().equals(observed.getPaxcounter());
            case STATUSMESSAGE -> requested.getStatusmessage().equals(observed.getStatusmessage());
            case PAYLOADVARIANT_NOT_SET -> false;
        };
    }

    /**
     * Verifies whether requested channel role/settings match read-back channel state.
     *
     * @param expected requested channel.
     * @param appliedChannel observed channel.
     * @return {@code true} when role and settings match.
     */
    boolean isChannelApplied(org.meshtastic.proto.ChannelProtos.Channel expected,
                             org.meshtastic.proto.ChannelProtos.Channel appliedChannel) {
        return expected.getRole() == appliedChannel.getRole()
                && expected.getSettings().equals(appliedChannel.getSettings());
    }

    private CompletableFuture<Boolean> verifyWithPolicy(String operation,
                                                        Supplier<CompletableFuture<Boolean>> verifier,
                                                        int attempt) {
        long delayMs = verificationDelayMs(attempt);
        CompletableFuture<Boolean> attemptFuture = delayMs <= 0
                ? invokeVerifier(verifier)
                : CompletableFuture.runAsync(
                        () -> {
                        },
                        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
                .thenCompose(unused -> invokeVerifier(verifier));

        return attemptFuture.handle((applied, error) -> new VerificationAttemptResult(Boolean.TRUE.equals(applied), error))
                .thenCompose(result -> {
                    if (result.applied()) {
                        return CompletableFuture.completedFuture(true);
                    }

                    int maxAttempts = verificationPolicy.getMaxAttempts();
                    if (attempt >= maxAttempts) {
                        if (result.error() != null) {
                            log.debug("[ADMIN] {} verification exhausted after {} attempt(s). Last error: {}",
                                    operation, attempt, unwrap(result.error()).getMessage());
                        }
                        return CompletableFuture.completedFuture(false);
                    }

                    if (result.error() != null) {
                        log.debug("[ADMIN] {} verification attempt {}/{} failed with error: {}",
                                operation, attempt, maxAttempts, unwrap(result.error()).getMessage());
                    } else {
                        log.debug("[ADMIN] {} verification attempt {}/{} did not match; retrying...",
                                operation, attempt, maxAttempts);
                    }

                    return verifyWithPolicy(operation, verifier, attempt + 1);
                });
    }

    /**
     * Computes retry delay for a verification attempt.
     *
     * @param attempt 1-based attempt number.
     * @return delay in milliseconds.
     */
    private long verificationDelayMs(int attempt) {
        if (attempt <= 1) {
            return 0L;
        }
        AdminVerificationPolicy policy = verificationPolicy;
        double base = policy.getInitialRetryDelay().toMillis();
        double multiplier = Math.pow(policy.getRetryBackoffMultiplier(), Math.max(0, attempt - 2));
        long computed = Math.round(base * multiplier);
        return Math.min(computed, policy.getMaxRetryDelay().toMillis());
    }

    /**
     * Invokes a verifier safely.
     *
     * @param verifier verification supplier.
     * @return verifier future, never {@code null}.
     */
    private static CompletableFuture<Boolean> invokeVerifier(Supplier<CompletableFuture<Boolean>> verifier) {
        try {
            CompletableFuture<Boolean> attempt = verifier.get();
            return attempt == null ? CompletableFuture.completedFuture(false) : attempt;
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /**
     * Unwraps nested async exceptions for logging.
     *
     * @param error throwable to unwrap.
     * @return root cause or original throwable.
     */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }
}
