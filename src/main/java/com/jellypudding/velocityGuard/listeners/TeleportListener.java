package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public class TeleportListener implements Listener {

    private final VelocityGuard plugin;

    public TeleportListener(VelocityGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;

        final Player player = event.getPlayer();
        final Location to = event.getTo();
        if (to == null) return;

        if (plugin.isDebugEnabled()) {
            final Location from = event.getFrom();
            plugin.getLogger().info("Player teleport: " + player.getName() +
                    " from " + String.format("(%.2f, %.2f, %.2f)", from.getX(), from.getY(), from.getZ()) +
                    " to " + String.format("(%.2f, %.2f, %.2f)", to.getX(), to.getY(), to.getZ()));
        }

        // Route through the GUARDED resetPlayerState (NOT registerPlayer). This
        // listener previously called registerPlayer, whose unconditional reset()
        // set awaitingSetback=false and opened a 500ms settle window on EVERY
        // teleport - including our own setback teleports. That killed the setback
        // enforcement machine each time it fired: during the settle window the
        // cheat kept flying client-side, then settle expiry accepted the first
        // far-ahead packet as a fresh anchor, so the "setback" target marched
        // forward and the player kept all the ground. resetPlayerState is guarded
        // to detect our own setback teleport (destination == setbackTarget) and
        // leave the machine running. PacketListener handles the same event the
        // same way; both calling this is idempotent.
        plugin.getMovementChecker().resetPlayerState(player, to);
    }
}
