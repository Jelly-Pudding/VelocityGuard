package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

public class DamageListener implements Listener {

    private final VelocityGuard plugin;

    public DamageListener(VelocityGuard plugin) {
        this.plugin = plugin;
    }

    /**
     * Handle all damage events to detect knockback
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        final Player player = (Player) event.getEntity();

        final boolean isDragonDamage = event instanceof EntityDamageByEntityEvent && 
                ((EntityDamageByEntityEvent) event).getDamager().getType() == EntityType.ENDER_DRAGON;

        // Record the damage immediately to avoid race conditions with movement checks
        plugin.getMovementChecker().recordPlayerDamage(player, isDragonDamage);

        if (plugin.isDebugEnabled()) {
            if (event instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent entityEvent = (EntityDamageByEntityEvent) event;
                plugin.getLogger().info(player.getName() + " was hit by " + 
                        entityEvent.getDamager().getName() + " for " + event.getDamage() + " damage" +
                        (isDragonDamage ? " (dragon damage)" : ""));
            } else if (event.getCause() != EntityDamageEvent.DamageCause.CUSTOM) {
                plugin.getLogger().info(player.getName() + " took damage: " + 
                        event.getCause() + " for " + event.getDamage() + " damage" +
                        (isDragonDamage ? " (dragon damage)" : ""));
            }
        }
    }
}
