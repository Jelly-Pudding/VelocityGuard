package com.jellypudding.velocityGuard.managers;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ViolationManager {
    private final VelocityGuard plugin;
    private final Map<UUID, Integer> violationLevels;
    private final Map<UUID, Long> lastViolationTime;
    
    // The time in milliseconds after which violations start to decay
    private static final long VIOLATION_DECAY_TIME = 10000; // 10 seconds
    
    public ViolationManager(VelocityGuard plugin) {
        this.plugin = plugin;
        this.violationLevels = new HashMap<>();
        this.lastViolationTime = new HashMap<>();
        
        // Start the violation decay task
        startViolationDecayTask();
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
        
        // Update last violation time
        lastViolationTime.put(playerUUID, System.currentTimeMillis());
        
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
        lastViolationTime.remove(playerUUID);
    }
    
    /**
     * Clears all player violations
     */
    public void clearAllViolations() {
        violationLevels.clear();
        lastViolationTime.clear();
    }
    
    /**
     * Starts a task to periodically decay violation levels
     */
    private void startViolationDecayTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                
                // Check each player's violations
                for (UUID playerUUID : violationLevels.keySet()) {
                    // Get the last time they violated
                    long lastViolation = lastViolationTime.getOrDefault(playerUUID, 0L);
                    
                    // If it's been long enough since their last violation, decay their level
                    if (currentTime - lastViolation > VIOLATION_DECAY_TIME) {
                        int currentLevel = violationLevels.get(playerUUID);
                        if (currentLevel > 0) {
                            violationLevels.put(playerUUID, currentLevel - 1);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // Run every second
    }

    /**
     * Get all players with violations above a certain threshold
     * 
     * @param threshold Minimum violation count to include player
     * @return Set of player UUIDs with violations above threshold
     */
    public Set<UUID> getPlayersWithViolations(int threshold) {
        Set<UUID> result = new HashSet<>();
        
        for (Map.Entry<UUID, Integer> entry : violationLevels.entrySet()) {
            if (entry.getValue() >= threshold) {
                result.add(entry.getKey());
            }
        }
        
        return result;
    }

    /**
     * Get the current violation count for a player
     * 
     * @param playerId The UUID of the player
     * @return The number of violations for the player
     */
    public int getViolations(UUID playerId) {
        return violationLevels.getOrDefault(playerId, 0);
    }
} 