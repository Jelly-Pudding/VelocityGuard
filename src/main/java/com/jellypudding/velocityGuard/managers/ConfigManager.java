package com.jellypudding.velocityGuard.managers;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {
    private final VelocityGuard plugin;
    private final FileConfiguration config;

    private final double maxHorizontalSpeed;
    private final int cancelDuration;
    private final double knockbackMultiplier;
    private final int knockbackDuration;
    private final double vehicleSpeedMultiplier;
    private final double vehicleIceSpeedMultiplier;
    private final double bufferMultiplier;
    private final boolean debugMode;

    // Blocks per second (including sprint-jumping)
    private static final double DEFAULT_MAX_HORIZONTAL_SPEED = 10.0;
    // Seconds to cancel movement
    private static final int DEFAULT_CANCEL_DURATION = 3;
    // Default knockback multiplier
    private static final double DEFAULT_KNOCKBACK_MULTIPLIER = 6.0;
    // Default knockback duration in milliseconds
    private static final int DEFAULT_KNOCKBACK_DURATION = 1000;
    // Default vehicle speed multiplier
    private static final double DEFAULT_VEHICLE_SPEED_MULTIPLIER = 1.1;
    // Default vehicle ice speed multiplier
    private static final double DEFAULT_VEHICLE_ICE_SPEED_MULTIPLIER = 3.6;
    // Default buffer multiplier
    private static final double DEFAULT_BUFFER_MULTIPLIER = 1.35;

    public ConfigManager(VelocityGuard plugin) {
        this.plugin = plugin;
        this.plugin.saveDefaultConfig();
        this.config = plugin.getConfig();

        this.maxHorizontalSpeed = Math.max(4.0, config.getDouble("checks.speed.max-horizontal-speed", DEFAULT_MAX_HORIZONTAL_SPEED));
        this.cancelDuration = Math.max(1, config.getInt("checks.speed.cancel-duration", DEFAULT_CANCEL_DURATION));
        this.knockbackMultiplier = Math.max(0.5, config.getDouble("checks.speed.knockback.multiplier", DEFAULT_KNOCKBACK_MULTIPLIER));
        this.knockbackDuration = Math.max(200, config.getInt("checks.speed.knockback.duration", DEFAULT_KNOCKBACK_DURATION));
        this.vehicleSpeedMultiplier = Math.max(1.0, config.getDouble("checks.speed.vehicle-speed-multiplier", DEFAULT_VEHICLE_SPEED_MULTIPLIER));
        this.vehicleIceSpeedMultiplier = Math.max(1.0, config.getDouble("checks.speed.vehicle-ice-speed-multiplier", DEFAULT_VEHICLE_ICE_SPEED_MULTIPLIER));
        this.bufferMultiplier = Math.max(1.0, config.getDouble("checks.speed.buffer-multiplier", DEFAULT_BUFFER_MULTIPLIER));

        this.debugMode = config.getBoolean("settings.debug-mode", false);
    }

    public double getMaxHorizontalSpeed() {
        return maxHorizontalSpeed;
    }

    public int getCancelDuration() {
        return cancelDuration;
    }

    public boolean isDebugModeEnabled() {
        return debugMode;
    }

    public double getKnockbackMultiplier() {
        return knockbackMultiplier;
    }

    public int getKnockbackDuration() {
        return knockbackDuration;
    }

    public double getVehicleSpeedMultiplier() {
        return vehicleSpeedMultiplier;
    }

    public double getVehicleIceSpeedMultiplier() {
        return vehicleIceSpeedMultiplier;
    }

    public double getBufferMultiplier() {
        return bufferMultiplier;
    }
}
