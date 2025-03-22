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

/**
 * Handles player connection events
 */
public class PlayerConnectionListener implements Listener {
    
    private final VelocityGuard plugin;
    
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
        
        // Remove from movement processor
        plugin.getMovementProcessor().unregisterPlayer(player.getUniqueId());
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
        
        Location from = event.getFrom();
        Location to = event.getTo();
        
        // Skip if locations are the same
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        
        Player player = event.getPlayer();
        plugin.getLogger().fine("PlayerMoveEvent backup handler: " + player.getName() + 
                " from " + formatLocation(from) + " to " + formatLocation(to));
        
        // Queue movement for processing
        plugin.getMovementProcessor().queueMovement(player, from, to);
    }
    
    private String formatLocation(Location loc) {
        return String.format("(%.2f, %.2f, %.2f)", loc.getX(), loc.getY(), loc.getZ());
    }
} 