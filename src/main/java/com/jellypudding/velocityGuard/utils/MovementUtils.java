package com.jellypudding.velocityGuard.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public class MovementUtils {

    private static final Set<Material> PASSABLE_BLOCKS = new HashSet<>();
    private static final Set<Material> ICE_BLOCKS = new HashSet<>();

    static {
        PASSABLE_BLOCKS.add(Material.AIR);
        PASSABLE_BLOCKS.add(Material.CAVE_AIR);
        PASSABLE_BLOCKS.add(Material.VOID_AIR);
        PASSABLE_BLOCKS.add(Material.WATER);
        PASSABLE_BLOCKS.add(Material.LAVA);

        ICE_BLOCKS.add(Material.ICE);
        ICE_BLOCKS.add(Material.PACKED_ICE);
        ICE_BLOCKS.add(Material.BLUE_ICE);
        ICE_BLOCKS.add(Material.FROSTED_ICE);
    }

    public static double calculateHorizontalDistance(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static int getPlayerPing(Player player) {
        try {
            return player.getPing();
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean isNearGround(Player player) {
        Location loc = player.getLocation();

        // Check a small area below the player.
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

        if (player.isSwimming()) {
            return true;
        }

        // Check current location and below
        Block block = loc.getBlock();
        Block blockBelow = loc.clone().subtract(0, 0.1, 0).getBlock();

        if (block.getType() == Material.WATER || block.getType() == Material.LAVA ||
            blockBelow.getType() == Material.WATER || blockBelow.getType() == Material.LAVA) {
            return true;
        }

        // Check the player's eye location for water.
        Location eyeLoc = player.getEyeLocation();
        Block eyeBlock = eyeLoc.getBlock();
        if (eyeBlock.getType() == Material.WATER || eyeBlock.getType() == Material.LAVA) {
            return true;
        }

        // Check a bit deeper below the player.
        Block deeperBelow = loc.clone().subtract(0, 0.5, 0).getBlock();
        if (deeperBelow.getType() == Material.WATER || deeperBelow.getType() == Material.LAVA) {
            return true;
        }

        // Check slightly around the player's position for water.
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                Block surroundingBlock = loc.clone().add(x, -0.2, z).getBlock();
                if (surroundingBlock.getType() == Material.WATER || surroundingBlock.getType() == Material.LAVA) {
                    return true;
                }
            }
        }

        return false;
    }

    public static boolean isOnIce(Player player) {
        Location loc = player.getLocation();

        // Check block directly below player.
        Block blockBelow = loc.clone().subtract(0, 0.2, 0).getBlock();

        return ICE_BLOCKS.contains(blockBelow.getType());
    }

    public static double getMaxHorizontalSpeed(Player player, double baseSpeed, Long elytraLandingTime,
                                     Long lastDamageTime, Long lastRiptideTime, long currentTime,
                                     double knockbackMultiplier, int knockbackDuration,
                                     double riptideMultiplier, int riptideDuration,
                                     boolean isDragonDamage, boolean isVehicle,
                                     double vehicleSpeedMultiplier, double vehicleIceSpeedMultiplier,
                                     double bufferMultiplier, int ping, boolean latencyCompensationEnabled,
                                     double lowPingCompensation, double mediumPingCompensation,
                                     double highPingCompensation, double veryHighPingCompensation,
                                     double extremePingCompensation, double veryLowPingCompensation,
                                     double ultraPingCompensation, double insanePingCompensation,
                                     double elytraGlidingMultiplier, int elytraLandingDuration) {
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
            maxSpeed *= 0.95;
        }

        if (player.isGliding()) {
            maxSpeed *= elytraGlidingMultiplier;
        }

        if (elytraLandingTime != null && (currentTime - elytraLandingTime < elytraLandingDuration)) {
            maxSpeed *= 3.5;
        }

        // Apply knockback adjustment if player was recently hit.
        if (lastDamageTime != null) {
            long timeSinceHit = currentTime - lastDamageTime;

            if (isDragonDamage) {
                if (timeSinceHit < 5000) {
                    return 500.0;
                }
            }

            else if (timeSinceHit < knockbackDuration) {
                double adjustment = knockbackMultiplier * (1 - (timeSinceHit / (double)knockbackDuration));
                maxSpeed *= (1 + adjustment);
            }
        }

        // Apply riptide adjustment if player recently used trident.
        if (lastRiptideTime != null) {
            long timeSinceRiptide = currentTime - lastRiptideTime;

            if (timeSinceRiptide < riptideDuration) {
                double adjustment = riptideMultiplier * (1 - (timeSinceRiptide / (double)riptideDuration));
                maxSpeed *= (1 + adjustment);
            }
        }

        // Increase speed for vehicles - apply higher multiplier if on ice.
        if (isVehicle) {
            if (isOnIce(player)) {
                maxSpeed *= vehicleIceSpeedMultiplier;
            } else {
                maxSpeed *= vehicleSpeedMultiplier;
            }
        }

        // Apply configurable base buffer multiplier to prevent false positives.
        maxSpeed *= bufferMultiplier;

        // Apply dynamic speed-based buffer for higher speeds.
        // This helps with momentary speed spikes at higher velocities.
        if (maxSpeed > 20.0) {
            // Add additional tolerance for higher speeds (non-linear scaling).
            double dynamicBuffer = 1.0 + ((maxSpeed - 20.0) / 40.0);
            maxSpeed *= dynamicBuffer;
        }

        // Apply latency compensation
        if (latencyCompensationEnabled) {
            double latencyCompensationFactor = 1.0;

            if (ping <= 20) {
                latencyCompensationFactor = 1.0;
            } else if (ping <= 50) {
                latencyCompensationFactor = veryLowPingCompensation;
            } else if (ping <= 100) {
                latencyCompensationFactor = lowPingCompensation;
            } else if (ping <= 200) {
                latencyCompensationFactor = mediumPingCompensation;
            } else if (ping <= 300) {
                latencyCompensationFactor = highPingCompensation;
            } else if (ping <= 500) {
                latencyCompensationFactor = veryHighPingCompensation;
            } else if (ping <= 750) {
                latencyCompensationFactor = extremePingCompensation;
            } else if (ping <= 1000) {
                latencyCompensationFactor = ultraPingCompensation;
            } else {
                latencyCompensationFactor = insanePingCompensation;
            }

            maxSpeed *= latencyCompensationFactor;
        }

        return maxSpeed;
    }

    public static boolean isRidingGhast(Player player) {
        if (player.isInsideVehicle() && player.getVehicle() != null) {
            return player.getVehicle().getType().toString().equals("HAPPY_GHAST");
        }
        return false;
    }

    public static boolean checkFlying(Player player, Location from, Location to,
                                     Map<UUID, Integer> airTicksMap,
                                     boolean debugEnabled, Logger logger) {
        UUID playerId = player.getUniqueId();
        boolean isNearGround = isNearGround(player);
        boolean inWater = isInLiquid(player);

        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            if (debugEnabled && airTicksMap.getOrDefault(playerId, 0) > 25) {
                logger.info(player.getName() + " has levitation effect - ignoring flight checks");
            }
            return false;
        }

        // Reset air ticks if on ground or in water.
        if (isNearGround || inWater) {
            airTicksMap.put(playerId, 0);
            return false;
        } else {
            // Increment air ticks if not on ground.
            int previousAirTicks = airTicksMap.getOrDefault(playerId, 0);
            int currentAirTicks = previousAirTicks + 1;
            airTicksMap.put(playerId, currentAirTicks);

            // Only check for fly cheats if player has been in air for a while (not just jumping).
            // Normal jump apex is around 11-13 ticks.
            if (currentAirTicks > 25) {
                // Check for hovering (staying at same Y level while in air).
                if (Math.abs(to.getY() - from.getY()) < 0.05 && !player.isGliding() && !player.isFlying()) {
                    if (debugEnabled) {
                        logger.info(player.getName() + " potential hover cheat: air ticks=" + currentAirTicks);
                    }
                    return currentAirTicks > 40;
                }

                // Check for ascending in air (only after being in air long enough).
                if (to.getY() > from.getY() && !player.isGliding() && !player.isFlying() && currentAirTicks > 30) {
                    if (debugEnabled) {
                        logger.info(player.getName() + " ascending in air after " + currentAirTicks + " ticks");
                    }
                    return currentAirTicks > 40;
                }
            }
        }

        return false;
    }
}
