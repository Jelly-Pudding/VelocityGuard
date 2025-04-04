package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

// Direct Minecraft imports
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Listens for movement packets using Netty packet interception - Optimised for 1.21.4
 * Using direct Minecraft imports rather than reflection to avoid module restrictions
 * Also handles teleport events to prevent false positives
 */
public class PacketListener implements Listener {

    private final VelocityGuard plugin;
    private final Map<UUID, Channel> playerChannels = new HashMap<>();
    private static final String HANDLER_NAME = "velocity_guard_handler";

    // Counters for diagnostics
    private final AtomicInteger successfulPackets = new AtomicInteger(0);
    private final AtomicInteger failedPackets = new AtomicInteger(0);

    public PacketListener(VelocityGuard plugin) {
        this.plugin = plugin;
        plugin.getLogger().info("Initialising packet listener for Minecraft 1.21.4 using direct imports");
    }
    
    /**
     * Sets up packet listeners for all online players
     */
    public void inject() {
        int successCount = 0;
        
        // Register event listener for player join/quit
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        
        // Inject currently online players
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (injectPlayer(player)) {
                successCount++;
            }
        }
        
        plugin.getLogger().info("Packet listeners registered for " + successCount + " players");
    }

    /**
     * Handle teleport events to prevent false positives
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        
        Player player = event.getPlayer();
        Location to = event.getTo();
        
        if (plugin.isDebugEnabled()) {
            Location from = event.getFrom();
            plugin.getLogger().info("Player teleport: " + player.getName() + 
                    " from " + String.format("(%.2f, %.2f, %.2f)", from.getX(), from.getY(), from.getZ()) + 
                    " to " + String.format("(%.2f, %.2f, %.2f)", to.getX(), to.getY(), to.getZ()));
        }
        
        // Update the player's last valid location to the teleport destination
        plugin.getMovementChecker().registerPlayer(player);
    }

    /**
     * Handle player joining to inject our packet handler
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        injectPlayer(player);
        plugin.getMovementChecker().registerPlayer(player);
        plugin.getLogger().info("VelocityGuard now tracking " + event.getPlayer().getName());
    }

    /**
     * Handle player quitting to clean up
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        uninjectPlayer(event.getPlayer());
        plugin.getMovementChecker().unregisterPlayer(event.getPlayer().getUniqueId());
    }

    /**
     * Injects a packet interceptor into a player's connection
     * @return true if injection was successful
     */
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

            // Store for later cleanup
            playerChannels.put(player.getUniqueId(), channel);

            // Check if already injected
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                return true;
            }

            // Add our packet handler to the pipeline
            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    try {
                        // Only proceed if it's a movement packet
                        if (msg instanceof ServerboundMovePlayerPacket movePacket) {
                            // Get current location as "from"
                            Location from = player.getLocation();
                            
                            // Extract position data directly from the packet
                            double x = movePacket.hasPos ? movePacket.x : from.getX();
                            double y = movePacket.hasPos ? movePacket.y : from.getY();
                            double z = movePacket.hasPos ? movePacket.z : from.getZ();
                            float yaw = movePacket.hasRot ? movePacket.yRot : from.getYaw();
                            float pitch = movePacket.hasRot ? movePacket.xRot : from.getPitch();
                            
                            boolean positionChanged = movePacket.hasPos;
                            
                            // Create destination location if position changed
                            if (positionChanged) {
                                Location to = new Location(player.getWorld(), x, y, z, yaw, pitch);
                                
                                // Only process if the player actually moved
                                if (from.distanceSquared(to) > 0.001) {
                                    // Count successful packet
                                    successfulPackets.incrementAndGet();

                                    boolean allowed = plugin.getMovementChecker().processMovement(player, from, to);

                                    if (!allowed) {
                                        return; // Don't call super.channelRead - this effectively cancels the packet
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        failedPackets.incrementAndGet();
                        if (plugin.isDebugEnabled()) {
                            plugin.getLogger().warning("Error processing packet: " + e.getMessage());
                        }
                    }
                    
                    // Pass the packet along if it wasn't cancelled
                    super.channelRead(ctx, msg);
                }
            });
            
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("Successfully injected packet handler for " + player.getName());
            }
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().severe("Error injecting player " + player.getName() + ": " + e.getMessage());
            if (plugin.isDebugEnabled()) {
                e.printStackTrace();
            }
            return false;
        }
    }
    
    /**
     * Get the Netty channel for a player
     */
    private Channel getChannel(CraftPlayer player) {
        try {
            ServerPlayer handle = player.getHandle();
            ServerGamePacketListenerImpl connection = handle.connection;
            Connection networkManager = connection.connection;
            return networkManager.channel;
        } catch (Exception e) {
            plugin.getLogger().severe("Error getting channel for player " + player.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Uninjects packet handlers from all players
     */
    public void uninject() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            uninjectPlayer(player);
        }
        plugin.getLogger().info("All packet listeners have been removed");
    }

    /**
     * Removes the packet handler from a player
     */
    public void uninjectPlayer(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        
        // Get channel and remove handler
        Channel channel = playerChannels.remove(playerId);
        if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
            channel.pipeline().remove(HANDLER_NAME);
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("Removed packet handler from " + player.getName());
            }
        }
    }
} 