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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SnapHookListener implements Listener {

    private static final double MAX_DISTANCE = 32.0;
    private static final long NORMAL_COOLDOWN_MS = 3500;
    private static final long FAIL_COOLDOWN_MS = 600;
    private static final int MIN_PULL_TICKS = 8;
    private static final int MAX_PULL_TICKS = 16;
    private static final int ANCHOR_TICKS = 5;
    private static final long FALL_PROTECTION_MS = 500;
    private static final double TARGET_OFFSET = 1.0;
    private static final double MAX_VELOCITY = 4.0;
    private static final double SPRING_K = 0.42;
    private static final double SPRING_DAMP = 0.68;

    private final SnapHookPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> failCooldowns = new HashMap<>();
    private final Set<UUID> pulling = new HashSet<>();
    private final Map<UUID, PullState> pullStates = new HashMap<>();
    private final Map<UUID, Long> fallProtection = new HashMap<>();

    private record PullState(Location target, UUID targetEntityId, double initialDist, int totalTicks, int elapsed) {
        boolean isAnchoring() { return elapsed < ANCHOR_TICKS; }
    }

    public SnapHookListener(SnapHookPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    private void tick() {
        long now = System.currentTimeMillis();

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

        for (UUID uuid : new HashSet<>(pulling)) {
            PullState state = pullStates.get(uuid);
            if (state == null) { pulling.remove(uuid); continue; }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || player.isDead()) { cleanupState(uuid); continue; }
            if (!player.getWorld().equals(state.target().getWorld())) { cleanupState(uuid); continue; }

            if (player.isSneaking()) { cancelPull(player); continue; }

            if (state.targetEntityId() != null) {
                Entity targetEntity = Bukkit.getEntity(state.targetEntityId());
                if (targetEntity == null || targetEntity.isDead()) { cancelPull(player); continue; }
                if (targetEntity.getLocation().distance(state.target()) > 5.0) {
                    cancelPullEx(targetEntity, player);
                    continue;
                }
            }

            if (state.isAnchoring()) {
                if (state.elapsed() == 0) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.8f);
                }
                player.setVelocity(new Vector(0, 0, 0));
                if (isUnsafe(state.target())) { cancelPull(player); continue; }
                spawnBeamProgress(player, state.target(), (state.elapsed() + 1) / (double) ANCHOR_TICKS);
                pullStates.put(uuid, new PullState(state.target(), state.targetEntityId(), state.initialDist(), state.totalTicks(), state.elapsed() + 1));
                continue;
            }

            int pullTick = state.elapsed() - ANCHOR_TICKS;
            int totalPull = state.totalTicks() - ANCHOR_TICKS;

            Location playerLoc = player.getLocation();
            Vector toTarget = state.target().toVector().subtract(playerLoc.toVector());
            double distance = toTarget.length();

            if (distance < 1.5) { endPull(player, state); continue; }
            if (isUnsafe(state.target())) { cancelPull(player); continue; }

            toTarget.normalize();

            double progress = Math.min(1.0, (double) pullTick / Math.max(1, totalPull - 1));
            double restLength = 1.5 + (state.initialDist() - 1.5) * Math.pow(1.0 - progress, 2.5);

            double stretch = Math.max(0, distance - restLength);
            double springForce = SPRING_K * stretch;

            double vRope = player.getVelocity().dot(toTarget);
            double dampingForce = SPRING_DAMP * Math.max(0, vRope);

            double impulse = springForce - dampingForce;
            impulse = Math.min(MAX_VELOCITY, Math.max(0.2, impulse));

            player.setVelocity(toTarget.multiply(impulse));
            spawnPullParticles(player, state.target());

            pullStates.put(uuid, new PullState(state.target(), state.targetEntityId(), state.initialDist(), state.totalTicks(), state.elapsed() + 1));
        }

        it = fallProtection.entrySet().iterator();
        while (it.hasNext()) {
            if (now >= it.next().getValue()) it.remove();
        }
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!plugin.isSnapHook(item)) return;

        UUID uuid = player.getUniqueId();
        if (isOnCooldown(uuid) || pulling.contains(uuid)) return;

        event.setCancelled(true);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        World world = player.getWorld();

        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, MAX_DISTANCE, FluidCollisionMode.NEVER, true);
        double maxEntityDist = blockHit != null
                ? blockHit.getHitPosition().distance(eye.toVector())
                : MAX_DISTANCE;
        RayTraceResult entityHit = world.rayTraceEntities(eye, dir, maxEntityDist, 0.2,
                e -> !e.getUniqueId().equals(uuid));

        double blockDist = blockHit != null ? blockHit.getHitPosition().distance(eye.toVector()) : Double.MAX_VALUE;
        double entityDist = entityHit != null ? entityHit.getHitPosition().distance(eye.toVector()) : Double.MAX_VALUE;

        if (blockHit == null && entityHit == null) {
            failCooldowns.put(uuid, System.currentTimeMillis() + FAIL_COOLDOWN_MS);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.5f);
            player.sendActionBar(ChatColor.RED + "钩索未命中目标");
            return;
        }

        Location target;
        UUID targetEntityId = null;
        if (blockDist <= entityDist) {
            Vector hitVec = blockHit.getHitPosition();
            target = hitVec.toLocation(world).subtract(dir.clone().multiply(TARGET_OFFSET));
            world.playSound(target, Sound.BLOCK_CHAIN_PLACE, 1.0f, 1.2f);
            world.playSound(target, Sound.ENTITY_ARROW_SHOOT, 0.7f, 1.5f);
        } else {
            Entity hitEntity = entityHit.getHitEntity();
            targetEntityId = hitEntity != null ? hitEntity.getUniqueId() : null;
            Vector hitVec = entityHit.getHitPosition();
            Vector toPlayer = eye.toVector().subtract(hitVec);
            if (toPlayer.lengthSquared() > 0) {
                toPlayer.normalize();
                hitVec = hitVec.clone().add(toPlayer.multiply(1.5));
            }
            target = hitVec.toLocation(world);
            world.playSound(target, Sound.ENTITY_ARROW_HIT, 0.8f, 1.0f);
        }

        world.playSound(target, Sound.ITEM_CROSSBOW_SHOOT, 0.6f, 1.8f);
        startPull(player, target, targetEntityId);
    }

    @EventHandler
    public void onLeftClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (pulling.contains(player.getUniqueId())) {
            cancelPull(player);
        }
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getCause() != DamageCause.FALL) return;
        Long until = fallProtection.get(player.getUniqueId());
        if (until != null && System.currentTimeMillis() < until) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cleanupState(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        cleanupState(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        cleanupState(event.getPlayer().getUniqueId());
    }

    private void startPull(Player player, Location target, UUID targetEntityId) {
        UUID uuid = player.getUniqueId();
        double distance = player.getLocation().distance(target);
        double t = Math.min(1.0, Math.max(0.0, distance / MAX_DISTANCE));
        int pullTicks = (int) (MIN_PULL_TICKS + (MAX_PULL_TICKS - MIN_PULL_TICKS) * t);
        int totalTicks = ANCHOR_TICKS + pullTicks;
        pulling.add(uuid);
        pullStates.put(uuid, new PullState(target, targetEntityId, distance, totalTicks, 0));
    }

    private void endPull(Player player, PullState state) {
        UUID uuid = player.getUniqueId();
        pulling.remove(uuid);
        pullStates.remove(uuid);
        cooldowns.put(uuid, System.currentTimeMillis() + NORMAL_COOLDOWN_MS);
        fallProtection.put(uuid, System.currentTimeMillis() + FALL_PROTECTION_MS);
        player.setVelocity(new Vector(0, 0, 0));
        player.getWorld().playSound(state.target(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.8f);
        player.sendActionBar(ChatColor.GREEN + "锚定到达");
    }

    private void cancelPull(Player player) {
        pulling.remove(player.getUniqueId());
        pullStates.remove(player.getUniqueId());
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + NORMAL_COOLDOWN_MS);
        player.setVelocity(new Vector(0, 0, 0));
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.0f);
        player.sendActionBar(ChatColor.RED + "钩索已取消");
    }

    private void cancelPullEx(Entity entity, Player player) {
        cancelPull(player);
        player.getWorld().playSound(entity.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.6f, 0.8f);
        player.sendActionBar(ChatColor.RED + "目标已脱离钩索");
    }

    private void cleanupState(UUID uuid) {
        pulling.remove(uuid);
        pullStates.remove(uuid);
        cooldowns.remove(uuid);
        failCooldowns.remove(uuid);
        fallProtection.remove(uuid);
    }

    private boolean isOnCooldown(UUID uuid) {
        Long normalEnd = cooldowns.get(uuid);
        if (normalEnd != null && System.currentTimeMillis() < normalEnd) return true;
        Long failEnd = failCooldowns.get(uuid);
        return failEnd != null && System.currentTimeMillis() < failEnd;
    }

    private boolean isUnsafe(Location loc) {
        return !loc.getBlock().isPassable() || !loc.clone().add(0, 1, 0).getBlock().isPassable();
    }

    private void spawnBeamProgress(Player player, Location target, double progress) {
        World world = player.getWorld();
        double radius = 0.3 * progress;
        int count = (int) (6 + 8 * progress);
        world.spawnParticle(Particle.END_ROD, target, count, radius, radius, radius, 0.02);
        world.spawnParticle(Particle.CRIT, target, count / 3, radius * 0.6, radius * 0.6, radius * 0.6, 0.01);
        if (progress > 0.6) world.spawnParticle(Particle.ELECTRIC_SPARK, target, 2, 0.2, 0.2, 0.2, 0);
    }

    private void spawnPullParticles(Player player, Location target) {
        World world = player.getWorld();
        world.spawnParticle(Particle.END_ROD, target, 3, 0.25, 0.25, 0.25, 0.02);
        Location playerLoc = player.getLocation().add(0, 0.5, 0);
        Vector fromTarget = playerLoc.toVector().subtract(target.toVector());
        double dist = fromTarget.length();
        if (dist <= 0) return;
        fromTarget.normalize();
        int trailCount = Math.min(3, (int) (dist / 5));
        for (int i = 1; i <= trailCount; i++) {
            Location point = target.clone().add(fromTarget.clone().multiply(i * 5));
            world.spawnParticle(Particle.END_ROD, point, 1, 0.08, 0.08, 0.08, 0);
        }
    }
}
