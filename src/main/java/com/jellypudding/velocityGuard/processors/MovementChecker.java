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

/**
 * Simple movement checker that directly prevents cheating by freezing players
 * who use speed/fly cheats until they stop cheating.
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
    
    // Constants for pattern detection
    private static final int SPEED_HISTORY_SIZE = 6;
    private static final double SPEED_VARIANCE_THRESHOLD = 0.05;
    private static final double SUSPICIOUS_SPEED_RATIO = 0.85; // 85% of max speed consistently is suspicious
    
    // Constants for legitimate movements
    private static final double SPRINT_JUMP_SPEED_MULTIPLIER = 1.8; // Higher multiplier for sprint jumping
    private static final long JUMP_GRACE_PERIOD_MS = 700; // Allow 700ms of higher speed for jump boosts
    private static final double ELYTRA_TAKEOFF_MAX_SPEED = 14.0; // Maximum allowed speed when first activating elytra
    
    public MovementChecker(VelocityGuard plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Process a player movement and check if it's valid
     * @param player The player who moved
     * @param from The previous location
     * @param to The new location
     * @return true if the move is allowed, false if it should be cancelled
     */
    public boolean processMovement(Player player, Location from, Location to) {
        if (player == null || from == null || to == null) return true;
        UUID playerId = player.getUniqueId();
        
        // Skip processing identical locations
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return true;
        }
        
        // Skip for creative/spectator mode players
        if (player.getGameMode().toString().contains("CREATIVE") ||
            player.getGameMode().toString().contains("SPECTATOR")) {
            lastValidLocations.put(playerId, to);
            airTicks.remove(playerId);
            return true;
        }
        
        // Check if this is likely a teleport (large distance)
        double distance = from.distance(to);
        if (distance > 20) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("Detected teleport for " + player.getName() + " - distance: " + String.format("%.2f", distance));
            }
            lastValidLocations.put(playerId, to);
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
            takeoffTime.put(playerId, currentTime);
            preTakeoffSpeed.put(playerId, horizontalSpeed);
            
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(player.getName() + " started gliding at speed: " + 
                        String.format("%.2f", horizontalSpeed) + " blocks/s");
            }
        }
        
        // Get max allowed speed with adjustments for game conditions
        double maxSpeed = getMaxAllowedSpeed(player);
        
        // Special handling for elytra - detect speed abuse during takeoff
        if (isCurrentlyGliding) {
            // If player just started gliding, check for abnormal takeoff speed
            long glideStartTime = takeoffTime.getOrDefault(playerId, 0L);
            long glideTime = currentTime - glideStartTime;
            
            // During the first second of gliding, apply stricter speed limits to prevent
            // the "activate speed hack then immediately elytra" exploit
            if (glideTime < 1000) {
                double preTakeoff = preTakeoffSpeed.getOrDefault(playerId, 0.0);
                
                // If player had an abnormal speed increase right before takeoff or during initial glide
                if (preTakeoff > plugin.getConfigManager().getMaxHorizontalSpeed() * 1.3 || 
                    horizontalSpeed > ELYTRA_TAKEOFF_MAX_SPEED) {
                    
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info(player.getName() + " suspicious elytra takeoff speed: " + 
                                String.format("%.2f", horizontalSpeed) + " blocks/s (pre-takeoff: " + 
                                String.format("%.2f", preTakeoff) + ")");
                    }
                    
                    // This is likely a speed hack + elytra exploit
                    Location validLoc = lastValidLocations.getOrDefault(playerId, from);
                    teleportPlayerSafely(player, validLoc);
                    player.sendMessage("§c[VelocityGuard] §fSuspicious elytra takeoff detected!");
                    isCheating.put(playerId, true);
                    return false;
                }
            }
        }
        
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
        
        // Determine if this could be a legitimate sprint-jump
        boolean isPossibleSprintJump = player.isSprinting() && isJumping;
        
        // Apply more generous speed limits for sprint-jumping
        double effectiveMaxSpeed = maxSpeed;
        if (isPossibleSprintJump) {
            effectiveMaxSpeed = Math.max(maxSpeed, plugin.getConfigManager().getMaxHorizontalSpeed() * SPRINT_JUMP_SPEED_MULTIPLIER);
            
            if (plugin.isDebugEnabled() && horizontalSpeed > maxSpeed) {
                plugin.getLogger().info(player.getName() + " sprint-jump speed: " + 
                        String.format("%.2f", horizontalSpeed) + " (adjusted max: " + 
                        String.format("%.2f", effectiveMaxSpeed) + ")");
            }
        }
        
        // Check 1: Basic speed check with adjusted max for sprint-jumping
        if (horizontalSpeed > effectiveMaxSpeed) {
            speedViolation = true;
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(player.getName() + " speed violation (basic): " + 
                        String.format("%.2f", horizontalSpeed) + " > " + String.format("%.2f", effectiveMaxSpeed) + 
                        (isPossibleSprintJump ? " (sprint-jump)" : ""));
            }
        }
        
        // Check 2: Pattern detection for consistent high speeds (speed hack signature)
        // Skip for possible sprint jumps to reduce false positives
        if (!speedViolation && !isPossibleSprintJump && hasSpeedPattern(playerId, maxSpeed)) {
            speedViolation = true;
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(player.getName() + " speed violation (pattern): Consistent high speed detected");
            }
        }
        
        // Check 3: Unusually large single movement
        // Skip during sprint-jumping to reduce false positives
        if (!speedViolation && !isPossibleSprintJump && horizontalDistance > 0.5 && !player.isGliding() && 
            !MovementUtils.isInLiquid(player) && timeDelta < 70) {
            
            // More strict check for ground movement vs air
            double distanceThreshold = isNearGround ? 0.5 : 0.7;
            
            if (horizontalDistance > distanceThreshold) {
                speedViolation = true;
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info(player.getName() + " speed violation (distance): " + 
                            String.format("%.2f", horizontalDistance) + " blocks in " + timeDelta + "ms");
                }
            }
        }
        
        // Check 4: Unrealistic movement angles (common in some speed hacks)
        // Skip during sprint-jumping to reduce false positives
        Location lastValid = lastValidLocations.getOrDefault(playerId, from);
        if (!speedViolation && !isPossibleSprintJump && !lastValid.equals(from) && horizontalDistance > 0.3) {
            double angle = calculateMovementAngle(lastValid, from, to);
            
            // Sharp turns at high speeds are physically impossible
            if (angle > 100 && horizontalSpeed > maxSpeed * 0.7) {
                speedViolation = true;
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info(player.getName() + " speed violation (angle): " + 
                            String.format("%.1f", angle) + "° turn at " + 
                            String.format("%.2f", horizontalSpeed) + " blocks/s");
                }
            }
        }
        
        // If either violation is detected
        boolean isCurrentlyCheating = speedViolation || flyingViolation;
        
        if (isCurrentlyCheating) {
            // Mark player as cheating
            isCheating.put(playerId, true);
            
            // Send feedback to player 
            if (plugin.isDebugEnabled()) {
                String violationType = speedViolation ? "Speed" : "Fly";
                plugin.getLogger().info(player.getName() + " " + violationType + " violation detected!");
            }
            
            // If this is their first detected violation, notify them
            if (!isCheating.getOrDefault(playerId, false)) {
                player.sendMessage("§c[VelocityGuard] §fIllegal movement detected!");
            }
            
            // Teleport player back to last valid location
            Location validLoc = lastValidLocations.getOrDefault(playerId, from);
            teleportPlayerSafely(player, validLoc);
            
            // Reject the movement
            return false;
        } else {
            // Player is not cheating, update their last valid location
            lastValidLocations.put(playerId, to);
            
            // If they were previously cheating but stopped, mark them as not cheating
            if (isCheating.getOrDefault(playerId, false)) {
                isCheating.put(playerId, false);
                
                // Inform them they can move again
                player.sendMessage("§a[VelocityGuard] §fYou can move freely now.");
            }
            
            // Allow the movement
            return true;
        }
    }
    
    /**
     * Update the player's speed history for pattern detection
     */
    private void updateSpeedHistory(UUID playerId, double speed) {
        Queue<Double> history = recentSpeeds.computeIfAbsent(playerId, k -> new LinkedList<>());
        
        // Add the current speed to history
        history.add(speed);
        
        // Keep only the most recent entries
        while (history.size() > SPEED_HISTORY_SIZE) {
            history.poll();
        }
    }
    
    /**
     * Reset a player's speed history
     */
    private void resetSpeedHistory(UUID playerId) {
        recentSpeeds.remove(playerId);
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
     * Calculate angle between movement vectors (for detecting unrealistic turns)
     */
    private double calculateMovementAngle(Location previous, Location current, Location next) {
        double dx1 = current.getX() - previous.getX();
        double dz1 = current.getZ() - previous.getZ();
        double dx2 = next.getX() - current.getX();
        double dz2 = next.getZ() - current.getZ();
        
        // Calculate angle between vectors (in degrees)
        double dot = dx1 * dx2 + dz1 * dz2;
        double mag1 = Math.sqrt(dx1 * dx1 + dz1 * dz1);
        double mag2 = Math.sqrt(dx2 * dx2 + dz2 * dz2);
        
        // Avoid division by zero
        if (mag1 < 0.01 || mag2 < 0.01) return 0;
        
        double cosAngle = dot / (mag1 * mag2);
        // Clamp to valid range to avoid precision errors
        cosAngle = Math.max(-1, Math.min(1, cosAngle));
        
        return Math.toDegrees(Math.acos(cosAngle));
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
     * Register a player when they join
     */
    public void registerPlayer(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        lastValidLocations.put(playerId, player.getLocation());
        isCheating.put(playerId, false);
        airTicks.put(playerId, 0);
        resetSpeedHistory(playerId);
        lastMoveTime.put(playerId, System.currentTimeMillis());
        wasGliding.put(playerId, player.isGliding());
    }
    
    /**
     * Unregister a player when they leave
     */
    public void unregisterPlayer(UUID playerId) {
        if (playerId == null) return;
        lastValidLocations.remove(playerId);
        isCheating.remove(playerId);
        airTicks.remove(playerId);
        recentSpeeds.remove(playerId);
        lastMoveTime.remove(playerId);
        wasGliding.remove(playerId);
        takeoffTime.remove(playerId);
        preTakeoffSpeed.remove(playerId);
    }
    
    /**
     * Teleport a player safely on the main thread
     */
    private void teleportPlayerSafely(Player player, Location location) {
        new BukkitRunnable() {
            @Override
            public void run() {
                player.teleport(location);
            }
        }.runTask(plugin);
    }
} 