package com.jellypudding.velocityGuard.processors;

import org.bukkit.Location;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayerMovementState {

    // Horizontal speed (blocks/tick) from the last accepted packet.
    public double trackedSpeed;

    // Predicted Y velocity (blocks/tick) for the next game tick.
    public double trackedVelocityY;

    // Whether the player was standing on a solid block when the last packet was processed.
    public boolean wasOnGround;

    // Position from the last accepted packet.
    public Location lastPosition;

    // Last position that passed every check (the anchor a setback rewinds to).
    public Location lastValidPosition;

    // Wall-clock time when the last packet was received.
    public long lastPacketMs;

    // Accumulated excess displacement (blocks). Grows when actual movement
    // exceeds the physics prediction; decays on clean packets. Punishment is
    // triggered only when this exceeds the configured threshold.
    public double violationBuffer;

    // Wall-clock time of the last damage event.
    public long lastDamageMs;

    // Whether the most recent damage source was the Ender Dragon.
    public boolean lastDragonDamage;

    // Wall-clock time of the last riptide use.
    public long lastRiptideMs;

    // Whether the player was gliding last packet.
    public boolean wasGliding;

    // Wall-clock time when elytra gliding stopped.
    public long elytraLandingMs;

    // Wall-clock time the player was last on/just above a slime block.
    public long lastSlimeContactMs;

    // Wall-clock time until all movement packets are denied.
    public long blockedUntilMs;

    // Post-teleport gate. A genuine server teleport (respawn, /tp, portal, end
    // gateway) is "settled" only once the client has provably acknowledged it via
    // the transaction clock - NOT after a fixed wall-clock window. While awaiting,
    // movement packets are accepted and used purely to re-anchor tracking (the
    // server stays in sync with the client's honest post-teleport physics), but the
    // speed/flight judgement is skipped. settleUntilMs is kept only as a lost-pong
    // fallback so a dropped transaction can never pin a player forever.
    public long settleUntilMs;
    public volatile boolean awaitingTeleport;
    public int teleportAnchorTxnId;

    public boolean awaitingSetback;
    public Location setbackTarget;
    public long lastSetbackMs;

    // Air-tick counter for flight detection.
    public int airTicks;

    // Set while the player is in creative/spectator so that the first
    // survival packet after a gamemode switch is accepted without checking.
    public boolean wasInCreative;

    // Transaction / ping system (server-anchored clock).
    public final ConcurrentLinkedDeque<long[]> transactionsSent = new ConcurrentLinkedDeque<>();

    // Transaction ids start in a recognisable range to avoid colliding with
    // ping ids any other source might use.
    private final AtomicInteger transactionIdCounter = new AtomicInteger(0x56470000);

    // Server nanotime the client has provably reached (send time of the most
    // recently acknowledged transaction).
    public volatile long playerClockAtLeast;

    // Last measured transaction round-trip (nanos) which is effectively the ping.
    public volatile long transactionPingNanos;

    public int lastTransactionReceivedId;

    // Running balance of claimed game-time (nanos). Each movement packet adds 50m.
    public long timerBalanceRealTime;
    public long lastMovementPlayerClock;
    public long knownPlayerClockTime;
    public boolean hasGottenMovementAfterTransaction;
    public double timerViolations;

    public PlayerMovementState(Location startPosition, long currentTimeMs) {
        this.lastPosition     = startPosition.clone();
        this.lastValidPosition = startPosition.clone();
        this.lastPacketMs     = currentTimeMs;
        initTimer();
    }

    private void initTimer() {
        long nowNano = System.nanoTime();
        this.playerClockAtLeast        = nowNano;
        this.lastMovementPlayerClock   = nowNano;
        this.knownPlayerClockTime      = nowNano;
        this.timerBalanceRealTime      = nowNano - 1_000_000_000L;
        this.hasGottenMovementAfterTransaction = false;
        this.timerViolations           = 0.0;
    }

    public void reset(Location position, long currentTimeMs) {
        this.trackedSpeed     = 0.0;
        this.trackedVelocityY = 0.0;
        this.wasOnGround      = true;
        this.lastPosition     = position.clone();
        this.lastValidPosition = position.clone();
        this.lastPacketMs     = currentTimeMs;
        this.violationBuffer  = 0.0;
        this.airTicks         = 0;
        this.timerViolations  = 0.0;
        this.awaitingSetback  = false;
        this.awaitingTeleport = false;
    }

    public int nextTransactionId() {
        return transactionIdCounter.getAndIncrement();
    }

    // The most recent id handed out by nextTransactionId (i.e. the last transaction
    // the server sent). Used to anchor teleport/setback acknowledgement to the
    // transaction clock. Before any transaction is sent this is one below the first
    // id, so the very first pong after a teleport confirms it.
    public int lastSentTransactionId() {
        return transactionIdCounter.get() - 1;
    }

    // True once the client has acknowledged a transaction sent at or after anchorId.
    // Ids increase monotonically from 0x56470000; compareUnsigned is wrap-safe.
    public boolean transactionAcknowledged(int anchorId) {
        return Integer.compareUnsigned(lastTransactionReceivedId, anchorId) > 0;
    }

    public void onTransactionSent(int id, long sendNano) {
        transactionsSent.add(new long[]{id, sendNano});
        while (transactionsSent.size() > 400) {
            transactionsSent.pollFirst();
        }
    }

    public boolean onTransactionResponse(int id, long nowNano) {
        boolean found = false;
        for (long[] pair : transactionsSent) {
            if (pair[0] == id) { found = true; break; }
        }
        if (!found) return false;

        long[] data;
        do {
            data = transactionsSent.pollFirst();
            if (data == null) break;
            playerClockAtLeast   = data[1];
            transactionPingNanos = nowNano - data[1];
            lastTransactionReceivedId = (int) data[0];
        } while (data[0] != id);
        return true;
    }
}
