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

    // Upper bound on how many ticks a single packet's over-limit BUDGET may span.
    // Lets a legitimately late/coalesced packet (lag, packet loss) carry several ticks
    // of distance without being flagged, while still catching a blatant single-packet
    // teleport. Only affects the over-limit decision - never the per-tick speed tracking.
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

            // Resync as soon as the client has actually arrived back at the target.
            // The tolerance is what makes this work at any ping: in-flight far packets
            // from before the teleport landed keep being denied (so the cheat nets no
            // ground), and the moment a packet near the target arrives we hand control
            // back. Position-based (not transaction-based) so latency can't livelock it.
            if (sHoriz <= SETBACK_RESYNC_TOLERANCE && sVert <= SETBACK_RESYNC_TOLERANCE_Y) {
                state.awaitingSetback = false;
                state.reset(state.setbackTarget, now);
                state.wasOnGround = clientOnGround;
                return true;
            }

            // Still away from the target: re-issue the teleport periodically in case the
            // first one was lost or the client is still catching up.
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

        // Post-teleport gate (respawn, /tp, portal, end gateway). A genuine server
        // teleport legitimately looks like a huge jump, so we must not judge speed
        // until the client has provably acknowledged the teleport. Unlike the old
        // wall-clock settle window we ACCEPT these packets (return true) and re-anchor
        // tracking to them, rather than dropping them - dropping the player's honest
        // post-teleport fall packets desynced the server (the server held the player
        // at the teleport Y while the client fell), which is what placed players under
        // the block after end-gateway teleports. Completion is transaction-anchored
        // (ping-correct for any latency); settleUntilMs is only a lost-pong fallback.
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
            // Acknowledged: take this packet as the fresh post-teleport anchor.
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

        // Block window: all movement packets denied for the configured duration.
        // On expiry the first packet is accepted as a fresh anchor - no teleport.
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

        // Per-tick speed tracking treats every packet as exactly ONE client tick (Grim's
        // model). We must NOT divide the tracked speed by a wall-clock tick count: dt
        // cannot tell "one tick that arrived late" from "two ticks coalesced" (both look
        // like ~80ms), and dividing corrupts the tracked speed during bursty movement
        // (sprint jumps) -> false rubberbands. So expectedTicks stays 1 for tracking.
        int expectedTicks = 1;

        // The OVER-LIMIT budget is separate: a packet that genuinely arrived late may
        // legitimately carry several ticks of motion. We size only the allowed-distance
        // budget to the real time since the last RECEIVED packet (capped) so those late
        // packets are not falsely flagged/cancelled - the cause of jitter rubberbanding -
        // WITHOUT touching the per-tick tracking above. lastPacketMs advances on every
        // packet (clean or cancelled), so a constant-rate cheat always sees a 1-tick
        // budget (dt ~50ms) and stays pinned, while a real network gap (lost/coalesced
        // packet) widens the budget for that one packet. Safe vs timer cheats: they send
        // packets EARLY (dt < 50ms -> 1 tick), and TimerCheck polices packet rate anyway.
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

        // When the elytra speed check is disabled, gliding movement is exempt from the
        // horizontal speed check entirely (vertical flight already ignores gliding).
        boolean elytraExempt = currentlyGliding && !cfg.isElytraCheckEnabled();

        if (!elytraExempt && !checkSpecialSpeedExemption(player, state, now, cfg)) {
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
                // Only flag upward or hovering violations (dy >= 0). When the player is
                // descending (dy < 0), a landing mid-arc would look like excess without
                // actually being a cheat, producing false positives.
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

        // Tracking advances on EVERY packet that did not itself trip a full setback
        // (Grim's model). We do NOT freeze lastPosition on a merely-over-limit packet:
        // freezing it makes the next normal packet look ever farther from the stale
        // anchor, snowballing a single misprediction into a guaranteed setback (and, with
        // trackedSpeed reset to 0 after a setback, an infinite rubberband loop). By
        // letting lastPosition/trackedSpeed follow the player, per-tick deltas stay ~1
        // tick and the prediction re-converges within a packet or two. Zero net progress
        // is still guaranteed two other ways:
        //   - trackedSpeed is capped at the per-tick physics prediction (maxPerTick), so a
        //     cheat's real speed is never baked in -> maxAllowed stays low -> it keeps
        //     accruing buffer until the threshold fires a setback.
        //   - lastValidPosition (the setback target) advances ONLY on clean packets, so a
        //     sustained cheat (no clean packets) is always rewound to where it began.
        if (!speedViolation) {
            double actualPerTick = packetDistance / expectedTicks;

            if (isVehicle) {
                state.trackedSpeed = Math.min(actualPerTick, PhysicsEngine.MAX_VEHICLE_SPEED);
            } else {
                // isJumpTick covers both normal jumps and the brief-flicker jump case so that
                // trackedSpeed is correctly seeded with sprint-jump speed for subsequent ticks.
                boolean isJumpTick = (state.wasOnGround || jumpLaunched) && !nowOnGround;
                double maxPerTick = currentlyGliding
                        ? cfg.getElytraSpeed()
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
            // The setback anchor only banks a position that was within the limits, so a
            // setback always rewinds past any over-limit movement => zero net progress.
            if (!exceededThisPacket) {
                state.lastValidPosition = to.clone();
            }
        }

        // Always advance lastPacketMs, even for a cancelled (over-limit) packet. budgetTicks
        // is derived from the time since this stamp; if it only moved on clean packets it
        // would keep growing while a cheat is being cancelled, inflating the budget until a
        // cheat packet slipped through. Updating it every packet keeps a constant-rate cheat
        // pinned at a 1-tick budget (zero progress), while a genuinely late/coalesced legit
        // packet (gap since the last RECEIVED packet) still gets its multi-tick budget.
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

        // Over the limit but below the setback threshold: let the packet through (the
        // server stays in sync and the prediction re-converges) while the buffer keeps
        // climbing on continued over-limit packets. Because lastValidPosition was NOT
        // advanced for it, the eventual setback still rewinds past everything since the
        // last clean packet => zero net progress. A packet whose excess alone crosses the
        // threshold already returned via setback() above, so a blatant teleport/speed
        // burst is cancelled outright and never forwarded.
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
            return cfg.getElytraSpeed() * ticks * cfg.getLeniencyMultiplier()
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

    // Rewind the player to their last valid position and enter the enforcement
    // window (see processMovement). Always returns false so the offending packet
    // is cancelled in one statement.
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

    // Arm the transaction-anchored post-teleport gate. The teleport is treated as
    // settled once the client acknowledges a transaction sent after this point;
    // settleUntilMs is a generous lost-pong fallback only.
    private void beginTeleportGate(PlayerMovementState state, long now) {
        state.teleportAnchorTxnId  = state.lastSentTransactionId();
        state.settleUntilMs        = now + 2_000L;
        // Volatile write LAST: publishes the two fields above to the Netty thread,
        // which gates on this flag before reading them.
        state.awaitingTeleport     = true;
    }
}
