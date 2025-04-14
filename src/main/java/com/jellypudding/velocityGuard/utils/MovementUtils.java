package com.jellypudding.velocityGuard.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

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

    public static double calculateHorizontalDistance(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

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
     * Get the maximum allowed speed for a player with all applicable modifiers
     * @param player The player to check
     * @param baseSpeed The base speed from config
     * @param elytraLandingTime When the player landed from elytra flight (null if not applicable)
     * @param currentTime The current time in milliseconds
     * @return The maximum allowed speed with all modifiers applied
     */
    public static double getMaxHorizontalSpeed(Player player, double baseSpeed, Long elytraLandingTime, long currentTime) {
        double maxSpeed = baseSpeed;

        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            maxSpeed += (level * 0.2 * baseSpeed);
        }

        if (player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            int level = player.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier() + 1;
            maxSpeed -= (level * 0.15 * baseSpeed);
            if (maxSpeed < 0.1) maxSpeed = 0.1;
        }

        if (player.isSprinting()) {
            maxSpeed *= 1.3;
        }

        if (isInLiquid(player)) {
            maxSpeed *= 0.8;
        }

        if (player.isGliding()) {
            maxSpeed *= 5.0;
        }

        if (elytraLandingTime != null && (currentTime - elytraLandingTime < 1500)) {
            maxSpeed *= 2.9;
        }

        return maxSpeed * 1.2;
    }
} 