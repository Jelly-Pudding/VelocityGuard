package com.jellypudding.velocityGuard.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.Set;
import java.util.logging.Logger;

public class MovementUtils {

    private static final Set<Material> PASSABLE = Set.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.WATER, Material.LAVA);

    private static final Set<Material> ICE_TYPES = Set.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    public static boolean isNearGround(Player player) {
        return isNearGroundAt(player.getLocation());
    }

    public static boolean isNearGroundAt(Location loc) {
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                Block b = loc.clone().add(x, -0.5, z).getBlock();
                if (!PASSABLE.contains(b.getType())) return true;
            }
        }
        return false;
    }

    public static boolean isInLiquid(Player player) {
        if (player.isSwimming()) return true;

        Location loc  = player.getLocation();
        Block block   = loc.getBlock();
        Block below   = loc.clone().subtract(0, 0.1, 0).getBlock();

        if (isLiquid(block) || isLiquid(below)) return true;
        if (isLiquid(player.getEyeLocation().getBlock())) return true;
        if (isLiquid(loc.clone().subtract(0, 0.5, 0).getBlock())) return true;

        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                if (isLiquid(loc.clone().add(x, -0.2, z).getBlock())) return true;
            }
        }
        return false;
    }

    private static boolean isLiquid(Block b) {
        return b.getType() == Material.WATER || b.getType() == Material.LAVA;
    }

    public static boolean isOnIce(Player player) {
        Block b = player.getLocation().clone().subtract(0, 0.2, 0).getBlock();
        return ICE_TYPES.contains(b.getType());
    }

    public static boolean isRidingGhast(Player player) {
        if (!player.isInsideVehicle() || player.getVehicle() == null) return false;
        return player.getVehicle().getType() == EntityType.HAPPY_GHAST;
    }

    public record FlightResult(int newAirTicks, boolean violation) {}

    public static FlightResult checkFlying(Player player, Location from, Location to,
                                           int currentAirTicks, boolean debugEnabled,
                                           Logger logger, int violationThreshold) {
        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            return new FlightResult(currentAirTicks, false);
        }

        if (isNearGround(player) || isInLiquid(player)) {
            return new FlightResult(0, false);
        }

        int ticks = currentAirTicks + 1;

        int hoverStart  = Math.max(15, Math.min(25, violationThreshold - 5));
        int ascendStart = Math.max(15, Math.min(30, violationThreshold));

        if (ticks > hoverStart && !player.isGliding() && !player.isFlying()) {
            // Hovering: Y barely changes while airborne
            if (Math.abs(to.getY() - from.getY()) < 0.05) {
                if (debugEnabled) logger.info(player.getName()
                        + " potential hover - airTicks=" + ticks);
                return new FlightResult(ticks, ticks >= violationThreshold);
            }
            // Ascending: moving upward past the jump apex
            if (to.getY() > from.getY() && ticks >= ascendStart) {
                if (debugEnabled) logger.info(player.getName()
                        + " ascending in air - airTicks=" + ticks);
                return new FlightResult(ticks, ticks >= violationThreshold);
            }
        }

        return new FlightResult(ticks, false);
    }
}
