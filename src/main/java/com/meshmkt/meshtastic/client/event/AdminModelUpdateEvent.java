package com.meshmkt.meshtastic.client.event;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;
import org.meshtastic.proto.AdminProtos.AdminMessage.ConfigType;
import org.meshtastic.proto.AdminProtos.AdminMessage.ModuleConfigType;

/**
 * Event emitted when local admin-facing radio model state changes.
 * <p>
 * This event is intended for settings/configuration UIs that need live updates for owner/config/channel/module
 * changes without reconnecting or polling continuously.
 * </p>
 */
@Getter
@Builder
public final class AdminModelUpdateEvent {

    /**
     * Model section that was updated.
     */
    private final Section section;

    /**
     * Optional channel index when {@link #section} is {@link Section#CHANNEL}.
     */
    private final Integer channelIndex;

    /**
     * Optional config type when {@link #section} is {@link Section#CONFIG}.
     */
    private final ConfigType configType;

    /**
     * Optional module config type when {@link #section} is {@link Section#MODULE_CONFIG}.
     */
    private final ModuleConfigType moduleConfigType;

    /**
     * Optional node id for updates tied to a specific node identity.
     */
    private final Integer nodeId;

    /**
     * Source tag describing where the update originated (for example {@code ADMIN_APP} or {@code FROM_RADIO}).
     */
    private final String source;

    /**
     * Event creation timestamp.
     */
    @Builder.Default
    private final Instant timestamp = Instant.now();

    /**
     * Admin model section types.
     */
    public enum Section {
        /**
         * Local node identity update (for example {@code my_info}).
         */
        LOCAL_NODE,
        /**
         * Owner/user identity update.
         */
        OWNER,
        /**
         * Config section update.
         */
        CONFIG,
        /**
         * Channel slot update.
         */
        CHANNEL,
        /**
         * Module config section update.
         */
        MODULE_CONFIG,
        /**
         * Device metadata update.
         */
        DEVICE_METADATA
    }
}
