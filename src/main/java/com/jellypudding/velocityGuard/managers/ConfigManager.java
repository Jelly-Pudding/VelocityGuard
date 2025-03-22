package com.jellypudding.velocityGuard.managers;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {
    private final VelocityGuard plugin;
    private final FileConfiguration config;
    
    // Configuration values
    private final double maxHorizontalSpeed;
    private final double maxVerticalSpeed;
    private final boolean lagCompensation;
    private final boolean debugMode;
    private final int violationThreshold;
    
    // Default speed values based on Minecraft's normal movement
    private static final double DEFAULT_MAX_HORIZONTAL_SPEED = 10.0;  // Blocks per second (including sprint-jumping)
    private static final double DEFAULT_MAX_VERTICAL_SPEED = 9.0;    // Blocks per second (initial jump velocity)
    private static final int DEFAULT_VIOLATION_THRESHOLD = 3;
    
    public ConfigManager(VelocityGuard plugin) {
        this.plugin = plugin;
        this.plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
        
        // Load configuration values with sensible defaults
        this.maxHorizontalSpeed = Math.max(4.0, config.getDouble("checks.speed.max-horizontal-speed", DEFAULT_MAX_HORIZONTAL_SPEED));
        this.maxVerticalSpeed = Math.max(1.0, config.getDouble("checks.flight.max-vertical-speed", DEFAULT_MAX_VERTICAL_SPEED));
        this.lagCompensation = config.getBoolean("settings.lag-compensation", true);
        this.debugMode = config.getBoolean("settings.debug-mode", false);
        this.violationThreshold = Math.max(1, config.getInt("settings.violation-threshold", DEFAULT_VIOLATION_THRESHOLD));
        
        // Log loaded values for debugging
        plugin.getLogger().info("Loaded config - Max horizontal speed: " + maxHorizontalSpeed + " blocks/s");
        plugin.getLogger().info("Loaded config - Max vertical speed: " + maxVerticalSpeed + " blocks/s");
        plugin.getLogger().info("Loaded config - Lag compensation: " + (lagCompensation ? "enabled" : "disabled"));
        plugin.getLogger().info("Loaded config - Debug mode: " + (debugMode ? "enabled" : "disabled"));
        plugin.getLogger().info("Loaded config - Violation threshold: " + violationThreshold);
    }
    
    public double getMaxHorizontalSpeed() {
        return maxHorizontalSpeed;
    }
    
    public double getMaxVerticalSpeed() {
        return maxVerticalSpeed;
    }
    
    public boolean isLagCompensationEnabled() {
        return lagCompensation;
    }
    
    public boolean isDebugModeEnabled() {
        return debugMode;
    }
    
    public int getViolationThreshold() {
        return violationThreshold;
    }
} 