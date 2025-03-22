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
    
    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();

        // Initialize managers
        this.configManager = new ConfigManager(this);
        this.violationManager = new ViolationManager(this);
        
        // Create the thread pool with a fixed number of threads
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.checkExecutor = Executors.newFixedThreadPool(threads);
        
        if (isDebugEnabled()) {
            getLogger().info("Debug mode enabled for troubleshooting");
            getLogger().info("Initialized thread pool with " + threads + " threads");
        }
        
        // Initialize the movement processor
        this.movementProcessor = new MovementProcessor(this);
        
        // Set up packet listener
        this.packetListener = new PacketListener(this);
        
        // Start the queue processing task (runs every tick)
        this.processQueueTask = new ProcessQueueTask(this);
        this.processQueueTask.runTaskTimer(this, 1L, 1L); // Run every tick (50ms) for maximum responsiveness
        
        // Get violation threshold from config
        int violationThreshold = configManager.getViolationThreshold();
        
        // Monitor task to check for speed violations and ensure cheaters don't make progress
        this.resetPositionTask = new ResetPositionTask(this, violationThreshold);
        this.resetPositionTask.runTaskTimer(this, 3L, 3L); // Run every 150ms - fast enough to catch cheats
        
        // Register event listeners - this provides backup detection
        PlayerConnectionListener connectionListener = new PlayerConnectionListener(this);
        getServer().getPluginManager().registerEvents(connectionListener, this);
        
        // Install packet handlers
        packetListener.inject();
        
        getLogger().info("VelocityGuard has been enabled with asynchronous processing on " + 
                threads + " threads. Now monitoring for speed and flight cheats.");
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
