package com.snaphook;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class SnapHookPlugin extends JavaPlugin {

    public static final String PDC_KEY = "snap_hook";
    private NamespacedKey nbtKey;
    private double maxDistance = 32.0;

    @Override
    public void onEnable() {
        nbtKey = new NamespacedKey(this, PDC_KEY);
        getServer().getPluginManager().registerEvents(new SnapHookListener(this), this);
        SnapHookCommand command = new SnapHookCommand(this);
        var pluginCommand = getCommand("snaphook");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }
        getLogger().info("SnapHook enabled.");
    }

    public double getMaxDistance() { return maxDistance; }
    public void setMaxDistance(double v) { maxDistance = Math.max(-1.0, Math.min(180.0, v)); }

    public ItemStack createSnapHook() {
        ItemStack item = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(nbtKey, PersistentDataType.INTEGER, 1);
        meta.setEnchantmentGlintOverride(true);
        meta.setDisplayName(ChatColor.GOLD + "Snap Hook");
        String distStr = maxDistance < 0 ? "无限" : String.valueOf((int) maxDistance);
        meta.setLore(List.of(
                ChatColor.GRAY.toString() + ChatColor.ITALIC + "瞬间锚定 · 快速拉拽 · 空岛机动",
                "",
                ChatColor.DARK_AQUA + "▶ 右键 " + ChatColor.GRAY + "锁定锚点",
                ChatColor.DARK_AQUA + "▶ 左键 " + ChatColor.GRAY + "发射拉拽",
                ChatColor.DARK_AQUA + "▶ 最大距离 " + ChatColor.GRAY + distStr + "格",
                ChatColor.DARK_AQUA + "▶ 冷却 " + ChatColor.GRAY + "3.5秒"
        ));
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSnapHook(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Integer val = item.getItemMeta().getPersistentDataContainer().get(nbtKey, PersistentDataType.INTEGER);
        return val != null && val == 1;
    }
}
