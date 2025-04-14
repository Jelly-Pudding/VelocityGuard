package com.jellypudding.velocityGuard.processors;

import com.jellypudding.velocityGuard.VelocityGuard;
import com.jellypudding.velocityGuard.utils.MovementUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Simple movement checker that directly prevents cheating by freezing players
 * who use speed/fly cheats until they stop cheating.
 * Optimised for minimal main thread impact.
 */
public class MovementChecker {
    
    private final VelocityGuard plugin;
    
    // Store the last valid position for each player
    private final Map<UUID, Location> lastValidLocations = new ConcurrentHashMap<>();
    
    // Track how long players have been in the air
    private final Map<UUID, Integer> airTicks = new ConcurrentHashMap<>();
    
    // Track recent movements to detect patterns
    private final Map<UUID, Queue<Double>> recentSpeeds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveTime = new ConcurrentHashMap<>();
    
    // Track previous player states
    private final Map<UUID, Boolean> wasGliding = new ConcurrentHashMap<>();
    private final Map<UUID, Long> takeoffTime = new ConcurrentHashMap<>();
    private final Map<UUID, Double> preTakeoffSpeed = new ConcurrentHashMap<>();
    
    // Cache whether a player was detected as cheating on their last movement
    private final Map<UUID, Boolean> isCheating = new ConcurrentHashMap<>();
    
    // Map to track when players can move again after a violation
    private final Map<UUID, Long> movementBlockedUntil = new ConcurrentHashMap<>();
    
    // Lock for operations
    private final ReentrantLock operationLock = new ReentrantLock();
    
    // Constants for pattern detection
    private static final int SPEED_HISTORY_SIZE = 6;
    private static final double SPEED_VARIANCE_THRESHOLD = 0.05;
    private static final double SUSPICIOUS_SPEED_RATIO = 0.85; // 85% of max speed consistently is suspicious
    
    // Constants for legitimate movements
    private static final double SPRINT_JUMP_SPEED_MULTIPLIER = 1.8; // Higher multiplier for sprint jumping
    private static final long JUMP_GRACE_PERIOD_MS = 700; // Allow 700ms of higher speed for jump boosts
    private static final long ELYTRA_LANDING_GRACE_PERIOD_MS = 1500; // Grace period after landing from elytra flight
    
    // Track when players landed from elytra flight
    private final Map<UUID, Long> elytraLandingTime = new ConcurrentHashMap<>();
    
