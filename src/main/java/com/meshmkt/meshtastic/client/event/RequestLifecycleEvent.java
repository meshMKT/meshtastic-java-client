package com.meshmkt.meshtastic.client.event;

import lombok.Getter;
import org.meshtastic.proto.Portnums.PortNum;

import java.time.Instant;
import java.util.Objects;

/**
 * Describes lifecycle transitions for one outbound request initiated by {@code MeshtasticClient}.
 * <p>
 * This event complements packet/domain events by exposing request-level outcomes in a single place:
 * sent, accepted, rejected, timed out, payload-observed, cancelled, or failed.
 * </p>
 */
@Getter
public final class RequestLifecycleEvent {

    /**
     * Stable lifecycle states for outbound requests.
     */
    public enum Stage {
        /**
         * Request bytes were written to transport.
         */
        SENT,
        /**
         * Request was accepted at protocol/correlation layer.
         */
        ACCEPTED,
        /**
         * Routing or protocol layer explicitly rejected request.
         */
        REJECTED,
        /**
         * Request timed out waiting for terminal protocol response.
         */
        TIMED_OUT,
        /**
         * A payload event associated with this request was observed by await helpers.
         */
        PAYLOAD_RECEIVED,
        /**
         * Request was cancelled (for example during disconnect/cleanup).
         */
        CANCELLED,
        /**
         * Request failed for non-timeout/non-rejection reasons.
         */
        FAILED
    }

    private final int requestId;
    private final int destinationNodeId;
    private final PortNum port;
    private final Stage stage;
    private final String message;
    private final Throwable error;
    private final Instant timestamp;

    private RequestLifecycleEvent(int requestId,
                                  int destinationNodeId,
                                  PortNum port,
                                  Stage stage,
                                  String message,
                                  Throwable error) {
        this.requestId = requestId;
        this.destinationNodeId = destinationNodeId;
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.stage = Objects.requireNonNull(stage, "stage must not be null");
        this.message = message == null ? "" : message;
        this.error = error;
        this.timestamp = Instant.now();
    }

    /**
     * Creates a new request lifecycle event instance.
     *
     * @param requestId correlated outbound request ID (0 when unavailable).
     * @param destinationNodeId destination node ID.
     * @param port target port.
     * @param stage lifecycle stage.
     * @param message human-readable diagnostic message.
     * @param error optional error for failure stages.
     * @return immutable lifecycle event.
     */
    public static RequestLifecycleEvent of(int requestId,
                                           int destinationNodeId,
                                           PortNum port,
                                           Stage stage,
                                           String message,
                                           Throwable error) {
        return new RequestLifecycleEvent(requestId, destinationNodeId, port, stage, message, error);
    }
}
