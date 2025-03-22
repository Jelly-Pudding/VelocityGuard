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
                        // Get the packet class name
                        String packetName = msg.getClass().getSimpleName();
                        
                        // Only log if it's a movement packet to reduce spam
                        if (packetName.contains("Position") || 
                            packetName.contains("Pos") || 
                            packetName.contains("Move") || 
                            packetName.contains("Look")) {
                            
                            // Only log occasionally
                            boolean shouldLog = Math.random() < 0.05;
                            
                            if (shouldLog) {
                                plugin.getLogger().info("VelocityGuard: Movement packet detected for " + player.getName() + ": " + packetName);
                            }
                            
                            // Get current location as "from"
                            Location from = player.getLocation();
                            Location to = null;
                            
                            if (shouldLog) {
                                plugin.getLogger().info("Packet handler triggered for " + player.getName() + ": " + packetName);
                            }
                            
                            try {
                                // Try to extract position using different common field names
                                double x = 0, y = 0, z = 0;
                                boolean positionFound = false;
                                
                                // Get all fields to check available ones for debugging
                                Field[] fields = msg.getClass().getDeclaredFields();
                                for (Field field : fields) {
                                    field.setAccessible(true);
                                    String fieldName = field.getName();
                                    Class<?> fieldType = field.getType();
                                    
                                    // Only log fields if we're actively debugging
                                    if (shouldLog) {
                                        plugin.getLogger().info("Field: " + fieldName + " (" + fieldType.getName() + ")");
                                    }
                                    
                                    // Check if this is a position field
                                    if (fieldType == double.class) {
                                        if (fieldName.equals("x") || fieldName.equals("a")) {
                                            x = field.getDouble(msg);
                                            positionFound = true;
                                        } else if (fieldName.equals("y") || fieldName.equals("b")) {
                                            y = field.getDouble(msg);
                                            positionFound = true;
                                        } else if (fieldName.equals("z") || fieldName.equals("c")) {
                                            z = field.getDouble(msg);
                                            positionFound = true;
                                        }
                                    }
                                }
                                
                                // If we found position data
                                if (positionFound) {
                                    // Get rotation from current location if not in packet
                                    float yaw = from.getYaw();
                                    float pitch = from.getPitch();
                                    
                                    // Create destination location
                                    to = new Location(player.getWorld(), x, y, z, yaw, pitch);
                                    
                                    // Log the movement
                                    if (shouldLog) {
                                        plugin.getLogger().info("VelocityGuard: " + player.getName() + " movement from packet: " + 
                                            String.format("(%.2f, %.2f, %.2f) -> (%.2f, %.2f, %.2f)", 
                                            from.getX(), from.getY(), from.getZ(), 
                                            to.getX(), to.getY(), to.getZ()));
                                    }
                                    
                                    // Queue the movement for async processing
                                    plugin.getMovementProcessor().queueMovement(player, from, to);
                                } else {
                                    // Fallback: Check if the packet has a member that contains Position info
                                    for (Field field : fields) {
                                        field.setAccessible(true);
                                        Object fieldValue = field.get(msg);
                                        
                                        if (fieldValue != null && 
                                            (field.getName().contains("pos") || field.getName().contains("Pos"))) {
                                            plugin.getLogger().info("Found possible position object: " + field.getName());
                                            
                                            // Try to extract x, y, z from this object
                                            // This is a fallback approach
                                            for (Field posField : fieldValue.getClass().getDeclaredFields()) {
                                                posField.setAccessible(true);
                                                String posFieldName = posField.getName();
                                                plugin.getLogger().info("Position field: " + posFieldName);
                                            }
                                        }
                                    }
                                }
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