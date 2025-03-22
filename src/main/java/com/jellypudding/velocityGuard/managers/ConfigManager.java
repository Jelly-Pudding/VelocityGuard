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
    
    public ConfigManager(VelocityGuard plugin) {
        this.plugin = plugin;
        this.plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
        
        // Load configuration values
        this.maxHorizontalSpeed = config.getDouble("checks.speed.max-horizontal-speed", 0.6);
        this.maxVerticalSpeed = config.getDouble("checks.flight.max-vertical-speed", 0.7);
        this.lagCompensation = config.getBoolean("settings.lag-compensation", true);
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
} 