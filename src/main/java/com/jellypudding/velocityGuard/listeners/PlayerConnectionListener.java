package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles player connection events only (join, quit, teleport)
 * All movement detection is done through packet listeners
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
        
        plugin.getLogger().info("VelocityGuard now tracking " + player.getName());
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        // Remove packet listener from player
        plugin.getPacketListener().uninjectPlayer(player);
        
        // Remove from movement processor and teleport tracking
        plugin.getMovementProcessor().unregisterPlayer(player.getUniqueId());
        lastTeleportTimes.remove(player.getUniqueId());
        
        plugin.getLogger().info("VelocityGuard stopped tracking " + player.getName());
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
    
    private String formatLocation(Location loc) {
        return String.format("(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }
} 