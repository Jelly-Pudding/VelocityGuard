package com.jellypudding.velocityGuard.managers;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {
    private final VelocityGuard plugin;
    private final FileConfiguration config;

    private final double maxHorizontalSpeed;
    private final int cancelDuration;
    private final boolean debugMode;

    private static final double DEFAULT_MAX_HORIZONTAL_SPEED = 10.0;  // Blocks per second (including sprint-jumping)
    private static final int DEFAULT_CANCEL_DURATION = 3;  // Seconds to cancel movement

    public ConfigManager(VelocityGuard plugin) {
        this.plugin = plugin;
        this.plugin.saveDefaultConfig();
        this.config = plugin.getConfig();

        this.maxHorizontalSpeed = Math.max(4.0, config.getDouble("checks.speed.max-horizontal-speed", DEFAULT_MAX_HORIZONTAL_SPEED));
        this.cancelDuration = Math.max(1, config.getInt("checks.speed.cancel-duration", DEFAULT_CANCEL_DURATION));
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
} 