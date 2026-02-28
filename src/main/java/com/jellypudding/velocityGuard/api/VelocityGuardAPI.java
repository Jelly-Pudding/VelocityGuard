package com.jellypudding.velocityGuard.api;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.entity.Player;

public class VelocityGuardAPI {

    /** Default air-tick threshold matching VelocityGuard's lenient global behaviour (~2 s). */
    public static final int DEFAULT_AIR_TICK_THRESHOLD = 40;

    /**
     * Recommended air-tick threshold for strict zone enforcement (~1 s).
     * A normal vanilla jump lands by ~tick 15, so this triggers shortly after
     * any sustained flight begins.
     */
    public static final int STRICT_AIR_TICK_THRESHOLD = 20;

    private final VelocityGuard plugin;

    public VelocityGuardAPI(VelocityGuard plugin) {
        this.plugin = plugin;
    }

    public void enableFlightEnforcement(Player player) {
        enableFlightEnforcement(player, true, STRICT_AIR_TICK_THRESHOLD, true);
    }

    public void enableFlightEnforcement(Player player, boolean groundOnViolation) {
        enableFlightEnforcement(player, groundOnViolation, STRICT_AIR_TICK_THRESHOLD, true);
    }

    public void enableFlightEnforcement(Player player, boolean groundOnViolation, int airTickThreshold) {
        enableFlightEnforcement(player, groundOnViolation, airTickThreshold, true);
    }

    public void enableFlightEnforcement(Player player, boolean groundOnViolation,
                                        int airTickThreshold, boolean groundWhenStationary) {
        if (player == null) return;
        int threshold = Math.max(15, airTickThreshold);
        plugin.getMovementChecker().addFlightEnforcement(
                player.getUniqueId(), groundOnViolation, threshold, groundWhenStationary);
    }

    public void disableFlightEnforcement(Player player) {
        if (player == null) return;
        plugin.getMovementChecker().removeFlightEnforcement(player.getUniqueId());
    }

    public boolean isFlightEnforcementActive(Player player) {
        if (player == null) return false;
        return plugin.getMovementChecker().isFlightEnforced(player.getUniqueId());
    }
}
