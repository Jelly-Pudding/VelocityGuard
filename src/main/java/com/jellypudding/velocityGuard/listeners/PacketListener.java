package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Listens for movement packets using Netty packet interception.
public class PacketListener implements Listener {

    private final VelocityGuard plugin;
    private final Map<UUID, Channel> playerChannels = new ConcurrentHashMap<>();
    private static final String HANDLER_NAME = "velocity_guard_handler";

    // Counters for diagnostics
    private final AtomicInteger successfulPackets = new AtomicInteger(0);
    private final AtomicInteger failedPackets = new AtomicInteger(0);
    private final AtomicInteger vehiclePackets = new AtomicInteger(0);

    private final ExecutorService asyncExecutor;

    public PacketListener(VelocityGuard plugin) {
        this.plugin = plugin;
        this.asyncExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "VelocityGuard-Worker");
            thread.setDaemon(true);
            return thread;
        });
        plugin.getLogger().info("Initialising packet listener");
    }

    public void inject() {
        // Register event listener for player join/quit.
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Inject currently online players - process in chunks to minimize main thread impact.
        final Player[] onlinePlayers = plugin.getServer().getOnlinePlayers().toArray(new Player[0]);

        new BukkitRunnable() {
            int index = 0;
            final int BATCH_SIZE = 10;

            @Override
            public void run() {
                int processed = 0;
                while (index < onlinePlayers.length && processed < BATCH_SIZE) {
                    Player player = onlinePlayers[index++];
                    if (injectPlayer(player)) {
                        processed++;
                    }
                }

                if (index >= onlinePlayers.length) {
                    plugin.getLogger().info("Packet listeners registered for " + index + " players");
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();

        asyncExecutor.execute(() -> {
            injectPlayer(player);

            new BukkitRunnable() {
                @Override
                public void run() {
                    plugin.getMovementChecker().registerPlayer(player);
                }
            }.runTask(plugin);

            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("VelocityGuard now tracking " + player.getName());
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();

        asyncExecutor.execute(() -> {
            uninjectPlayer(player);

            new BukkitRunnable() {
                @Override
                public void run() {
                    plugin.getMovementChecker().unregisterPlayer(playerId);
                }
            }.runTask(plugin);
        });
    }

    public boolean injectPlayer(Player player) {
        try {
            if (!(player instanceof CraftPlayer)) {
                plugin.getLogger().warning("Player " + player.getName() + " is not a CraftPlayer, cannot inject");
                return false;
            }

            CraftPlayer craftPlayer = (CraftPlayer) player;
            Channel channel = getChannel(craftPlayer);

            if (channel == null) {
                plugin.getLogger().warning("Could not get channel for player " + player.getName());
                return false;
            }

            playerChannels.put(player.getUniqueId(), channel);

            if (channel.pipeline().get(HANDLER_NAME) != null) {
                return true;
            }

            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    try {
                        // Handle regular player movement.
                        if (msg instanceof ServerboundMovePlayerPacket movePacket) {
                            // Only process if position has changed.
                            if (movePacket.hasPos) {
                                double x = movePacket.x;
                                double y = movePacket.y;
                                double z = movePacket.z;

                                Location from = player.getLocation();
                                Location to = new Location(player.getWorld(), x, y, z);

                                if (from.distanceSquared(to) > 0.001) {
                                    successfulPackets.incrementAndGet();

                                    boolean allowed = plugin.getMovementChecker().processMovement(player, from, to, false);

                                    if (!allowed) {
                                        // Don't call super.channelRead - this effectively cancels the packet.
                                        return;
                                    }
                                }
                            }
                        }
                        // Handle vehicle movement
                        else if (msg instanceof ServerboundMoveVehiclePacket vehiclePacket) {
                            // Only process if player is actually in a vehicle.
                            Entity vehicle = player.getVehicle();
                            if (vehicle != null) {
                                vehiclePackets.incrementAndGet();

                                double packetX = vehiclePacket.position().x;
                                double packetY = vehiclePacket.position().y;
                                double packetZ = vehiclePacket.position().z;

                                Location vehicleLocation = vehicle.getLocation();

                                double dx = packetX - vehicleLocation.getX();
                                double dy = packetY - vehicleLocation.getY();
                                double dz = packetZ - vehicleLocation.getZ();

                                if (dx*dx + dy*dy + dz*dz > 0.001) {
                                    Location playerFrom = player.getLocation();
                                    Location playerTo = playerFrom.clone().add(dx, dy, dz);

                                    boolean allowed = plugin.getMovementChecker().processMovement(player, playerFrom, playerTo, true);

                                    if (!allowed) {
                                        // Don't call super.channelRead - this effectively cancels the packet.
                                        return;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        failedPackets.incrementAndGet();
                        if (plugin.isDebugEnabled()) {
                            asyncExecutor.execute(() -> {
                                plugin.getLogger().warning("Error processing packet: " + e.getMessage());
                            });
                        }
                    }

                    // Pass the packet along if it wasn't cancelled...
                    super.channelRead(ctx, msg);
                }
            });

            if (plugin.isDebugEnabled()) {
                asyncExecutor.execute(() -> {
                    plugin.getLogger().info("Successfully injected packet handler for " + player.getName());
                });
            }
            return true;

        } catch (Exception e) {
            final String errorMsg = e.getMessage();
            asyncExecutor.execute(() -> {
                plugin.getLogger().severe("Error injecting player " + player.getName() + ": " + errorMsg);
                if (plugin.isDebugEnabled()) {
                    e.printStackTrace();
                }
            });
            return false;
        }
    }

    private Channel getChannel(CraftPlayer player) {
        try {
            ServerPlayer handle = player.getHandle();
            ServerGamePacketListenerImpl connection = handle.connection;
            Connection networkManager = connection.connection;
            return networkManager.channel;
        } catch (Exception e) {
            final String errorMsg = e.getMessage();
            asyncExecutor.execute(() -> {
                plugin.getLogger().severe("Error getting channel for player " + player.getName() + ": " + errorMsg);
            });
            return null;
        }
    }

    public void uninject() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            uninjectPlayer(player);
        }

        asyncExecutor.shutdown();

        plugin.getLogger().info("All packet listeners have been removed");
    }

    public void uninjectPlayer(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();

        Channel channel = playerChannels.remove(playerId);
        if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
            channel.pipeline().remove(HANDLER_NAME);
            if (plugin.isDebugEnabled()) {
                asyncExecutor.execute(() -> {
                    plugin.getLogger().info("Removed packet handler from " + player.getName());
                });
            }
        }
    }
}
