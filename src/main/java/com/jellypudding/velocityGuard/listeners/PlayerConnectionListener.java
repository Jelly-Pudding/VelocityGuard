package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
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

/**
 * Handles player connection events
 */
public class PlayerConnectionListener implements Listener {
    
    private final VelocityGuard plugin;
    
    // Track when players last teleported to avoid movement checks right after teleport
    private final Map<UUID, Long> lastTeleportTimes = new HashMap<>();
    private static final long TELEPORT_COOLDOWN_MS = 1000; // 1 second cooldown after teleport
    
    public PlayerConnectionListener(VelocityGuard plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Add the player to our movement processor for tracking
        plugin.getMovementProcessor().registerPlayer(player);
        
        // Add packet listener to the player
        plugin.getPacketListener().injectPlayer(player);
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        // Remove packet listener from player
        plugin.getPacketListener().uninjectPlayer(player);
        
        // Remove from movement processor and teleport tracking
        plugin.getMovementProcessor().unregisterPlayer(player.getUniqueId());
        lastTeleportTimes.remove(player.getUniqueId());
    }
    
    /**
     * Handle teleport events to prevent false positives
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        
        // Record teleport time to avoid checking movements right after teleport
        lastTeleportTimes.put(player.getUniqueId(), System.currentTimeMillis());
        
        // Update the player's last location directly to avoid false positives
        plugin.getLogger().info("Player teleport event for " + player.getName() + 
                " from " + formatLocation(from) + " to " + formatLocation(to) + 
                " (" + event.getCause() + ")");
        
        // Update player's last known position without checking
        plugin.getMovementProcessor().registerPlayer(player);
    }
    
    /**
     * Backup event handler for movement if packet handling fails
     * This runs at the MONITOR priority to ensure it doesn't interfere
     * with other plugins that might cancel the event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Skip if event is cancelled or player never actually moved
        if (event.isCancelled()) return;
        
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Skip movement check if player teleported recently
        long currentTime = System.currentTimeMillis();
        long lastTeleportTime = lastTeleportTimes.getOrDefault(playerId, 0L);
        
        if (currentTime - lastTeleportTime < TELEPORT_COOLDOWN_MS) {
            // Just update location without checking if within cooldown period
            plugin.getMovementProcessor().registerPlayer(player);
            return;
        }
        
        Location from = event.getFrom();
        Location to = event.getTo();
        
        // Skip if locations are the same
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        

        plugin.getLogger().info("PlayerMoveEvent backup handler triggered for " + player.getName());
        
        // Queue movement for processing
        plugin.getMovementProcessor().queueMovement(player, from, to);
    }
    
    private String formatLocation(Location loc) {
        return String.format("(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }
} 