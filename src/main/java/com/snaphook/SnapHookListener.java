package com.snaphook;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
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
    private static final int ANCHOR_TICKS = 3;
    private static final int ANCHORED_WINDOW_TICKS = 60;
    private static final long FALL_PROTECTION_MS = 500;
    private static final double TARGET_OFFSET = 1.0;
    private static final double LANDING_ZONE = 4.0;
    private static final double UNLIMITED_DISTANCE = 512.0;
    private static final double START_PULL_SPEED = 0.65;
    private static final double PULL_ACCEL_PER_TICK = 0.22;
    private static final double MAX_PULL_SPEED = 3.25;
    private static final double MAX_UP_SPEED = 1.75;
    private static final double MAX_DOWN_SPEED = -0.55;
    private static final double EDGE_POP_UP_SPEED = 1.05;
    private static final double ARRIVE_DISTANCE = 1.8;
    private static final double ARRIVE_POP_UP_SPEED = 0.45;
    private static final double ENTITY_STOP_DISTANCE = 1.0;
    private static final double HOOK_ENTITY_DAMAGE = 1.0;
    private static final double GLIDE_PULL_ACCEL = 0.42;
    private static final double GLIDE_MAX_SPEED = 4.8;
    private static final double GLIDE_MAX_DOWN_SPEED = -1.45;
    private static final double GLIDE_U_EXIT_DISTANCE = 5.5;
    private static final double GLIDE_U_EXIT_LIFT = 1.05;
    private static final double STRIKE_ACCEL = 0.55;
    private static final double STRIKE_MAX_SPEED = 5.6;
    private static final double STRIKE_RADIUS = 4.5;
    private static final double STRIKE_KNOCKBACK = 1.25;
    private static final double STRIKE_SHOCKWAVE_RADIUS = 100.0;
    private static final int MIN_HOOK_FLIGHT_TICKS = 10;
    private static final int MAX_HOOK_FLIGHT_TICKS = 18;
    private static final int MAX_ROPE_PARTICLES = 48;

    private final SnapHookPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> failCooldowns = new HashMap<>();
    private final Set<UUID> rightClickThisTick = new HashSet<>();
    private final Map<UUID, HookState> hookStates = new HashMap<>();
    private final Map<UUID, Long> fallProtection = new HashMap<>();
    private enum Phase { FLYING, ANCHORED, LAUNCHING, STRIKE }

    private static class HookState {
        Location target;
        UUID targetEntityId;
        EquipmentSlot hand;
        double initialDist;
        int elapsed;
        int anchoredTicks;
        int flyingTicks;
        int totalFlyingTicks;
        double strikeMaxSpeed;
        boolean flightWasAllowed;
        boolean hookFlightEnabled;
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
                case FLYING -> tickFlying(uuid, player, s);
                case ANCHORED -> tickAnchored(uuid, player, s);
                case LAUNCHING -> tickLaunching(uuid, player, s);
                case STRIKE -> tickStrike(uuid, player, s);
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (hookStates.containsKey(uuid) || cooldowns.containsKey(uuid) || failCooldowns.containsKey(uuid)) continue;
            if (!plugin.isSnapHook(player.getInventory().getItemInMainHand())
                    && !plugin.isSnapHook(player.getInventory().getItemInOffHand())) continue;
            double dist = targetDistance(player);
            double max = plugin.getMaxDistance();
            if (max < 0) max = UNLIMITED_DISTANCE;
            if (dist <= 0) player.sendActionBar(ChatColor.RED + "距离: 无目标");
            else if (dist <= max) player.sendActionBar(ChatColor.GREEN + "距离: " + String.format("%.1f", dist) + "格");
            else player.sendActionBar(ChatColor.RED + "超距: " + String.format("%.1f", dist) + "格");
        }

        it = fallProtection.entrySet().iterator();
        while (it.hasNext()) { if (now >= it.next().getValue()) it.remove(); }
    }

    private void tickFlying(UUID uuid, Player player, HookState s) {
        if (player.isSneaking()) { cancelAnchor(player); return; }
        if (s.targetEntityId != null && !refreshEntityTarget(player, s)) { cancelAnchor(player); return; }
        if (!s.targetIsEntity && isUnsafe(s.target)) { cancelAnchor(player); return; }

        double progress = Math.min(1.0, (s.flyingTicks + 1) / (double) s.totalFlyingTicks);
        spawnRopeBeam(player, s.target, progress);
        s.flyingTicks++;
        if (s.flyingTicks < s.totalFlyingTicks) return;

        s.phase = Phase.ANCHORED;
        s.anchoredTicks = 0;
        if (s.targetIsEntity) {
            damageHookedEntity(player, s);
            plugin.grantAdvancement(player, "live_anchor");
            if (Bukkit.getEntity(s.targetEntityId) instanceof Player) plugin.grantAdvancement(player, "player_anchor");
            if (player.isGliding()) plugin.grantAdvancement(player, "aerial_intercept");
            player.getWorld().playSound(s.target, Sound.ENTITY_ARROW_HIT, 0.8f, 1.0f);
        } else {
            player.getWorld().playSound(s.target, Sound.BLOCK_CHAIN_PLACE, 1.0f, 1.2f);
            player.getWorld().playSound(s.target, Sound.ENTITY_ARROW_SHOOT, 0.7f, 1.5f);
        }
        plugin.grantAdvancement(player, "chain_link");
        spawnAnchorPulse(player, s.target);
        if (player.isGliding() && !player.isOnGround() && player.getLocation().getPitch() > 20) startStrike(player, s);
        else startLaunch(player, s);
    }

    private void tickAnchored(UUID uuid, Player player, HookState s) {
        s.anchoredTicks++;
        int remaining = Math.max(0, ANCHORED_WINDOW_TICKS - s.anchoredTicks);
        if (remaining <= 0) { cancelAnchor(player); return; }
        if (s.targetEntityId != null && !refreshEntityTarget(player, s)) { cancelAnchor(player); return; }
        if (!s.targetIsEntity && isUnsafe(s.target)) { cancelAnchor(player); return; }
        if (player.isSneaking()) { cancelAnchor(player); return; }

        int sec = remaining / 20 + 1;
        String targetType = s.targetIsEntity ? "实体" : "地形";
        String msg = ChatColor.YELLOW + "锚点已锁定" + ChatColor.GRAY + " | 目标: " + ChatColor.GOLD + targetType
                + ChatColor.GRAY + " | " + ChatColor.GREEN + "[左键]发射"
                + ChatColor.GRAY + " (" + sec + "秒)";
        if (s.targetIsEntity) msg = entityHookMessage(s);
        if (!player.isOnGround()) msg += ChatColor.GRAY + " | " + ChatColor.RED + "[Shift]脱离";
        player.sendActionBar(msg);

        spawnAnchorPulse(player, s.target);
        spawnRopeLine(player, s.target);
    }

    private void tickLaunching(UUID uuid, Player player, HookState s) {
        if (player.isSneaking()) { cancelPull(player, s); return; }
        if (s.targetEntityId != null && !refreshEntityTarget(player, s)) { cancelPull(player, s); return; }
        if (!s.targetIsEntity && isUnsafe(s.target)) { cancelPull(player, s); return; }

        if (s.isAnchoring()) {
            if (s.elapsed == 0) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.8f);
            double beamProgress = (s.elapsed + 1) / (double) ANCHOR_TICKS;
            spawnRopeBeam(player, s.target, beamProgress);
            s.elapsed++;
            return;
        }

        Location playerLoc = player.getLocation();
        Vector toTarget = s.target.toVector().subtract(playerLoc.toVector());
        double distance = toTarget.length();

        if (distance < ARRIVE_DISTANCE) { endPull(player, s); return; }
        toTarget.normalize();

        int pullTick = s.elapsed - ANCHOR_TICKS;
        double speed = Math.min(MAX_PULL_SPEED, START_PULL_SPEED + pullTick * PULL_ACCEL_PER_TICK);
        if (distance < LANDING_ZONE) {
            double t = distance / LANDING_ZONE;
            double smooth = t * t * (3.0 - 2.0 * t);
            speed *= Math.max(0.22, smooth);
        }

        Vector nextVelocity = buildPullVelocity(player, s, toTarget, speed, distance);
        nextVelocity = liftOverEdge(player, nextVelocity);
        if (nextVelocity == null) { stopOnCollision(player); return; }
        player.setVelocity(nextVelocity);
        applyFovBoost(player, speed);
        if (!player.isOnGround() && heightAboveGround(player) <= 3.0) plugin.grantAdvancement(player, "low_flight");
        player.sendActionBar(s.targetIsEntity ? entityHookMessage(s) : ChatColor.AQUA + "飞行中 " + ChatColor.WHITE + "[双击Space]脱钩");

        spawnRopeLine(player, s.target);
        s.elapsed++;
    }

    private void tickStrike(UUID uuid, Player player, HookState s) {
        if (s.targetEntityId != null && !refreshEntityTarget(player, s)) { cancelPull(player, s); return; }
        if (player.isOnGround()) { strikeImpact(player, s); return; }

        Vector toTarget = s.target.toVector().subtract(player.getLocation().toVector());
        double distance = toTarget.length();
        if (distance < ARRIVE_DISTANCE) { strikeImpact(player, s); return; }
        toTarget.normalize();

        Vector velocity = player.getVelocity().add(toTarget.multiply(STRIKE_ACCEL));
        if (velocity.lengthSquared() > STRIKE_MAX_SPEED * STRIKE_MAX_SPEED) velocity.normalize().multiply(STRIKE_MAX_SPEED);
        s.strikeMaxSpeed = Math.max(s.strikeMaxSpeed, velocity.length());
        player.setVelocity(velocity);
        fallProtection.put(uuid, System.currentTimeMillis() + FALL_PROTECTION_MS);
        applyFovBoost(player, STRIKE_MAX_SPEED);
        player.sendActionBar(s.targetIsEntity ? entityHookMessage(s) : ChatColor.RED + "Strike" + ChatColor.GRAY + " | " + ChatColor.WHITE + "[双击Space]脱钩");
        spawnRopeLine(player, s.target);
        s.elapsed++;
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        boolean isMainHand = plugin.isSnapHook(item);
        if (!isMainHand) {
            item = player.getInventory().getItemInOffHand();
            if (!plugin.isSnapHook(item)) return;
        }
        plugin.grantSnapHookAdvancement(player);
        UUID uuid = player.getUniqueId();
        if (!rightClickThisTick.add(uuid)) return;
        HookState existing = hookStates.get(uuid);
        if (existing != null) return;
        if (isOnCooldown(uuid)) return;

        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        World world = player.getWorld();
        double maxDist = player.getLocation().getPitch() > 85 ? UNLIMITED_DISTANCE : plugin.getMaxDistance();
        if (maxDist < 0) maxDist = UNLIMITED_DISTANCE;

        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, maxDist, FluidCollisionMode.NEVER, true);
        double maxEntityDist = blockHit != null ? blockHit.getHitPosition().distance(eye.toVector()) : maxDist;
        RayTraceResult entityHit = world.rayTraceEntities(eye, dir, maxEntityDist, 0.2,
                e -> isHookableEntity(e, uuid));

        double bDist = blockHit != null ? blockHit.getHitPosition().distance(eye.toVector()) : Double.MAX_VALUE;
        double eDist = entityHit != null ? entityHit.getHitPosition().distance(eye.toVector()) : Double.MAX_VALUE;

        if (blockHit == null && entityHit == null) {
            failCooldowns.put(uuid, System.currentTimeMillis() + FAIL_COOLDOWN_MS);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.5f);
            player.sendActionBar(ChatColor.RED + "钩索未命中目标");
            return;
        }

        HookState s = new HookState();
        s.phase = Phase.FLYING;
        s.anchoredTicks = 0;
        s.hand = event.getHand();
        if (bDist <= eDist) {
            Vector hv = blockHit.getHitPosition();
            BlockFace face = blockHit.getHitBlockFace();
            Vector offset = face != null ? face.getDirection() : dir.clone().multiply(-1);
            s.target = hv.toLocation(world).add(offset.normalize().multiply(TARGET_OFFSET));
            s.targetIsEntity = false;
        } else {
            Entity hitEntity = entityHit.getHitEntity();
            s.targetEntityId = hitEntity != null ? hitEntity.getUniqueId() : null;
            s.targetIsEntity = true;
            Vector hv = entityHit.getHitPosition();
            Vector toPlayer = eye.toVector().subtract(hv);
            if (toPlayer.lengthSquared() > 0) { toPlayer.normalize(); hv = hv.clone().add(toPlayer.multiply(1.5)); }
            s.target = hv.toLocation(world);
        }
        double dist = eye.distance(s.target);
        double hookProgress = Math.min(1.0, dist / maxDist);
        s.totalFlyingTicks = MIN_HOOK_FLIGHT_TICKS + (int) Math.round((MAX_HOOK_FLIGHT_TICKS - MIN_HOOK_FLIGHT_TICKS) * hookProgress);
        player.playSound(player.getLocation(), Sound.ITEM_CROSSBOW_QUICK_CHARGE_1, 0.6f, 1.6f);
        hookStates.put(uuid, s);
        enableHookFlight(player, s);
        plugin.damageSnapHook(item, 1);
        plugin.grantAdvancement(player, "first_hook");
        if (!player.isOnGround()) plugin.grantAdvancement(player, "skybridge");
        if (player.getVelocity().getY() < -0.7 || player.getFallDistance() > 5.0) plugin.grantAdvancement(player, "edge_save");
        if (player.getLocation().getY() - s.target.getY() > 16.0) plugin.grantAdvancement(player, "high_recover");
        if (s.targetIsEntity && dist <= 4.0) plugin.grantAdvancement(player, "close_anchor");
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.isSnapHook(event.getItemInHand())) event.setCancelled(true);
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player && plugin.isSnapHook(event.getRecipe().getResult())) {
            plugin.grantSnapHookAdvancement(player);
        }
    }

    @EventHandler
    public void onLeftClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        HookState s = hookStates.get(player.getUniqueId());
        if (s == null) return;
        event.setCancelled(true);
        if (s.phase == Phase.FLYING) return;

        if (s.phase == Phase.ANCHORED) {
            startLaunch(player, s);
            return;
        }
        cancelPull(player, s);
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getCause() != DamageCause.FALL) return;
        HookState s = hookStates.get(player.getUniqueId());
        if (s != null && s.phase == Phase.STRIKE) {
            event.setCancelled(true);
            strikeImpact(player, s);
            return;
        }
        Long until = fallProtection.get(player.getUniqueId());
        if (until != null && System.currentTimeMillis() < until) {
            event.setCancelled(true);
            plugin.grantAdvancement(player, "no_fall");
        }
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
        if (s.phase == Phase.LAUNCHING || s.phase == Phase.STRIKE) cancelPull(player, s);
        else cancelAnchor(player);
    }

    @EventHandler
    public void onJump(PlayerJumpEvent event) {
        Player player = event.getPlayer();
        HookState s = hookStates.get(player.getUniqueId());
        if (s == null) return;
        releasePull(player);
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        HookState s = hookStates.get(player.getUniqueId());
        if (s == null) return;
        event.setCancelled(true);
        player.setFlying(false);
        releasePull(player);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        HookState s = hookStates.get(player.getUniqueId());
        if (s == null) return;
        event.setCancelled(true);
        if (s.phase == Phase.LAUNCHING || s.phase == Phase.STRIKE) cancelPull(player, s);
        else cancelAnchor(player);
    }

    private void cancelAnchor(Player player) {
        HookState s = hookStates.remove(player.getUniqueId());
        if (s != null) restoreStateFlight(player, s);
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.0f);
        player.sendActionBar(ChatColor.RED + "钩索已脱离");
    }

    private void startLaunch(Player player, HookState s) {
        s.phase = Phase.LAUNCHING;
        s.elapsed = 0;
        s.initialDist = player.getLocation().distance(s.target);
        enableHookFlight(player, s);
        player.playSound(player.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.6f, 1.8f);
        player.sendActionBar(ChatColor.GREEN + "发射！");
        plugin.grantAdvancement(player, "launch");
    }

    private void startStrike(Player player, HookState s) {
        s.phase = Phase.STRIKE;
        s.elapsed = 0;
        s.strikeMaxSpeed = player.getVelocity().length();
        enableHookFlight(player, s);
        fallProtection.put(player.getUniqueId(), System.currentTimeMillis() + FALL_PROTECTION_MS);
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.6f, 1.4f);
        player.sendMessage(ChatColor.RED + "Strike 模式启动：沿钩锁方向加速砸地。");
        plugin.grantAdvancement(player, "strike_mode");
    }

    private void endPull(Player player, HookState s) {
        UUID uuid = player.getUniqueId();
        restoreStateFlight(player, s);
        player.removePotionEffect(PotionEffectType.SPEED);
        hookStates.remove(uuid);
        cooldowns.put(uuid, System.currentTimeMillis() + NORMAL_COOLDOWN_MS);
        fallProtection.put(uuid, System.currentTimeMillis() + FALL_PROTECTION_MS);
        Vector settle = s.target.toVector().subtract(player.getLocation().toVector());
        if (settle.lengthSquared() > 0.01) settle.normalize().multiply(0.35);
        settle.setY(Math.max(settle.getY(), ARRIVE_POP_UP_SPEED));
        player.setVelocity(clampPullVelocity(settle));
        player.getWorld().playSound(s.target, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.8f);
        player.sendActionBar(ChatColor.GREEN + "锚定到达");
    }

    private void cancelPull(Player player, HookState s) {
        restoreStateFlight(player, s);
        player.removePotionEffect(PotionEffectType.SPEED);
        hookStates.remove(player.getUniqueId());
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + NORMAL_COOLDOWN_MS);
        player.setVelocity(new Vector(0, 0, 0));
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.0f);
        player.sendActionBar(ChatColor.RED + "钩索已取消");
    }

    private void releasePull(Player player) {
        UUID uuid = player.getUniqueId();
        HookState s = hookStates.get(uuid);
        if (s != null) restoreStateFlight(player, s);
        player.removePotionEffect(PotionEffectType.SPEED);
        hookStates.remove(uuid);
        cooldowns.put(uuid, System.currentTimeMillis() + (long) (NORMAL_COOLDOWN_MS * 0.30));
        fallProtection.put(uuid, System.currentTimeMillis() + FALL_PROTECTION_MS);
        Vector velocity = player.getVelocity();
        if (velocity.lengthSquared() < 0.04) velocity = player.getLocation().getDirection().multiply(1.2);
        velocity.setY(Math.max(velocity.getY(), 0.35));
        player.setVelocity(clampPullVelocity(velocity));
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.45f, 1.35f);
        player.sendActionBar(ChatColor.GREEN + "钩索已脱离，惯性保留");
        player.sendMessage(ChatColor.GREEN + "钩索脱离：返还 70% 冷却。");
        plugin.grantAdvancement(player, "safe_release");
    }

    private void stopOnCollision(Player player) {
        UUID uuid = player.getUniqueId();
        HookState s = hookStates.get(uuid);
        if (s != null) restoreStateFlight(player, s);
        player.removePotionEffect(PotionEffectType.SPEED);
        hookStates.remove(uuid);
        cooldowns.put(uuid, System.currentTimeMillis() + NORMAL_COOLDOWN_MS);
        fallProtection.put(uuid, System.currentTimeMillis() + FALL_PROTECTION_MS);
        player.setVelocity(new Vector(0, 0, 0));
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.5f, 0.8f);
        player.sendActionBar(ChatColor.RED + "钩索碰撞中止");
    }

    private void cleanupState(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        HookState s = hookStates.get(uuid);
        if (player != null) {
            if (s != null) restoreStateFlight(player, s);
            player.removePotionEffect(PotionEffectType.SPEED);
        }
        hookStates.remove(uuid);
        cooldowns.remove(uuid);
        failCooldowns.remove(uuid);
        fallProtection.remove(uuid);
    }

    private void restoreStateFlight(Player player, HookState s) {
        restoreHookFlight(player, s);
    }

    private void enableHookFlight(Player player, HookState s) {
        if (s.hookFlightEnabled) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        s.flightWasAllowed = player.getAllowFlight();
        if (!s.flightWasAllowed) {
            player.setAllowFlight(true);
            s.hookFlightEnabled = true;
        }
    }

    private void restoreHookFlight(Player player, HookState s) {
        if (!s.hookFlightEnabled) return;
        player.setFlying(false);
        player.setAllowFlight(s.flightWasAllowed);
        s.hookFlightEnabled = false;
    }

    private boolean isHookableEntity(Entity entity, UUID shooter) {
        if (entity.getUniqueId().equals(shooter) || !(entity instanceof LivingEntity living) || living.isDead()) return false;
        return !(entity instanceof Player player) || player.getGameMode() != GameMode.SPECTATOR;
    }

    private boolean refreshEntityTarget(Player player, HookState s) {
        Entity entity = Bukkit.getEntity(s.targetEntityId);
        if (!(entity instanceof LivingEntity living) || living.isDead() || !entity.getWorld().equals(player.getWorld())) return false;
        Vector fromEntityToPlayer = player.getLocation().toVector().subtract(entity.getLocation().toVector());
        if (fromEntityToPlayer.lengthSquared() < 0.01) fromEntityToPlayer = player.getLocation().getDirection().multiply(-1);
        Vector offset = fromEntityToPlayer.normalize().multiply(ENTITY_STOP_DISTANCE);
        s.target = entity.getLocation().add(offset).add(0, 0.2, 0);
        double max = plugin.getMaxDistance() < 0 ? UNLIMITED_DISTANCE : Math.max(24.0, plugin.getMaxDistance() + 6.0);
        return player.getLocation().distanceSquared(entity.getLocation()) <= max * max;
    }

    private void damageHookedEntity(Player player, HookState s) {
        Entity entity = Bukkit.getEntity(s.targetEntityId);
        if (entity instanceof LivingEntity living && !living.isDead()) {
            living.damage(HOOK_ENTITY_DAMAGE, player);
            living.getWorld().spawnParticle(Particle.CRIT, living.getLocation().add(0, 1, 0), 8, 0.25, 0.25, 0.25, 0.05);
            plugin.grantAdvancement(player, "half_heart");
        }
    }

    private void strikeImpact(Player player, HookState s) {
        restoreStateFlight(player, s);
        player.removePotionEffect(PotionEffectType.SPEED);
        hookStates.remove(player.getUniqueId());
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + NORMAL_COOLDOWN_MS);
        fallProtection.put(player.getUniqueId(), System.currentTimeMillis() + FALL_PROTECTION_MS);

        double speed = Math.max(s.strikeMaxSpeed, player.getVelocity().length());
        double damage = Math.max(4.0, Math.min(16.0, speed * 1.2));
        int hits = 0;
        boolean hitPlayer = false;
        Location center = player.getLocation();
        ItemStack hook = hookItem(player, s);
        if (hook != null) plugin.damageSnapHook(hook, 2);
        for (Entity entity : player.getWorld().getNearbyEntities(center, STRIKE_RADIUS, STRIKE_RADIUS, STRIKE_RADIUS)) {
            if (!(entity instanceof LivingEntity living) || entity.getUniqueId().equals(player.getUniqueId()) || living.isDead()) continue;
            living.damage(damage, player);
            Vector knock = living.getLocation().toVector().subtract(center.toVector());
            if (knock.lengthSquared() > 0.01) {
                knock.normalize().multiply(STRIKE_KNOCKBACK).setY(0.45);
                living.setVelocity(knock);
            }
            if (living instanceof Player) hitPlayer = true;
            hits++;
        }
        spawnStrikeExplosion(center);
        player.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 1.25f);
        player.sendMessage(ChatColor.RED + "Strike 命中：" + ChatColor.WHITE + String.format("%.1f", damage / 2.0)
                + " 心伤害，命中 " + hits + " 个目标，速度 " + String.format("%.2f", speed) + "。");
        plugin.grantAdvancement(player, "meteor");
        if (hits >= 2) plugin.grantAdvancement(player, "shockwave");
        if (speed >= 4.5) plugin.grantAdvancement(player, "high_speed");
        if (hitPlayer) plugin.grantAdvancement(player, "captain");
    }

    private void spawnStrikeExplosion(Location center) {
        World world = center.getWorld();
        if (world == null) return;

        world.spawnParticle(Particle.EXPLOSION, center, 10, 1.2, 0.5, 1.2, 0);
        world.spawnParticle(Particle.CRIT, center, 220, 4.0, 0.8, 4.0, 0.35);
        world.spawnParticle(Particle.ELECTRIC_SPARK, center, 160, 3.5, 0.6, 3.5, 0.18);
        world.spawnParticle(Particle.CLOUD, center, 90, 3.0, 0.3, 3.0, 0.08);
        world.spawnParticle(Particle.END_ROD, center.clone().add(0, 0.6, 0), 70, 2.2, 0.5, 2.2, 0.12);

        for (double radius = 6.0; radius <= STRIKE_SHOCKWAVE_RADIUS; radius += 4.0) {
            int points = Math.max(32, Math.min(140, (int) (radius * 2.2)));
            for (int i = 0; i < points; i++) {
                double angle = Math.PI * 2.0 * i / points;
                Location point = center.clone().add(Math.cos(angle) * radius, 0.12, Math.sin(angle) * radius);
                world.spawnParticle(Particle.CLOUD, point, 1, 0, 0, 0, 0.02);
                if (i % 4 == 0) world.spawnParticle(Particle.ELECTRIC_SPARK, point, 1, 0, 0, 0, 0);
            }
        }
    }

    private String entityHookMessage(HookState s) {
        Entity entity = Bukkit.getEntity(s.targetEntityId);
        String name = entity instanceof Player player ? player.getName() : entity != null ? entity.getName() : "实体";
        return ChatColor.GOLD + "勾中 " + ChatColor.WHITE + name + ChatColor.GRAY + " | " + ChatColor.AQUA + "[Space]脱钩";
    }

    private ItemStack hookItem(Player player, HookState s) {
        if (s.hand == EquipmentSlot.OFF_HAND) return player.getInventory().getItemInOffHand();
        return player.getInventory().getItemInMainHand();
    }

    private double heightAboveGround(Player player) {
        RayTraceResult hit = player.getWorld().rayTraceBlocks(player.getLocation(), new Vector(0, -1, 0), 3.0, FluidCollisionMode.NEVER, true);
        return hit == null ? Double.MAX_VALUE : hit.getHitPosition().distance(player.getLocation().toVector());
    }

    private Vector clampPullVelocity(Vector velocity) {
        if (velocity.lengthSquared() > MAX_PULL_SPEED * MAX_PULL_SPEED) {
            velocity.normalize().multiply(MAX_PULL_SPEED);
        }
        velocity.setY(Math.max(MAX_DOWN_SPEED, Math.min(MAX_UP_SPEED, velocity.getY())));
        return velocity;
    }

    private Vector buildPullVelocity(Player player, HookState s, Vector toTarget, double speed, double distance) {
        if (!player.isGliding()) return clampPullVelocity(toTarget.multiply(speed));

        Vector velocity = player.getVelocity().add(toTarget.clone().multiply(GLIDE_PULL_ACCEL));
        if (!s.targetIsEntity && s.target.getY() < player.getLocation().getY() - 1.0 && distance < GLIDE_U_EXIT_DISTANCE) {
            double exit = 1.0 - distance / GLIDE_U_EXIT_DISTANCE;
            velocity.setY(Math.max(velocity.getY(), GLIDE_U_EXIT_LIFT * exit));
        }
        return clampGlideVelocity(velocity);
    }

    private Vector clampGlideVelocity(Vector velocity) {
        if (velocity.lengthSquared() > GLIDE_MAX_SPEED * GLIDE_MAX_SPEED) {
            velocity.normalize().multiply(GLIDE_MAX_SPEED);
        }
        velocity.setY(Math.max(GLIDE_MAX_DOWN_SPEED, Math.min(MAX_UP_SPEED, velocity.getY())));
        return velocity;
    }

    private void applyFovBoost(Player player, double speed) {
        int amplifier = speed >= 2.5 ? 2 : speed >= 1.6 ? 1 : 0;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 8, amplifier, false, false, false));
    }

    private Vector liftOverEdge(Player player, Vector velocity) {
        Vector flat = velocity.clone().setY(0);
        if (flat.lengthSquared() < 0.01) return velocity;
        Vector step = flat.normalize().multiply(0.75);
        Location front = player.getLocation().add(step);
        boolean footBlocked = !front.getBlock().isPassable();
        boolean bodyBlocked = !front.clone().add(0, 1, 0).getBlock().isPassable();
        if (!footBlocked && !bodyBlocked) return velocity;

        boolean canPopOver = footBlocked
                && front.clone().add(0, 1, 0).getBlock().isPassable()
                && front.clone().add(0, 2, 0).getBlock().isPassable();
        if (canPopOver) {
            velocity.setY(Math.max(velocity.getY(), EDGE_POP_UP_SPEED));
            return clampPullVelocity(velocity);
        }
        return null;
    }

    private boolean isOnCooldown(UUID uuid) {
        Long n = cooldowns.get(uuid);
        if (n != null && System.currentTimeMillis() < n) return true;
        Long f = failCooldowns.get(uuid);
        return f != null && System.currentTimeMillis() < f;
    }

    private double targetDistance(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        double max = UNLIMITED_DISTANCE;
        RayTraceResult blockHit = player.getWorld().rayTraceBlocks(eye, dir, max, FluidCollisionMode.NEVER, true);
        double cap = blockHit != null ? blockHit.getHitPosition().distance(eye.toVector()) : max;
        RayTraceResult entityHit = player.getWorld().rayTraceEntities(eye, dir, cap, 0.2,
                e -> isHookableEntity(e, player.getUniqueId()));
        if (entityHit != null) {
            double eDist = entityHit.getHitPosition().distance(eye.toVector());
            if (blockHit != null) {
                double bDist = blockHit.getHitPosition().distance(eye.toVector());
                return Math.min(bDist, eDist);
            }
            return eDist;
        }
        return blockHit != null ? blockHit.getHitPosition().distance(eye.toVector()) : 0;
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
        double step = ropeStep(len);
        for (double d = 0; d <= len; d += step)
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
        double step = ropeStep(totalDist);
        for (double d = 0; d <= totalDist; d += step)
            world.spawnParticle(Particle.END_ROD, origin.clone().add(toTarget.clone().multiply(d)), 1, 0, 0, 0, 0);
    }

    private double ropeStep(double length) {
        return Math.max(0.85, length / MAX_ROPE_PARTICLES);
    }
}
