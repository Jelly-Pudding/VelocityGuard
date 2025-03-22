package com.jellypudding.velocityGuard.tasks;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Task that processes the movement queue at regular intervals
 */
public class ProcessQueueTask extends BukkitRunnable {
    
    private final VelocityGuard plugin;
    
    public ProcessQueueTask(VelocityGuard plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void run() {
        // Process all queued movements
        plugin.getMovementProcessor().processQueue();
    }
} 