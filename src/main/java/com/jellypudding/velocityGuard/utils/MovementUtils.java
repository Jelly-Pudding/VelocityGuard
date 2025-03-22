package com.jellypudding.velocityGuard.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;

public class MovementUtils {

    private static final Set<Material> PASSABLE_BLOCKS = new HashSet<>();
    
    static {
        PASSABLE_BLOCKS.add(Material.AIR);
        PASSABLE_BLOCKS.add(Material.CAVE_AIR);
        PASSABLE_BLOCKS.add(Material.VOID_AIR);
        PASSABLE_BLOCKS.add(Material.WATER);
        PASSABLE_BLOCKS.add(Material.LAVA);
    }
    
    /**
     * Calculates the horizontal speed between two locations
     * 
     * @param from The starting location
     * @param to The ending location
     * @return The horizontal speed (blocks per tick)
     */
    public static double calculateHorizontalSpeed(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
    
    /**
     * Calculates the horizontal distance between two locations
     * 
     * @param from The starting location
     * @param to The ending location
     * @return The horizontal distance in blocks
     */
    public static double calculateHorizontalDistance(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
    
    /**
     * Calculates the vertical speed between two locations
     * 
     * @param from The starting location
     * @param to The ending location
     * @return The vertical speed (blocks per tick)
     */
    public static double calculateVerticalSpeed(Location from, Location to) {
        return to.getY() - from.getY();
    }
    
    /**
     * Gets the maximum allowed horizontal speed for a player
     * based on their potion effects and game state
     * 
     * @param player The player to check
     * @param baseSpeed The base speed limit from config
     * @return The adjusted maximum speed
     */
    public static double getMaxHorizontalSpeed(Player player, double baseSpeed) {
        double maxSpeed = baseSpeed;
        
        // Adjust for speed effect
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            maxSpeed += (level * 0.2 * baseSpeed); // 20% speed boost per level
        }
        
        // Adjust for slowness
        if (player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            int level = player.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier() + 1;
            maxSpeed -= (level * 0.15 * baseSpeed); // 15% speed reduction per level
            if (maxSpeed < 0.1) maxSpeed = 0.1; // Minimum speed
        }
        
        // Adjust for sprinting
        if (player.isSprinting()) {
            maxSpeed *= 1.3; // 30% boost when sprinting
        }
        
        return maxSpeed;
    }
    
    /**
     * Checks if the player is on ground or near ground
     * 
     * @param player The player to check
     * @return True if the player is on or near ground
     */
    public static boolean isNearGround(Player player) {
        Location loc = player.getLocation();
        
        // Check a small area below the player
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                Block block = loc.clone().add(x, -0.5, z).getBlock();
                if (!PASSABLE_BLOCKS.contains(block.getType())) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Checks if the player was near ground at a previous location
     * 
     * @param player The player
     * @param prevLocation The location to check
     * @return True if the location is near ground
     */
    public static boolean wasNearGround(Player player, Location prevLocation) {
        // Check a small area below the previous location
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                Block block = prevLocation.clone().add(x, -0.5, z).getBlock();
                if (!PASSABLE_BLOCKS.contains(block.getType())) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Checks if the player is in a liquid (water or lava)
     * 
     * @param player The player to check
     * @return True if the player is in liquid
     */
    public static boolean isInLiquid(Player player) {
        Location loc = player.getLocation();
        Block block = loc.getBlock();
        Block blockBelow = loc.clone().subtract(0, 0.1, 0).getBlock();
        
        return block.getType() == Material.WATER || 
               block.getType() == Material.LAVA ||
               blockBelow.getType() == Material.WATER || 
               blockBelow.getType() == Material.LAVA;
    }
    
    /**
     * Adjusts a player's velocity to prevent them from moving too fast
     * 
     * @param player The player to adjust
     * @param maxSpeed The maximum allowed speed
     */
    public static void limitPlayerVelocity(Player player, double maxSpeed) {
        Vector velocity = player.getVelocity();
        double speed = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
        
        if (speed > maxSpeed) {
            double scaleFactor = maxSpeed / speed;
            velocity.setX(velocity.getX() * scaleFactor);
            velocity.setZ(velocity.getZ() * scaleFactor);
            player.setVelocity(velocity);
        }
    }
} 