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

    private static final double SETBACK_RESYNC_TOLERANCE   = 0.5;
    private static final double SETBACK_RESYNC_TOLERANCE_Y = 2.0;

    private static final long SETBACK_RETELEPORT_MS = 500L;

    private static final int MAX_CATCHUP_TICKS = 4;

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

        if (state.awaitingSetback && state.setbackTarget != null) {
            double sdx = to.getX() - state.setbackTarget.getX();
            double sdz = to.getZ() - state.setbackTarget.getZ();
            double sHoriz = Math.sqrt(sdx * sdx + sdz * sdz);
            double sVert  = Math.abs(to.getY() - state.setbackTarget.getY());
            boolean atTarget = sHoriz <= SETBACK_RESYNC_TOLERANCE && sVert <= SETBACK_RESYNC_TOLERANCE_Y;

            if (atTarget || state.transactionAcknowledged(state.setbackAnchorTxnId)) {
                state.awaitingSetback = false;
                if (!atTarget && player.isGliding()) {
                    state.reset(to, now);
                    state.trackedSpeed = PhysicsEngine.ELYTRA_FIREWORK_TERMINAL;
                } else {
                    state.reset(state.setbackTarget, now);
                }
                state.wasOnGround = clientOnGround;
                return true;
            }

            if (now - state.lastSetbackMs > SETBACK_RETELEPORT_MS) {
                teleportToTarget(player, state.setbackTarget.clone());
                state.lastSetbackMs = now;
            }
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info(String.format(
                        "[VG-Setback] %s PINNED: client at (%.1f,%.1f,%.1f) target (%.1f,%.1f,%.1f) - denying",
                        player.getName(), to.getX(), to.getY(), to.getZ(),
                        state.setbackTarget.getX(), state.setbackTarget.getY(), state.setbackTarget.getZ()));
            }
            return false;
        }

        if (state.awaitingTeleport) {
            boolean confirmed = state.transactionAcknowledged(state.teleportAnchorTxnId)
                    || (state.settleUntilMs > 0 && now >= state.settleUntilMs);
            if (!confirmed) {
                state.lastPosition     = to.clone();
                state.lastValidPosition = to.clone();
                state.lastPacketMs     = now;
                state.trackedSpeed     = 0.0;
                state.trackedVelocityY = 0.0;
                state.violationBuffer  = 0.0;
                state.wasOnGround      = clientOnGround;
                return true;
            }
            state.awaitingTeleport = false;
            state.settleUntilMs    = 0;
            state.lastPosition     = to.clone();
            state.lastValidPosition = to.clone();
            state.lastPacketMs     = now;
            state.trackedSpeed     = 0.0;
            state.trackedVelocityY = 0.0;
            state.violationBuffer  = 0.0;
            state.wasOnGround      = clientOnGround;
            return true;
        }

        if (now < state.blockedUntilMs) return false;
        if (state.blockedUntilMs > 0) {
            state.blockedUntilMs  = 0;
            state.lastPosition    = to.clone();
            state.lastValidPosition = to.clone();
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
            state.lastValidPosition = to.clone();
            state.lastPacketMs    = now;
            state.trackedSpeed    = 0.0;
            state.trackedVelocityY = 0.0;
            state.wasOnGround     = true;
            state.airTicks        = 0;
            return true;
        }

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
        boolean nowOnGround = isVehicle ? PhysicsEngine.isNearGroundAt(to) : clientOnGround;

        int expectedTicks = 1;

        int budgetTicks = (int) Math.max(1L,
                Math.min(MAX_CATCHUP_TICKS, Math.round((now - state.lastPacketMs) / 50.0)));

        if (!isVehicle && MovementUtils.isNearSlime(player)) {
            state.lastSlimeContactMs = now;
        }

        if (cfg.isTimerCheckEnabled()) {
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
        boolean exceededThisPacket = false;

        if (!checkSpecialSpeedExemption(player, state, now, cfg)) {
            double maxAllowed = computeMaxAllowedDisplacementVehicle(
                    player, state, budgetTicks, cfg, isVehicle, to, nowOnGround);

            if (packetDistance > 0.001 && packetDistance > maxAllowed) {
                double excess = packetDistance - maxAllowed;
                state.violationBuffer += excess;
                exceededThisPacket = true;

                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info(String.format(
                            "[VG] %s  actual=%.3f  max=%.3f  budget=%d  trackedSpeed=%.3f  buf=%.3f%s",
                            player.getName(), packetDistance, maxAllowed, budgetTicks,
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

        boolean normalJump = !isVehicle && state.wasOnGround && !nowOnGround && dy > 0.3;

        boolean jumpLaunched = !isVehicle && !state.wasOnGround && !nowOnGround
                && serverSideAirborne && dy > 0.3 && state.trackedVelocityY <= 0.1
                && PhysicsEngine.isNearGroundAt(state.lastPosition);

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
                    || player.hasPotionEffect(PotionEffectType.LEVITATION)
                    || MovementUtils.isClimbing(player)
                    || MovementUtils.isInBubbleColumn(player)
                    || (state.lastSlimeContactMs > 0
                            && now - state.lastSlimeContactMs < 1_000)
                    || (state.lastDamageMs > 0
                            && now - state.lastDamageMs < cfg.getKnockbackDuration())
                    || (state.lastRiptideMs > 0
                            && now - state.lastRiptideMs < cfg.getRiptideDuration());

            if (!yExempt) {
                double gravityVal = player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                        ? 0.01 : PhysicsEngine.GRAVITY;
                double maxDy = 0.0;
                double vy    = effectiveVelocityY;
                for (int t = 0; t < budgetTicks; t++) {
                    maxDy += vy;
                    vy = (vy - gravityVal) * PhysicsEngine.VERTICAL_DRAG;
                }
                double yTolerance = cfg.getPerTickTolerance() * 1.5 * budgetTicks;
                double yThreshold = maxDy * cfg.getLeniencyMultiplier() + yTolerance;
                if (dy >= 0 && dy > yThreshold) {
                    double excess = dy - yThreshold;
                    state.violationBuffer += excess;
                    exceededThisPacket = true;
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info(String.format(
                                "[VG-Y] %s  dy=%.3f  maxDy=%.3f  effVy=%.3f  budget=%d  buf=%.3f",
                                player.getName(), dy, maxDy, effectiveVelocityY,
                                budgetTicks, state.violationBuffer));
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
                boolean isJumpTick = (state.wasOnGround || jumpLaunched) && !nowOnGround;
                double maxPerTick = currentlyGliding
                        ? PhysicsEngine.simulateElytraHorizontal(state.trackedSpeed, state.trackedVelocityY)
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

            if (currentlyGliding) {
                state.trackedVelocityY = dy;
            } else if (normalJump || jumpLaunched) {
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
            if (!exceededThisPacket) {
                state.lastValidPosition = to.clone();
            }
        }

        state.lastPacketMs = now;

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

        if (plugin.isDebugEnabled() && exceededThisPacket) {
            plugin.getLogger().info(String.format(
                    "[VG-Decision] %s OVER (buffering, not cancelled) dist=%.3f budget=%d buf=%.2f",
                    player.getName(), packetDistance, budgetTicks, state.violationBuffer));
        }

        return true;
    }

    private double computeMaxAllowedDisplacement(Player player, PlayerMovementState state,
                                                 int ticks, Location to, ConfigManager cfg,
                                                 boolean toOnGround) {
        long now = System.currentTimeMillis();

        if (player.isGliding()) {
            double maxH = PhysicsEngine.simulateElytraHorizontal(state.trackedSpeed, state.trackedVelocityY);
            return maxH * ticks * cfg.getLeniencyMultiplier()
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
        boolean couldJump = !toOnGround
                && (onGround || PhysicsEngine.isNearGroundAt(state.lastPosition));

        double totalMax  = 0.0;
        double speed     = startSpeed;
        boolean grounded = onGround;

        for (int t = 0; t < ticks; t++) {
            boolean jumpTick = (t == 0) && couldJump;
            if (jumpTick) grounded = false;
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

    private boolean setback(Player player, String reason) {
        PlayerMovementState state = playerStates.get(player.getUniqueId());
        if (state == null) return false;

        final Location target = state.lastValidPosition != null
                ? state.lastValidPosition.clone()
                : player.getLocation();

        long now = System.currentTimeMillis();
        state.awaitingSetback = true;
        state.setbackTarget   = target.clone();
        state.lastSetbackMs   = now;
        state.setbackAnchorTxnId = state.lastSentTransactionId();
        state.violationBuffer = 0.0;
        state.timerViolations = 0.0;

        teleportToTarget(player, target);

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[VG] Setback " + player.getName() + " - " + reason);
        }
        return false;
    }

    private void teleportToTarget(Player player, Location target) {
        new BukkitRunnable() {
            @Override public void run() {
                if (!player.isOnline()) return;
                Location current = player.getLocation();
                target.setYaw(current.getYaw());
                target.setPitch(current.getPitch());
                player.teleport(target);
            }
        }.runTask(plugin);
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
        state.lastDamageMs   = 0;
        state.lastRiptideMs  = 0;
        beginTeleportGate(state, now);
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
        if (state == null || location == null) return;

        if (state.awaitingSetback && state.setbackTarget != null) {
            double dx = location.getX() - state.setbackTarget.getX();
            double dy = location.getY() - state.setbackTarget.getY();
            double dz = location.getZ() - state.setbackTarget.getZ();
            if (dx * dx + dy * dy + dz * dz < 0.01) {
                return;
            }
        }

        long now = System.currentTimeMillis();
        state.awaitingSetback = false;
        state.reset(location, now);
        beginTeleportGate(state, now);
    }

    private void beginTeleportGate(PlayerMovementState state, long now) {
        state.teleportAnchorTxnId  = state.lastSentTransactionId();
        state.settleUntilMs        = now + 2_000L;
        state.awaitingTeleport     = true;
    }
}
