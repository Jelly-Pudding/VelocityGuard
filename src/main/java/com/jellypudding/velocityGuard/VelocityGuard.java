package com.jellypudding.velocityGuard;

import com.jellypudding.velocityGuard.listeners.PacketListener;
import com.jellypudding.velocityGuard.listeners.PlayerConnectionListener;
import com.jellypudding.velocityGuard.managers.ConfigManager;
import com.jellypudding.velocityGuard.managers.ViolationManager;
import com.jellypudding.velocityGuard.processors.MovementProcessor;
import com.jellypudding.velocityGuard.tasks.ProcessQueueTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VelocityGuard extends JavaPlugin {
    
    private ConfigManager configManager;
    private ViolationManager violationManager;
    private MovementProcessor movementProcessor;
    private PacketListener packetListener;
    private ProcessQueueTask processQueueTask;
    
    // Thread pool for handling checks asynchronously
    private ExecutorService checkExecutor;
    
    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();
        
        // Initialize managers
        this.configManager = new ConfigManager(this);
        this.violationManager = new ViolationManager(this);
        
        // Create the thread pool with a fixed number of threads
        // Typically you'd want fewer threads than CPU cores to avoid overwhelming the system
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        this.checkExecutor = Executors.newFixedThreadPool(threads);
        
        // Initialize the movement processor
        this.movementProcessor = new MovementProcessor(this);
        
        // Set up packet listener
        this.packetListener = new PacketListener(this);
        
        // Start the queue processing task (runs every tick)
        this.processQueueTask = new ProcessQueueTask(this);
        this.processQueueTask.runTaskTimer(this, 1L, 1L);
        
        // Register event listeners
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        
        // Install packet handlers
        packetListener.inject();
        
        getLogger().info("VelocityGuard has been enabled with asynchronous processing.");
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
        
        // Shutdown thread pool
        if (checkExecutor != null) {
            checkExecutor.shutdown();
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
}
