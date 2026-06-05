package com.jellypudding.velocityGuard.managers;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    private final FileConfiguration config;

    private final double perTickTolerance;

    private final double violationThreshold;

    private final double violationDecay;

    private final double knockbackMultiplier;
    private final int    knockbackDuration;
    private final double riptideMultiplier;
    private final int    riptideDuration;
    private final boolean elytraCheckEnabled;
    private final double elytraSpeed;
    private final int    elytraLandingDuration;
    private final double vehicleSpeedMultiplier;
    private final double vehicleIceSpeedMultiplier;

    private final double leniencyMultiplier;

    private final boolean flightCheckEnabled;

    private final boolean timerCheckEnabled;
    private final long    timerDriftNanos;
    private final int     timerMaxViolations;

    private final boolean debugMode;

    private static final double DEF_PER_TICK_TOLERANCE      = 0.08;
    private static final double DEF_VIOLATION_THRESHOLD     = 2.0;
    private static final double DEF_VIOLATION_DECAY         = 0.15;
    private static final double DEF_KNOCKBACK_MULTIPLIER    = 6.0;
    private static final int    DEF_KNOCKBACK_DURATION      = 1_000;
    private static final double DEF_RIPTIDE_MULTIPLIER      = 2.5;
    private static final int    DEF_RIPTIDE_DURATION        = 3_000;
    private static final boolean DEF_ELYTRA_CHECK_ENABLED   = true;
    private static final double DEF_ELYTRA_SPEED            = 4.0;
    private static final int    DEF_ELYTRA_LANDING_DURATION = 1_500;
    private static final double DEF_VEHICLE_SPEED_MULT      = 1.5;
    private static final double DEF_VEHICLE_ICE_SPEED_MULT  = 4.3;
    private static final double DEF_LENIENCY_MULTIPLIER     = 1.10;
    private static final boolean DEF_FLIGHT_CHECK_ENABLED   = true;
    private static final boolean DEF_TIMER_CHECK_ENABLED     = true;
    private static final int     DEF_TIMER_DRIFT_MILLIS      = 120;
    private static final int     DEF_TIMER_MAX_VIOLATIONS    = 5;

    public ConfigManager(VelocityGuard plugin) {
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();

        perTickTolerance      = Math.max(0.0,  config.getDouble("checks.speed.per-tick-tolerance",     DEF_PER_TICK_TOLERANCE));
        violationThreshold    = Math.max(0.1,  config.getDouble("checks.speed.violation-threshold",    DEF_VIOLATION_THRESHOLD));
        violationDecay        = Math.max(0.01, config.getDouble("checks.speed.violation-decay",        DEF_VIOLATION_DECAY));
        knockbackMultiplier   = Math.max(0.5,  config.getDouble("checks.speed.knockback.multiplier",   DEF_KNOCKBACK_MULTIPLIER));
        knockbackDuration     = Math.max(200,  config.getInt   ("checks.speed.knockback.duration",     DEF_KNOCKBACK_DURATION));
        riptideMultiplier     = Math.max(1.0,  config.getDouble("checks.speed.riptide.multiplier",     DEF_RIPTIDE_MULTIPLIER));
        riptideDuration       = Math.max(500,  config.getInt   ("checks.speed.riptide.duration",       DEF_RIPTIDE_DURATION));
        elytraCheckEnabled    = config.getBoolean("checks.speed.elytra.enabled", DEF_ELYTRA_CHECK_ENABLED);
        elytraSpeed           = Math.max(1.0,  config.getDouble("checks.speed.elytra.max-speed",       DEF_ELYTRA_SPEED));
        elytraLandingDuration = Math.max(500,  config.getInt   ("checks.speed.elytra.landing-duration",DEF_ELYTRA_LANDING_DURATION));
        vehicleSpeedMultiplier    = Math.max(1.0, config.getDouble("checks.speed.vehicle-speed-multiplier",     DEF_VEHICLE_SPEED_MULT));
        vehicleIceSpeedMultiplier = Math.max(1.0, config.getDouble("checks.speed.vehicle-ice-speed-multiplier", DEF_VEHICLE_ICE_SPEED_MULT));
        leniencyMultiplier        = Math.max(1.0, config.getDouble("checks.leniency-multiplier",               DEF_LENIENCY_MULTIPLIER));
        flightCheckEnabled        = config.getBoolean("checks.flight.enabled", DEF_FLIGHT_CHECK_ENABLED);

        timerCheckEnabled  = config.getBoolean("checks.timer.enabled", DEF_TIMER_CHECK_ENABLED);
        timerDriftNanos    = Math.max(0, config.getInt("checks.timer.drift-millis", DEF_TIMER_DRIFT_MILLIS)) * 1_000_000L;
        timerMaxViolations = Math.max(1, config.getInt("checks.timer.max-violations", DEF_TIMER_MAX_VIOLATIONS));

        debugMode = config.getBoolean("settings.debug-mode", false);
    }

    public double getPerTickTolerance()           { return perTickTolerance; }
    public double getViolationThreshold()         { return violationThreshold; }
    public double getViolationDecay()             { return violationDecay; }
    public double getKnockbackMultiplier()        { return knockbackMultiplier; }
    public int    getKnockbackDuration()          { return knockbackDuration; }
    public double getRiptideMultiplier()          { return riptideMultiplier; }
    public int    getRiptideDuration()            { return riptideDuration; }
    public boolean isElytraCheckEnabled()          { return elytraCheckEnabled; }
    public double getElytraSpeed()                 { return elytraSpeed; }
    public int    getElytraLandingDuration()      { return elytraLandingDuration; }
    public double getVehicleSpeedMultiplier()     { return vehicleSpeedMultiplier; }
    public double getVehicleIceSpeedMultiplier()  { return vehicleIceSpeedMultiplier; }
    public double getLeniencyMultiplier()         { return leniencyMultiplier; }
    public boolean isFlightCheckEnabled()         { return flightCheckEnabled; }
    public boolean isTimerCheckEnabled()          { return timerCheckEnabled; }
    public long    getTimerDriftNanos()           { return timerDriftNanos; }
    public int     getTimerMaxViolations()        { return timerMaxViolations; }
    public boolean isDebugModeEnabled()           { return debugMode; }
}
