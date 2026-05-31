package com.jellypudding.velocityGuard.managers;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.GameMode;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.server.level.ServerPlayer;

public class TransactionManager {

    private final VelocityGuard plugin;
    private BukkitTask task;

    public TransactionManager(VelocityGuard plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    sendTransaction(player);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void sendTransaction(Player player) {
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        if (!(player instanceof CraftPlayer craftPlayer)) return;

        // Reserve an id and record its send-time before actually sending.
        long sendNano = System.nanoTime();
        int id = plugin.getMovementChecker()
                .registerOutgoingTransaction(player.getUniqueId(), sendNano);
        if (id == Integer.MIN_VALUE) return; // player not tracked yet

        try {
            ServerPlayer handle = craftPlayer.getHandle();
            handle.connection.send(new ClientboundPingPacket(id));
        } catch (Exception e) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().warning("Failed to send transaction to "
                        + player.getName() + ": " + e.getMessage());
            }
        }
    }
}
