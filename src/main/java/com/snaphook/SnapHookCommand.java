package com.snaphook;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class SnapHookCommand implements CommandExecutor, TabCompleter {
    private final SnapHookPlugin plugin;

    public SnapHookCommand(SnapHookPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
            sendHelp(sender);
            return true;
        }
        if (!sender.hasPermission("snaphook.give")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令。");
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "玩家不在线: " + args[1]);
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(ChatColor.RED + "控制台用法: /snaphook give <player>");
            return true;
        }

        ItemStack hook = plugin.createSnapHook();
        target.getInventory().addItem(hook);
        target.sendMessage(ChatColor.GREEN + "获得 " + hook.getItemMeta().getDisplayName());
        if (!target.equals(sender)) {
            sender.sendMessage(ChatColor.GREEN + "已给予 " + target.getName() + " Snap Hook");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            list.add("give");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player player : Bukkit.getOnlinePlayers()) list.add(player.getName());
        }
        return list;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== SnapHook ===");
        sender.sendMessage(ChatColor.YELLOW + "/snaphook give" + ChatColor.GRAY + " - 获得 Snap Hook");
        sender.sendMessage(ChatColor.YELLOW + "/snaphook give <player>" + ChatColor.GRAY + " - 给玩家 Snap Hook");
    }
}
