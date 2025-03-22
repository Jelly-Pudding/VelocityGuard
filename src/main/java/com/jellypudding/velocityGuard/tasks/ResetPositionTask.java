package com.jellypudding.velocityGuard.tasks;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

/**
 * Task that regularly checks for players with violations and resets their position
 * to prevent them from gaining ground while using movement hacks
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
        // Get players with violations over the threshold
        for (UUID playerId : plugin.getViolationManager().getPlayersWithViolations(violationThreshold)) {
            Player player = Bukkit.getPlayer(playerId);
            
            if (player != null && player.isOnline()) {
                // Count violations for this player
                int violations = plugin.getViolationManager().getViolations(playerId);
                
                // Use the movement processor to reset position
                if (plugin.getMovementProcessor().resetPlayerPosition(player)) {
                    plugin.getLogger().warning("Reset position for " + player.getName() + 
                            " (violations: " + violations + ")");
                }
            }
        }
    }
} 