package com.jellypudding.velocityGuard.managers;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ViolationManager {
    private final VelocityGuard plugin;
    private final Map<UUID, Integer> violationLevels;
    
    public ViolationManager(VelocityGuard plugin) {
        this.plugin = plugin;
        this.violationLevels = new HashMap<>();
    }
    
    /**
     * Records a violation for a player without notification or punishment
     * 
     * @param player The player who violated
     * @param checkName The name of the check that was violated
     * @param details Additional details about the violation
     */
    public void addViolation(Player player, String checkName, String details) {
        UUID playerUUID = player.getUniqueId();
        
        // Initialize violation level if not present
        violationLevels.putIfAbsent(playerUUID, 0);
        
        // Increment violation level
        int newLevel = violationLevels.get(playerUUID) + 1;
        violationLevels.put(playerUUID, newLevel);
        
        // Log to console with plugin prefix
        plugin.getLogger().warning("VelocityGuard: " + player.getName() + " failed " + checkName + 
                " check (" + details + ") VL: " + newLevel);
        
        // Actually notify the player
        player.sendMessage("§c[VelocityGuard] §fIllegal movement detected!");
    }
    
    /**
     * Gets the current violation level for a player
     * 
     * @param playerUUID The UUID of the player
     * @return The player's violation level
     */
    public int getViolationLevel(UUID playerUUID) {
        return violationLevels.getOrDefault(playerUUID, 0);
    }
    
    /**
     * Resets the violation level for a player
     * 
     * @param playerUUID The UUID of the player
     */
    public void resetViolations(UUID playerUUID) {
        violationLevels.put(playerUUID, 0);
    }
    
    /**
     * Clears all player violations
     */
    public void clearAllViolations() {
        violationLevels.clear();
    }
} 