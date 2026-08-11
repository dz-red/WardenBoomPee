package dev.wardenpee;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BoomPee extends JavaPlugin {

    private final Map<UUID, Long> peeCooldowns   = new HashMap<>();
    private final Map<UUID, Long> vomitCooldowns = new HashMap<>();
    private final Map<UUID, Long> poopCooldowns  = new HashMap<>();
    private final Map<UUID, Long> fartCooldowns  = new HashMap<>();

    private final Map<UUID, BukkitRunnable> peeActive   = new HashMap<>();
    private final Map<UUID, BukkitRunnable> vomitActive = new HashMap<>();
    private final Map<UUID, BukkitRunnable> poopActive  = new HashMap<>();

    private static final long COOLDOWN_MS    = 5000;
    private static final int  DURATION_TICKS = 100;

    private static final Particle.DustOptions YELLOW       = new Particle.DustOptions(Color.fromRGB(255, 210, 20), 0.65f);
    private static final Particle.DustOptions YELLOW_SMALL = new Particle.DustOptions(Color.fromRGB(255, 210, 20), 0.4f);
    private static final Particle.DustOptions GREEN        = new Particle.DustOptions(Color.fromRGB(50, 200, 50),  1.1f);
    private static final Particle.DustOptions GREEN_SMALL  = new Particle.DustOptions(Color.fromRGB(50, 200, 50),  0.7f);
    private static final Particle.DustOptions BROWN        = new Particle.DustOptions(Color.fromRGB(101, 55, 0),   1.2f);
    private static final Particle.DustOptions BROWN_SMALL  = new Particle.DustOptions(Color.fromRGB(101, 55, 0),   0.7f);
    private static final Particle.DustOptions GAS = new Particle.DustOptions(Color.fromRGB(120, 210, 30), 1.4f);

    @Override
    public void onEnable() {
        getLogger().info("BoomPee включён");
        getServer().getPluginManager().registerEvents(new Boombox(this), this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игроков.");
            return true;
        }
        return switch (cmd.getName().toLowerCase()) {
            case "pee"   -> handlePee(player);
            case "vomit" -> handleVomit(player);
            case "poop"  -> handlePoop(player);
            case "fart"  -> handleFart(player);
            default -> false;
        };
    }

    // ─── /pee ───────────────────────────────────────────────────────────────

    private boolean handlePee(Player player) {
        UUID id = player.getUniqueId();
        if (peeActive.containsKey(id)) { peeActive.remove(id).cancel(); return true; }
        long rem = cooldownRemaining(peeCooldowns, id);
        if (rem > 0) { player.sendMessage("§7Подожди ещё §e" + rem + " §7сек."); return true; }
        peeCooldowns.put(id, System.currentTimeMillis());
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_WATER_AMBIENT, 10.0f, 0.5f);
        BukkitRunnable task = new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!player.isOnline()) { peeActive.remove(id); cancel(); return; }
                if (tick >= DURATION_TICKS) { peeActive.remove(id); cancel(); return; }
                spawnPee(player);
                tick++;
            }
        };
        task.runTaskTimer(this, 0L, 1L);
        peeActive.put(id, task);
        return true;
    }

    private void spawnPee(Player player) {
        Location base = player.getLocation().clone().add(0, 0.75, 0);
        Vector fwd = player.getLocation().getDirection().clone().setY(0).normalize();
        base.add(fwd.clone().multiply(0.15));
        traceStream(player, base, fwd, 1.8, -1.1, 14, 1.2, YELLOW, YELLOW_SMALL);
    }

    // ─── /vomit ─────────────────────────────────────────────────────────────

    private boolean handleVomit(Player player) {
        UUID id = player.getUniqueId();
        if (vomitActive.containsKey(id)) { vomitActive.remove(id).cancel(); return true; }
        long rem = cooldownRemaining(vomitCooldowns, id);
        if (rem > 0) { player.sendMessage("§7Подожди ещё §e" + rem + " §7сек."); return true; }
        vomitCooldowns.put(id, System.currentTimeMillis());
        player.addPotionEffect(new PotionEffect(PotionEffectType.CONFUSION, DURATION_TICKS, 0, false, false));
        BukkitRunnable task = new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!player.isOnline()) { vomitActive.remove(id); cancel(); return; }
                if (tick >= DURATION_TICKS) { vomitActive.remove(id); cancel(); return; }
                spawnVomit(player);
                if (tick % 20 == 0)
                    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 10.0f, 0.5f);
                tick++;
            }
        };
        task.runTaskTimer(this, 0L, 1L);
        vomitActive.put(id, task);
        return true;
    }

    private void spawnVomit(Player player) {
        Location base = player.getEyeLocation().clone();
        Vector fwd = player.getLocation().getDirection().clone().normalize();
        base.add(fwd.clone().multiply(0.2));
        traceStream(player, base, fwd, 1.3, -1.8, 20, 3.0, GREEN, GREEN_SMALL);
    }

    // ─── /poop ──────────────────────────────────────────────────────────────

    private boolean handlePoop(Player player) {
        UUID id = player.getUniqueId();
        if (poopActive.containsKey(id)) { poopActive.remove(id).cancel(); return true; }
        long rem = cooldownRemaining(poopCooldowns, id);
        if (rem > 0) { player.sendMessage("§7Подожди ещё §e" + rem + " §7сек."); return true; }
        poopCooldowns.put(id, System.currentTimeMillis());
        BukkitRunnable task = new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!player.isOnline()) { poopActive.remove(id); cancel(); return; }
                if (tick >= DURATION_TICKS) { poopActive.remove(id); cancel(); return; }
                spawnPoop(player);
                if (tick % 25 == 0)
                    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SLIME_SQUISH, 10.0f, 0.2f);
                tick++;
            }
        };
        task.runTaskTimer(this, 0L, 1L);
        poopActive.put(id, task);
        return true;
    }

    private void spawnPoop(Player player) {
        Vector behind = player.getLocation().getDirection().clone().setY(0).normalize().multiply(-1);
        Location base = player.getLocation().clone().add(0, 0.5, 0).add(behind.clone().multiply(0.3));
        traceStream(player, base, behind, 1.2, -2.0, 16, 1.0, BROWN, BROWN_SMALL);
    }

    // ─── /fart ──────────────────────────────────────────────────────────────

    private boolean handleFart(Player player) {
        UUID id = player.getUniqueId();
        long rem = cooldownRemaining(fartCooldowns, id);
        if (rem > 0) { player.sendMessage("§7Подожди ещё §e" + rem + " §7сек."); return true; }
        fartCooldowns.put(id, System.currentTimeMillis());

        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 10.0f, 0.1f);

        Vector fwd = player.getLocation().getDirection().clone().setY(0).normalize();
        Vector behind = fwd.clone().multiply(-1);
        Location butt = player.getLocation().clone().add(0, 0.6, 0).add(behind.clone().multiply(0.25));
        World w = butt.getWorld();

        // Выстрел частицами костра назад — count=0 означает velocity mode
        for (int i = 0; i < 20; i++) {
            double spread = 0.25;
            double vx = behind.getX() + (Math.random() - 0.5) * spread;
            double vy = (Math.random()) * 0.3;
            double vz = behind.getZ() + (Math.random() - 0.5) * spread;
            w.spawnParticle(Particle.REDSTONE, butt, 1, (Math.random()-0.5)*0.5 + behind.getX()*0.4, Math.random()*0.25, (Math.random()-0.5)*0.5 + behind.getZ()*0.4, 0, GAS);
        }
        return true;
    }

    // ─── Общая логика трассировки ────────────────────────────────────────────

    private void traceStream(Player player, Location base, Vector fwd,
                             double hDist, double gravity, int points, double maxDrop,
                             Particle.DustOptions dust, Particle.DustOptions dustSmall) {
        World w = base.getWorld();
        for (int i = 0; i < points; i++) {
            double t = i / (double) points;
            double x = fwd.getX() * t * hDist;
            double y = fwd.getY() * t * hDist + gravity * t * t;
            double z = fwd.getZ() * t * hDist;

            Location loc = base.clone().add(x, y, z);
            if (loc.getY() < base.getY() - maxDrop) {
                spawnSplatter(loc, fwd, true, dust, dustSmall);
                return;
            }
            Block block = loc.getBlock();
            if (block.getType().isSolid()) {
                spawnSplatter(loc, fwd, false, dust, dustSmall);
                return;
            }
            boolean hitEntity = false;
            for (Entity e : w.getNearbyEntities(loc, 0.4, 0.8, 0.4)) {
                if (e != player && e instanceof LivingEntity) { hitEntity = true; break; }
            }
            if (hitEntity) { spawnSplatter(loc, fwd, false, dust, dustSmall); return; }

            w.spawnParticle(Particle.REDSTONE, loc, 1, 0.02, 0.02, 0.02, 0, dust);
        }
    }

    private void spawnSplatter(Location loc, Vector streamDir, boolean hitFloor,
                               Particle.DustOptions dust, Particle.DustOptions dustSmall) {
        World w = loc.getWorld();
        if (hitFloor) {
            w.spawnParticle(Particle.REDSTONE, loc, 5, 0.35, 0.05, 0.35, 0, dust);
            w.spawnParticle(Particle.REDSTONE, loc.clone().add(0, 0.05, 0), 3, 0.5, 0.02, 0.5, 0, dustSmall);
        } else {
            Vector side = new Vector(-streamDir.getZ(), 0, streamDir.getX());
            w.spawnParticle(Particle.REDSTONE, loc, 4, 0.15, 0.3, 0.15, 0, dust);
            w.spawnParticle(Particle.REDSTONE, loc.clone().add(side.clone().multiply(0.3)),  3, 0.1, 0.25, 0.1, 0, dustSmall);
            w.spawnParticle(Particle.REDSTONE, loc.clone().add(side.clone().multiply(-0.3)), 3, 0.1, 0.25, 0.1, 0, dustSmall);
            w.spawnParticle(Particle.REDSTONE, loc.clone().add(0, -0.2, 0), 2, 0.1, 0.15, 0.1, 0, dustSmall);
        }
    }

    // ─── Утилита ─────────────────────────────────────────────────────────────

    private long cooldownRemaining(Map<UUID, Long> map, UUID id) {
        Long last = map.get(id);
        if (last == null) return 0;
        long elapsed = System.currentTimeMillis() - last;
        return elapsed < COOLDOWN_MS ? (COOLDOWN_MS - elapsed + 999) / 1000 : 0;
    }
}