    public MovementChecker(VelocityGuard plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Process a player movement and check if it's valid
     * This method is designed to run safely from any thread
     * @param player The player who moved
     * @param from The previous location
     * @param to The new location
     * @return true if the move is allowed, false if it should be cancelled
     */
    public boolean processMovement(Player player, Location from, Location to) {
        if (player == null || from == null || to == null) return true;
        UUID playerId = player.getUniqueId();
        
        // SIMPLIFIED APPROACH - Check if player is currently blocked from moving
        Long blockedUntil = movementBlockedUntil.get(playerId);
        if (blockedUntil != null && System.currentTimeMillis() < blockedUntil) {
            if (plugin.isDebugEnabled()) {
                long remainingTime = (blockedUntil - System.currentTimeMillis()) / 1000;
                if (remainingTime % 5 == 0) { // Only log every 5 seconds to avoid spam
                    plugin.getLogger().info("Blocked movement for " + player.getName() + " - remaining: " + remainingTime + "s");
                }
            }
            return false; // Block all movement
        }
        
        // Skip processing identical locations
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return true;
        }
        
        // Skip for creative/spectator mode players
        if (player.getGameMode().toString().contains("CREATIVE") ||
            player.getGameMode().toString().contains("SPECTATOR")) {
            lastValidLocations.put(playerId, to.clone());
            airTicks.remove(playerId);
            return true;
        }
        
        // Check if this is likely a teleport (large distance)
        double distance = from.distance(to);
        if (distance > 20) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("Detected teleport for " + player.getName() + " - distance: " + String.format("%.2f", distance));
            }
            lastValidLocations.put(playerId, to.clone());
            airTicks.remove(playerId);
            resetSpeedHistory(playerId);
            return true;
        }
        
        // Track time between movements more accurately
        long currentTime = System.currentTimeMillis();
        long timeDelta = currentTime - lastMoveTime.getOrDefault(playerId, currentTime - 50);
        lastMoveTime.put(playerId, currentTime);
        
        // Prevent division by zero and unreasonable values
        timeDelta = Math.max(25, Math.min(timeDelta, 200));
        
        // Calculate horizontal speed
        double horizontalDistance = MovementUtils.calculateHorizontalDistance(from, to);
        double horizontalSpeed = (horizontalDistance / timeDelta) * 1000; // Convert to blocks per second
        
        // Check for elytra takeoff (detect player starting to glide)
        boolean isCurrentlyGliding = player.isGliding();
        boolean wasGlidingPreviously = wasGliding.getOrDefault(playerId, false);
        
        // Update gliding state for next check
        wasGliding.put(playerId, isCurrentlyGliding);
        
        // If player just started gliding, record takeoff information
        if (isCurrentlyGliding && !wasGlidingPreviously) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(player.getName() + " started gliding");
            }
        }
        
        // If player just stopped gliding (landed), record the landing time
        if (!isCurrentlyGliding && wasGlidingPreviously) {
            elytraLandingTime.put(playerId, currentTime);
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(player.getName() + " stopped gliding (landed)");
            }
        }
        
        // Get max allowed speed with adjustments for game conditions
        double maxSpeed = getMaxAllowedSpeed(player);
        
        // Update speed history for pattern detection
        updateSpeedHistory(playerId, horizontalSpeed);
        
        // Check for flying (needs to be more forgiving for jumps)
        boolean flyingViolation = false;
        boolean isNearGround = MovementUtils.isNearGround(player);
        boolean isJumping = false;
        
        // Reset air ticks if on ground
        if (isNearGround) {
            airTicks.put(playerId, 0);
        } else {
            // Check if player is likely jumping (moving upward in early air time)
            int previousAirTicks = airTicks.getOrDefault(playerId, 0);
            isJumping = previousAirTicks < 10 && to.getY() > from.getY();
            
            // Increment air ticks if not on ground
            int currentAirTicks = previousAirTicks + 1;
            airTicks.put(playerId, currentAirTicks);
            
            // Only check for fly hacks if player has been in air for a while (not just jumping)
            // Normal jump apex is around 11-13 ticks
            if (currentAirTicks > 25) {
                // Check for hovering (staying at same Y level while in air)
                if (Math.abs(to.getY() - from.getY()) < 0.05 && !player.isGliding() && !player.isFlying()) {
                    flyingViolation = true;
                    
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info(player.getName() + " potential hover hack: air ticks=" + currentAirTicks);
                    }
                }
                
                // Check for ascending in air (only after being in air long enough)
                if (to.getY() > from.getY() && !player.isGliding() && !player.isFlying() && 
                    !MovementUtils.isInLiquid(player) && currentAirTicks > 30) {
                    flyingViolation = true;
                    
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info(player.getName() + " ascending in air after " + currentAirTicks + " ticks");
                    }
                }
            }
        }
        
        // Enhanced speed hack detection with multiple checks
        boolean speedViolation = false;
        
        // Only perform regular speed check if not in jump or not in jump grace period
        boolean isInGracePeriod = false;
        if (isJumping || isNearGround) {
            // Reset the jump timer when we detect a jump
            isInGracePeriod = true;
        } else {
            // Check if we're still within the grace period for jump or sprint
            long timeSinceGrounded = currentTime - lastMoveTime.getOrDefault(playerId, currentTime);
            isInGracePeriod = timeSinceGrounded < JUMP_GRACE_PERIOD_MS;
            
            // Check if we just landed from elytra flight
            Long landingTime = elytraLandingTime.get(playerId);
            if (landingTime != null && (currentTime - landingTime < ELYTRA_LANDING_GRACE_PERIOD_MS)) {
                isInGracePeriod = true;
                if (plugin.isDebugEnabled() && horizontalSpeed > maxSpeed) {
                    plugin.getLogger().info(player.getName() + " in elytra landing grace period, speed: " + 
                            String.format("%.2f", horizontalSpeed) + " blocks/s");
                }
            }
        }
        
        // Standard speed check (with adjusted max for jumps and sprints)
        double speedMultiplier = isInGracePeriod ? SPRINT_JUMP_SPEED_MULTIPLIER : 1.0;
        double allowedSpeed = maxSpeed * speedMultiplier;
        
        if (horizontalSpeed > allowedSpeed) {
            // First check - basic speed threshold
            speedViolation = true;
            
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(player.getName() + " speed violation: " + 
                        String.format("%.2f", horizontalSpeed) + " blocks/s (max allowed: " + 
                        String.format("%.2f", allowedSpeed) + ")");
            }
            
            // Check if speed is severely excessive (over 2x allowed)
            if (horizontalSpeed > allowedSpeed * 2) {
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info(player.getName() + " extreme speed violation: " + 
                            String.format("%.2f", horizontalSpeed) + " blocks/s (over 2x allowed)");
                }
            }
        }
        
        // Second check - consistent speed pattern
        if (!speedViolation && hasSpeedPattern(playerId, allowedSpeed)) {
            speedViolation = true;
            
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(player.getName() + " has suspicious speed pattern: " + 
                        String.format("%.2f", horizontalSpeed) + " blocks/s");
            }
        }
        
        // SIMPLIFIED: If a violation was detected, immediately block all movement
        if (speedViolation || (flyingViolation && airTicks.getOrDefault(playerId, 0) > 40)) {
            String message = speedViolation ? "Excessive speed detected" : "Illegal flight detected";
            
            // Block movement for the configured duration
            blockPlayerMovement(player, message);
            return false;
        } else {
            // No violations, update last valid location
            lastValidLocations.put(playerId, to.clone());
            isCheating.put(playerId, false);
        }
        
        return true;
    }
    
    /**
     * Block all player movement for the configured duration
     */
    private void blockPlayerMovement(Player player, String reason) {
        UUID playerId = player.getUniqueId();
        
        // Get block duration from config (in seconds)
        int blockDuration = plugin.getConfigManager().getCancelDuration();
        
        // Set blocked until timestamp
        long currentTime = System.currentTimeMillis();
        long blockedUntil = currentTime + (blockDuration * 1000L);
        
        // Use lock to ensure thread safety
        operationLock.lock();
        try {
            movementBlockedUntil.put(playerId, blockedUntil);
        } finally {
            operationLock.unlock();
        }
        
        // Store current location as the last valid one
        lastValidLocations.put(playerId, player.getLocation().clone());
        
        // Notify the player on the main thread
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (player.isOnline()) {
                        player.sendMessage("§c[VelocityGuard] §f" + reason + "! Movement blocked for " + blockDuration + " seconds.");
                        
                        if (plugin.isDebugEnabled()) {
                            plugin.getLogger().info("Blocked all movement for " + player.getName() + 
                                    " for " + blockDuration + " seconds. Reason: " + reason);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Error notifying player " + player.getName() + ": " + e.getMessage());
                }
            }
        }.runTask(plugin);
    }
    
    /**
     * Update speed history for a player
     */
    private void updateSpeedHistory(UUID playerId, double speed) {
        Queue<Double> speeds = recentSpeeds.computeIfAbsent(playerId, k -> new LinkedList<>());
        
        // Add the new speed to the history
        speeds.add(speed);
        
        // Keep history size limited
        while (speeds.size() > SPEED_HISTORY_SIZE) {
            speeds.poll();
        }
    }
    
    /**
     * Reset speed history for a player
     */
    private void resetSpeedHistory(UUID playerId) {
        Queue<Double> speeds = recentSpeeds.get(playerId);
        if (speeds != null) {
            speeds.clear();
        }
    }
    
    /**
     * Check if player's recent movement shows a speed hack pattern
     * Speed hacks often maintain suspiciously consistent high speeds
     */
    private boolean hasSpeedPattern(UUID playerId, double maxSpeed) {
        Queue<Double> history = recentSpeeds.get(playerId);
        if (history == null || history.size() < SPEED_HISTORY_SIZE) {
            return false;
        }
        
        // Calculate average and check for suspiciously consistent speeds
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        int highSpeedCount = 0;
        
        for (double speed : history) {
            sum += speed;
            min = Math.min(min, speed);
            max = Math.max(max, speed);
            
            // Count how many speeds are suspiciously high
            if (speed > maxSpeed * SUSPICIOUS_SPEED_RATIO) {
                highSpeedCount++;
            }
        }
        
        double average = sum / history.size();
        double variance = max - min;
        
        // Speed hacks often have suspiciously consistent speeds just under the detection threshold
        boolean suspiciouslyConsistent = variance < SPEED_VARIANCE_THRESHOLD && average > maxSpeed * SUSPICIOUS_SPEED_RATIO;
        
        // Another pattern: too many movements near the maximum allowed speed
        boolean tooManyHighSpeeds = highSpeedCount >= SPEED_HISTORY_SIZE - 1;
        
        return suspiciouslyConsistent || tooManyHighSpeeds;
    }

    /**
     * Get the maximum allowed speed for a player
     */
    private double getMaxAllowedSpeed(Player player) {
        double baseSpeed = plugin.getConfigManager().getMaxHorizontalSpeed();
        
        // Adjust for special conditions
        if (player.isSprinting()) {
            baseSpeed *= 1.3; // Allow higher speed for sprint
        }
        
        if (MovementUtils.isInLiquid(player)) {
            baseSpeed *= 0.8; // Slower in water
        }
        
        if (player.isGliding()) {
            // Now we apply a more controlled elytra speed multiplier
            baseSpeed *= 5.0; // Allow much higher speeds when gliding
        }
        
        // Add a buffer to reduce false positives
        return baseSpeed * 1.2;
    }
    
    /**
     * Register a player with the movement checker
     * Called when a player joins or teleports
     */
    public void registerPlayer(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        
        // Store current location as valid
        lastValidLocations.put(playerId, player.getLocation().clone());
        
        // Reset tracking variables
        airTicks.put(playerId, 0);
        resetSpeedHistory(playerId);
        isCheating.put(playerId, false);
        wasGliding.put(playerId, player.isGliding());
        
        // Let them move again (in case they were previously blocked)
        movementBlockedUntil.remove(playerId);
    }
    
    /**
     * Unregister a player from the movement checker
     * Called when a player leaves the server
     */
    public void unregisterPlayer(UUID playerId) {
        if (playerId == null) return;
        
        // Remove player from all tracking maps
        lastValidLocations.remove(playerId);
        airTicks.remove(playerId);
        recentSpeeds.remove(playerId);
        lastMoveTime.remove(playerId);
        wasGliding.remove(playerId);
        takeoffTime.remove(playerId);
        preTakeoffSpeed.remove(playerId);
        elytraLandingTime.remove(playerId);
        isCheating.remove(playerId);
        movementBlockedUntil.remove(playerId);
    }

} 