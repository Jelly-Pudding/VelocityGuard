package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class TeleportListener implements Listener {

    private final VelocityGuard plugin;

    public TeleportListener(VelocityGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;

        final Player player = event.getPlayer();
        final Location to = event.getTo().clone();

        if (plugin.isDebugEnabled()) {
            final Location from = event.getFrom().clone();
            plugin.getLogger().info("Player teleport: " + player.getName() + 
                    " from " + String.format("(%.2f, %.2f, %.2f)", from.getX(), from.getY(), from.getZ()) +
                    " to " + String.format("(%.2f, %.2f, %.2f)", to.getX(), to.getY(), to.getZ()));
        }

        // Prevents false positives from teleport events.
        new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getMovementChecker().registerPlayer(player);
            }
        }.runTask(plugin);
    }
}
