package com.jellypudding.velocityGuard.processors;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class PhysicsEngine {

    private PhysicsEngine() {}

    // Air horizontal friction applied each tick while the player is airborne.
    public static final double AIR_FRICTION = 0.91;

    // Ground friction for a normal (non-ice) solid block  (0.6 × 0.91).
    public static final double NORMAL_GROUND_FRICTION = 0.546;

    // Minecraft MOVEMENT_SPEED attribute values (base × sprint modifier 1.3).
    private static final double BASE_SPRINT_SPEED = 0.13;
    private static final double BASE_WALK_SPEED   = 0.10;

    // Sprint-jump horizontal velocity boost added to stored velocity.
    private static final double SPRINT_JUMP_BOOST_VELOCITY = 0.2;

    // Maximum input added per tick while in the air.
    private static final double AIR_INPUT_SPRINT = 0.026;
    private static final double AIR_INPUT_WALK   = 0.020;

    // vy_new = (vy - GRAVITY) * VERTICAL_DRAG  each tick.
    public static final double GRAVITY           = 0.08;
    public static final double VERTICAL_DRAG     = 0.98;
    // Base upward velocity set by a jump (JUMP_STRENGTH attribute default = 0.42).
    // Jump Boost potion adds 0.1 * (amplifier + 1) on top.
    public static final double BASE_JUMP_VELOCITY = 0.42;

    // Water horizontal drag and base swim speed.
    private static final double WATER_DRAG  = 0.800;
    private static final double WATER_INPUT = 0.040;

    // Flat per-tick speed cap for all vehicle movement.
    public static final double MAX_VEHICLE_SPEED = 0.85;

    // Elytra horizontal physics.
    private static final double ELYTRA_DRAG             = 0.99;
    private static final double ELYTRA_DIVE_FACTOR      = 0.20;
    public static final double ELYTRA_FIREWORK_TERMINAL = 3.0;

    // Max horizontal speed a gliding player could legitimately reach next tick.
    public static double simulateElytraHorizontal(double currentHorizontal, double prevVelocityY) {
        double dive     = Math.max(0.0, -prevVelocityY) * ELYTRA_DIVE_FACTOR;
        double firework = Math.max(0.0, ELYTRA_FIREWORK_TERMINAL - currentHorizontal) * 0.5 + 0.15;
        return (currentHorizontal + dive) * ELYTRA_DRAG + firework;
    }

    private static final float SLIPPERINESS_DEFAULT  = 0.600f;
    private static final float SLIPPERINESS_ICE      = 0.980f;
    private static final float SLIPPERINESS_BLUE_ICE = 0.989f;
    private static final float SLIPPERINESS_SLIME    = 0.800f;

    public static double simulateOneTick(double currentSpeed, boolean onGround,
                                         boolean inLiquid, boolean sprinting,
                                         double speedModifier, float slipperiness,
                                         boolean jumpTick) {
        if (inLiquid) {
            return (currentSpeed + WATER_INPUT * speedModifier) * WATER_DRAG;
        }

        if (onGround || jumpTick) {
            double frictionFactor = (double) slipperiness * 0.91;
            double fCubed         = (double) slipperiness * slipperiness * slipperiness;
            double baseSpeed      = (sprinting ? BASE_SPRINT_SPEED : BASE_WALK_SPEED) * speedModifier;
            double inputAccel     = 0.98 * baseSpeed * 0.21600002 / (fCubed * frictionFactor);
            double boost = (jumpTick && sprinting) ? (SPRINT_JUMP_BOOST_VELOCITY / frictionFactor) : 0.0;
            return (currentSpeed + inputAccel + boost) * frictionFactor;
        }

        double airInput = (sprinting ? AIR_INPUT_SPRINT : AIR_INPUT_WALK) * speedModifier;
        return (currentSpeed + airInput) * AIR_FRICTION;
    }

    public static float getBlockSlipperiness(Location loc) {
        Block block = loc.clone().subtract(0, 0.1, 0).getBlock();
        return switch (block.getType()) {
            case ICE, PACKED_ICE, FROSTED_ICE -> SLIPPERINESS_ICE;
            case BLUE_ICE                      -> SLIPPERINESS_BLUE_ICE;
            case SLIME_BLOCK                   -> SLIPPERINESS_SLIME;
            default                            -> SLIPPERINESS_DEFAULT;
        };
    }

    public static boolean isNearGroundAt(Location loc) {
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                Block b = loc.clone().add(x, -0.65, z).getBlock();
                if (isSolid(b)) return true;
            }
        }
        return false;
    }

    private static boolean isSolid(Block b) {
        return switch (b.getType()) {
            case AIR, CAVE_AIR, VOID_AIR, WATER, LAVA -> false;
            default -> true;
        };
    }

    public static double getJumpVelocity(Player player) {
        double v = BASE_JUMP_VELOCITY;
        PotionEffect boost = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        if (boost != null) v += 0.1 * (boost.getAmplifier() + 1);
        return v;
    }

    public static double getPotionSpeedModifier(Player player) {
        double mod = 1.0;

        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) {
            mod *= (1.0 + (speed.getAmplifier() + 1) * 0.2);
        }

        PotionEffect slow = player.getPotionEffect(PotionEffectType.SLOWNESS);
        if (slow != null) {
            mod *= Math.max(0.05, 1.0 - (slow.getAmplifier() + 1) * 0.15);
        }

        return mod;
    }
}
