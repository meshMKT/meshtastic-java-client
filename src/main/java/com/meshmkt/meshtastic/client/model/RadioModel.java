package com.meshmkt.meshtastic.client.model;

import lombok.Data;
import org.meshtastic.proto.AdminProtos.AdminMessage.ModuleConfigType;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.MeshProtos.User;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents the current state and configuration of the connected Radio. This
 * model acts as the local "Source of Truth" for the UI.
 */
@Data
public class RadioModel {

    // Identity
    private User owner;
    private int nodeId;

    // Core Config Sections (Read/Write via setConfig)
    private Config.LoRaConfig loraConfig;
    private Config.DeviceConfig deviceConfig;
    private Config.DisplayConfig displayConfig;
    private Config.NetworkConfig networkConfig;

    // Channel Slots (0-7)
    private final Map<Integer, Channel> channels = new ConcurrentHashMap<>();

    /**
     * Stores Module-specific configurations (Canned Messages, Telemetry, MQTT,
     * etc.) This is used because AdminMessage handles modules as a generic
     * type.
     */
    private final Map<ModuleConfigType, ModuleConfig> moduleConfigs = new ConcurrentHashMap<>();

    /**
     * Helper to update or add a channel to the local cache.
     *
     * @param index 0-7
     * @param channel The channel object returned by the radio.
     */
    public void setChannel(int index, Channel channel) {
        channels.put(index, channel);
    }

    /**
     * Helper to check if we have enough info to show the "Settings" UI.
     * Requires identity, LoRa settings, and at least the Primary channel.
     */
    public boolean isFullySynced() {
        return owner != null && loraConfig != null && !channels.isEmpty();
    }

    /**
     * Clears the model. Use this when the radio disconnects or after a factory
     * reset.
     */
    public void reset() {
        owner = null;
        nodeId = 0;
        loraConfig = null;
        deviceConfig = null;
        displayConfig = null;
        networkConfig = null;
        channels.clear();
        moduleConfigs.clear();
    }
}
