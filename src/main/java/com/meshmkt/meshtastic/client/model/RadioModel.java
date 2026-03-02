package com.meshmkt.meshtastic.client.model;

import org.meshtastic.proto.AdminProtos.AdminMessage.ConfigType;
import org.meshtastic.proto.AdminProtos.AdminMessage.ModuleConfigType;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.MeshProtos.DeviceMetadata;
import org.meshtastic.proto.MeshProtos.User;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable in-memory state holder for the currently connected local radio.
 * <p>
 * This model intentionally stores raw protobuf values because generated protobuf messages are immutable,
 * stable transport contracts, and avoid a large DTO layer that must track upstream schema changes.
 * </p>
 * <p>
 * To protect callers from accidental shared-state mutation, collection getters return immutable copies.
 * All state mutation is expected to happen through {@code AdminService}.
 * </p>
 */
public class RadioModel {

    /**
     * Latest known local owner/user identity.
     */
    private User owner;

    /**
     * Local node number for the connected radio.
     */
    private int nodeId;

    /**
     * Common config sections exposed directly for convenience.
     */
    private Config.LoRaConfig loraConfig;

    /**
     * Common config sections exposed directly for convenience.
     */
    private Config.DeviceConfig deviceConfig;

    /**
     * Common config sections exposed directly for convenience.
     */
    private Config.DisplayConfig displayConfig;

    /**
     * Common config sections exposed directly for convenience.
     */
    private Config.NetworkConfig networkConfig;

    /**
     * Latest metadata snapshot received from the radio.
     */
    private DeviceMetadata deviceMetadata;

    /**
     * Channel slots indexed by channel index ({@code 0..7}).
     */
    private final Map<Integer, Channel> channels = new ConcurrentHashMap<>();

    /**
     * Config snapshots keyed by requested {@link ConfigType}.
     */
    private final Map<ConfigType, Config> configs = new ConcurrentHashMap<>();

    /**
     * Module config snapshots keyed by requested {@link ModuleConfigType}.
     */
    private final Map<ModuleConfigType, ModuleConfig> moduleConfigs = new ConcurrentHashMap<>();

    /**
     * Returns the latest owner snapshot.
     *
     * @return latest owner payload, or {@code null} when unavailable.
     */
    public User getOwner() {
        return owner;
    }

    /**
     * Replaces the latest owner snapshot.
     *
     * @param owner owner payload to cache.
     */
    public void setOwner(User owner) {
        this.owner = owner;
    }

    /**
     * Returns the local node number.
     *
     * @return local node id, or {@code 0} when unknown.
     */
    public int getNodeId() {
        return nodeId;
    }

