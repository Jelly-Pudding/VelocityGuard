package com.jellypudding.velocityGuard.processors;

import com.jellypudding.velocityGuard.VelocityGuard;
import com.jellypudding.velocityGuard.utils.MovementUtils;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

/**
 * Simple movement checker that directly prevents cheating by freezing players
 * who use speed/fly cheats until they stop cheating.
 * Optimized for minimal main thread impact.
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
    
    // Keep track of players with pending teleports to avoid spamming
    private final Map<UUID, Long> pendingTeleports = new ConcurrentHashMap<>();
    
    // Track when players were frozen and for how long
    private final Map<UUID, Long> frozenUntil = new ConcurrentHashMap<>();
    
    // Lock for teleport operations
    private final ReentrantLock teleportLock = new ReentrantLock();
    
    // Track violation counts for more gradual enforcement
    private final Map<UUID, AtomicInteger> violationLevels = new ConcurrentHashMap<>();
    
    // Constants for pattern detection
    private static final int SPEED_HISTORY_SIZE = 6;
    private static final double SPEED_VARIANCE_THRESHOLD = 0.05;
    private static final double SUSPICIOUS_SPEED_RATIO = 0.85; // 85% of max speed consistently is suspicious
    
    // Constants for legitimate movements
    private static final double SPRINT_JUMP_SPEED_MULTIPLIER = 1.8; // Higher multiplier for sprint jumping
    private static final long JUMP_GRACE_PERIOD_MS = 700; // Allow 700ms of higher speed for jump boosts
    private static final double ELYTRA_TAKEOFF_MAX_SPEED = 14.0; // Maximum allowed speed when first activating elytra
    
    // Teleport cooldown to prevent packet spam
    private static final long TELEPORT_COOLDOWN_MS = 500;
    
    // Violation thresholds
    private static final int VIOLATION_THRESHOLD_MINOR = 3;
    private static final int VIOLATION_THRESHOLD_MAJOR = 8;
    private static final int VIOLATION_THRESHOLD_SEVERE = 15;
    private static final int VIOLATION_RESET_SECONDS = 5;
    
    // Freeze settings
    private static final long FREEZE_DURATION_MS = 3000; // 3 seconds freeze for first offense
    private static final long MAX_FREEZE_DURATION_MS = 10000; // 10 seconds max freeze time
    private static final double FREEZE_DURATION_MULTIPLIER = 1.5; // Each subsequent freeze lasts longer
    
    public MovementChecker(VelocityGuard plugin) {
        this.plugin = plugin;
        
        // Start violation decay task (runs every 5 seconds)
        new BukkitRunnable() {
            @Override
            public void run() {
                // This task runs on the main thread but is very lightweight
                // and only executes every 5 seconds
                decayViolationLevels();
            }
        }.runTaskTimer(plugin, 100L, 100L);
        
        // Start frozen player check task (runs every second)
        new BukkitRunnable() {
            @Override
            public void run() {
                checkFrozenPlayers();
            }
        }.runTaskTimer(plugin, 20L, 20L);
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
        
        // Check if player is currently frozen
        Long frozenTime = frozenUntil.get(playerId);
        if (frozenTime != null && System.currentTimeMillis() < frozenTime) {
            // Player is frozen, deny movement
            return false;
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
        
        // Don't check if there's a pending teleport cooldown
        Long lastTeleport = pendingTeleports.get(playerId);
        if (lastTeleport != null && System.currentTimeMillis() - lastTeleport < TELEPORT_COOLDOWN_MS) {
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
                    
                    // This is likely a speed hack + elytra exploit - severe violation
                    incrementViolationLevel(playerId, 5); // Major violation
                    
                    if (getViolationLevel(playerId) >= VIOLATION_THRESHOLD_SEVERE) {
                        // Force them off elytra by teleporting and applying freeze
                        Location validLoc = lastValidLocations.getOrDefault(playerId, from);
                        teleportPlayerSafely(player, validLoc);
                        freezePlayer(player, "Suspicious elytra takeoff detected!");
                        return false;
                    } else if (shouldTeleportBack(playerId)) {
                        Location validLoc = lastValidLocations.getOrDefault(playerId, from);
                        teleportPlayerSafely(player, validLoc);
                        player.sendMessage("§c[VelocityGuard] §fSuspicious elytra takeoff detected!");
                        isCheating.put(playerId, true);
                        return false;
                    }
                }
            } else if (horizontalSpeed > maxSpeed * 2.5 && currentTime - glideStartTime > 2000) {
                // Check for extremely high speeds after the initial takeoff (likely elytra + speed hack)
                incrementViolationLevel(playerId, 3);
                
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info(player.getName() + " extreme elytra speed: " + 
                            String.format("%.2f", horizontalSpeed) + " blocks/s");
                }
                
                if (getViolationLevel(playerId) >= VIOLATION_THRESHOLD_SEVERE) {
                    freezePlayer(player, "Extreme elytra speed detected!");
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
                    incrementViolationLevel(playerId, 2); // Medium violation
                    
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info(player.getName() + " potential hover hack: air ticks=" + currentAirTicks);
                    }
                }
                
                // Check for ascending in air (only after being in air long enough)
                if (to.getY() > from.getY() && !player.isGliding() && !player.isFlying() && 
                    !MovementUtils.isInLiquid(player) && currentAirTicks > 30) {
                    flyingViolation = true;
                    incrementViolationLevel(playerId, 3); // Medium-high violation
                    
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info(player.getName() + " ascending in air after " + currentAirTicks + " ticks");
                    }
                }
            }
        }
        
        // Enhanced speed hack detection with multiple checks
        boolean speedViolation = false;
        
        // Only perform regular speed check if not in jump or not in jump grace period
        boolean isInJumpGracePeriod = false;
        if (isJumping || isNearGround) {
            // Reset the jump timer when we detect a jump
            isInJumpGracePeriod = true;
        } else {
            // Check if we're still within the grace period for jump or sprint
            long timeSinceGrounded = currentTime - lastMoveTime.getOrDefault(playerId, currentTime);
            isInJumpGracePeriod = timeSinceGrounded < JUMP_GRACE_PERIOD_MS;
        }
        
        // Standard speed check (with adjusted max for jumps and sprints)
        double speedMultiplier = isInJumpGracePeriod ? SPRINT_JUMP_SPEED_MULTIPLIER : 1.0;
        double allowedSpeed = maxSpeed * speedMultiplier;
        
        if (horizontalSpeed > allowedSpeed) {
            // First check - basic speed threshold
            speedViolation = true;
            incrementViolationLevel(playerId, 1); // Minor violation
            
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(player.getName() + " speed violation: " + 
                        String.format("%.2f", horizontalSpeed) + " blocks/s (max allowed: " + 
                        String.format("%.2f", allowedSpeed) + ")");
            }
            
            // Check if speed is severely excessive (over 2x allowed)
            if (horizontalSpeed > allowedSpeed * 2) {
                incrementViolationLevel(playerId, 2); // Add more violation points for extreme speeds
                
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info(player.getName() + " extreme speed violation: " + 
                            String.format("%.2f", horizontalSpeed) + " blocks/s (over 2x allowed)");
                }
            }
        }
        
        // Second check - consistent speed pattern
        if (!speedViolation && hasSpeedPattern(playerId, allowedSpeed)) {
            speedViolation = true;
            incrementViolationLevel(playerId, 2); // Medium violation
            
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(player.getName() + " has suspicious speed pattern: " + 
                        String.format("%.2f", horizontalSpeed) + " blocks/s");
            }
        }
        
        // If a violation was detected, handle it 
        if (speedViolation || flyingViolation) {
            // Simplify: If player is going extremely fast (2x allowed), freeze them immediately
            if (horizontalSpeed > allowedSpeed * 2) {
                Location validLoc = lastValidLocations.getOrDefault(playerId, from);
                teleportPlayerSafely(player, validLoc);
                
                String message = speedViolation ? "Excessive speed detected!" : "Illegal flight detected!";
                freezePlayer(player, message);
                
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info("FREEZING " + player.getName() + " for speed: " + 
                            String.format("%.2f", horizontalSpeed) + " blocks/s");
                }
                return false;
            }
            // Otherwise just teleport back
            else if (shouldTeleportBack(playerId)) {
                Location validLoc = lastValidLocations.getOrDefault(playerId, from);
                teleportPlayerSafely(player, validLoc);
                
                // Send appropriate message based on violation type
                if (speedViolation) {
                    player.sendMessage("§c[VelocityGuard] §fMoving too fast!");
                } else {
                    player.sendMessage("§c[VelocityGuard] §fIllegal flight detected!");
                }
                
                isCheating.put(playerId, true);
                return false;
            }
        } else {
            // No violations, update last valid location
            lastValidLocations.put(playerId, to.clone());
            isCheating.put(playerId, false);
        }
        
        return true;
    }
    
    /**
     * Freeze a player for a period of time
     */
    private void freezePlayer(Player player, String message) {
        UUID playerId = player.getUniqueId();
        
        // Calculate freeze duration based on past violations
        long currentTime = System.currentTimeMillis();
        long previousFreezeEnd = frozenUntil.getOrDefault(playerId, 0L);
        long timeSincePreviousFreeze = currentTime - previousFreezeEnd;
        
        // Determine freeze duration - longer for repeated offenses
        long computedFreezeDuration = FREEZE_DURATION_MS;
        
        // If player was frozen recently, increase duration
        if (previousFreezeEnd > 0 && timeSincePreviousFreeze < TimeUnit.MINUTES.toMillis(1)) {
            // Calculate scaling freeze time based on violation level
            int level = getViolationLevel(playerId);
            double multiplier = Math.min(3.0, 1.0 + (level - VIOLATION_THRESHOLD_SEVERE) * 0.2);
            computedFreezeDuration = (long) (FREEZE_DURATION_MS * multiplier);
            
            // Cap at maximum freeze time
            computedFreezeDuration = Math.min(computedFreezeDuration, MAX_FREEZE_DURATION_MS);
        }
        
        // Make it final for use in the lambda
        final long freezeDuration = computedFreezeDuration;
        
        // Set freeze until timestamp
        frozenUntil.put(playerId, currentTime + freezeDuration);
        
        // Apply slowness effect to visually indicate they're frozen
        // This runs on the main thread
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (player.isOnline()) {
                        // Apply strong slowness effect
                        player.addPotionEffect(new PotionEffect(
                            PotionEffectType.SLOWNESS, 
                            (int) (freezeDuration / 50) + 5, // Convert to ticks, add buffer
                            4, // Amplifier (Slowness V)
                            false, // No ambient particles
                            false, // No particles
                            true  // Show icon
                        ));
                        
                        // Apply jump prevention
                        player.addPotionEffect(new PotionEffect(
                            PotionEffectType.JUMP_BOOST, 
                            (int) (freezeDuration / 50) + 5, // Convert to ticks, add buffer
                            200, // Extreme negative jump effect (prevents jumping)
                            false,
                            false,
                            true
                        ));
                        
                        // Notify the player
                        player.sendMessage("§c[VelocityGuard] §f" + message + " You've been frozen for " + 
                                (freezeDuration / 1000) + " seconds.");
                        
                        if (plugin.isDebugEnabled()) {
                            plugin.getLogger().info("Froze " + player.getName() + " for " + (freezeDuration / 1000) + 
                                    " seconds due to severe movement violations");
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Error applying freeze effects to " + player.getName() + ": " + e.getMessage());
                }
            }
        }.runTask(plugin);
    }
    
    /**
     * Check and unfreeze players whose freeze time has expired
     */
    private void checkFrozenPlayers() {
        long currentTime = System.currentTimeMillis();
        frozenUntil.forEach((playerId, endTime) -> {
            if (currentTime > endTime) {
                // Freeze period has ended, remove from map
                frozenUntil.remove(playerId);
                
                // Get player
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    // Notify player they're unfrozen
                    player.sendMessage("§a[VelocityGuard] §fYou can move normally now.");
                    
                    // Remove effects
                    player.removePotionEffect(PotionEffectType.SLOWNESS);
                    player.removePotionEffect(PotionEffectType.JUMP_BOOST);
                    
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("Unfroze " + player.getName() + " after freeze period");
                    }
                }
            }
        });
    }
    
    /**
     * Get a player's current violation level
     */
    private int getViolationLevel(UUID playerId) {
        AtomicInteger level = violationLevels.get(playerId);
        return level != null ? level.get() : 0;
    }
    
    /**
     * Increment the violation level for a player
     */
    private void incrementViolationLevel(UUID playerId, int amount) {
        violationLevels.computeIfAbsent(playerId, k -> new AtomicInteger(0))
                      .addAndGet(amount);
    }
    
    /**
     * Check if a player's violation level is high enough to trigger teleport
     */
    private boolean shouldTeleportBack(UUID playerId) {
        return getViolationLevel(playerId) >= VIOLATION_THRESHOLD_MAJOR;
    }
    
    /**
     * Periodically reduce violation levels (called on timer)
     */
    private void decayViolationLevels() {
        long now = System.currentTimeMillis();
        violationLevels.forEach((playerId, level) -> {
            // Reduce violations by 1 every 5 seconds
            int current = level.get();
            if (current > 0) {
                level.updateAndGet(val -> Math.max(0, val - 1));
            }
        });
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
        violationLevels.put(playerId, new AtomicInteger(0));
        frozenUntil.remove(playerId); // Ensure player isn't frozen on join/teleport
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
        isCheating.remove(playerId);
        pendingTeleports.remove(playerId);
        violationLevels.remove(playerId);
        frozenUntil.remove(playerId);
    }
    
    /**
     * Teleport a player safely back to a valid location
     * This method is designed to minimize main thread impact by using Bukkit scheduler
     */
    private void teleportPlayerSafely(Player player, Location location) {
        // Use lock to prevent spamming teleports for the same player
        teleportLock.lock();
        try {
            UUID playerId = player.getUniqueId();
            long now = System.currentTimeMillis();
            
            // Check if we've teleported this player recently
            Long lastTeleport = pendingTeleports.get(playerId);
            if (lastTeleport != null && (now - lastTeleport < TELEPORT_COOLDOWN_MS)) {
                return; // Skip this teleport - too soon after the last one
            }
            
            // Mark that we're teleporting this player
            pendingTeleports.put(playerId, now);
            
            // Ensure teleport happens on the main thread
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        if (player.isOnline()) {
                            player.teleport(location);
                            
                            if (plugin.isDebugEnabled()) {
                                plugin.getLogger().info("Teleported " + player.getName() + " back to valid location");
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error teleporting player " + player.getName() + ": " + e.getMessage());
                    }
                }
            }.runTask(plugin);
        } finally {
            teleportLock.unlock();
        }
    }
} 