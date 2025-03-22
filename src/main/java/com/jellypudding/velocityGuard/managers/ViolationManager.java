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
    private static final long VIOLATION_DECAY_TIME = 500; // 0.5 seconds - extremely fast decay to avoid punishing after they stop
    
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
                            // Decay at a faster rate - remove up to 5 violations at once for faster clearing
                            int decayAmount = Math.min(currentLevel, 5);
                            int newLevel = currentLevel - decayAmount;
                            
                            if (newLevel <= 0) {
                                // Remove completely if they have no more violations
                                violationLevels.remove(playerUUID);
                                lastViolationTime.remove(playerUUID);
                                plugin.getLogger().info("Player violations cleared");
                            } else {
                                violationLevels.put(playerUUID, newLevel);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 8L, 8L); // Run slightly faster (every 8 ticks = ~400ms)
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

    /**
     * Decreases violation count for a player
     * 
     * @param playerUUID Player's UUID
     * @param amount Amount to decrease by (default 1)
     */
    public void decreaseViolations(UUID playerUUID, int amount) {
        if (!violationLevels.containsKey(playerUUID)) return;
        
        int currentViolations = violationLevels.get(playerUUID);
        int newViolations = Math.max(0, currentViolations - amount);
        
        if (newViolations <= 0) {
            // Remove the player from the violations map if they have no violations
            violationLevels.remove(playerUUID);
            plugin.getLogger().info("Player " + playerUUID + " is no longer flagged for violations");
        } else {
            violationLevels.put(playerUUID, newViolations);
        }
    }

    /**
     * Decreases violation count for a player by 1
     * 
     * @param playerUUID Player's UUID
     */
    public void decreaseViolations(UUID playerUUID) {
        decreaseViolations(playerUUID, 1);
    }

    /**
     * Add a violation for a player
     *
     * @param playerUUID Player's UUID
     */
    public void addViolation(UUID playerUUID) {
        int currentViolations = violationLevels.getOrDefault(playerUUID, 0);
        violationLevels.put(playerUUID, currentViolations + 1);
    }
} 