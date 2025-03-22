package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
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
     * Backup detection method using Bukkit events
     * This helps catch cheat clients that bypass the packet system
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Skip head rotation only
        if (event.getFrom().getX() == event.getTo().getX() &&
            event.getFrom().getY() == event.getTo().getY() &&
            event.getFrom().getZ() == event.getTo().getZ()) {
            return;
        }
        
        Player player = event.getPlayer();
        
        // Skip creative/spectator mode
        if (player.getGameMode().toString().contains("CREATIVE") ||
            player.getGameMode().toString().contains("SPECTATOR")) {
            return;
        }
        
        // Calculate distance moved
        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double horizontalDistance = Math.sqrt(dx*dx + dz*dz);
        
        // Check for fast movement (anything over 5 blocks is almost certainly cheating)
        if (horizontalDistance > 5.0) {
            plugin.getLogger().warning("VelocityGuard BACKUP: " + player.getName() + 
                    " moved " + String.format("%.2f", horizontalDistance) + " blocks horizontally in one tick!");
            
            // Cancel the event and teleport back
            event.setCancelled(true);
            player.teleport(event.getFrom());
            
            // Record violation
            plugin.getViolationManager().addViolation(player, "SpeedHack", 
                    "Fast movement: " + String.format("%.2f", horizontalDistance) + " blocks in one tick");
        }
    }
} 