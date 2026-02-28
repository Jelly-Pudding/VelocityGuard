package com.jellypudding.velocityGuard.processors;

import com.jellypudding.velocityGuard.VelocityGuard;
import com.jellypudding.velocityGuard.utils.MovementUtils;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class MovementChecker {

    private final VelocityGuard plugin;

    // Track how long players have been in the air
    private final Map<UUID, Integer> airTicks = new ConcurrentHashMap<>();

    // Track movement timing
    private final Map<UUID, Long> lastMoveTime = new ConcurrentHashMap<>();

    // Track consecutive speed violations
    private final Map<UUID, Integer> speedViolationsCounter = new ConcurrentHashMap<>();

    // Track recent damage time to account for knockback
    private final Map<UUID, Long> lastDamageTime = new ConcurrentHashMap<>();

    // Track if last damage was from Ender Dragon
    private final Map<UUID, Boolean> dragonDamage = new ConcurrentHashMap<>();

    // Track trident riptide usage
    private final Map<UUID, Long> lastRiptideTime = new ConcurrentHashMap<>();

    // Track Elytra stuff
    private final Map<UUID, Boolean> wasGliding = new ConcurrentHashMap<>();
    private final Map<UUID, Long> elytraLandingTime = new ConcurrentHashMap<>();

    // Map to track when players can move again after a violation
    private final Map<UUID, Long> movementBlockedUntil = new ConcurrentHashMap<>();

    // Track players who need a full data reset on their next movement after being unblocked
    private final Map<UUID, Boolean> needsReset = new ConcurrentHashMap<>();

    // Track when players were last blocked to ignore old movement packets
    private final Map<UUID, Long> lastBlockedTime = new ConcurrentHashMap<>();

    private record FlightEnforcementConfig(boolean groundOnViolation, int airTickThreshold, boolean groundWhenStationary) {}

    // Players with per-player flight enforcement enabled via the API.
    private final Map<UUID, FlightEnforcementConfig> flightEnforcedPlayers = new ConcurrentHashMap<>();

    // Lock for operations
    private final ReentrantLock operationLock = new ReentrantLock();

    public MovementChecker(VelocityGuard plugin) {
        this.plugin = plugin;
        startStationaryGroundCheck();
    }

    private void startStationaryGroundCheck() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (flightEnforcedPlayers.isEmpty()) return;
                long currentTime = System.currentTimeMillis();

                for (Map.Entry<UUID, FlightEnforcementConfig> entry : flightEnforcedPlayers.entrySet()) {
                    if (!entry.getValue().groundWhenStationary()) continue;

                    UUID playerId = entry.getKey();

                    // Skip players already being handled by a recent violation response
                    Long blockedUntil = movementBlockedUntil.get(playerId);
                    if (blockedUntil != null && currentTime < blockedUntil) continue;

                    // Skip players who are actively sending movement packets — they are
                    // jumping/moving and are already covered by the packet-based check.
                    // This prevents false positives on normal jumps.
                    Long lastMove = lastMoveTime.get(playerId);
                    if (lastMove != null && currentTime - lastMove < 1000L) continue;

                    Player player = plugin.getServer().getPlayer(playerId);
                    if (player == null || !player.isOnline()) continue;
                    if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) continue;
                    if (player.isGliding() || player.isFlying()) continue;
                    if (player.hasPotionEffect(PotionEffectType.LEVITATION)) continue;
                    if (MovementUtils.isNearGround(player) || MovementUtils.isInLiquid(player)) continue;

                    groundPlayerForViolation(player);
                }
            }
        }.runTaskTimer(plugin, 20L, 10L);
    }

    // This method only returns true if the player is allowed to move.
    public boolean processMovement(Player player, Location from, Location to, boolean isVehicle) {
        if (player == null || from == null || to == null) return true;

        if (player.isDead()) {
            return true;
        }

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // Check if player is currently blocked from moving
        Long blockedUntil = movementBlockedUntil.get(playerId);

        if (blockedUntil != null && currentTime < blockedUntil) {
            if (plugin.isDebugEnabled()) {
                long remainingTime = (blockedUntil - currentTime) / 1000;
                if (remainingTime % 1 == 0) {
                    plugin.getLogger().info("Blocked movement for " + player.getName() + " - remaining: " + remainingTime + "s");
                }
            }
            return false;
        }

        if (blockedUntil != null && currentTime >= blockedUntil) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("Player " + player.getName() + " is now unblocked - will reset on next movement");
            }
            movementBlockedUntil.remove(playerId);
            needsReset.put(playerId, true);
            return true;
        }

        // Check if this movement packet is from before the player was blocked (ignore old packets).
        Long lastBlocked = lastBlockedTime.get(playerId);
        if (lastBlocked != null) {
            // Estimate when this movement packet was created (account for processing time and network delay).
            // Subtract ping to estimate when packet was sent.
            long estimatedPacketTime = currentTime;

            // Estimate based on their ping.
            int ping = player.getPing();
            estimatedPacketTime -= Math.max(ping, 50);

            // If this packet originated before the block, ignore it.
            if (estimatedPacketTime < lastBlocked) {
                return false; // Block this old packet but don't punish for it.
            }
        }

        if (needsReset.remove(playerId) != null) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("Resetting movement data for " + player.getName() + " after unblock");
            }
            airTicks.remove(playerId);
            lastMoveTime.remove(playerId);
            lastBlockedTime.remove(playerId);
            return true;
        }

        // Skip processing identical locations.
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return true;
        }

        // Skip for creative/spectator mode players.
        if (player.getGameMode().toString().contains("CREATIVE") ||
            player.getGameMode().toString().contains("SPECTATOR")) {
            airTicks.remove(playerId);
            return true;
        }

        // Calculate blocks per second.
        long timeDelta = currentTime - lastMoveTime.getOrDefault(playerId, currentTime - 50);
        lastMoveTime.put(playerId, currentTime);
        // Prevent division by zero and unreasonable values.
        timeDelta = Math.max(25, Math.min(timeDelta, 200));
        double horizontalDistance = MovementUtils.calculateHorizontalDistance(from, to);
        // Blocks per second.
        double horizontalSpeed = (horizontalDistance / timeDelta) * 1000;

        // Track elytra and dragon damage
        // which will be used to calculate the maximum allowed speed.
        boolean isCurrentlyGliding = player.isGliding();
        boolean wasGlidingPreviously = wasGliding.getOrDefault(playerId, false);
        wasGliding.put(playerId, isCurrentlyGliding);
        if (!isCurrentlyGliding && wasGlidingPreviously) {
            elytraLandingTime.put(playerId, currentTime);
        }
        boolean isRecentDragonDamage = dragonDamage.getOrDefault(playerId, false);

        int ping = MovementUtils.getPlayerPing(player);
        // Get max allowed speed with adjustments for game conditions.
        double maxSpeed = MovementUtils.getMaxHorizontalSpeed(
            player,
            plugin.getConfigManager().getMaxHorizontalSpeed(),
            elytraLandingTime.get(playerId),
            lastDamageTime.get(playerId),
            lastRiptideTime.get(playerId),
            currentTime,
            plugin.getConfigManager().getKnockbackMultiplier(),
            plugin.getConfigManager().getKnockbackDuration(),
            plugin.getConfigManager().getRiptideMultiplier(),
            plugin.getConfigManager().getRiptideDuration(),
            isRecentDragonDamage,
            isVehicle,
            plugin.getConfigManager().getVehicleSpeedMultiplier(),
            plugin.getConfigManager().getVehicleIceSpeedMultiplier(),
            plugin.getConfigManager().getBufferMultiplier(),
            ping,
            plugin.getConfigManager().isLatencyCompensationEnabled(),
            plugin.getConfigManager().getLowPingCompensation(),
            plugin.getConfigManager().getMediumPingCompensation(),
            plugin.getConfigManager().getHighPingCompensation(),
            plugin.getConfigManager().getVeryHighPingCompensation(),
            plugin.getConfigManager().getExtremePingCompensation(),
            plugin.getConfigManager().getVeryLowPingCompensation(),
            plugin.getConfigManager().getUltraPingCompensation(),
            plugin.getConfigManager().getInsanePingCompensation(),
            plugin.getConfigManager().getElytraGlidingMultiplier(),
            plugin.getConfigManager().getElytraLandingDuration()
        );

        // Check for speed violations
        boolean speedViolation = false;

        Long recentDamage = lastDamageTime.get(playerId);
        Long recentRiptide = lastRiptideTime.get(playerId);
        boolean justTookDamage = recentDamage != null && (currentTime - recentDamage < 150);
        boolean justUsedRiptide = recentRiptide != null && (currentTime - recentRiptide < 1500);

        if (horizontalSpeed > maxSpeed) {
            // Check if the player just took damage or used riptide in the past 150ms.
            // This helps prevent race conditions between events and movement processing.
            if (!justTookDamage && !justUsedRiptide) {
                int burstTolerance = plugin.getConfigManager().getBurstToleranceForPing(ping);

                int violations = speedViolationsCounter.getOrDefault(playerId, 0) + 1;
                speedViolationsCounter.put(playerId, violations);

                if (violations > burstTolerance) {
                    speedViolation = true;

                    if (plugin.isDebugEnabled()) {
                        String vehicleInfo = isVehicle ? " (in vehicle)" : "";
                        plugin.getLogger().info(player.getName() + " speed violation" + vehicleInfo + ": " +
                                String.format("%.2f", horizontalSpeed) + " blocks/s (max allowed: " +
                                String.format("%.2f", maxSpeed) + "), ping: " + ping + "ms, violations: " + violations + "/" + burstTolerance);
                    }
                } else if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info(player.getName() + " exceeded speed but within burst tolerance (" +
                            violations + "/" + burstTolerance + "): " + String.format("%.2f", horizontalSpeed) +
                            " blocks/s (max allowed: " + String.format("%.2f", maxSpeed) + ")");
                }
            } else if (plugin.isDebugEnabled()) {
                String reason = justTookDamage ? "recently damaged" : "recently used riptide";
                plugin.getLogger().info(player.getName() + " exceeded speed limit but was " + reason + " - ignoring.");
                speedViolationsCounter.put(playerId, 0);
            }
        } else {
            speedViolationsCounter.put(playerId, 0);
        }

        // Only check for flying if there's no speed violation yet, and either the global
        // flight check is enabled or this player has per-player enforcement active.
        FlightEnforcementConfig enforcementConfig = flightEnforcedPlayers.get(playerId);
        int flightThreshold = enforcementConfig != null ? enforcementConfig.airTickThreshold() : 40;

        boolean flyingViolation = false;
        if (!speedViolation && (plugin.getConfigManager().isFlightCheckEnabled() || enforcementConfig != null)) {
            // Skip flying check if player just used riptide or is riding a ghast
            boolean isRidingGhast = MovementUtils.isRidingGhast(player);
            if (!justUsedRiptide && !isRidingGhast) {
                flyingViolation = MovementUtils.checkFlying(player, from, to, airTicks,
                                                          plugin.isDebugEnabled(), plugin.getLogger(),
                                                          flightThreshold);
            } else if (plugin.isDebugEnabled() && airTicks.getOrDefault(playerId, 0) > 30) {
                String reason = justUsedRiptide ? "recent riptide use" : "riding ghast";
                if (isRidingGhast && player.getVehicle() != null) {
                    String entityType = player.getVehicle().getType().toString();
                    reason = "riding " + entityType.toLowerCase();
                }
                plugin.getLogger().info(player.getName() + " exempt from flight checks due to " + reason);
            }
        }

        // If a violation was detected, immediately block all movement or ground the player
        if (speedViolation || (flyingViolation && airTicks.getOrDefault(playerId, 0) >= flightThreshold)) {
            if (!speedViolation && enforcementConfig != null && enforcementConfig.groundOnViolation()) {
                groundPlayerForViolation(player);
            } else {
                String message = speedViolation ? "Excessive speed detected" : "Illegal flight detected";
                blockPlayerMovement(player, message);
            }
            return false;
        }

        return true;
    }

    private void groundPlayerForViolation(Player player) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        operationLock.lock();
        try {
            movementBlockedUntil.put(playerId, currentTime + 1000L);
            lastBlockedTime.put(playerId, currentTime);
        } finally {
            operationLock.unlock();
        }

        airTicks.remove(playerId);

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (player.isOnline()) {
                        Location loc = player.getLocation();
                        World world = loc.getWorld();
                        if (world != null) {
                            int highestY = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
                            Location ground = new Location(world, loc.getX(), highestY + 1, loc.getZ(),
                                    loc.getYaw(), loc.getPitch());
                            player.teleport(ground);
                        }
                        if (plugin.isDebugEnabled()) {
                            plugin.getLogger().info("Grounded " + player.getName() + " after flight violation.");
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Error grounding player " + player.getName() + ": " + e.getMessage());
                }
            }
        }.runTask(plugin);
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
            // Record when this player was blocked to ignore old packets.
            lastBlockedTime.put(playerId, currentTime);
        } finally {
            operationLock.unlock();
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (player.isOnline()) {
                        String unit = (blockDuration == 1) ? "second" : "seconds";
                        player.sendMessage("§c[VelocityGuard] §f" + reason + ". Movement blocked for " + blockDuration + " " + unit + ".");

                        if (plugin.isDebugEnabled()) {
                            plugin.getLogger().info("Blocked all movement for " + player.getName() +
                                    " for " + blockDuration + " " + unit + ". Reason: " + reason);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Error notifying player " + player.getName() + ": " + e.getMessage());
                }
            }
        }.runTask(plugin);
    }

    public void recordPlayerDamage(Player player, boolean isDragonDamage) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        lastDamageTime.put(playerId, System.currentTimeMillis());
        dragonDamage.put(playerId, isDragonDamage);

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info(player.getName() + " took damage" +
                    (isDragonDamage ? " from dragon" : "") +
                    " - adjusting speed threshold for knockback");
        }
    }

    public void recordRiptideUse(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        lastRiptideTime.put(playerId, System.currentTimeMillis());

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info(player.getName() + " used trident with riptide enchantment" +
                    " - adjusting speed threshold");
        }
    }

    public void registerPlayer(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();

        // Reset tracking variables.
        airTicks.put(playerId, 0);
        wasGliding.put(playerId, player.isGliding());
        lastDamageTime.remove(playerId);
        lastRiptideTime.remove(playerId);
        speedViolationsCounter.put(playerId, 0);

        // Let them move again (in case they were previously blocked).
        movementBlockedUntil.remove(playerId);
    }

    public void unregisterPlayer(UUID playerId) {
        if (playerId == null) return;

        airTicks.remove(playerId);
        lastMoveTime.remove(playerId);
        wasGliding.remove(playerId);
        elytraLandingTime.remove(playerId);
        movementBlockedUntil.remove(playerId);
        needsReset.remove(playerId);
        lastDamageTime.remove(playerId);
        dragonDamage.remove(playerId);
        lastRiptideTime.remove(playerId);
        speedViolationsCounter.remove(playerId);
        lastBlockedTime.remove(playerId);
        flightEnforcedPlayers.remove(playerId);
    }

    public void addFlightEnforcement(UUID playerId, boolean groundOnViolation, int airTickThreshold, boolean groundWhenStationary) {
        if (playerId == null) return;
        flightEnforcedPlayers.put(playerId, new FlightEnforcementConfig(groundOnViolation, airTickThreshold, groundWhenStationary));
    }

    public void removeFlightEnforcement(UUID playerId) {
        if (playerId == null) return;
        flightEnforcedPlayers.remove(playerId);
    }

    public boolean isFlightEnforced(UUID playerId) {
        return playerId != null && flightEnforcedPlayers.containsKey(playerId);
    }
}
