package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

// Direct Minecraft imports
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Listens for movement packets using Netty packet interception - Optimized for 1.21.4
 * Using direct Minecraft imports rather than reflection to avoid module restrictions
 */
public class PacketListener implements Listener {
    
    private final VelocityGuard plugin;
    private final Map<UUID, Channel> playerChannels = new HashMap<>();
    private static final String HANDLER_NAME = "velocity_guard_handler";
    
    // Counters for diagnostics
    private final AtomicInteger successfulPackets = new AtomicInteger(0);
    private final AtomicInteger failedPackets = new AtomicInteger(0);
    
    // Track whether we're on the first diagnostic check
    private boolean isFirstDiagnosticCheck = true;
    
    public PacketListener(VelocityGuard plugin) {
        this.plugin = plugin;
        plugin.getLogger().info("Initializing packet listener for Minecraft 1.21.4 using direct imports");
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
        
        // Schedule the first diagnostic report after 10 seconds
        // After this initial check, diagnostics will run every 30 seconds
        Bukkit.getAsyncScheduler().runDelayed(plugin, scheduledTask -> {
            logDiagnostics();
        }, 10, java.util.concurrent.TimeUnit.SECONDS);
    }
    
    /**
     * Handle player joining to inject our packet handler
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        injectPlayer(event.getPlayer());
        plugin.getLogger().info("VelocityGuard now tracking " + event.getPlayer().getName());
    }
    
    /**
     * Handle player quitting to clean up
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        uninjectPlayer(event.getPlayer());
    }
    
    /**
     * Log the current status of the packet listener
     */
    public void logDiagnostics() {
        int playersTracked = playerChannels.size();
        
        // Calculate packets per minute
        double packetsPerMinute = successfulPackets.get() / 0.5; // 30 seconds = 0.5 minutes

        // Determine if the packet listener is working properly
        boolean isWorking = successfulPackets.get() > 0;
        
        // Check if status has changed for logging purposes
        boolean statusChanged = isWorking != plugin.isPacketListenerWorking();
        
        // Update the plugin about our status
        plugin.setPacketListenerWorking(isWorking);
        
        // Log status information if debug is enabled
        if (plugin.isDebugEnabled()) {
            // Always log detailed diagnostics in debug mode
            plugin.getLogger().info("VelocityGuard Asynchronous Processing Status:");
            plugin.getLogger().info(" - Players tracked: " + playersTracked);
            plugin.getLogger().info(" - Packets processed per minute: " + String.format("%.2f", packetsPerMinute));
            plugin.getLogger().info(" - Failed packet attempts: " + failedPackets.get());
            
            // Log operational status
            if (isWorking) {
                plugin.getLogger().info(" - Status: OPERATIONAL - All movement checks running asynchronously");
                
                // Log status change specifically
                if (statusChanged) {
                    plugin.getLogger().info("Packet listener is now working correctly");
                }
            } else {
                plugin.getLogger().warning(" - Status: WARNING - No movement packets detected yet");
                plugin.getLogger().warning(" - This could be because no player has moved yet");
                plugin.getLogger().warning(" - Plugin will continue operating normally");
                
                // Log status change specifically
                if (statusChanged) {
                    plugin.getLogger().warning("Packet listener reported non-operational status");
                    plugin.getLogger().warning("This may be temporary - will continue monitoring");
                }
                
                // Print some debug info about player connections
                if (!playerChannels.isEmpty() && Bukkit.getOnlinePlayers().size() > 0) {
                    try {
                        Player randomPlayer = Bukkit.getOnlinePlayers().iterator().next();
                        CraftPlayer craftPlayer = (CraftPlayer) randomPlayer;
                        ServerPlayer handle = craftPlayer.getHandle();
                        plugin.getLogger().warning(" - Player handle class: " + handle.getClass().getName());
                        plugin.getLogger().warning(" - Connection class: " + handle.connection.getClass().getName());
                    } catch (Exception e) {
                        plugin.getLogger().warning(" - Error debugging player: " + e.getMessage());
                    }
                }
            }
        }
        
        // Reset counters for next diagnostic interval
        successfulPackets.set(0);
        failedPackets.set(0);
        
        // Schedule the next diagnostic run every 30 seconds
        Bukkit.getAsyncScheduler().runDelayed(plugin, scheduledTask -> {
            logDiagnostics();
        }, 30, java.util.concurrent.TimeUnit.SECONDS);
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
                        // Get the packet class name
                        String packetName = msg.getClass().getSimpleName();
                        
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
                            boolean lookChanged = movePacket.hasRot;
                            
                            // Create destination location if something changed
                            if (positionChanged || lookChanged) {
                                Location to = new Location(player.getWorld(), x, y, z, yaw, pitch);
                                
                                // Only process if the player actually moved
                                if (positionChanged && from.distanceSquared(to) > 0.001 || 
                                    lookChanged && (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch())) {
                                    // Count successful packet
                                    successfulPackets.incrementAndGet();
                                    
                                    // Queue the movement for async processing
                                    // This is the important part - we're not processing on the main thread
                                    plugin.getMovementProcessor().queueMovement(player, from, to);
                                }
                            }
                        }
                    } catch (Exception e) {
                        failedPackets.incrementAndGet();
                        if (plugin.isDebugEnabled()) {
                            plugin.getLogger().warning("Error processing packet: " + e.getMessage());
                        }
                    }
                    
                    // Always pass the packet along
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
     * Gets the Netty channel from a player's connection for Minecraft 1.21.4
     * Using direct access to Minecraft classes instead of reflection
     */
    private Channel getChannel(CraftPlayer player) {
        try {
            // Get the ServerPlayer (EntityPlayer) handle
            ServerPlayer serverPlayer = player.getHandle();
            
            // Get the connection (ServerGamePacketListenerImpl in 1.21.4)
            ServerGamePacketListenerImpl connection = serverPlayer.connection;
            
            // Access the underlying Connection object (previously NetworkManager)
            // In 1.21.4, it's directly accessible as a field named 'connection'
            Connection networkConnection = connection.connection;
            
            // Get the channel directly
            Channel channel = networkConnection.channel;
            
            if (channel == null) {
                throw new Exception("Channel is null on network connection");
            }
            
            return channel;
            
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to get channel: " + e.getMessage());
            if (plugin.isDebugEnabled()) {
                e.printStackTrace();
            }
            return null;
        }
    }
    
    /**
     * Removes packet listeners when plugin is disabled
     */
    public void uninject() {
        // Remove handlers from all players
        for (Map.Entry<UUID, Channel> entry : playerChannels.entrySet()) {
            Channel channel = entry.getValue();
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                try {
                    channel.pipeline().remove(HANDLER_NAME);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error removing handler: " + e.getMessage());
                }
            }
        }
        
        playerChannels.clear();
    }
    
    /**
     * Uninjects a specific player when they leave
     */
    public void uninjectPlayer(Player player) {
        Channel channel = playerChannels.remove(player.getUniqueId());
        
        if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
            try {
                channel.pipeline().remove(HANDLER_NAME);
            } catch (Exception e) {
                plugin.getLogger().warning("Error removing handler for " + player.getName() + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * @return The number of successful packets processed
     */
    public int getSuccessfulPacketsCount() {
        return successfulPackets.get();
    }
    
    /**
     * @return The number of failed packet processing attempts
     */
    public int getFailedPacketsCount() {
        return failedPackets.get();
    }
} 