package com.jellypudding.velocityGuard.processors;

import org.bukkit.Location;

public class PlayerMovementState {

    // Horizontal speed (blocks/tick) from the last accepted packet.
    public double trackedSpeed;

    // Predicted Y velocity (blocks/tick) for the next game tick.
    public double trackedVelocityY;

    // Whether the player was standing on a solid block when the last packet was processed.
    public boolean wasOnGround;

    // Position from the last accepted packet.
    public Location lastPosition;

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

    // Wall-clock time until all movement packets are denied.
    public long blockedUntilMs;

    // Short post-teleport settle window: packets are denied until the server
    // teleport (respawn, /tp, portal) reaches the client.  On expiry the first
    // packet is accepted as a fresh anchor without any violation check.
    public long settleUntilMs;

    // Air-tick counter for flight detection.
    public int airTicks;

    // Set while the player is in creative/spectator so that the first
    // survival packet after a gamemode switch is accepted without checking.
    public boolean wasInCreative;

    public PlayerMovementState(Location startPosition, long currentTimeMs) {
        this.trackedSpeed     = 0.0;
        this.trackedVelocityY = 0.0;
        this.wasOnGround      = true;
        this.lastPosition    = startPosition.clone();
        this.lastPacketMs    = currentTimeMs;
        this.violationBuffer = 0.0;
        this.lastDamageMs    = 0;
        this.lastDragonDamage = false;
        this.lastRiptideMs   = 0;
        this.wasGliding      = false;
        this.elytraLandingMs = 0;
        this.blockedUntilMs  = 0;
        this.settleUntilMs   = 0;
        this.airTicks        = 0;
        this.wasInCreative   = false;
    }
    
    // Resets transient tracking to a fresh baseline. Call this after a
    // teleport or when unblocking a player.
    public void reset(Location position, long currentTimeMs) {
        this.trackedSpeed     = 0.0;
        this.trackedVelocityY = 0.0;
        this.wasOnGround      = true;
        this.lastPosition    = position.clone();
        this.lastPacketMs    = currentTimeMs;
        this.violationBuffer = 0.0;
        this.airTicks        = 0;
    }
}
