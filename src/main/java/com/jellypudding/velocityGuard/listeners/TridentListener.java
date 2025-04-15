package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRiptideEvent;

public class TridentListener implements Listener {

    private final VelocityGuard plugin;

    public TridentListener(VelocityGuard plugin) {
        this.plugin = plugin;
    }

    // This event is triggered when a player launches with a trident that has the Riptide enchantment.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerRiptide(PlayerRiptideEvent event) {
        Player player = event.getPlayer();

        plugin.getMovementChecker().recordRiptideUse(player);

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info(player.getName() + " used Riptide with a trident. Adjusting speed threshold.");
        }
    }
}
