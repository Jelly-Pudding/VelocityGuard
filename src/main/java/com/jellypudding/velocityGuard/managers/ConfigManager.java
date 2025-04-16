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
    private final double riptideMultiplier;
    private final int riptideDuration;
    private final double vehicleSpeedMultiplier;
    private final double vehicleIceSpeedMultiplier;
    private final double bufferMultiplier;
    private final boolean debugMode;

    private final boolean enableLatencyCompensation;
    private final double veryLowPingCompensation;
    private final double lowPingCompensation;
    private final double mediumPingCompensation;
    private final double highPingCompensation;
    private final double veryHighPingCompensation;
    private final double extremePingCompensation;

    // Blocks per second (including sprint-jumping)
    private static final double DEFAULT_MAX_HORIZONTAL_SPEED = 10.0;
    // Seconds to cancel movement
    private static final int DEFAULT_CANCEL_DURATION = 3;
    // Default knockback multiplier
    private static final double DEFAULT_KNOCKBACK_MULTIPLIER = 6.0;
    // Default knockback duration in milliseconds
    private static final int DEFAULT_KNOCKBACK_DURATION = 1000;
    // Default riptide multiplier
    private static final double DEFAULT_RIPTIDE_MULTIPLIER = 8.0;
    // Default riptide duration in milliseconds
    private static final int DEFAULT_RIPTIDE_DURATION = 3000;
    // Default vehicle speed multiplier
    private static final double DEFAULT_VEHICLE_SPEED_MULTIPLIER = 1.1;
    // Default vehicle ice speed multiplier
    private static final double DEFAULT_VEHICLE_ICE_SPEED_MULTIPLIER = 3.6;
    // Default buffer multiplier
    private static final double DEFAULT_BUFFER_MULTIPLIER = 2.5;

    // Default latency compensation values
    private static final boolean DEFAULT_ENABLE_LATENCY_COMPENSATION = true;
    private static final double DEFAULT_VERY_LOW_PING_COMPENSATION = 2.5;
    private static final double DEFAULT_LOW_PING_COMPENSATION = 5.8;
    private static final double DEFAULT_MEDIUM_PING_COMPENSATION = 5.9;
    private static final double DEFAULT_HIGH_PING_COMPENSATION = 5.9;
    private static final double DEFAULT_VERY_HIGH_PING_COMPENSATION = 5.9;
    private static final double DEFAULT_EXTREME_PING_COMPENSATION = 5.9;

    public ConfigManager(VelocityGuard plugin) {
        this.plugin = plugin;
        this.plugin.saveDefaultConfig();
        this.config = plugin.getConfig();

        this.maxHorizontalSpeed = Math.max(4.0, config.getDouble("checks.speed.max-horizontal-speed", DEFAULT_MAX_HORIZONTAL_SPEED));
        this.cancelDuration = Math.max(1, config.getInt("checks.speed.cancel-duration", DEFAULT_CANCEL_DURATION));
        this.knockbackMultiplier = Math.max(0.5, config.getDouble("checks.speed.knockback.multiplier", DEFAULT_KNOCKBACK_MULTIPLIER));
        this.knockbackDuration = Math.max(200, config.getInt("checks.speed.knockback.duration", DEFAULT_KNOCKBACK_DURATION));
        this.riptideMultiplier = Math.max(1.0, config.getDouble("checks.speed.riptide.multiplier", DEFAULT_RIPTIDE_MULTIPLIER));
        this.riptideDuration = Math.max(500, config.getInt("checks.speed.riptide.duration", DEFAULT_RIPTIDE_DURATION));
        this.vehicleSpeedMultiplier = Math.max(1.0, config.getDouble("checks.speed.vehicle-speed-multiplier", DEFAULT_VEHICLE_SPEED_MULTIPLIER));
        this.vehicleIceSpeedMultiplier = Math.max(1.0, config.getDouble("checks.speed.vehicle-ice-speed-multiplier", DEFAULT_VEHICLE_ICE_SPEED_MULTIPLIER));
        this.bufferMultiplier = Math.max(1.0, config.getDouble("checks.speed.buffer-multiplier", DEFAULT_BUFFER_MULTIPLIER));

        this.enableLatencyCompensation = config.getBoolean("checks.speed.latency-compensation.enabled", DEFAULT_ENABLE_LATENCY_COMPENSATION);
        this.veryLowPingCompensation = Math.max(1.0, config.getDouble("checks.speed.latency-compensation.very-low-ping", DEFAULT_VERY_LOW_PING_COMPENSATION));
        this.lowPingCompensation = Math.max(1.0, config.getDouble("checks.speed.latency-compensation.low-ping", DEFAULT_LOW_PING_COMPENSATION));
        this.mediumPingCompensation = Math.max(1.0, config.getDouble("checks.speed.latency-compensation.medium-ping", DEFAULT_MEDIUM_PING_COMPENSATION));
        this.highPingCompensation = Math.max(1.0, config.getDouble("checks.speed.latency-compensation.high-ping", DEFAULT_HIGH_PING_COMPENSATION));
        this.veryHighPingCompensation = Math.max(1.0, config.getDouble("checks.speed.latency-compensation.very-high-ping", DEFAULT_VERY_HIGH_PING_COMPENSATION));
        this.extremePingCompensation = Math.max(1.0, config.getDouble("checks.speed.latency-compensation.extreme-ping", DEFAULT_EXTREME_PING_COMPENSATION));

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

    public double getRiptideMultiplier() {
        return riptideMultiplier;
    }

    public int getRiptideDuration() {
        return riptideDuration;
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

    public boolean isLatencyCompensationEnabled() {
        return enableLatencyCompensation;
    }

    public double getVeryLowPingCompensation() {
        return veryLowPingCompensation;
    }

    public double getLowPingCompensation() {
        return lowPingCompensation;
    }

    public double getMediumPingCompensation() {
        return mediumPingCompensation;
    }

    public double getHighPingCompensation() {
        return highPingCompensation;
    }

    public double getVeryHighPingCompensation() {
        return veryHighPingCompensation;
    }

    public double getExtremePingCompensation() {
        return extremePingCompensation;
    }
}
