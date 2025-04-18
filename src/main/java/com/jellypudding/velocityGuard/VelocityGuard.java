package com.jellypudding.velocityGuard;

import com.jellypudding.velocityGuard.commands.VelocityGuardCommand;
import com.jellypudding.velocityGuard.listeners.PacketListener;
import com.jellypudding.velocityGuard.listeners.DamageListener;
import com.jellypudding.velocityGuard.listeners.TeleportListener;
import com.jellypudding.velocityGuard.listeners.TridentListener;
import com.jellypudding.velocityGuard.managers.ConfigManager;
import com.jellypudding.velocityGuard.processors.MovementChecker;
import org.bukkit.plugin.java.JavaPlugin;

public final class VelocityGuard extends JavaPlugin {

    private ConfigManager configManager;
    private MovementChecker movementChecker;
    private PacketListener packetListener;
    private DamageListener damageListener;
    private TeleportListener teleportListener;
    private TridentListener tridentListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);

        this.movementChecker = new MovementChecker(this);

        this.packetListener = new PacketListener(this);
        this.damageListener = new DamageListener(this);
        this.teleportListener = new TeleportListener(this);
        this.tridentListener = new TridentListener(this);

        // Install packet handlers
        packetListener.inject();

        // Register event listeners
        getServer().getPluginManager().registerEvents(this.damageListener, this);
        getServer().getPluginManager().registerEvents(this.teleportListener, this);
        getServer().getPluginManager().registerEvents(this.tridentListener, this);

        // Register commands
        VelocityGuardCommand command = new VelocityGuardCommand(this);
        getCommand("velocityguard").setExecutor(command);
        getCommand("velocityguard").setTabCompleter(command);

        getLogger().info("VelocityGuard has been enabled.");
    }

    @Override
    public void onDisable() {
        // Remove packet handlers
        if (packetListener != null) {
            packetListener.uninject();
        }

        getLogger().info("VelocityGuard has been disabled.");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MovementChecker getMovementChecker() {
        return movementChecker;
    }

    public boolean isDebugEnabled() {
        return configManager != null && configManager.isDebugModeEnabled();
    }

    public void reloadConfigManager() {
        reloadConfig();
        this.configManager = new ConfigManager(this);
        this.movementChecker = new MovementChecker(this);
    }
}
