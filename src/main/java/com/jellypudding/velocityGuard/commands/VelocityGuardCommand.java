package com.jellypudding.velocityGuard.commands;

import com.jellypudding.velocityGuard.VelocityGuard;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class VelocityGuardCommand implements CommandExecutor, TabCompleter {

    private final VelocityGuard plugin;

    public VelocityGuardCommand(VelocityGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("velocityguard.admin")) {
            sender.sendMessage("§c[VelocityGuard] §fYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6[VelocityGuard] §fUsage: /velocityguard reload");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfigManager();
            sender.sendMessage("§a[VelocityGuard] §fConfiguration has been reloaded.");
            return true;
        }

        sender.sendMessage("§c[VelocityGuard] §fUnknown command. Usage: /velocityguard reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.add("reload");
        }

        return completions;
    }
}
