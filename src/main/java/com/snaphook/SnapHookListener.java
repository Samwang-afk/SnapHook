package com.snaphook;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SnapHookListener implements Listener {

    private static final long NORMAL_COOLDOWN_MS = 3500;
    private static final long FAIL_COOLDOWN_MS = 600;
    private static final int MIN_PULL_TICKS = 8;
    private static final int MAX_PULL_TICKS = 20;
    private static final int ANCHOR_TICKS = 2;
    private static final int ANCHORED_WINDOW_TICKS = 60;
    private static final long FALL_PROTECTION_MS = 500;
    private static final double TARGET_OFFSET = 1.0;
    private static final double SPRING_K = 0.7;
    private static final double SPRING_DAMP = 0.45;
    private static final double LANDING_ZONE = 4.0;

    private final SnapHookPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> failCooldowns = new HashMap<>();
    private final Set<UUID> rightClickThisTick = new HashSet<>();
    private final Map<UUID, HookState> hookStates = new HashMap<>();
    private final Map<UUID, Long> fallProtection = new HashMap<>();
    private enum Phase { ANCHORED, LAUNCHING }

    private static class HookState {
        Location target;
        UUID targetEntityId;
        double initialDist;
        int totalPullTicks;
        int elapsed;
        int anchoredTicks;
        boolean targetIsEntity;
        Phase phase;

        boolean isAnchoring() { return phase == Phase.LAUNCHING && elapsed < ANCHOR_TICKS; }
    }

    public SnapHookListener(SnapHookPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        rightClickThisTick.clear();

        Iterator<Map.Entry<UUID, Long>> it = cooldowns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (now >= e.getValue()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && p.isOnline()) p.sendActionBar(ChatColor.GREEN + "Snap Hook 就绪");
                it.remove();
                continue;
            }
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) { it.remove(); continue; }
            double left = (e.getValue() - now) / 1000.0;
            p.sendActionBar(ChatColor.GOLD + "Snap Hook 冷却: " + ChatColor.RED + String.format("%.2f", left) + "s");
        }

        it = failCooldowns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (now >= e.getValue()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && p.isOnline()) p.sendActionBar(ChatColor.GREEN + "Snap Hook 就绪");
                it.remove();
                continue;
            }
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) { it.remove(); continue; }
            double left = (e.getValue() - now) / 1000.0;
            p.sendActionBar(ChatColor.GOLD + "Snap Hook 冷却: " + ChatColor.RED + String.format("%.2f", left) + "s");
        }

        for (UUID uuid : new HashSet<>(hookStates.keySet())) {
            HookState s = hookStates.get(uuid);
            if (s == null) continue;
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || player.isDead()) { cleanupState(uuid); continue; }
            if (!player.getWorld().equals(s.target.getWorld())) { cleanupState(uuid); continue; }

            switch (s.phase) {
                case ANCHORED -> tickAnchored(uuid, player, s);
                case LAUNCHING -> tickLaunching(uuid, player, s);
            }
        }

        it = fallProtection.entrySet().iterator();
        while (it.hasNext()) { if (now >= it.next().getValue()) it.remove(); }
    }

    private void tickAnchored(UUID uuid, Player player, HookState s) {
        s.anchoredTicks++;
        int remaining = Math.max(0, ANCHORED_WINDOW_TICKS - s.anchoredTicks);
        if (remaining <= 0) { cancelAnchor(player); return; }
        if (s.targetEntityId != null) {
            Entity e = Bukkit.getEntity(s.targetEntityId);
            if (e == null || e.isDead() || e.getLocation().distance(s.target) > 5.0)
            { cancelAnchor(player); return; }
        }
        if (isUnsafe(s.target)) { cancelAnchor(player); return; }
        if (player.isSneaking()) { cancelAnchor(player); return; }

        int sec = remaining / 20 + 1;
        String targetType = s.targetIsEntity ? "实体" : "地形";
        String msg = ChatColor.YELLOW + "锚点已锁定" + ChatColor.GRAY + " | 目标: " + ChatColor.GOLD + targetType
                + ChatColor.GRAY + " | " + ChatColor.GREEN + "[左键]发射"
                + ChatColor.GRAY + " (" + sec + "秒)";
        if (!player.isOnGround()) msg += ChatColor.GRAY + " | " + ChatColor.RED + "[Shift]脱离";
        player.sendActionBar(msg);

        spawnAnchorPulse(player, s.target);
        spawnRopeLine(player, s.target);
    }

    private void tickLaunching(UUID uuid, Player player, HookState s) {
        if (player.isSneaking()) { cancelPull(player, s); return; }
        if (s.targetEntityId != null) {
            Entity e = Bukkit.getEntity(s.targetEntityId);
            if (e == null || e.isDead() || e.getLocation().distance(s.target) > 5.0)
            { cancelPull(player, s); return; }
        }
        if (isUnsafe(s.target)) { cancelPull(player, s); return; }

        if (s.isAnchoring()) {
            if (s.elapsed == 0) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.8f);
            double beamProgress = (s.elapsed + 1) / (double) ANCHOR_TICKS;
            spawnRopeBeam(player, s.target, beamProgress);
            s.elapsed++;
            return;
        }

        if (s.elapsed == ANCHOR_TICKS) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    (s.totalPullTicks - ANCHOR_TICKS) * 3, 1, false, false, true));
        }

        Location playerLoc = player.getLocation();
        Vector toTarget = s.target.toVector().subtract(playerLoc.toVector());
        double distance = toTarget.length();

        if (distance < 1.2) { endPull(player, s); return; }
        toTarget.normalize();

        if (distance < LANDING_ZONE) {
            double t = distance / LANDING_ZONE;
            double speed = t * 0.9 + 0.08;
            Vector curVel = player.getVelocity();
            double vRope = curVel.getX() * toTarget.getX() + curVel.getY() * toTarget.getY() + curVel.getZ() * toTarget.getZ();
            Vector ropePart = toTarget.clone().multiply(vRope);
            Vector perpPart = curVel.clone().subtract(ropePart);
            player.setVelocity(perpPart.add(toTarget.clone().multiply(speed)));
        } else {
            int pullTick = s.elapsed - ANCHOR_TICKS;
            int totalPull = s.totalPullTicks - ANCHOR_TICKS;
            double progress = Math.min(1.0, (double) pullTick / Math.max(1, totalPull - 1));
            double restLength = 1.2 + (s.initialDist - 1.2) * Math.pow(1.0 - progress, 2.5);
            double stretch = Math.max(0, distance - restLength);
            double springForce = SPRING_K * stretch;
            Vector curVel = player.getVelocity();
            double vRope = curVel.getX() * toTarget.getX() + curVel.getY() * toTarget.getY() + curVel.getZ() * toTarget.getZ();
            double dampingForce = SPRING_DAMP * Math.max(0, vRope);
            double impulse = Math.max(0.06, springForce - dampingForce);
            Vector ropePart = toTarget.clone().multiply(vRope);
            Vector perpPart = curVel.clone().subtract(ropePart);
            player.setVelocity(perpPart.add(toTarget.clone().multiply(vRope + impulse)));
        }

        spawnRopeLine(player, s.target);
        s.elapsed++;
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!plugin.isSnapHook(item)) return;
        UUID uuid = player.getUniqueId();
        if (!rightClickThisTick.add(uuid)) return;
        HookState existing = hookStates.get(uuid);
        if (existing != null) return;
        if (isOnCooldown(uuid)) return;

        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        World world = player.getWorld();
        double maxDist = plugin.getMaxDistance();
        if (maxDist < 0) maxDist = 512.0;

        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, maxDist, FluidCollisionMode.NEVER, true);
        double maxEntityDist = blockHit != null ? blockHit.getHitPosition().distance(eye.toVector()) : maxDist;
        RayTraceResult entityHit = world.rayTraceEntities(eye, dir, maxEntityDist, 0.2,
                e -> !e.getUniqueId().equals(uuid));

        double bDist = blockHit != null ? blockHit.getHitPosition().distance(eye.toVector()) : Double.MAX_VALUE;
        double eDist = entityHit != null ? entityHit.getHitPosition().distance(eye.toVector()) : Double.MAX_VALUE;

        if (blockHit == null && entityHit == null) {
            failCooldowns.put(uuid, System.currentTimeMillis() + FAIL_COOLDOWN_MS);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.5f);
            player.sendActionBar(ChatColor.RED + "钩索未命中目标");
            return;
        }

        HookState s = new HookState();
        s.phase = Phase.ANCHORED;
        s.anchoredTicks = 0;
        if (bDist <= eDist) {
            Vector hv = blockHit.getHitPosition();
            s.target = hv.toLocation(world).subtract(dir.clone().multiply(TARGET_OFFSET));
            s.targetIsEntity = false;
            world.playSound(s.target, Sound.BLOCK_CHAIN_PLACE, 1.0f, 1.2f);
            world.playSound(s.target, Sound.ENTITY_ARROW_SHOOT, 0.7f, 1.5f);
        } else {
            Entity hitEntity = entityHit.getHitEntity();
            s.targetEntityId = hitEntity != null ? hitEntity.getUniqueId() : null;
            s.targetIsEntity = true;
            Vector hv = entityHit.getHitPosition();
            Vector toPlayer = eye.toVector().subtract(hv);
            if (toPlayer.lengthSquared() > 0) { toPlayer.normalize(); hv = hv.clone().add(toPlayer.multiply(1.5)); }
            s.target = hv.toLocation(world);
            world.playSound(s.target, Sound.ENTITY_ARROW_HIT, 0.8f, 1.0f);
        }
        hookStates.put(uuid, s);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.isSnapHook(event.getItemInHand())) event.setCancelled(true);
    }

    @EventHandler
    public void onLeftClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        HookState s = hookStates.get(player.getUniqueId());
        if (s == null) return;

        if (s.phase == Phase.ANCHORED) {
            s.phase = Phase.LAUNCHING;
            s.elapsed = 0;
            s.initialDist = player.getLocation().distance(s.target);
            double t = Math.min(1.0, Math.max(0.0, s.initialDist / plugin.getMaxDistance()));
            s.totalPullTicks = ANCHOR_TICKS + (int)(MIN_PULL_TICKS + (MAX_PULL_TICKS - MIN_PULL_TICKS) * t);
            player.playSound(player.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.6f, 1.8f);
            player.sendActionBar(ChatColor.GREEN + "发射！");
            return;
        }
        cancelPull(player, s);
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getCause() != DamageCause.FALL) return;
        Long until = fallProtection.get(player.getUniqueId());
        if (until != null && System.currentTimeMillis() < until) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { cleanupState(event.getPlayer().getUniqueId()); }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) { cleanupState(event.getEntity().getUniqueId()); }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) { cleanupState(event.getPlayer().getUniqueId()); }

    @EventHandler
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();
        HookState s = hookStates.get(player.getUniqueId());
        if (s == null) return;
        if (s.phase == Phase.ANCHORED) cancelAnchor(player);
        else cancelPull(player, s);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        HookState s = hookStates.get(player.getUniqueId());
        if (s == null) return;
        event.setCancelled(true);
        if (s.phase == Phase.ANCHORED) cancelAnchor(player);
        else cancelPull(player, s);
    }

    private void cancelAnchor(Player player) {
        hookStates.remove(player.getUniqueId());
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.0f);
        player.sendActionBar(ChatColor.RED + "钩索已脱离");
    }

    private void endPull(Player player, HookState s) {
        UUID uuid = player.getUniqueId();
        player.removePotionEffect(PotionEffectType.SPEED);
        hookStates.remove(uuid);
        cooldowns.put(uuid, System.currentTimeMillis() + NORMAL_COOLDOWN_MS);
        fallProtection.put(uuid, System.currentTimeMillis() + FALL_PROTECTION_MS);
        player.setVelocity(new Vector(0, 0, 0));
        player.getWorld().playSound(s.target, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.8f);
        player.sendActionBar(ChatColor.GREEN + "锚定到达");
    }

    private void cancelPull(Player player, HookState s) {
        player.removePotionEffect(PotionEffectType.SPEED);
        hookStates.remove(player.getUniqueId());
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + NORMAL_COOLDOWN_MS);
        player.setVelocity(new Vector(0, 0, 0));
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.0f);
        player.sendActionBar(ChatColor.RED + "钩索已取消");
    }

    private void cleanupState(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) player.removePotionEffect(PotionEffectType.SPEED);
        hookStates.remove(uuid);
        cooldowns.remove(uuid);
        failCooldowns.remove(uuid);
        fallProtection.remove(uuid);
    }

    private boolean isOnCooldown(UUID uuid) {
        Long n = cooldowns.get(uuid);
        if (n != null && System.currentTimeMillis() < n) return true;
        Long f = failCooldowns.get(uuid);
        return f != null && System.currentTimeMillis() < f;
    }

    private boolean isUnsafe(Location loc) {
        return !loc.getBlock().isPassable() || !loc.clone().add(0, 1, 0).getBlock().isPassable();
    }

    private Location handPos(Player player) {
        Location loc = player.getLocation().add(0, 1.2, 0);
        Vector dir = loc.getDirection();
        return loc.add(dir.multiply(0.4));
    }

    private void spawnAnchorPulse(Player player, Location target) {
        World world = player.getWorld();
        world.spawnParticle(Particle.END_ROD, target, 8, 0.3, 0.3, 0.3, 0.02);
        world.spawnParticle(Particle.ELECTRIC_SPARK, target, 2, 0.2, 0.2, 0.2, 0);
    }

    private void spawnRopeBeam(Player player, Location target, double progress) {
        Location origin = handPos(player);
        Vector toTarget = target.toVector().subtract(origin.toVector());
        double totalDist = toTarget.length();
        if (totalDist <= 0) return;
        toTarget.normalize();
        World world = player.getWorld();
        double len = totalDist * progress;
        for (double d = 0; d <= len; d += 0.2)
            world.spawnParticle(Particle.END_ROD, origin.clone().add(toTarget.clone().multiply(d)), 1, 0, 0, 0, 0);
        if (progress >= 1.0) world.spawnParticle(Particle.ELECTRIC_SPARK, target, 3, 0.15, 0.15, 0.15, 0);
    }

    private void spawnRopeLine(Player player, Location target) {
        Location origin = handPos(player);
        Vector toTarget = target.toVector().subtract(origin.toVector());
        double totalDist = toTarget.length();
        if (totalDist <= 0) return;
        toTarget.normalize();
        World world = player.getWorld();
        for (double d = 0; d <= totalDist; d += 0.2)
            world.spawnParticle(Particle.END_ROD, origin.clone().add(toTarget.clone().multiply(d)), 1, 0, 0, 0, 0);
    }
}
