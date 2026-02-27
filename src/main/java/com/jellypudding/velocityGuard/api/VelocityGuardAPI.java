package com.jellypudding.velocityGuard.api;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.entity.Player;

public class VelocityGuardAPI {

    private final VelocityGuard plugin;

    public VelocityGuardAPI(VelocityGuard plugin) {
        this.plugin = plugin;
    }

    public void enableFlightEnforcement(Player player) {
        enableFlightEnforcement(player, true);
    }

    public void enableFlightEnforcement(Player player, boolean groundOnViolation) {
        if (player == null) return;
        plugin.getMovementChecker().addFlightEnforcement(player.getUniqueId(), groundOnViolation);
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
