package com.jellypudding.velocityGuard.listeners;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.Location;
import org.bukkit.Bukkit;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Listens for movement packets using direct Netty channel access
 * This is the key to making our anti-cheat faster and non-blocking
 */
public class PacketListener {
    
    private final VelocityGuard plugin;
    private final Map<UUID, Channel> playerChannels = new HashMap<>();
    private static final String HANDLER_NAME = "velocity_guard_handler";
    
    public PacketListener(VelocityGuard plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Sets up packet listeners for all online players
     */
    public void inject() {
        // Inject currently online players
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            injectPlayer(player);
        }
        
        plugin.getLogger().info("Packet listeners registered successfully.");
    }
    
    /**
     * Injects a packet interceptor into a player's connection
     */
    public void injectPlayer(Player player) {
        try {
            CraftPlayer craftPlayer = (CraftPlayer) player;
            Channel channel = getChannel(craftPlayer);
            
            if (channel == null) {
                plugin.getLogger().warning("Could not get channel for player " + player.getName());
                return;
            }
            
            // Store for later cleanup
            playerChannels.put(player.getUniqueId(), channel);
            
            // Check if already injected
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                return;
            }
            
            // Add our packet handler to the pipeline
            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    try {
                        // Debug log to confirm packet processing
                        plugin.getLogger().info("VelocityGuard: Processing packet for player " + player.getName() + ": " + msg.getClass().getSimpleName());
                        
                        // Check if it's a position packet by class name (version-safe approach)
                        String packetName = msg.getClass().getSimpleName();
                        
                        // Print the full classname to help debug
                        plugin.getLogger().info("VelocityGuard: Full packet class: " + msg.getClass().getName());
                        
                        // Try directly checking movement across several possible packet names
                        if (packetName.contains("Position") || 
                            packetName.contains("Pos") || 
                            packetName.contains("Move") || 
                            packetName.contains("Look")) {
                            
                            // Get current location as "from"
                            Location from = player.getLocation();
                            
                            // Debug log position packet detection
                            plugin.getLogger().info("VelocityGuard: Movement packet detected for " + player.getName());
                            
                            try {
                                // Get position from packet using reflection
                                double x = getDoubleField(msg, "x", "a");
                                double y = getDoubleField(msg, "y", "b");
                                double z = getDoubleField(msg, "z", "c");
                                
                                // Print available fields to help with debugging
                                plugin.getLogger().info("VelocityGuard: Available fields in packet:");
                                for (Field field : msg.getClass().getDeclaredFields()) {
                                    field.setAccessible(true);
                                    plugin.getLogger().info("  - " + field.getName() + " (" + field.getType().getSimpleName() + ")");
                                }
                                
                                // Get rotation from packet or current rotation
                                float yaw = player.getLocation().getYaw();
                                float pitch = player.getLocation().getPitch();
                                
                                if (packetName.contains("Rot") || packetName.contains("Look")) {
                                    yaw = getFloatField(msg, "yRot", "e");
                                    pitch = getFloatField(msg, "xRot", "d");
                                }
                                
                                // Create destination location
                                Location to = new Location(player.getWorld(), x, y, z, yaw, pitch);
                                
                                // Debug log movement details
                                plugin.getLogger().info("VelocityGuard: " + player.getName() + " movement: " + 
                                    String.format("(%.2f, %.2f, %.2f) -> (%.2f, %.2f, %.2f)", 
                                    from.getX(), from.getY(), from.getZ(), 
                                    to.getX(), to.getY(), to.getZ()));
                                
                                // Queue the movement for async processing
                                plugin.getMovementProcessor().queueMovement(player, from, to);
                            } catch (Exception e) {
                                plugin.getLogger().warning("VelocityGuard: Error extracting packet data: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("VelocityGuard: Error processing packet: " + e.getMessage());
                        e.printStackTrace();
                    }
                    
                    // Always pass the packet along
                    super.channelRead(ctx, msg);
                }
            });
            
        } catch (Exception e) {
            plugin.getLogger().severe("Error injecting player " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Gets a double value from a packet field
     */
    private double getDoubleField(Object packet, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Field field = packet.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.getDouble(packet);
            } catch (Exception ignored) {
                // Try next field name
            }
        }
        return 0.0;
    }
    
    /**
     * Gets a float value from a packet field
     */
    private float getFloatField(Object packet, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Field field = packet.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.getFloat(packet);
            } catch (Exception ignored) {
                // Try next field name
            }
        }
        return 0.0f;
    }
    
    /**
     * Gets the netty channel from a player's connection
     */
    private Channel getChannel(CraftPlayer player) {
        try {
            Object connection = player.getHandle().connection;
            
            // Try to find channel field directly
            for (Field field : connection.getClass().getDeclaredFields()) {
                if (Channel.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return (Channel) field.get(connection);
                }
            }
            
            // If not found, try through the network manager
            Field networkField = null;
            for (Field field : connection.getClass().getDeclaredFields()) {
                if (field.getType().getSimpleName().contains("Connection") || 
                        field.getType().getSimpleName().contains("NetworkManager")) {
                    networkField = field;
                    break;
                }
            }
            
            if (networkField != null) {
                networkField.setAccessible(true);
                Object networkManager = networkField.get(connection);
                
                for (Field field : networkManager.getClass().getDeclaredFields()) {
                    if (Channel.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        return (Channel) field.get(networkManager);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to get channel: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Removes packet listeners
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
} 