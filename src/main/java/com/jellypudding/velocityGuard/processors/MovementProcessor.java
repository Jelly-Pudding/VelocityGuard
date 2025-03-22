package com.jellypudding.velocityGuard.processors;

import com.jellypudding.velocityGuard.VelocityGuard;
import com.jellypudding.velocityGuard.utils.MovementUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
        
        // Process all queued movements
        MovementData data;
        while ((data = movementQueue.poll()) != null) {
            // Submit to thread pool for processing
            final MovementData finalData = data;
            plugin.getCheckExecutor().execute(() -> checkMovement(finalData));
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
     */
    private void checkMovement(MovementData data) {
        UUID playerUUID = data.getPlayerUUID();
        Player player = plugin.getServer().getPlayer(playerUUID);
        
        // Skip if player is offline or we don't have their last location
        if (player == null || !lastLocations.containsKey(playerUUID)) return;
        
        Location from = lastLocations.get(playerUUID);
        Location to = data.getTo();
        long currentTime = data.getTimestamp();

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
        
        // Calculate time delta in milliseconds
        long timeDelta = currentTime - lastMoveTime.getOrDefault(playerUUID, currentTime);
        if (timeDelta <= 0) timeDelta = 50; // Default to 50ms if time delta is invalid
        
        // Only log detailed movement for flagged movements to reduce spam
        boolean isDetailedLogging = false;
        
        // Apply latency compensation
        boolean compensateLag = plugin.getConfigManager().isLagCompensationEnabled();
        double lagFactor = compensateLag ? Math.max(1.0, 1.0 + (player.getPing() / 1000.0)) : 1.0;
        
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
        
        // Update last location and time
        lastLocations.put(playerUUID, to);
        lastMoveTime.put(playerUUID, currentTime);
        
        // Handle violations on main thread if needed
        if (speedViolation || flightViolation) {
            final String speedDetails = "Speed: " + String.format("%.2f", horizontalSpeed) + 
                    " blocks/s > Max: " + String.format("%.2f", maxHorizontalSpeed) + " blocks/s";
            
            // Run teleport and violation reporting on the main thread
            new BukkitRunnable() {
                @Override
                public void run() {
                    // Check if player is still online
                    if (player.isOnline()) {
                        if (speedViolation) {
                            plugin.getLogger().info("Handling speed violation for " + player.getName());
                            plugin.getViolationManager().addViolation(player, "SpeedHack", speedDetails);
                        }
                        
                        if (flightViolation) {
                            plugin.getLogger().info("Handling flight violation for " + player.getName());
                            plugin.getViolationManager().addViolation(player, "FlightHack", flightDetails);
                        }
                        
                        // Correct player position
                        plugin.getLogger().info("Teleporting " + player.getName() + " back to " + formatLocation(from));
                        player.teleport(from);
                    }
                }
            }.runTask(plugin);
        }
    }
    
    /**
     * Format location for readable logging
     */
    private String formatLocation(Location loc) {
        return String.format("(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
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
} 