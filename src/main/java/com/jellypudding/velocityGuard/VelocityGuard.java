package com.jellypudding.velocityGuard;

import com.jellypudding.velocityGuard.listeners.PacketListener;
import com.jellypudding.velocityGuard.listeners.PlayerConnectionListener;
import com.jellypudding.velocityGuard.managers.ConfigManager;
import com.jellypudding.velocityGuard.managers.ViolationManager;
import com.jellypudding.velocityGuard.processors.MovementProcessor;
import com.jellypudding.velocityGuard.tasks.ProcessQueueTask;
import com.jellypudding.velocityGuard.tasks.ResetPositionTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class VelocityGuard extends JavaPlugin {
    
    private ConfigManager configManager;
    private ViolationManager violationManager;
    private MovementProcessor movementProcessor;
    private PacketListener packetListener;
    private ProcessQueueTask processQueueTask;
    private ResetPositionTask resetPositionTask;
    
    // Thread pool for handling checks asynchronously
    private ExecutorService checkExecutor;
    
    // Packet listener status
    private boolean packetListenerWorking = false;
    private boolean debugMode = false;
    
    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();
        
        // Initialize managers
        this.configManager = new ConfigManager(this);
        this.violationManager = new ViolationManager(this);
        
        // Enable debug mode for troubleshooting 1.21.4 compatibility
        setDebugMode(true);
        getLogger().info("Debug mode enabled for 1.21.4 compatibility troubleshooting");
        
        // Create the thread pool with a fixed number of threads
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.checkExecutor = Executors.newFixedThreadPool(threads);
        getLogger().info("Initialized thread pool with " + threads + " threads");
        
        // Initialize the movement processor
        this.movementProcessor = new MovementProcessor(this);
        
        // Set up packet listener
        this.packetListener = new PacketListener(this);
        
        // Start the queue processing task (runs every tick)
        this.processQueueTask = new ProcessQueueTask(this);
        this.processQueueTask.runTaskTimer(this, 1L, 1L); // Run every tick (50ms) for maximum responsiveness
        
        // Monitor task to check for speed violations and ensure cheaters don't make progress
        this.resetPositionTask = new ResetPositionTask(this, 3);
        this.resetPositionTask.runTaskTimer(this, 3L, 3L); // Run every 150ms - fast enough to catch cheats
        
        // Register event listeners - this provides backup detection
        PlayerConnectionListener connectionListener = new PlayerConnectionListener(this);
        getServer().getPluginManager().registerEvents(connectionListener, this);
        
        // Install packet handlers
        packetListener.inject();
        
        // Check packet listener status after 10 seconds
        getServer().getScheduler().runTaskLater(this, this::checkPacketListenerStatus, 20 * 10);
        
        getLogger().info("VelocityGuard has been enabled with asynchronous processing on " + 
                threads + " threads. Now monitoring for speed and flight hacks.");
    }
    
    /**
     * Check if packet listener is working and update status
     */
    private void checkPacketListenerStatus() {
        int successfulPackets = packetListener.getSuccessfulPacketsCount();
        int failedPackets = packetListener.getFailedPacketsCount();
        
        if (successfulPackets > 0) {
            packetListenerWorking = true;
            getLogger().info("Packet listener is working correctly! (" + successfulPackets + " packets processed)");
        } else {
            // Don't disable - just warn
            packetListenerWorking = false;
            getLogger().warning("No packets detected yet - this might be normal if players haven't moved");
            getLogger().warning("Packet detection on server version: " + getServer().getVersion());
            getLogger().warning("Plugin will continue to operate normally");
        }
    }

    @Override
    public void onDisable() {
        // Remove packet handlers
        if (packetListener != null) {
            packetListener.uninject();
        }
        
        // Cancel tasks
        if (processQueueTask != null) {
            processQueueTask.cancel();
        }
        
        if (resetPositionTask != null) {
            resetPositionTask.cancel();
        }
        
        // Shutdown thread pool gracefully
        if (checkExecutor != null) {
            checkExecutor.shutdown();
            try {
                if (!checkExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    getLogger().warning("Thread pool didn't shut down cleanly, forcing shutdown");
                    checkExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                checkExecutor.shutdownNow();
            }
        }
        
        getLogger().info("VelocityGuard has been disabled.");
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public ViolationManager getViolationManager() {
        return violationManager;
    }
    
    public MovementProcessor getMovementProcessor() {
        return movementProcessor;
    }
    
    public ExecutorService getCheckExecutor() {
        return checkExecutor;
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
     * Update the packet listener working status based on diagnostics
     * 
     * @param status true if packet listener is working
     */
    public void setPacketListenerWorking(boolean status) {
        if (this.packetListenerWorking != status) {
            if (status) {
                getLogger().info("Packet listener is now working correctly");
            } else {
                // Just log a warning instead of disabling
                getLogger().warning("Packet listener reported non-operational status");
                getLogger().warning("This may be temporary - will continue monitoring");
            }
        }
        this.packetListenerWorking = status;
    }

    /**
     * Returns whether debug mode is enabled
     */
    public boolean isDebugEnabled() {
        return debugMode;
    }

    /**
     * Enable or disable debug mode
     */
    public void setDebugMode(boolean enabled) {
        this.debugMode = enabled;
        getLogger().info("Debug mode " + (enabled ? "enabled" : "disabled"));
        
        if (enabled) {
            getLogger().info("Detailed debug information will be logged");
        }
    }
}
