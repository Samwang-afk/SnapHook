package com.snaphook;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SnapHookPlugin extends JavaPlugin {

    public static final String PDC_KEY = "snap_hook";
    public static final int MAX_DURABILITY = 50;
    private NamespacedKey nbtKey;
    private NamespacedKey recipeKey;
    private final Map<String, NamespacedKey> advancementKeys = new HashMap<>();
    private final double maxDistance = 150.0;
    private static final String[][] ADVANCEMENTS = {
            {"snap_hook", "Snap Hook", "钩锁已校准，准备切入战场", "task"},
            {"first_hook", "第一钩", "第一次命中锚点", "task"},
            {"chain_link", "链路建立", "钩线完整命中目标", "task"},
            {"launch", "起飞许可", "第一次被钩锁拉动", "task"},
            {"safe_release", "安全脱钩", "Space 脱钩一次", "task"},
            {"skybridge", "空岛摆渡人", "空中使用钩锁", "goal"},
            {"low_flight", "贴地飞行", "低空钩锁拉拽", "goal"},
            {"high_recover", "高空回收", "从高处钩回地面", "goal"},
            {"edge_save", "边缘救援", "掉落中成功钩住", "goal"},
            {"no_fall", "不是摔，是降落", "坠落保护抵消伤害", "goal"},
            {"live_anchor", "活体锚点", "勾中任意实体", "task"},
            {"player_anchor", "你过来啊", "勾中玩家", "goal"},
            {"half_heart", "半颗心警告", "勾中实体造成伤害", "task"},
            {"aerial_intercept", "空中截击", "滑翔时勾中实体", "goal"},
            {"close_anchor", "贴脸开链", "近距离勾中实体", "task"},
            {"strike_mode", "Strike Mode", "第一次进入 Strike", "goal"},
            {"meteor", "陨落打击", "Strike 落地爆炸", "challenge"},
            {"shockwave", "冲击波", "Strike 命中 2 个以上目标", "challenge"},
            {"high_speed", "高速入场", "Strike 速度达到阈值", "challenge"},
            {"captain", "绝望的机长", "鞘翅飞行中 Strike 命中玩家", "challenge"}
    };

    @Override
    public void onEnable() {
        nbtKey = new NamespacedKey(this, PDC_KEY);
        recipeKey = new NamespacedKey(this, "snap_hook_recipe");
        getServer().getPluginManager().registerEvents(new SnapHookListener(this), this);
        registerRecipe();
        registerAdvancement();
        getLogger().info("SnapHook enabled.");
    }

    public double getMaxDistance() { return maxDistance; }

    private void registerRecipe() {
        getServer().removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createSnapHook());
        recipe.shape("SBS", "BHB", "SPS");
        recipe.setIngredient('S', Material.STRING);
        recipe.setIngredient('B', Material.IRON_BLOCK);
        recipe.setIngredient('H', Material.TRIPWIRE_HOOK);
        recipe.setIngredient('P', Material.BLAZE_POWDER);
        getServer().addRecipe(recipe);
    }

    private void registerAdvancement() {
        advancementKeys.clear();
        for (String[] advancement : ADVANCEMENTS) {
            NamespacedKey key = new NamespacedKey(this, advancement[0]);
            advancementKeys.put(advancement[0], key);
            Bukkit.getUnsafe().removeAdvancement(key);
        }
        String rootJson = """
                {
                  "display": {
                    "icon": {"id": "minecraft:tripwire_hook"},
                    "title": {"text": "%s", "color": "gold"},
                    "description": {"text": "%s", "color": "gray"},
                    "frame": "task",
                    "show_toast": true,
                    "announce_to_chat": false,
                    "hidden": false
                  },
                  "criteria": {
                    "manual": {"trigger": "minecraft:impossible"}
                  }
                }
                """.formatted(ADVANCEMENTS[0][1], ADVANCEMENTS[0][2]);
        Bukkit.getUnsafe().loadAdvancement(advancementKeys.get("snap_hook"), rootJson);

        String parent = advancementKeys.get("snap_hook").toString();
        for (int i = 1; i < ADVANCEMENTS.length; i++) {
            String[] advancement = ADVANCEMENTS[i];
            String json = """
                    {
                      "parent": "%s",
                      "display": {
                        "icon": {"id": "minecraft:tripwire_hook"},
                        "title": {"text": "%s", "color": "gold"},
                        "description": {"text": "%s", "color": "gray"},
                        "frame": "%s",
                        "show_toast": true,
                        "announce_to_chat": false,
                        "hidden": false
                      },
                      "criteria": {
                        "manual": {"trigger": "minecraft:impossible"}
                      }
                    }
                    """.formatted(parent, advancement[1], advancement[2], advancement[3]);
            Bukkit.getUnsafe().loadAdvancement(advancementKeys.get(advancement[0]), json);
        }
    }

    public void grantSnapHookAdvancement(Player player) {
        grantAdvancement(player, "snap_hook");
    }

    public void grantAdvancement(Player player, String id) {
        NamespacedKey key = advancementKeys.get(id);
        if (key == null) return;
        Advancement advancement = Bukkit.getAdvancement(key);
        if (advancement == null) return;
        var progress = player.getAdvancementProgress(advancement);
        if (!progress.isDone()) progress.awardCriteria("manual");
    }

    public ItemStack createSnapHook() {
        ItemStack item = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(nbtKey, PersistentDataType.INTEGER, 1);
        meta.setMaxStackSize(1);
        if (meta instanceof Damageable damageable) {
            damageable.setMaxDamage(MAX_DURABILITY);
            damageable.setDamage(0);
        }
        meta.setEnchantmentGlintOverride(true);
        meta.setDisplayName(ChatColor.GOLD + "Snap Hook");
        String distStr = maxDistance < 0 ? "无限" : String.valueOf((int) maxDistance);
        meta.setLore(List.of(
                ChatColor.GRAY.toString() + ChatColor.ITALIC + "右键锁定 · 自动发射 · 空岛机动",
                "",
                ChatColor.DARK_AQUA + "▶ 右键 " + ChatColor.GRAY + "锁定并发射",
                ChatColor.DARK_AQUA + "▶ Space " + ChatColor.GRAY + "脱钩返还70%冷却",
                ChatColor.DARK_AQUA + "▶ 最大距离 " + ChatColor.GRAY + distStr + "格",
                ChatColor.DARK_AQUA + "▶ 耐久 " + ChatColor.GRAY + MAX_DURABILITY + "次",
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

    public boolean damageSnapHook(ItemStack item, int amount) {
        if (!isSnapHook(item) || !(item.getItemMeta() instanceof Damageable meta)) return false;
        if (!meta.hasMaxStackSize()) meta.setMaxStackSize(1);
        if (!meta.hasMaxDamage()) meta.setMaxDamage(MAX_DURABILITY);
        int damage = meta.getDamage() + amount;
        if (damage >= MAX_DURABILITY) {
            item.setAmount(item.getAmount() - 1);
            return true;
        }
        meta.setDamage(damage);
        item.setItemMeta(meta);
        return false;
    }
}
