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
            plugin.getLogger().info("VelocityGuard: Processing " + queueSize + " queued movements");
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
        
        // Calculate time delta in milliseconds
        long timeDelta = currentTime - lastMoveTime.getOrDefault(playerUUID, currentTime);
        if (timeDelta <= 0) timeDelta = 50; // Default to 50ms if time delta is invalid
        
        // Debug log that we're checking movement
        plugin.getLogger().info("Checking movement for " + player.getName() + 
                " from " + formatLocation(from) + " to " + formatLocation(to));
        
        // Apply latency compensation
        boolean compensateLag = plugin.getConfigManager().isLagCompensationEnabled();
        double lagFactor = compensateLag ? Math.max(1.0, 1.0 + (player.getPing() / 1000.0)) : 1.0;
        
        // Calculate horizontal speed in blocks per second
        double horizontalDistance = MovementUtils.calculateHorizontalDistance(from, to);
        double horizontalSpeed = (horizontalDistance / timeDelta) * 1000.0;
        
        double maxHorizontalSpeed = MovementUtils.getMaxHorizontalSpeed(player, 
                plugin.getConfigManager().getMaxHorizontalSpeed()) * lagFactor;
        
        // Debug log the speed values
        plugin.getLogger().info(player.getName() + " speed check: current=" + String.format("%.2f", horizontalSpeed) + 
                " blocks/s, max=" + String.format("%.2f", maxHorizontalSpeed) + " blocks/s");
        
        // Adjust for special conditions
        if (MovementUtils.isInLiquid(player)) {
            maxHorizontalSpeed *= 0.8;
        }
        
        if (player.isGliding()) {
            maxHorizontalSpeed *= 5.0;
        }
        
        // Check if speed exceeds limit (with a small threshold to reduce false positives)
        final boolean speedViolation = horizontalSpeed > (maxHorizontalSpeed * 1.05);
        
        if (speedViolation) {
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
            
            // Adjust for falling (negative vertical movement has different physics constraints)
            if (verticalDistance < 0) {
                // Terminal velocity for falling is higher
                maxVerticalSpeed = Math.max(maxVerticalSpeed, 20.0); // Typical maximum falling speed
            }
            
            // Debug log vertical speed
            plugin.getLogger().info(player.getName() + " vertical speed check: current=" + 
                    String.format("%.2f", verticalSpeed) + " blocks/s, max=" + 
                    String.format("%.2f", maxVerticalSpeed * 1.5) + " blocks/s");
            
            if (player.isGliding()) {
                maxVerticalSpeed *= 3.0;
            }
            
            if (Math.abs(verticalSpeed) > Math.abs(maxVerticalSpeed * 1.5)) {
                flightViolation = true;
                flightDetails = "Vertical Speed: " + String.format("%.2f", verticalSpeed) + 
                        " blocks/s > Max: " + String.format("%.2f", maxVerticalSpeed * 1.5) +
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