package com.jellypudding.velocityGuard.processors;

import com.jellypudding.velocityGuard.VelocityGuard;
import com.jellypudding.velocityGuard.managers.ConfigManager;
import com.jellypudding.velocityGuard.utils.MovementUtils;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MovementChecker {

    private final VelocityGuard plugin;

    private final Map<UUID, PlayerMovementState> playerStates = new ConcurrentHashMap<>();

    private record FlightEnforcementConfig(
            boolean groundOnViolation,
            int     airTickThreshold,
            boolean groundWhenStationary) {}

    private final Map<UUID, FlightEnforcementConfig> flightEnforcedPlayers = new ConcurrentHashMap<>();

    public MovementChecker(VelocityGuard plugin) {
        this.plugin = plugin;
        startStationaryGroundCheck();
    }

    public boolean processMovement(Player player, Location from, Location to,
                                   boolean isVehicle, boolean clientOnGround) {
        if (player == null || from == null || to == null || player.isDead()) return true;

        final UUID id  = player.getUniqueId();
        final long now = System.currentTimeMillis();
        final ConfigManager cfg = plugin.getConfigManager();

        PlayerMovementState state = playerStates.computeIfAbsent(
                id, k -> new PlayerMovementState(to, now));

        // Settle window: absorbs round-trip latency after a server teleport
        // (respawn, /tp, portal).  On expiry the first packet is accepted as a
        // fresh anchor so the position jump doesn't trigger a false violation.
        if (now < state.settleUntilMs) return false;
        if (state.settleUntilMs > 0) {
            state.settleUntilMs   = 0;
            state.lastPosition    = to.clone();
            state.lastPacketMs    = now;
            state.trackedSpeed    = 0.0;
            state.trackedVelocityY = 0.0;
            state.violationBuffer = 0.0;
            state.wasOnGround     = clientOnGround;
            return true;
        }

        // Block window: all movement packets denied for the configured duration.
        // On expiry the first packet is accepted as a fresh anchor - no teleport.
        if (now < state.blockedUntilMs) return false;
        if (state.blockedUntilMs > 0) {
            state.blockedUntilMs  = 0;
            state.lastPosition    = to.clone();
            state.lastPacketMs    = now;
            state.trackedSpeed    = 0.0;
            state.trackedVelocityY = 0.0;
            state.violationBuffer = 0.0;
            state.wasOnGround     = clientOnGround;
            return true;
        }

        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            state.wasInCreative   = true;
            state.lastPosition    = to.clone();
            state.lastPacketMs    = now;
            state.trackedSpeed    = 0.0;
            state.trackedVelocityY = 0.0;
            state.wasOnGround     = true;
            state.airTicks        = 0;
            return true;
        }

        // First survival packet after leaving creative/spectator: reset and accept without
        // checking to avoid false positives from the position/speed discontinuity.
        if (state.wasInCreative) {
            state.wasInCreative = false;
            state.reset(to, now);
            return true;
        }

        boolean currentlyGliding = player.isGliding();
        if (!currentlyGliding && state.wasGliding) {
            state.elytraLandingMs = now;
        }
        state.wasGliding = currentlyGliding;

        double dx = to.getX() - state.lastPosition.getX();
        double dz = to.getZ() - state.lastPosition.getZ();
        double dy = to.getY() - state.lastPosition.getY();
        double packetDistance = Math.sqrt(dx * dx + dz * dz);
        // Use the client's own onGround flag for players: it is set to false the instant
        // the client jumps, one packet before isNearGroundAt(to) catches up.
        // For vehicles there is no onGround in the packet, so fall back to server-side.
        boolean nowOnGround = isVehicle ? PhysicsEngine.isNearGroundAt(to) : clientOnGround;

        // Each accepted movement packet is exactly one client tick.
        int expectedTicks = 1;

        if (cfg.isTimerCheckEnabled() && !isVehicle) {
            boolean tooFast = TimerCheck.onMovement(state, cfg.getTimerDriftNanos());
            if (tooFast) {
                state.timerViolations += 1.0;
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info(String.format(
                            "[VG-Timer] %s  timerViolations=%.1f  ping=%dms",
                            player.getName(), state.timerViolations,
                            state.transactionPingNanos / 1_000_000L));
                }
                if (state.timerViolations >= cfg.getTimerMaxViolations()) {
                    return setback(player, "Timer / speed cheat detected");
                }
            } else {
                state.timerViolations = Math.max(0.0, state.timerViolations - 0.25);
            }
        }

        boolean speedViolation = false;

        if (!checkSpecialSpeedExemption(player, state, now, cfg)) {
            double maxAllowed = computeMaxAllowedDisplacementVehicle(
                    player, state, expectedTicks, cfg, isVehicle, to, nowOnGround);

            if (packetDistance > 0.001 && packetDistance > maxAllowed) {
                double excess = packetDistance - maxAllowed;
                state.violationBuffer += excess;

                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info(String.format(
                            "[VG] %s  actual=%.3f  max=%.3f  ticks=%d  trackedSpeed=%.3f  buf=%.3f%s",
                            player.getName(), packetDistance, maxAllowed, expectedTicks,
                            state.trackedSpeed, state.violationBuffer,
                            isVehicle ? " (vehicle)" : ""));
                }

                if (state.violationBuffer >= cfg.getViolationThreshold()) {
                    speedViolation = true;
                }
            } else {
                state.violationBuffer = Math.max(0.0,
                        state.violationBuffer - cfg.getViolationDecay());
            }
        } else {
            state.violationBuffer = Math.max(0.0,
                    state.violationBuffer - cfg.getViolationDecay());
        }

        boolean serverSideAirborne = !PhysicsEngine.isNearGroundAt(to);

        // Detect a normal jump (wasOnGround=true, now airborne, moving up).
        boolean normalJump = !isVehicle && state.wasOnGround && !nowOnGround && dy > 0.3;

        // Detect a jump after a brief clientOnGround flicker (wasOnGround=false due to
        // terrain, but the player just actually jumped). We confirm by checking that the
        // previous position was still near the ground (so this can't fire mid-flight).
        boolean jumpLaunched = !isVehicle && !state.wasOnGround && !nowOnGround
                && serverSideAirborne && dy > 0.3 && state.trackedVelocityY <= 0.1
                && PhysicsEngine.isNearGroundAt(state.lastPosition);

        // Pre-compute the effective starting Y velocity for this tick's simulation.
        // For any detected jump (normal or flicker-launched) this is the first post-jump
        // velocity so the Y check uses the correct prediction instead of stale/zero state.
        double gravityPreJump = player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                ? 0.01 : PhysicsEngine.GRAVITY;
        double effectiveVelocityY = state.trackedVelocityY;
        if (normalJump || jumpLaunched) {
            effectiveVelocityY = (PhysicsEngine.getJumpVelocity(player) - gravityPreJump)
                    * PhysicsEngine.VERTICAL_DRAG;
        }

        if (cfg.isFlightCheckEnabled() && !isVehicle && !speedViolation && !state.wasOnGround
                && serverSideAirborne
                && !checkSpecialSpeedExemption(player, state, now, cfg)) {

            boolean yExempt = currentlyGliding
                    || MovementUtils.isInLiquid(player)
                    || player.isFlying()
                    || player.hasPotionEffect(PotionEffectType.LEVITATION);

            if (!yExempt) {
                double gravityVal = player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                        ? 0.01 : PhysicsEngine.GRAVITY;
                double maxDy = 0.0;
                double vy    = effectiveVelocityY;
                for (int t = 0; t < expectedTicks; t++) {
                    maxDy += vy;
                    vy = (vy - gravityVal) * PhysicsEngine.VERTICAL_DRAG;
                }
                double yTolerance = cfg.getPerTickTolerance() * 1.5 * expectedTicks;
                double yThreshold = maxDy * cfg.getLeniencyMultiplier() + yTolerance;
                // Only flag upward or hovering violations (dy >= 0). When the player is
                // descending (dy < 0), a landing mid-arc would look like excess without
                // actually being a cheat, producing false positives.
                if (dy >= 0 && dy > yThreshold) {
                    double excess = dy - yThreshold;
                    state.violationBuffer += excess;
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info(String.format(
                                "[VG-Y] %s  dy=%.3f  maxDy=%.3f  effVy=%.3f  ticks=%d  buf=%.3f",
                                player.getName(), dy, maxDy, effectiveVelocityY,
                                expectedTicks, state.violationBuffer));
                    }
                    if (state.violationBuffer >= cfg.getViolationThreshold()) {
                        speedViolation = true;
                    }
                }
            }
        }

        if (!speedViolation) {
            double actualPerTick = packetDistance / expectedTicks;

            if (isVehicle) {
                state.trackedSpeed = Math.min(actualPerTick, PhysicsEngine.MAX_VEHICLE_SPEED);
            } else {
                // isJumpTick covers both normal jumps and the brief-flicker jump case so that
                // trackedSpeed is correctly seeded with sprint-jump speed for subsequent ticks.
                boolean isJumpTick = (state.wasOnGround || jumpLaunched) && !nowOnGround;
                double maxPerTick = currentlyGliding
                        ? 4.0
                        : PhysicsEngine.simulateOneTick(
                                state.trackedSpeed,
                                PhysicsEngine.isNearGroundAt(state.lastPosition) && !isJumpTick,
                                MovementUtils.isInLiquid(player),
                                player.isSprinting(),
                                PhysicsEngine.getPotionSpeedModifier(player),
                                PhysicsEngine.getBlockSlipperiness(state.lastPosition),
                                isJumpTick);
                state.trackedSpeed = Math.min(actualPerTick, maxPerTick);
            }

            if (normalJump || jumpLaunched) {
                // effectiveVelocityY was already pre-computed to the correct first post-jump
                // velocity, covering both the normal-jump and the flicker-launched cases.
                state.trackedVelocityY = effectiveVelocityY;
            } else if (!isVehicle && !nowOnGround && serverSideAirborne) {
                double gravityVal = player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                        ? 0.01 : PhysicsEngine.GRAVITY;
                double vy = state.trackedVelocityY;
                for (int t = 0; t < expectedTicks; t++) {
                    vy = (vy - gravityVal) * PhysicsEngine.VERTICAL_DRAG;
                }
                state.trackedVelocityY = vy;
            } else {
                state.trackedVelocityY = 0.0;
            }

            state.wasOnGround   = nowOnGround;
            state.lastPosition  = to.clone();
            state.lastValidPosition = to.clone();
            state.lastPacketMs  = now;
        }

        FlightEnforcementConfig flightCfg = flightEnforcedPlayers.get(id);
        int flightThreshold = flightCfg != null ? flightCfg.airTickThreshold() : 40;
        boolean flyingViolation = false;

        if (!speedViolation && flightCfg != null) {
            boolean ridingGhast    = MovementUtils.isRidingGhast(player);
            boolean justUsedRiptide = state.lastRiptideMs > 0
                    && (now - state.lastRiptideMs < 1_500);

            if (!justUsedRiptide && !ridingGhast) {
                MovementUtils.FlightResult fr = MovementUtils.checkFlying(
                        player, from, to, state.airTicks,
                        plugin.isDebugEnabled(), plugin.getLogger(), flightThreshold);
                state.airTicks    = fr.newAirTicks();
                flyingViolation   = fr.violation();
            } else if (plugin.isDebugEnabled() && state.airTicks > 30) {
                plugin.getLogger().info(player.getName()
                        + " exempt from flight check: "
                        + (ridingGhast ? "riding ghast" : "recent riptide"));
            }
        }

        if (speedViolation) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[VG] " + player.getName()
                        + " flagged for speed (buffer exceeded threshold).");
            }
            return setback(player, "Excessive speed detected");
        }

        if (flyingViolation && state.airTicks >= flightThreshold) {
            if (flightCfg != null && flightCfg.groundOnViolation()) {
                groundPlayerForViolation(player);
                return false;
            }
            return setback(player, "Illegal flight detected");
        }

        return true;
    }

    private double computeMaxAllowedDisplacement(Player player, PlayerMovementState state,
                                                 int ticks, Location to, ConfigManager cfg,
                                                 boolean toOnGround) {
        long now = System.currentTimeMillis();

        if (player.isGliding()) {
            return 4.0 * ticks * cfg.getLeniencyMultiplier()
                    + cfg.getPerTickTolerance() * ticks;
        }

        if (state.elytraLandingMs > 0
                && now - state.elytraLandingMs < cfg.getElytraLandingDuration()) {
            return state.trackedSpeed * ticks * 3.5 * cfg.getLeniencyMultiplier()
                    + cfg.getPerTickTolerance() * ticks;
        }

        if (state.lastDamageMs > 0) {
            long elapsed = now - state.lastDamageMs;
            if (state.lastDragonDamage && elapsed < 5_000) {
                return 500.0 * ticks;
            }
            if (elapsed < cfg.getKnockbackDuration()) {
                double kbFactor = cfg.getKnockbackMultiplier()
                        * (1.0 - (double) elapsed / cfg.getKnockbackDuration());
                double kbTracked = state.trackedSpeed * (1.0 + kbFactor);
                return computeSimulatedMax(player, state, kbTracked, ticks, to, cfg, toOnGround);
            }
        }

        if (state.lastRiptideMs > 0) {
            long elapsed = now - state.lastRiptideMs;
            if (elapsed < cfg.getRiptideDuration()) {
                double rtFactor = cfg.getRiptideMultiplier()
                        * (1.0 - (double) elapsed / cfg.getRiptideDuration());
                double rtTracked = state.trackedSpeed * (1.0 + rtFactor);
                return computeSimulatedMax(player, state, rtTracked, ticks, to, cfg, toOnGround);
            }
        }

        return computeSimulatedMax(player, state, state.trackedSpeed, ticks, to, cfg, toOnGround);
    }

    private double computeSimulatedMax(Player player, PlayerMovementState state,
                                       double startSpeed, int ticks, Location to,
                                       ConfigManager cfg, boolean toOnGround) {
        boolean onGround  = state.wasOnGround;
        boolean inLiquid  = MovementUtils.isInLiquid(player);
        boolean sprinting = player.isSprinting();
        double  speedMod  = PhysicsEngine.getPotionSpeedModifier(player);
        float   slip      = PhysicsEngine.getBlockSlipperiness(state.lastPosition);

        // couldJump is true when the player was on the ground last tick but the client
        // reports it is now airborne.  Using the client flag avoids the one-packet lag
        // that isNearGroundAt(to) has (player rises ~0.42 b but the check still sees
        // the ground block 0.5 b below).
        // We also allow couldJump when wasOnGround was briefly false due to a clientOnGround
        // flicker on terrain: if the previous position was still near the ground and the
        // client now reports airborne, the player almost certainly just jumped from there.
        boolean couldJump = !toOnGround
                && (onGround || PhysicsEngine.isNearGroundAt(state.lastPosition));

        double totalMax  = 0.0;
        double speed     = startSpeed;
        boolean grounded = onGround;

        for (int t = 0; t < ticks; t++) {
            boolean jumpTick = (t == 0) && couldJump;
            if (jumpTick) grounded = false;   // airborne from this tick onward
            speed    = PhysicsEngine.simulateOneTick(speed, grounded && !jumpTick, inLiquid,
                                                     sprinting, speedMod, slip, jumpTick);
            totalMax += speed;
        }

        return totalMax * cfg.getLeniencyMultiplier()
                + cfg.getPerTickTolerance() * ticks;
    }

    private double computeMaxAllowedDisplacementVehicle(Player player, PlayerMovementState state,
                                                        int ticks, ConfigManager cfg,
                                                        boolean isVehicle, Location to,
                                                        boolean toOnGround) {
        if (!isVehicle) {
            return computeMaxAllowedDisplacement(player, state, ticks, to, cfg, toOnGround);
        }

        long now = System.currentTimeMillis();
        if (state.lastDamageMs > 0 && state.lastDragonDamage
                && now - state.lastDamageMs < 5_000) {
            return 500.0 * ticks;
        }

        double vMult = MovementUtils.isOnIce(player)
                ? cfg.getVehicleIceSpeedMultiplier()
                : cfg.getVehicleSpeedMultiplier();
        return PhysicsEngine.MAX_VEHICLE_SPEED * vMult * ticks * cfg.getLeniencyMultiplier()
                + cfg.getPerTickTolerance() * ticks;
    }

    private boolean checkSpecialSpeedExemption(Player player, PlayerMovementState state,
                                               long now, ConfigManager cfg) {
        if (state.lastDamageMs > 0 && now - state.lastDamageMs < 150) return true;
        if (state.lastRiptideMs > 0 && now - state.lastRiptideMs < 500) return true;
        return false;
    }

    // Rewind the player to their last valid position.
    private boolean setback(Player player, String reason) {
        PlayerMovementState state = playerStates.get(player.getUniqueId());
        if (state == null) return false;

        final Location target = state.lastValidPosition != null
                ? state.lastValidPosition.clone()
                : player.getLocation();

        long now = System.currentTimeMillis();
        // Deny movement packets until the teleport round-trips, then re-anchor.
        state.settleUntilMs   = now + 500L;
        state.violationBuffer = 0.0;
        state.timerViolations = 0.0;

        new BukkitRunnable() {
            @Override public void run() {
                if (!player.isOnline()) return;
                Location current = player.getLocation();
                target.setYaw(current.getYaw());
                target.setPitch(current.getPitch());
                player.teleport(target);
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info("[VG] Setback " + player.getName()
                            + " - " + reason);
                }
            }
        }.runTask(plugin);

        return false;
    }

    private void groundPlayerForViolation(Player player) {
        PlayerMovementState state = playerStates.get(player.getUniqueId());
        if (state == null) return;
        state.blockedUntilMs = System.currentTimeMillis() + 1_000L;
        state.airTicks = 0;

        if (plugin.isDebugEnabled()) {
            new BukkitRunnable() {
                @Override public void run() {
                    if (!player.isOnline()) return;
                    plugin.getLogger().info("[VG] Grounded " + player.getName()
                            + " after flight violation.");
                }
            }.runTask(plugin);
        }
    }

    private void startStationaryGroundCheck() {
        new BukkitRunnable() {
            @Override public void run() {
                if (flightEnforcedPlayers.isEmpty()) return;
                long now = System.currentTimeMillis();

                for (Map.Entry<UUID, FlightEnforcementConfig> entry
                        : flightEnforcedPlayers.entrySet()) {
                    if (!entry.getValue().groundWhenStationary()) continue;

                    UUID id    = entry.getKey();
                    PlayerMovementState state = playerStates.get(id);

                    if (state != null && now < state.blockedUntilMs) continue;
                    if (state != null && now - state.lastPacketMs < 1_000L) continue;

                    Player player = plugin.getServer().getPlayer(id);
                    if (player == null || !player.isOnline()) continue;
                    if (player.getGameMode() == GameMode.CREATIVE
                            || player.getGameMode() == GameMode.SPECTATOR) continue;
                    if (player.isGliding() || player.isFlying()) continue;
                    if (player.hasPotionEffect(PotionEffectType.LEVITATION)) continue;
                    if (MovementUtils.isNearGround(player)
                            || MovementUtils.isInLiquid(player)) continue;

                    groundPlayerForViolation(player);
                }
            }
        }.runTaskTimer(plugin, 20L, 10L);
    }

    public void registerPlayer(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        PlayerMovementState state = playerStates.computeIfAbsent(
                id, k -> new PlayerMovementState(player.getLocation(), now));
        state.reset(player.getLocation(), now);
        state.blockedUntilMs = 0;
        state.settleUntilMs  = now + 500L;
        state.lastDamageMs   = 0;
        state.lastRiptideMs  = 0;
    }

    public int registerOutgoingTransaction(UUID id, long sendNano) {
        PlayerMovementState state = playerStates.get(id);
        if (state == null) return Integer.MIN_VALUE;
        int transactionId = state.nextTransactionId();
        state.onTransactionSent(transactionId, sendNano);
        return transactionId;
    }

    public boolean handlePong(UUID id, int transactionId, long nowNano) {
        PlayerMovementState state = playerStates.get(id);
        if (state == null) return false;
        boolean matched = state.onTransactionResponse(transactionId, nowNano);
        if (matched) TimerCheck.onTransaction(state);
        return matched;
    }

    public void unregisterPlayer(UUID id) {
        if (id == null) return;
        playerStates.remove(id);
        flightEnforcedPlayers.remove(id);
    }

    public void unregisterPlayer(UUID id) {
        if (id == null) return;
        playerStates.remove(id);
        flightEnforcedPlayers.remove(id);
    }

    public void recordPlayerDamage(Player player, boolean isDragonDamage) {
        if (player == null) return;
        PlayerMovementState state = playerStates.get(player.getUniqueId());
        if (state == null) return;
        state.lastDamageMs    = System.currentTimeMillis();
        state.lastDragonDamage = isDragonDamage;

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info(player.getName() + " took damage"
                    + (isDragonDamage ? " (dragon)" : "")
                    + " - knockback allowance applied.");
        }
    }

    public void recordRiptideUse(Player player) {
        if (player == null) return;
        PlayerMovementState state = playerStates.get(player.getUniqueId());
        if (state == null) return;
        state.lastRiptideMs = System.currentTimeMillis();

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info(player.getName()
                    + " used riptide - riptide speed allowance applied.");
        }
    }

    public void addFlightEnforcement(UUID id, boolean groundOnViolation,
                                     int airTickThreshold, boolean groundWhenStationary) {
        if (id == null) return;
        flightEnforcedPlayers.put(id,
                new FlightEnforcementConfig(groundOnViolation, airTickThreshold, groundWhenStationary));
    }

    public void removeFlightEnforcement(UUID id) {
        if (id == null) return;
        flightEnforcedPlayers.remove(id);
    }

    public boolean isFlightEnforced(UUID id) {
        return id != null && flightEnforcedPlayers.containsKey(id);
    }

    public void resetPlayerState(Player player, Location location) {
        PlayerMovementState state = playerStates.get(player.getUniqueId());
        if (state != null && location != null) {
            long now = System.currentTimeMillis();
            state.reset(location, now);
            state.settleUntilMs = now + 500L;
        }
    }
}
