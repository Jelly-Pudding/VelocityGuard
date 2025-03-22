package com.jellypudding.velocityGuard.tasks;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * Task that checks for suspicious movements but ONLY teleports players for active violations.
 * Players who stop cheating are NEVER punished afterward.
 */
public class ResetPositionTask extends BukkitRunnable {
    
    private final VelocityGuard plugin;
    private final int violationThreshold;
    
    public ResetPositionTask(VelocityGuard plugin, int violationThreshold) {
        this.plugin = plugin;
        this.violationThreshold = violationThreshold;
    }
    
    @Override
    public void run() {
        // Check online players for suspicious movements
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            int violations = plugin.getViolationManager().getViolations(playerId);
            
            // Always decay violations on EVERY check to ensure players aren't punished after stopping
            if (violations > 0) {
                // Decay faster for players with fewer violations
                int decayAmount = violations <= violationThreshold ? 2 : 1;
                plugin.getViolationManager().decreaseViolations(playerId, decayAmount);
                
                // Only check for active cheats - if they're currently moving suspiciously
                checkCurrentMovement(player);
            }
        }
    }
    
    /**
     * Check a player's current movement - ONLY teleports for ACTIVE cheating
     */
    private void checkCurrentMovement(Player player) {
        if (!player.isOnline()) return;
        
        UUID playerId = player.getUniqueId();
        
        // Get player's current location
        Location current = player.getLocation();
        
        // Get last known good location
        Location lastKnown = plugin.getMovementProcessor().getLastKnownLocation(playerId);
        if (lastKnown == null) return;
        
        // Calculate time since last check
        long lastMoveTime = plugin.getMovementProcessor().getLastMoveTime(playerId);
        long now = System.currentTimeMillis();
        long timeDelta = now - lastMoveTime;
        
        // Skip if we checked very recently or player hasn't moved
        if (timeDelta < 50) return;
        
        // Check if current movement is suspicious
        double distance = current.distance(lastKnown);
        
        // Skip if barely moved
        if (distance < 0.5) return;
        
        // Calculate speed in blocks per second
        double speed = (distance / Math.max(50, timeDelta)) * 1000.0;
        
        // Stricter checks for players with more violations
        int violations = plugin.getViolationManager().getViolations(playerId);
        
        // Set basic threshold for speed
        double speedThreshold = Math.max(7.0, 10.0 - (violations * 0.4));
        
        if (speed > speedThreshold) {
            // Check the CURRENT movement for cheating
            plugin.getMovementProcessor().handleExtremeSpeedCheck(player, lastKnown, current, timeDelta);
        }
    }
} 