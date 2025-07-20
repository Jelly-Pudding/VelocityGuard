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
    private final double elytraGlidingMultiplier;
    private final int elytraLandingDuration;
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
    private final double ultraPingCompensation;
    private final double insanePingCompensation;

    private final int defaultBurstTolerance;
    private final int veryLowPingBurstTolerance;
    private final int lowPingBurstTolerance;
    private final int mediumPingBurstTolerance;
    private final int highPingBurstTolerance;
    private final int veryHighPingBurstTolerance;
    private final int extremePingBurstTolerance;
    private final int ultraPingBurstTolerance;
    private final int insanePingBurstTolerance;

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
    // Default elytra gliding multiplier
    private static final double DEFAULT_ELYTRA_GLIDING_MULTIPLIER = 3.0;
    // Default elytra landing duration in milliseconds
    private static final int DEFAULT_ELYTRA_LANDING_DURATION = 1500;
    // Default vehicle speed multiplier
    private static final double DEFAULT_VEHICLE_SPEED_MULTIPLIER = 1.9;
    // Default vehicle ice speed multiplier
    private static final double DEFAULT_VEHICLE_ICE_SPEED_MULTIPLIER = 4.3;
    // Default buffer multiplier
    private static final double DEFAULT_BUFFER_MULTIPLIER = 1.1;

    // Default latency compensation values
    private static final boolean DEFAULT_ENABLE_LATENCY_COMPENSATION = true;
    private static final double DEFAULT_VERY_LOW_PING_COMPENSATION = 1.1;
    private static final double DEFAULT_LOW_PING_COMPENSATION = 2.1;
    private static final double DEFAULT_MEDIUM_PING_COMPENSATION = 2.9;
    private static final double DEFAULT_HIGH_PING_COMPENSATION = 3.6;
    private static final double DEFAULT_VERY_HIGH_PING_COMPENSATION = 4.6;
    private static final double DEFAULT_EXTREME_PING_COMPENSATION = 5.7;
    private static final double DEFAULT_ULTRA_PING_COMPENSATION = 6.6;
    private static final double DEFAULT_INSANE_PING_COMPENSATION = 7.5;

    // Default burst tolerance values
    private static final int DEFAULT_DEFAULT_BURST_TOLERANCE = 15;
    private static final int DEFAULT_VERY_LOW_PING_BURST_TOLERANCE = 15;
    private static final int DEFAULT_LOW_PING_BURST_TOLERANCE = 20;
    private static final int DEFAULT_MEDIUM_PING_BURST_TOLERANCE = 22;
    private static final int DEFAULT_HIGH_PING_BURST_TOLERANCE = 24;
    private static final int DEFAULT_VERY_HIGH_PING_BURST_TOLERANCE = 27;
    private static final int DEFAULT_EXTREME_PING_BURST_TOLERANCE = 30;
    private static final int DEFAULT_ULTRA_PING_BURST_TOLERANCE = 33;
    private static final int DEFAULT_INSANE_PING_BURST_TOLERANCE = 35;

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
        this.elytraGlidingMultiplier = Math.max(1.0, config.getDouble("checks.speed.elytra.gliding-multiplier", DEFAULT_ELYTRA_GLIDING_MULTIPLIER));
        this.elytraLandingDuration = Math.max(500, config.getInt("checks.speed.elytra.landing-duration", DEFAULT_ELYTRA_LANDING_DURATION));
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
        this.ultraPingCompensation = Math.max(1.0, config.getDouble("checks.speed.latency-compensation.ultra-ping", DEFAULT_ULTRA_PING_COMPENSATION));
        this.insanePingCompensation = Math.max(1.0, config.getDouble("checks.speed.latency-compensation.insane-ping", DEFAULT_INSANE_PING_COMPENSATION));

        // Load burst tolerance values per ping category
        this.defaultBurstTolerance = Math.max(1, config.getInt("checks.speed.burst-tolerance.default", DEFAULT_DEFAULT_BURST_TOLERANCE));
        this.veryLowPingBurstTolerance = Math.max(1, config.getInt("checks.speed.burst-tolerance.very-low-ping", DEFAULT_VERY_LOW_PING_BURST_TOLERANCE));
        this.lowPingBurstTolerance = Math.max(1, config.getInt("checks.speed.burst-tolerance.low-ping", DEFAULT_LOW_PING_BURST_TOLERANCE));
        this.mediumPingBurstTolerance = Math.max(1, config.getInt("checks.speed.burst-tolerance.medium-ping", DEFAULT_MEDIUM_PING_BURST_TOLERANCE));
        this.highPingBurstTolerance = Math.max(1, config.getInt("checks.speed.burst-tolerance.high-ping", DEFAULT_HIGH_PING_BURST_TOLERANCE));
        this.veryHighPingBurstTolerance = Math.max(1, config.getInt("checks.speed.burst-tolerance.very-high-ping", DEFAULT_VERY_HIGH_PING_BURST_TOLERANCE));
        this.extremePingBurstTolerance = Math.max(1, config.getInt("checks.speed.burst-tolerance.extreme-ping", DEFAULT_EXTREME_PING_BURST_TOLERANCE));
        this.ultraPingBurstTolerance = Math.max(1, config.getInt("checks.speed.burst-tolerance.ultra-ping", DEFAULT_ULTRA_PING_BURST_TOLERANCE));
        this.insanePingBurstTolerance = Math.max(1, config.getInt("checks.speed.burst-tolerance.insane-ping", DEFAULT_INSANE_PING_BURST_TOLERANCE));

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
    
    public double getElytraGlidingMultiplier() {
        return elytraGlidingMultiplier;
    }
    
    public int getElytraLandingDuration() {
        return elytraLandingDuration;
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

    public double getUltraPingCompensation() {
        return ultraPingCompensation;
    }

    public double getInsanePingCompensation() {
        return insanePingCompensation;
    }

    public int getBurstToleranceForPing(int ping) {
        if (ping <= 20) {
            return defaultBurstTolerance;
        } else if (ping <= 50) {
            return veryLowPingBurstTolerance;
        } else if (ping <= 100) {
            return lowPingBurstTolerance;
        } else if (ping <= 200) {
            return mediumPingBurstTolerance;
        } else if (ping <= 300) {
            return highPingBurstTolerance;
        } else if (ping <= 500) {
            return veryHighPingBurstTolerance;
        } else if (ping <= 750) {
            return extremePingBurstTolerance;
        } else if (ping <= 1000) {
            return ultraPingBurstTolerance;
        } else {
            return insanePingBurstTolerance;
        }
    }
}
