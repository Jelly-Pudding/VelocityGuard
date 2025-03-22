package com.jellypudding.velocityGuard.processors;

import com.jellypudding.velocityGuard.VelocityGuard;
import com.jellypudding.velocityGuard.utils.MovementUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.HashMap;

public class MovementProcessor {
    
    private final VelocityGuard plugin;
    
    // Thread-safe data structures for concurrent access
    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveTime = new ConcurrentHashMap<>();
    private final Queue<MovementData> movementQueue = new ConcurrentLinkedQueue<>();
    
    // Constants
    private static final int PING_COMPENSATION_MS = 100;
    
    public MovementProcessor(VelocityGuard plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Queues movement data for asynchronous processing
     * 
     * @param player The player who moved
     * @param from Original location
     * @param to Destination location
     */
    public void queueMovement(Player player, Location from, Location to) {
        if (player == null || from == null || to == null) return;
        
        // Skip processing identical locations
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        
        // Skip for creative/spectator mode
        if (player.getGameMode().toString().contains("CREATIVE") ||
            player.getGameMode().toString().contains("SPECTATOR")) {
            lastLocations.put(player.getUniqueId(), to);
            return;
        }

        // Check if this is likely a teleport by measuring the distance
        double distance = from.distance(to);
        if (distance > 20) { // If player moved more than 20 blocks instantly, it's likely a teleport
            plugin.getLogger().info("Detected teleport for " + player.getName() + " - distance: " + String.format("%.2f", distance));
            // Just update the location without checking
            lastLocations.put(player.getUniqueId(), to);
            lastMoveTime.put(player.getUniqueId(), System.currentTimeMillis());
            return;
        }
        
        // Add to queue for processing
        movementQueue.add(new MovementData(player.getUniqueId(), from, to, System.currentTimeMillis()));
    }
    
    /**
     * Processes all queued movements asynchronously
     */
    public void processQueue() {
        if (movementQueue.isEmpty()) return;
        
        int queueSize = movementQueue.size();
        if (queueSize > 0) {
            // Only log if there are more than 3 movements to avoid spam
            if (queueSize > 3) {
                plugin.getLogger().info("VelocityGuard: Processing " + queueSize + " queued movements");
            }
        }
        
        // Track players with rapid movement sequences
        Map<UUID, Integer> movementsPerPlayer = new HashMap<>();
        
        // First pass: count movements per player and detect potential speeders
        for (MovementData data : movementQueue) {
            UUID playerId = data.getPlayerUUID();
            movementsPerPlayer.merge(playerId, 1, Integer::sum);
        }
        
        // Process all queued movements
        MovementData data;
        while ((data = movementQueue.poll()) != null) {
            // Get player's movement count in this batch
            int playerMovements = movementsPerPlayer.getOrDefault(data.getPlayerUUID(), 0);
            
            // Prioritize processing for players with many queued movements or recent violations
            final MovementData finalData = data;
            if (playerMovements > 5 || plugin.getViolationManager().getViolationLevel(data.getPlayerUUID()) > 0) {
                // Process with higher priority for suspicious players
                plugin.getCheckExecutor().execute(() -> {
                    // Mark this as a higher priority check
                    checkMovement(finalData, true);
                });
            } else {
                // Normal priority for other players
                plugin.getCheckExecutor().execute(() -> checkMovement(finalData, false));
            }
        }
    }
    
    /**
     * Registers a player for movement tracking
     */
    public void registerPlayer(Player player) {
        if (player == null) return;
        lastLocations.put(player.getUniqueId(), player.getLocation());
        lastMoveTime.put(player.getUniqueId(), System.currentTimeMillis());
    }
    
    /**
     * Unregisters a player from movement tracking
     */
    public void unregisterPlayer(UUID playerUUID) {
        if (playerUUID == null) return;
        lastLocations.remove(playerUUID);
        lastMoveTime.remove(playerUUID);
    }
    
    /**
     * Performs the actual movement check asynchronously
     * 
     * @param data Movement data to check
     * @param highPriority Whether this check should be more thorough/restrictive
     */
    private void checkMovement(MovementData data, boolean highPriority) {
        UUID playerUUID = data.getPlayerUUID();
        Player player = plugin.getServer().getPlayer(playerUUID);
        
        // Skip if player is offline or we don't have their last location
        if (player == null || !lastLocations.containsKey(playerUUID)) return;
        
        Location from = lastLocations.get(playerUUID);
        Location to = data.getTo();
        long currentTime = data.getTimestamp();

        // Calculate time delta in milliseconds
        long timeDelta = currentTime - lastMoveTime.getOrDefault(playerUUID, currentTime);
        if (timeDelta <= 0) timeDelta = 50; // Default to 50ms if time delta is invalid
        
        // ALWAYS check for extreme speed - this method handles teleporting if needed
        boolean extremeViolation = handleExtremeSpeedCheck(player, from, to, timeDelta);
        if (extremeViolation) {
            // If we found an extreme violation, update last location but don't need further checks
            lastLocations.put(playerUUID, from); // Keep the previous location
            lastMoveTime.put(playerUUID, currentTime);
            return;
        }
        
        // Continue with regular checks only if this wasn't an extreme speed violation
        
        // Additional teleportation check - if distance is suspicious
        double distanceSquared = from.distanceSquared(to);
        if (distanceSquared > 100) { // 10 blocks distance squared
            plugin.getLogger().info("Skipping check for " + player.getName() + " - likely teleport (distance: " + 
                    String.format("%.2f", Math.sqrt(distanceSquared)) + ")");
            
            // Update last location and time without checking
            lastLocations.put(playerUUID, to);
            lastMoveTime.put(playerUUID, currentTime);
            return;
        }
        
        // For high priority checks, use a shorter time delta to be more restrictive
        // This effectively increases the calculated speed for players who may be cheating
        if (highPriority && timeDelta > 50) {
            timeDelta = Math.max(30, timeDelta / 2); // More restrictive time delta for suspicious players
        }
        
        // Only log detailed movement for flagged movements to reduce spam
        boolean isDetailedLogging = false;
        
        // Apply latency compensation
        boolean compensateLag = plugin.getConfigManager().isLagCompensationEnabled();
        double lagFactor = compensateLag ? Math.max(1.0, 1.0 + (player.getPing() / 1000.0)) : 1.0;
        
        // For high priority checks, reduce lag compensation to be more strict
        if (highPriority) {
            lagFactor = Math.min(lagFactor, 1.2); // Cap lag factor for suspicious players
        }
        
        // Calculate horizontal speed in blocks per second
        double horizontalDistance = MovementUtils.calculateHorizontalDistance(from, to);
        double horizontalSpeed = (horizontalDistance / timeDelta) * 1000.0;
        
        double maxHorizontalSpeed = MovementUtils.getMaxHorizontalSpeed(player, 
                plugin.getConfigManager().getMaxHorizontalSpeed()) * lagFactor;
        
        // SPECIAL CASE: Check if player is sprint-jumping
        // The combination of sprinting and jumping can temporarily produce very high speeds
        boolean isJumping = to.getY() > from.getY() && !player.isFlying();
        boolean isNearJumpStart = MovementUtils.wasNearGround(player, from);
        
        // If player is sprint-jumping, allow higher speeds
        if (player.isSprinting() && isJumping && isNearJumpStart) {
            maxHorizontalSpeed *= 1.6; // Allow 60% higher speed for sprint-jumps
            
            // Debug log for sprint-jumping
            plugin.getLogger().fine(player.getName() + " detected sprint-jumping, increased max speed to " + 
                   String.format("%.2f", maxHorizontalSpeed));
        }
        
        // Adjust for special conditions
        if (MovementUtils.isInLiquid(player)) {
            maxHorizontalSpeed *= 0.8;
        }
        
        if (player.isGliding()) {
            maxHorizontalSpeed *= 5.0;
        }
        
        // Check if speed exceeds limit (with a buffer to reduce false positives)
        // Add a buffer of 20% to reduce false positives
        final boolean speedViolation = horizontalSpeed > (maxHorizontalSpeed * 1.2);
        
        // Only log detailed info if it's a violation or debug mode
        if (speedViolation) {
            isDetailedLogging = true;
            plugin.getLogger().info("Checking movement for " + player.getName() + 
                    " from " + formatLocation(from) + " to " + formatLocation(to));
            plugin.getLogger().info(player.getName() + " speed check: current=" + String.format("%.2f", horizontalSpeed) + 
                    " blocks/s, max=" + String.format("%.2f", maxHorizontalSpeed) + " blocks/s");
            plugin.getLogger().warning(player.getName() + " SPEED VIOLATION DETECTED! " + 
                    String.format("%.2f", horizontalSpeed) + " > " + 
                    String.format("%.2f", maxHorizontalSpeed));
        }
        
        // Check vertical movement (flight)
        final boolean flightViolation;
        final String flightDetails;
        
        if (!MovementUtils.isNearGround(player) && !MovementUtils.isInLiquid(player) && !player.isFlying()) {
            double verticalDistance = to.getY() - from.getY();
            double verticalSpeed = (verticalDistance / timeDelta) * 1000.0;
            double maxVerticalSpeed = plugin.getConfigManager().getMaxVerticalSpeed() * lagFactor;
            
            // Special handling for jumping - the first part of a jump can have higher velocity
            // Standard Minecraft jump reaches ~0.42 blocks on the first tick, then gradually falls
            if (verticalDistance > 0 && verticalDistance < 0.5 && 
                    (MovementUtils.wasNearGround(player, from) || timeDelta < 100)) {
                // Initial jump velocity can be higher, allow it
                maxVerticalSpeed = Math.max(maxVerticalSpeed, 9.0); // Allow higher initial jump
            }
            
            // Adjust for falling (negative vertical movement has different physics constraints)
            if (verticalDistance < 0) {
                // Terminal velocity for falling is higher
                maxVerticalSpeed = Math.max(maxVerticalSpeed, 20.0); // Typical maximum falling speed
            }
            
            // Adjust for elytra gliding
            if (player.isGliding()) {
                maxVerticalSpeed *= 3.0;
            }
            
            // Add a more generous buffer for vertical speed (jumps can vary based on client tick rate)
            // Use 200% buffer for upward motion, which is most likely to be legitimate jumps
            double buffer = verticalDistance > 0 ? 2.0 : 1.65;
            
            if (Math.abs(verticalSpeed) > Math.abs(maxVerticalSpeed * buffer)) {
                if (!isDetailedLogging) {
                    plugin.getLogger().info("Checking movement for " + player.getName() + 
                        " from " + formatLocation(from) + " to " + formatLocation(to));
                }
                
                isDetailedLogging = true;
                plugin.getLogger().info(player.getName() + " vertical speed check: current=" + 
                        String.format("%.2f", verticalSpeed) + " blocks/s, max=" + 
                        String.format("%.2f", maxVerticalSpeed * buffer) + " blocks/s");
                
                flightViolation = true;
                flightDetails = "Vertical Speed: " + String.format("%.2f", verticalSpeed) + 
                        " blocks/s > Max: " + String.format("%.2f", maxVerticalSpeed * buffer) +
                        " blocks/s" + (player.isGliding() ? " (Elytra)" : "");
                plugin.getLogger().warning(player.getName() + " FLIGHT VIOLATION DETECTED! " + flightDetails);
            } else {
                flightViolation = false;
                flightDetails = "";
            }
        } else {
            flightViolation = false;
            flightDetails = "";
        }
        
        // Handle violations on main thread if needed
        if (speedViolation || flightViolation) {
            final String speedDetails = "Speed: " + String.format("%.2f", horizontalSpeed) + 
                    " blocks/s > Max: " + String.format("%.2f", maxHorizontalSpeed) + " blocks/s";
            
            // Always use the last known legitimate position for teleporting back
            final Location teleportTo = from.clone();
            
            // Run teleport and violation reporting on the main thread
            new BukkitRunnable() {
                @Override
                public void run() {
                    // Check if player is still online
                    if (player.isOnline()) {
                        if (speedViolation) {
                            plugin.getLogger().info("Handling speed violation for " + player.getName());
                            plugin.getViolationManager().addViolation(player, "SpeedCheat", speedDetails);
                        }
                        
                        if (flightViolation) {
                            plugin.getLogger().info("Handling flight violation for " + player.getName());
                            plugin.getViolationManager().addViolation(player, "FlightCheat", flightDetails);
                        }
                        
                        // Only correct position for this specific violation, not future movements
                        // This way the player isn't punished after they stop cheating
                        double currentDistance = player.getLocation().distance(teleportTo);
                        if (currentDistance > 0.1) { // Only teleport if they've actually moved away
                            plugin.getLogger().info("Resetting " + player.getName() + " position from " + 
                                    formatLocation(player.getLocation()) + " back to " + formatLocation(teleportTo) +
                                    " (gain prevented: " + String.format("%.2f", currentDistance) + " blocks)");
                            player.teleport(teleportTo);
                        }
                    }
                }
            }.runTask(plugin);
            
            // Update the last location map with the current legitimate position
            // This way, future non-cheating movements won't be compared against old positions
            lastLocations.put(playerUUID, teleportTo);
            
            return;
        }
        
        // Only update last location and time if no violations occurred
        lastLocations.put(playerUUID, to);
        lastMoveTime.put(playerUUID, currentTime);
    }
    
    /**
     * Format location for readable logging
     */
    private String formatLocation(Location loc) {
        return String.format("(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }
    
    /**
     * Private class to hold movement data in the queue
     */
    private static class MovementData {
        private final UUID playerUUID;
        private final Location from;
        private final Location to;
        private final long timestamp;
        
        public MovementData(UUID playerUUID, Location from, Location to, long timestamp) {
            this.playerUUID = playerUUID;
            this.from = from.clone();
            this.to = to.clone();
            this.timestamp = timestamp;
        }
        
        public UUID getPlayerUUID() {
            return playerUUID;
        }
        
        public Location getFrom() {
            return from;
        }
        
        public Location getTo() {
            return to;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
    }
    
    /**
     * Resets a player's position to their last valid location
     * This is called both during movement checks when violations are detected
     * and periodically by the ResetPositionTask for players with multiple violations.
     *
     * @param player The player to reset
     * @return true if player was teleported, false if not (no valid last position or player already at last position)
     */
    public boolean resetPlayerPosition(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        
        UUID playerId = player.getUniqueId();
        Location lastLocation = lastLocations.get(playerId);
        
        if (lastLocation == null) {
            return false;
        }
        
        Location currentLocation = player.getLocation();
        
        // Skip if player is already at or very close to the last valid location
        double distanceSquared = currentLocation.distanceSquared(lastLocation);
        if (distanceSquared < 0.01) { // Less than 0.1 blocks distance
            return false;
        }
        
        // Calculate distance prevented by teleporting back
        double distance = Math.sqrt(distanceSquared);
        
        // Teleport player back to their last valid position
        player.teleport(lastLocation);
        
        // Log the reset with detailed information
        plugin.getLogger().warning("Reset position for " + player.getName() + 
                " from " + formatLocation(currentLocation) + 
                " to " + formatLocation(lastLocation) + 
                " (prevented " + String.format("%.2f", distance) + " blocks of movement)");
        
        return true;
    }

    /**
     * Gets the last known location for a player
     * @param playerUUID The player's UUID
     * @return The last known valid location, or null if none exists
     */
    public Location getLastKnownLocation(UUID playerUUID) {
        return lastLocations.get(playerUUID);
    }

    /**
     * Gets the timestamp of when the player last moved
     * @param playerUUID The player's UUID
     * @return The timestamp in milliseconds, or 0 if no record exists
     */
    public long getLastMoveTime(UUID playerUUID) {
        Long time = lastMoveTime.get(playerUUID);
        return time != null ? time : 0;
    }

    /**
     * Handles an extreme speed check independently of violations
     * Only resets position if the CURRENT movement is cheating
     * @param player The player
     * @param from Current location 
     * @param to Destination location
     * @param timeDelta Time difference in ms
     * @return true if cheating was detected
     */
    public boolean handleExtremeSpeedCheck(Player player, Location from, Location to, long timeDelta) {
        // First check if this is a teleport - large distances or command teleports
        double totalDistance = from.distance(to);
        if (totalDistance > 20) { // Keep using 20 blocks as in the original code
            // This is very likely a teleport, not a speed cheat.
            plugin.getLogger().info("Detected teleport for " + player.getName() + " - distance: " + 
                    String.format("%.2f", totalDistance) + " blocks");
            // Just update the location without flagging
            lastLocations.put(player.getUniqueId(), to);
            lastMoveTime.put(player.getUniqueId(), System.currentTimeMillis());
            return false;
        }

        // Calculate horizontal distance
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontalDistance = Math.sqrt(dx*dx + dz*dz);
        
        // Skip tiny movements
        if (horizontalDistance < 0.05) return false;
        
        // Calculate speed in blocks per second
        double speed = (horizontalDistance / Math.max(50, timeDelta)) * 1000.0;
        
        // Get base speed limit from config
        double speedLimit = plugin.getConfigManager().getMaxHorizontalSpeed();
        
        // SPECIAL CASE: Detect sprint-jumping the same way the main check does
        boolean isJumping = to.getY() > from.getY() && !player.isFlying();
        boolean isNearJumpStart = MovementUtils.wasNearGround(player, from);
        
        // If player is sprint-jumping, allow higher speeds just like in the main check
        if (player.isSprinting() && isJumping && isNearJumpStart) {
            speedLimit *= 1.6; // Allow 60% higher speed for sprint-jumps, matching the main check
            
            // Debug log for sprint-jumping
            plugin.getLogger().fine(player.getName() + " detected sprint-jumping, increased max speed to " + 
                    String.format("%.2f", speedLimit));
        }
        
        // Check if player is in special movement states
        if (player.isFlying() || player.isGliding() || MovementUtils.isInLiquid(player)) {
            // These states all allow higher speeds
            speedLimit *= 2.0;
        }
        
        // Use a stricter limit for players with existing violations
        UUID playerId = player.getUniqueId();
        int violations = plugin.getViolationManager().getViolationLevel(playerId);
        
        // For players with violations, use stricter limits, but only if they're not sprint-jumping
        if (violations > 0 && !(player.isSprinting() && isJumping && isNearJumpStart)) {
            // The more violations, the stricter the limit, but never below 7.0
            speedLimit = Math.max(7.0, speedLimit - (violations * 0.5));
        }
        
        // Add a 20% buffer to reduce false positives
        double speedLimitWithBuffer = speedLimit * 1.2;
        
        if (speed > speedLimitWithBuffer) {
            // This is a current, active speed cheat - teleport back immediately
            plugin.getLogger().warning("Speed cheat detected for " + player.getName() + 
                    " - " + String.format("%.2f", speed) + " blocks/s (limit: " + 
                    String.format("%.1f", speedLimitWithBuffer) + ")");
            
            // Teleport back to last known good position safely on main thread
            safelyTeleportPlayer(player, from);
            
            // Add a violation
            plugin.getViolationManager().addViolation(player, "Speed", 
                    "Moving at " + String.format("%.2f", speed) + " blocks/s");
            
            return true;
        }
        
        return false;
    }

    /**
     * Safely teleport a player on the main thread
     */
    private void safelyTeleportPlayer(Player player, Location location) {
        // If already on main thread, teleport immediately
        if (Bukkit.isPrimaryThread()) {
            player.teleport(location);
            player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        } else {
            // Schedule on main thread
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        player.teleport(location);
                        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    }
                }
            }.runTask(plugin);
        }
    }
} 