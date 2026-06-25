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
    private static final List<String> DISTANCES = List.of("-1", "8", "16", "24", "32", "48", "64", "96", "128", "180");

    public SnapHookCommand(SnapHookPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sendHelp(sender); return true; }

        return switch (args[0].toLowerCase()) {
            case "give" -> give(sender, args);
            case "distance" -> distance(sender, args);
            default -> { sendHelp(sender); yield true; }
        };
    }

    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("snaphook.give")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令。");
            return true;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { sender.sendMessage(ChatColor.RED + "玩家不在线: " + args[1]); return true; }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(ChatColor.RED + "控制台用法: /snaphook give <player>");
            return true;
        }
        ItemStack hook = plugin.createSnapHook();
        target.getInventory().addItem(hook);
        target.sendMessage(ChatColor.GREEN + "获得 " + hook.getItemMeta().getDisplayName());
        if (!target.equals(sender)) sender.sendMessage(ChatColor.GREEN + "已给予 " + target.getName() + " Snap Hook");
        return true;
    }

    private boolean distance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("snaphook.distance")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令。");
            return true;
        }
        if (args.length < 2) {
            String cur = plugin.getMaxDistance() < 0 ? "无限" : String.valueOf((int) plugin.getMaxDistance());
            sender.sendMessage(ChatColor.YELLOW + "当前最大距离: " + ChatColor.GOLD + cur + " 格");
            sender.sendMessage(ChatColor.GRAY + "用法: /snaphook distance <-1|5-180> (-1=无限)");
            return true;
        }
        try {
            double val = Double.parseDouble(args[1]);
            if ((val < 5 && val != -1) || val > 180) { sender.sendMessage(ChatColor.RED + "范围: -1 (无限) 或 5-180"); return true; }
            plugin.setMaxDistance(val);
            String label = val < 0 ? "无限" : String.valueOf((int) val);
            sender.sendMessage(ChatColor.GREEN + "Snap Hook 最大距离已设为 " + ChatColor.GOLD + label + " 格");
        } catch (NumberFormatException ignored) {
            sender.sendMessage(ChatColor.RED + "无效数值: " + args[1]);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) { list.add("give"); list.add("distance"); }
        else if (args.length == 2 && args[0].equalsIgnoreCase("give"))
            for (Player player : Bukkit.getOnlinePlayers()) list.add(player.getName());
        else if (args.length == 2 && args[0].equalsIgnoreCase("distance"))
            list.addAll(DISTANCES);
        return list;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== SnapHook ===");
        sender.sendMessage(ChatColor.YELLOW + "/snaphook give [player]" + ChatColor.GRAY + " - 获得 Snap Hook");
        sender.sendMessage(ChatColor.YELLOW + "/snaphook distance <-1|5-180>" + ChatColor.GRAY + " - 设置最大距离 (-1=无限)");
    }
}
