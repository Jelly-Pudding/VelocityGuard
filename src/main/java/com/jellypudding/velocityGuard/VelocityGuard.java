package com.jellypudding.velocityGuard;

import com.jellypudding.velocityGuard.listeners.PacketListener;
import com.jellypudding.velocityGuard.managers.ConfigManager;
import com.jellypudding.velocityGuard.processors.MovementChecker;
import org.bukkit.plugin.java.JavaPlugin;

public final class VelocityGuard extends JavaPlugin {
    
    private ConfigManager configManager;
    private MovementChecker movementChecker;
    private PacketListener packetListener;
    
    // Packet listener status
    private boolean packetListenerWorking = false;
    
    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();

        // Initialize managers
        this.configManager = new ConfigManager(this);
        
        // Initialize the movement checker
        this.movementChecker = new MovementChecker(this);
        
        // Set up packet listener
        this.packetListener = new PacketListener(this);
        
        // Install packet handlers
        packetListener.inject();
        
        getLogger().info("VelocityGuard has been enabled.");
    }

    @Override
    public void onDisable() {
        // Remove packet handlers
        if (packetListener != null) {
            packetListener.uninject();
        }

        // Shutdown thread pools and clean up resources
        if (movementChecker != null) {
            movementChecker.shutdown();
        }
        
        getLogger().info("VelocityGuard has been disabled.");
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public MovementChecker getMovementChecker() {
        return movementChecker;
    }

    public PacketListener getPacketListener() {
        return packetListener;
    }
    
    /**
     * @return Whether the packet listener is working correctly
     */
    public boolean isPacketListenerWorking() {
        return packetListenerWorking;
    }

    /**
     * Update the packet listener working status
     * 
     * @param status true if packet listener is working
     */
    public void setPacketListenerWorking(boolean status) {
        this.packetListenerWorking = status;
    }

    /**
     * Returns whether debug mode is enabled
     */
    public boolean isDebugEnabled() {
        return configManager != null && configManager.isDebugModeEnabled();
    }
}