    /**
     * Updates the local node number.
     *
     * @param nodeId local node id.
     */
    public void setNodeId(int nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * Returns the cached LoRa config section.
     *
     * @return LoRa config payload, or {@code null} when unavailable.
     */
    public Config.LoRaConfig getLoraConfig() {
        return loraConfig;
    }

    /**
     * Updates the cached LoRa config section.
     *
     * @param loraConfig LoRa config payload.
     */
    public void setLoraConfig(Config.LoRaConfig loraConfig) {
        this.loraConfig = loraConfig;
    }

    /**
     * Returns the cached Device config section.
     *
     * @return Device config payload, or {@code null} when unavailable.
     */
    public Config.DeviceConfig getDeviceConfig() {
        return deviceConfig;
    }

    /**
     * Updates the cached Device config section.
     *
     * @param deviceConfig device config payload.
     */
    public void setDeviceConfig(Config.DeviceConfig deviceConfig) {
        this.deviceConfig = deviceConfig;
    }

    /**
     * Returns the cached Display config section.
     *
     * @return Display config payload, or {@code null} when unavailable.
     */
    public Config.DisplayConfig getDisplayConfig() {
        return displayConfig;
    }

    /**
     * Updates the cached Display config section.
     *
     * @param displayConfig display config payload.
     */
    public void setDisplayConfig(Config.DisplayConfig displayConfig) {
        this.displayConfig = displayConfig;
    }

    /**
     * Returns the cached Network config section.
     *
     * @return Network config payload, or {@code null} when unavailable.
     */
    public Config.NetworkConfig getNetworkConfig() {
        return networkConfig;
    }

    /**
     * Updates the cached Network config section.
     *
     * @param networkConfig network config payload.
     */
    public void setNetworkConfig(Config.NetworkConfig networkConfig) {
        this.networkConfig = networkConfig;
    }

    /**
     * Returns the cached device metadata snapshot.
     *
     * @return device metadata payload, or {@code null} when unavailable.
     */
    public DeviceMetadata getDeviceMetadata() {
        return deviceMetadata;
    }

    /**
     * Updates the cached device metadata snapshot.
     *
     * @param deviceMetadata metadata payload.
     */
    public void setDeviceMetadata(DeviceMetadata deviceMetadata) {
        this.deviceMetadata = deviceMetadata;
    }

    /**
     * Inserts or replaces one cached channel slot.
     *
     * @param index channel index ({@code 0..7}).
     * @param channel channel payload returned by the radio.
     */
    public void setChannel(int index, Channel channel) {
        channels.put(index, channel);
    }

    /**
     * Returns one cached channel slot.
     *
     * @param index channel index ({@code 0..7}).
     * @return optional channel payload for the slot.
     */
    public Optional<Channel> getChannel(int index) {
        return Optional.ofNullable(channels.get(index));
    }

    /**
     * Returns an immutable copy of all cached channels keyed by index.
     *
     * @return immutable map of channel snapshots.
     */
    public Map<Integer, Channel> getChannels() {
        return Map.copyOf(channels);
    }

    /**
     * Inserts or replaces one cached config section by type.
     *
     * @param type config type key.
     * @param config config payload.
     */
    public void putConfig(ConfigType type, Config config) {
        if (type != null && config != null) {
            configs.put(type, config);
        }
    }

    /**
     * Returns one cached config section.
     *
     * @param type config type key.
     * @return optional config snapshot.
     */
    public Optional<Config> getConfig(ConfigType type) {
        return Optional.ofNullable(configs.get(type));
    }

    /**
     * Returns an immutable copy of all cached config sections keyed by type.
     *
     * @return immutable map of config snapshots.
     */
    public Map<ConfigType, Config> getConfigs() {
        return Map.copyOf(configs);
    }

    /**
     * Inserts or replaces one cached module config section by type.
     *
     * @param type module config type key.
     * @param moduleConfig module config payload.
     */
    public void putModuleConfig(ModuleConfigType type, ModuleConfig moduleConfig) {
        if (type != null && moduleConfig != null) {
            moduleConfigs.put(type, moduleConfig);
        }
    }

    /**
     * Returns one cached module config section.
     *
     * @param type module config type key.
     * @return optional module config snapshot.
     */
    public Optional<ModuleConfig> getModuleConfig(ModuleConfigType type) {
        return Optional.ofNullable(moduleConfigs.get(type));
    }

    /**
     * Returns an immutable copy of all cached module config sections keyed by type.
     *
     * @return immutable map of module config snapshots.
     */
    public Map<ModuleConfigType, ModuleConfig> getModuleConfigs() {
        return Map.copyOf(moduleConfigs);
    }

    /**
     * Returns whether enough baseline state is present for settings UI initialization.
     * This requires owner identity, LoRa config, and at least one channel snapshot.
     *
     * @return {@code true} when minimum settings state has been cached.
     */
    public boolean isFullySynced() {
        return owner != null && loraConfig != null && !channels.isEmpty();
    }

    /**
     * Clears all cached state.
     * Use this when the radio disconnects or after a factory reset/reboot sequence.
     */
    public void reset() {
        owner = null;
        nodeId = 0;
        loraConfig = null;
        deviceConfig = null;
        displayConfig = null;
        networkConfig = null;
        deviceMetadata = null;
        channels.clear();
        configs.clear();
        moduleConfigs.clear();
    }
}
