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
        
        plugin.getLogger().info("VelocityGuard: Processing " + movementQueue.size() + " queued movements");
        
        while (!movementQueue.isEmpty()) {
            MovementData data = movementQueue.poll();
            if (data == null) continue;
            
            // Process in thread pool
            plugin.getCheckExecutor().execute(() -> checkMovement(data));
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
        
        // Debug log that we're checking movement
        plugin.getLogger().info("Checking movement for " + player.getName());
        
        Location from = lastLocations.get(playerUUID);
        Location to = data.getTo();
        long currentTime = data.getTimestamp();
        long timeDelta = currentTime - lastMoveTime.getOrDefault(playerUUID, currentTime);
        
        // Apply latency compensation
        boolean compensateLag = plugin.getConfigManager().isLagCompensationEnabled();
        double lagFactor = compensateLag ? Math.max(1.0, 1.0 + (player.getPing() / PING_COMPENSATION_MS) * 0.1) : 1.0;
        
        // Check horizontal speed
        double horizontalSpeed = MovementUtils.calculateHorizontalSpeed(from, to);
        double maxHorizontalSpeed = MovementUtils.getMaxHorizontalSpeed(player, 
                plugin.getConfigManager().getMaxHorizontalSpeed()) * lagFactor;
        
        // Debug log the speed values
        plugin.getLogger().info(player.getName() + " speed check: current=" + String.format("%.2f", horizontalSpeed) + 
                ", max=" + String.format("%.2f", maxHorizontalSpeed));
        
        // Adjust for conditions
        if (MovementUtils.isInLiquid(player)) {
            maxHorizontalSpeed *= 0.8;
        }
        
        if (player.isGliding()) {
            maxHorizontalSpeed *= 5.0;
        }
        
        // Check if speed exceeds limit
        final boolean speedViolation = horizontalSpeed > maxHorizontalSpeed;
        
        if (speedViolation) {
            plugin.getLogger().info(player.getName() + " SPEED VIOLATION DETECTED!");
        }
        
        // Check vertical movement
        final boolean flightViolation;
        final String flightDetails;
        
        if (!MovementUtils.isNearGround(player) && !MovementUtils.isInLiquid(player)) {
            double verticalSpeed = MovementUtils.calculateVerticalSpeed(from, to);
            double maxVerticalSpeed = plugin.getConfigManager().getMaxVerticalSpeed() * lagFactor;
            
            // Debug log vertical speed
            plugin.getLogger().info(player.getName() + " vertical speed check: current=" + 
                    String.format("%.2f", verticalSpeed) + ", max=" + String.format("%.2f", maxVerticalSpeed * 1.5));
            
            if (player.isGliding()) {
                maxVerticalSpeed *= 3.0;
            }
            
            if (Math.abs(verticalSpeed) > Math.abs(maxVerticalSpeed * 1.5)) {
                flightViolation = true;
                flightDetails = "Vertical Speed: " + String.format("%.2f", verticalSpeed) + 
                        " > Max: " + String.format("%.2f", maxVerticalSpeed * 1.5) +
                        (player.isGliding() ? " (Elytra)" : "");
                plugin.getLogger().info(player.getName() + " FLIGHT VIOLATION DETECTED!");
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
                    " > Max: " + String.format("%.2f", maxHorizontalSpeed);
            
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
                        plugin.getLogger().info("Teleporting " + player.getName() + " back to " + 
                                String.format("(%.2f, %.2f, %.2f)", from.getX(), from.getY(), from.getZ()));
                        player.teleport(from);
                    }
                }
            }.runTask(plugin);
        }
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