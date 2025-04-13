package com.jellypudding.velocityGuard.managers;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {
    private final VelocityGuard plugin;
    private final FileConfiguration config;

    // Configuration values
    private final double maxHorizontalSpeed;
    private final int cancelDuration;
    private final int maxChunksPerSecond;
    private final boolean detailedChunkMetrics;
    private final boolean debugMode;
    
    // Default speed values based on Minecraft's normal movement
    private static final double DEFAULT_MAX_HORIZONTAL_SPEED = 10.0;  // Blocks per second (including sprint-jumping)
    private static final int DEFAULT_CANCEL_DURATION = 10;  // Seconds to cancel movement
    private static final int DEFAULT_MAX_CHUNKS_PER_SECOND = 8;
    
    public ConfigManager(VelocityGuard plugin) {
        this.plugin = plugin;
        this.plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
        
        // Load configuration values with sensible defaults
        this.maxHorizontalSpeed = Math.max(4.0, config.getDouble("checks.speed.max-horizontal-speed", DEFAULT_MAX_HORIZONTAL_SPEED));
        this.cancelDuration = Math.max(1, config.getInt("checks.speed.cancel-duration", DEFAULT_CANCEL_DURATION));
        this.maxChunksPerSecond = Math.max(1, config.getInt("checks.chunk-loading.max-chunks-per-second", DEFAULT_MAX_CHUNKS_PER_SECOND));
        this.detailedChunkMetrics = config.getBoolean("checks.chunk-loading.detailed-metrics", false);
        this.debugMode = config.getBoolean("settings.debug-mode", false);
        
        // Log loaded values for debugging
        plugin.getLogger().info("Loaded config - Max horizontal speed: " + maxHorizontalSpeed + " blocks/s");
        plugin.getLogger().info("Loaded config - Movement cancel duration: " + cancelDuration + " seconds");
        plugin.getLogger().info("Loaded config - Max chunks per second: " + maxChunksPerSecond);
        plugin.getLogger().info("Loaded config - Detailed chunk metrics: " + (detailedChunkMetrics ? "enabled" : "disabled"));
        plugin.getLogger().info("Loaded config - Debug mode: " + (debugMode ? "enabled" : "disabled"));
    }
    
    public double getMaxHorizontalSpeed() {
        return maxHorizontalSpeed;
    }
    
    public int getCancelDuration() {
        return cancelDuration;
    }
    
    public int getMaxChunksPerSecond() {
        return maxChunksPerSecond;
    }
    
    public boolean isDetailedChunkMetricsEnabled() {
        return detailedChunkMetrics;
    }
    
    public boolean isDebugModeEnabled() {
        return debugMode;
    }
} 