package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
import com.jellypudding.velocityGuard.utils.MovementUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerMovementListener implements Listener {
    
    private final VelocityGuard plugin;
    private final Map<UUID, Location> lastLocations;
    private final Map<UUID, Long> lastMoveTime;
    private static final int PING_COMPENSATION_MS = 100; // Latency compensation in milliseconds
    
    public PlayerMovementListener(VelocityGuard plugin) {
        this.plugin = plugin;
        this.lastLocations = new HashMap<>();
        this.lastMoveTime = new HashMap<>();
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Initialize tracking data when player joins
        Player player = event.getPlayer();
        lastLocations.put(player.getUniqueId(), player.getLocation());
        lastMoveTime.put(player.getUniqueId(), System.currentTimeMillis());
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up tracking data when player leaves
        UUID playerUUID = event.getPlayer().getUniqueId();
        lastLocations.remove(playerUUID);
        lastMoveTime.remove(playerUUID);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        // Update last location on teleport to prevent false positives
        lastLocations.put(event.getPlayer().getUniqueId(), event.getTo());
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        Location to = event.getTo();
        
        // Ignore very small movements or if player doesn't have both locations
        if (to == null || !lastLocations.containsKey(playerUUID) ||
                (event.getFrom().getX() == to.getX() && 
                 event.getFrom().getY() == to.getY() && 
                 event.getFrom().getZ() == to.getZ())) {
            return;
        }
        
        // Skip checks for players in creative/spectator mode
        if (player.getGameMode().toString().contains("CREATIVE") ||
            player.getGameMode().toString().contains("SPECTATOR")) {
            lastLocations.put(playerUUID, to);
            return;
        }
        
        Location from = lastLocations.get(playerUUID);
        long currentTime = System.currentTimeMillis();
        long timeDelta = currentTime - lastMoveTime.getOrDefault(playerUUID, currentTime);
        
        // Apply latency compensation if enabled
        boolean compensateLag = plugin.getConfigManager().isLagCompensationEnabled();
        double lagFactor = compensateLag ? Math.max(1.0, 1.0 + (player.getPing() / PING_COMPENSATION_MS) * 0.1) : 1.0;
        
        // Check horizontal speed (XZ plane movement)
        double horizontalSpeed = MovementUtils.calculateHorizontalSpeed(from, to);
        double maxHorizontalSpeed = MovementUtils.getMaxHorizontalSpeed(player, 
                plugin.getConfigManager().getMaxHorizontalSpeed()) * lagFactor;
        
        // Adjust speed expectations for certain conditions
        if (MovementUtils.isInLiquid(player)) {
            maxHorizontalSpeed *= 0.8; // Slower in liquids
        }
        
        // Allow higher speeds for elytra flight
        if (player.isGliding()) {
            maxHorizontalSpeed *= 5.0; // Elytra allows much faster horizontal movement
        }
        
        if (horizontalSpeed > maxHorizontalSpeed) {
            // Player is moving too fast horizontally
            plugin.getViolationManager().addViolation(player, "SpeedHack", 
                    "Speed: " + String.format("%.2f", horizontalSpeed) + 
                    " > Max: " + String.format("%.2f", maxHorizontalSpeed));
            
            // Just set the player back to their previous location
            player.teleport(from);
            return;
        }
        
        // Check vertical movement (flight) for all players, with adjusted thresholds for elytra
        if (!MovementUtils.isNearGround(player) && !MovementUtils.isInLiquid(player)) {
            double verticalSpeed = MovementUtils.calculateVerticalSpeed(from, to);
            double maxVerticalSpeed = plugin.getConfigManager().getMaxVerticalSpeed() * lagFactor;
            
            // Apply higher threshold for elytra users, but still check them
            if (player.isGliding()) {
                maxVerticalSpeed *= 3.0; // Elytra can move faster vertically but still has limits
            }
            
            // Only check for abnormal vertical movement (both up and down)
            if (Math.abs(verticalSpeed) > Math.abs(maxVerticalSpeed * 1.5)) {
                // Player is moving vertically too fast (up or down)
                plugin.getViolationManager().addViolation(player, "FlightHack", 
                        "Vertical Speed: " + String.format("%.2f", verticalSpeed) + 
                        " > Max: " + String.format("%.2f", maxVerticalSpeed * 1.5) +
                        (player.isGliding() ? " (Elytra)" : ""));
                
                // Just set the player back to their previous location
                player.teleport(from);
                return;
            }
        }
        
        // Update tracking data
        lastLocations.put(playerUUID, to);
        lastMoveTime.put(playerUUID, currentTime);
    }
} 