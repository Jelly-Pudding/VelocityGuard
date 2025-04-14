package com.jellypudding.velocityGuard.processors;

import com.jellypudding.velocityGuard.VelocityGuard;
import com.jellypudding.velocityGuard.utils.MovementUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class MovementChecker {

    private final VelocityGuard plugin;

    // Track how long players have been in the air
    private final Map<UUID, Integer> airTicks = new ConcurrentHashMap<>();

    // Track recent movements to detect patterns
    private final Map<UUID, Queue<Double>> recentSpeeds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveTime = new ConcurrentHashMap<>();

    // Track Elytra stuff
    private final Map<UUID, Boolean> wasGliding = new ConcurrentHashMap<>();
    private final Map<UUID, Long> elytraLandingTime = new ConcurrentHashMap<>();

    // Map to track when players can move again after a violation
    private final Map<UUID, Long> movementBlockedUntil = new ConcurrentHashMap<>();

    // Track players who need a full data reset on their next movement after being unblocked
    private final Map<UUID, Boolean> needsReset = new ConcurrentHashMap<>();

    // Lock for operations
    private final ReentrantLock operationLock = new ReentrantLock();

    // Constants for pattern detection
    private static final int SPEED_HISTORY_SIZE = 6;
    private static final double SPEED_VARIANCE_THRESHOLD = 0.05;
    private static final double SUSPICIOUS_SPEED_RATIO = 0.85;

    public MovementChecker(VelocityGuard plugin) {
        this.plugin = plugin;
    }

    // This method only returns true if the player is allowed to move.
    public boolean processMovement(Player player, Location from, Location to) {
        if (player == null || from == null || to == null) return true;

        UUID playerId = player.getUniqueId();
        // Check if player is currently blocked from moving
        Long blockedUntil = movementBlockedUntil.get(playerId);

        if (blockedUntil != null && System.currentTimeMillis() < blockedUntil) {
            if (plugin.isDebugEnabled()) {
                long remainingTime = (blockedUntil - System.currentTimeMillis()) / 1000;
                if (remainingTime % 1 == 0) {
                    plugin.getLogger().info("Blocked movement for " + player.getName() + " - remaining: " + remainingTime + "s");
                }
            }
            return false;
        }

        // Player was blocked but now is allowed to move - mark them for reset
        if (blockedUntil != null && System.currentTimeMillis() >= blockedUntil) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("Player " + player.getName() + " is now unblocked - will reset on next movement");
            }
            movementBlockedUntil.remove(playerId);
            needsReset.put(playerId, true);
            return true;
        }

        // Reset movement data on first movement after being unblocked
        if (needsReset.remove(playerId) != null) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("Resetting movement data for " + player.getName() + " after unblock");
            }
            airTicks.remove(playerId);
            resetSpeedHistory(playerId);
            lastMoveTime.remove(playerId);
            return true;
        }

        // Skip processing identical locations
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return true;
        }

        // Skip for creative/spectator mode players
        if (player.getGameMode().toString().contains("CREATIVE") ||
            player.getGameMode().toString().contains("SPECTATOR")) {
            airTicks.remove(playerId);
            return true;
        }

        long currentTime = System.currentTimeMillis();
        long timeDelta = currentTime - lastMoveTime.getOrDefault(playerId, currentTime - 50);
        lastMoveTime.put(playerId, currentTime);

        // Prevent division by zero and unreasonable values.
        timeDelta = Math.max(25, Math.min(timeDelta, 200));

        double horizontalDistance = MovementUtils.calculateHorizontalDistance(from, to);
        // Convert to blocks per second.
        double horizontalSpeed = (horizontalDistance / timeDelta) * 1000;

        // Update speed history for pattern detection.
        updateSpeedHistory(playerId, horizontalSpeed);

        boolean isCurrentlyGliding = player.isGliding();
        boolean wasGlidingPreviously = wasGliding.getOrDefault(playerId, false);

        wasGliding.put(playerId, isCurrentlyGliding);

        if (!isCurrentlyGliding && wasGlidingPreviously) {
            elytraLandingTime.put(playerId, currentTime);
        }
        
        // Get max allowed speed with adjustments for game conditions
        double maxSpeed = MovementUtils.getMaxHorizontalSpeed(
            player, 
            plugin.getConfigManager().getMaxHorizontalSpeed(),
            elytraLandingTime.get(playerId),
            currentTime
        );
        
        // Enhanced speed cheat detection with multiple checks...
        boolean speedViolation = false;

        if (horizontalSpeed > maxSpeed) {
            // First check - basic speed threshold
            speedViolation = true;
            
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(player.getName() + " speed violation: " + 
                        String.format("%.2f", horizontalSpeed) + " blocks/s (max allowed: " + 
                        String.format("%.2f", maxSpeed) + ")");
            }
        }
        
        // Second check - consistent speed pattern
        if (!speedViolation && hasSpeedPattern(playerId, maxSpeed)) {
            speedViolation = true;
            
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(player.getName() + " has suspicious speed pattern: " + 
                        String.format("%.2f", horizontalSpeed) + " blocks/s");
            }
        }

        // Only check for flying if there's no speed violation yet.
        boolean flyingViolation = false;
        if (!speedViolation) {
            flyingViolation = checkFlying(player, from, to);
        }

        // If a violation was detected, immediately block all movement
        if (speedViolation || (flyingViolation && airTicks.getOrDefault(playerId, 0) > 40)) {
            String message = speedViolation ? "Excessive speed detected" : "Illegal flight detected";

            // Block movement for the configured duration
            blockPlayerMovement(player, message);
            return false;
        }

        return true;
    }

    private void blockPlayerMovement(Player player, String reason) {
        UUID playerId = player.getUniqueId();

        int blockDuration = plugin.getConfigManager().getCancelDuration();

        long currentTime = System.currentTimeMillis();
        long blockedUntil = currentTime + (blockDuration * 1000L);

        // Use lock to ensure thread safety
        operationLock.lock();
        try {
            movementBlockedUntil.put(playerId, blockedUntil);
        } finally {
            operationLock.unlock();
        }

        // Notify the player on the main thread
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (player.isOnline()) {
                        player.sendMessage("§c[VelocityGuard] §f" + reason + "! Movement blocked for " + blockDuration + " seconds.");

                        if (plugin.isDebugEnabled()) {
                            plugin.getLogger().info("Blocked all movement for " + player.getName() + 
                                    " for " + blockDuration + " seconds. Reason: " + reason);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Error notifying player " + player.getName() + ": " + e.getMessage());
                }
            }
        }.runTask(plugin);
    }

    private void updateSpeedHistory(UUID playerId, double speed) {
        Queue<Double> speeds = recentSpeeds.computeIfAbsent(playerId, k -> new LinkedList<>());

        // Add the new speed to the history
        speeds.add(speed);

        // Keep history size limited
        while (speeds.size() > SPEED_HISTORY_SIZE) {
            speeds.poll();
        }
    }

    private void resetSpeedHistory(UUID playerId) {
        Queue<Double> speeds = recentSpeeds.get(playerId);
        if (speeds != null) {
            speeds.clear();
        }
    }

    private boolean hasSpeedPattern(UUID playerId, double maxSpeed) {
        Queue<Double> history = recentSpeeds.get(playerId);
        if (history == null || history.size() < SPEED_HISTORY_SIZE) {
            return false;
        }

        // Calculate average and check for suspiciously consistent speeds
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        int highSpeedCount = 0;

        for (double speed : history) {
            sum += speed;
            min = Math.min(min, speed);
            max = Math.max(max, speed);

            // Count how many speeds are suspiciously high
            if (speed > maxSpeed * SUSPICIOUS_SPEED_RATIO) {
                highSpeedCount++;
            }
        }

        double average = sum / history.size();
        double variance = max - min;

        // Speed cheats often have suspiciously consistent speeds just under the detection threshold
        boolean suspiciouslyConsistent = variance < SPEED_VARIANCE_THRESHOLD && average > maxSpeed * SUSPICIOUS_SPEED_RATIO;

        // Another pattern: too many movements near the maximum allowed speed
        boolean tooManyHighSpeeds = highSpeedCount >= SPEED_HISTORY_SIZE - 1;

        return suspiciouslyConsistent || tooManyHighSpeeds;
    }

    private boolean checkFlying(Player player, Location from, Location to) {
        UUID playerId = player.getUniqueId();
        boolean isNearGround = MovementUtils.isNearGround(player);
        boolean inWater = MovementUtils.isInLiquid(player);

        // Reset air ticks if on ground or in water
        if (isNearGround || inWater) {
            airTicks.put(playerId, 0);
            return false;
        } else {
            // Increment air ticks if not on ground
            int previousAirTicks = airTicks.getOrDefault(playerId, 0);
            int currentAirTicks = previousAirTicks + 1;
            airTicks.put(playerId, currentAirTicks);

            // Only check for fly cheats if player has been in air for a while (not just jumping)
            // Normal jump apex is around 11-13 ticks
            if (currentAirTicks > 25) {
                // Check for hovering (staying at same Y level while in air)
                if (Math.abs(to.getY() - from.getY()) < 0.05 && !player.isGliding() && !player.isFlying()) {
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info(player.getName() + " potential hover cheat: air ticks=" + currentAirTicks);
                    }
                    return currentAirTicks > 40;
                }

                // Check for ascending in air (only after being in air long enough)
                if (to.getY() > from.getY() && !player.isGliding() && !player.isFlying() && currentAirTicks > 30) {
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info(player.getName() + " ascending in air after " + currentAirTicks + " ticks");
                    }
                    return currentAirTicks > 40;
                }
            }
        }

        return false;
    }

    public void registerPlayer(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();

        // Reset tracking variables
        airTicks.put(playerId, 0);
        resetSpeedHistory(playerId);
        wasGliding.put(playerId, player.isGliding());

        // Let them move again (in case they were previously blocked)
        movementBlockedUntil.remove(playerId);
    }

    public void unregisterPlayer(UUID playerId) {
        if (playerId == null) return;

        airTicks.remove(playerId);
        recentSpeeds.remove(playerId);
        lastMoveTime.remove(playerId);
        wasGliding.remove(playerId);
        elytraLandingTime.remove(playerId);
        movementBlockedUntil.remove(playerId);
        needsReset.remove(playerId);
    }

}
