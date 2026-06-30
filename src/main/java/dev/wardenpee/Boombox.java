package dev.wardenpee;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Boombox implements Listener {

    private final JavaPlugin plugin;

    private static final String NAME_KEYWORD = "бумбокс";
    private static final int    GUI_SIZE     = 27;
    private static final int    DISC_SLOT    = 13;
    private static final int    START_SLOT   = 11;
    private static final int    STOP_SLOT    = 15;
    private static final float  SOUND_VOLUME = 4.0f;
    private static final double STOP_RADIUS  = 65.0;

    private final Map<UUID, Session> active = new HashMap<>();
    private final Map<UUID, Integer> sourceSlot = new HashMap<>();
    private final Map<UUID, Object> activeAudioPlayers = new HashMap<>();
    private final org.bukkit.NamespacedKey STORED_DISC_KEY;

    public Boombox(JavaPlugin plugin) {
        this.plugin = plugin;
        this.STORED_DISC_KEY = new org.bukkit.NamespacedKey(plugin, "stored_disc");
    }

    // ─── Защита от установки ─────────────────────────────────────────────

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (isBoombox(event.getItemInHand())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§eБумбокс нельзя ставить. ПКМ — открыть.");
        }
    }

    // ─── Открытие GUI по ПКМ ─────────────────────────────────────────────

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {}
            default -> { return; }
        }
        ItemStack item = event.getItem();
        if (!isBoombox(item)) return;
        event.setCancelled(true);
        openGui(event.getPlayer());
    }

    @SuppressWarnings("deprecation")
    private void openGui(Player player) {
        BoomboxHolder holder = new BoomboxHolder();
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, " ");
        holder.inv = inv;

        ItemStack filler = makeButton(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, filler);

        // Загружаем сохранённую пластинку из бумбокса
        int slot = player.getInventory().getHeldItemSlot();
        sourceSlot.put(player.getUniqueId(), slot);
        ItemStack boombox = player.getInventory().getItem(slot);
        ItemStack storedDisc = loadStoredDisc(boombox);
        inv.setItem(DISC_SLOT, storedDisc);

        inv.setItem(START_SLOT, makeButton(Material.LIME_STAINED_GLASS_PANE,
                "§a▶ Старт", NamedTextColor.GREEN));
        inv.setItem(STOP_SLOT, makeButton(Material.RED_STAINED_GLASS_PANE,
                "§c■ Стоп", NamedTextColor.RED));

        player.openInventory(inv);
    }

    // ─── Клики в GUI ─────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BoomboxHolder)) return;

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        // Клик в инвентаре игрока
        if (slot >= topSize) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && isMusicDisc(clicked.getType())) {
                    Inventory top = event.getView().getTopInventory();
                    ItemStack inDisc = top.getItem(DISC_SLOT);
                    if (inDisc == null || inDisc.getType() == Material.AIR) {
                        top.setItem(DISC_SLOT, clicked.clone());
                        event.setCurrentItem(null);
                        stopIfPlaying(player); // на всякий случай (вдруг играет)
                    }
                }
            }
            return;
        }

        // Слот пластинки — разрешаем класть/забирать только диски
        if (slot == DISC_SLOT) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR && !isMusicDisc(cursor.getType())) {
                event.setCancelled(true);
                return;
            }
            // Любое взаимодействие с диск-слотом (взять, заменить, положить) — глушим воспроизведение
            stopIfPlaying(player);
            return;
        }

        // Все остальные слоты в GUI — кнопки или филлер, ничего не двигаем
        event.setCancelled(true);

        if (slot == START_SLOT) {
            ItemStack disc = event.getInventory().getItem(DISC_SLOT);
            if (disc == null || !isMusicDisc(disc.getType())) {
                player.sendMessage("§cВставь пластинку в центральный слот.");
                return;
            }
            startPlaying(player, disc);
        } else if (slot == STOP_SLOT) {
            if (active.containsKey(player.getUniqueId())) {
                stopPlaying(player);
                player.sendMessage("§7■ Остановлено.");
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof BoomboxHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize && slot != DISC_SLOT) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ─── Закрытие — пластинку отдаём обратно игроку ──────────────────────

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BoomboxHolder)) return;
        Player player = (Player) event.getPlayer();
        Integer slot = sourceSlot.remove(player.getUniqueId());
        if (slot == null) slot = player.getInventory().getHeldItemSlot();

        ItemStack boombox = player.getInventory().getItem(slot);
        if (!isBoombox(boombox)) {
            // Бумбокс куда-то делся — ищем по всему инвентарю
            for (int i = 0; i < player.getInventory().getSize(); i++) {
                ItemStack it = player.getInventory().getItem(i);
                if (isBoombox(it)) { boombox = it; slot = i; break; }
            }
        }
        if (!isBoombox(boombox)) {
            // Совсем потерян — дропаем пластинку
            ItemStack disc = event.getInventory().getItem(DISC_SLOT);
            if (disc != null && disc.getType() != Material.AIR) {
                player.getWorld().dropItemNaturally(player.getLocation(), disc);
            }
            return;
        }

        ItemStack disc = event.getInventory().getItem(DISC_SLOT);
        saveStoredDisc(boombox, disc);
        player.getInventory().setItem(slot, boombox);
    }

    private void saveStoredDisc(ItemStack boombox, ItemStack disc) {
        ItemMeta meta = boombox.getItemMeta();
        if (meta == null) return;
        if (disc == null || disc.getType() == Material.AIR) {
            meta.getPersistentDataContainer().remove(STORED_DISC_KEY);
        } else {
            byte[] data = disc.serializeAsBytes();
            meta.getPersistentDataContainer().set(STORED_DISC_KEY,
                    org.bukkit.persistence.PersistentDataType.BYTE_ARRAY, data);
        }
        boombox.setItemMeta(meta);
    }

    private ItemStack loadStoredDisc(ItemStack boombox) {
        if (!isBoombox(boombox)) return null;
        ItemMeta meta = boombox.getItemMeta();
        if (meta == null) return null;
        byte[] data = meta.getPersistentDataContainer().get(STORED_DISC_KEY,
                org.bukkit.persistence.PersistentDataType.BYTE_ARRAY);
        if (data == null) return null;
        try {
            return ItemStack.deserializeBytes(data);
        } catch (Throwable t) {
            return null;
        }
    }

    // ─── Воспроизведение ─────────────────────────────────────────────────

    private static final org.bukkit.NamespacedKey CD_FILENAME_KEY =
            new org.bukkit.NamespacedKey("customdiscs", "customdisc");
    private static final org.bukkit.NamespacedKey CD_RANGE_KEY =
            new org.bukkit.NamespacedKey("customdiscs", "range");

    private void startPlaying(Player player, ItemStack disc) {
        stopPlaying(player);

        Location loc = player.getLocation();
        org.bukkit.block.Block block = loc.getBlock();

        // Сначала пробуем кастомную пластинку — играть через EntityAudioChannel (идёт за игроком)
        if (tryPlayCustomDiscFollowing(player, disc)) {
            String name = customDiscName(disc);
            active.put(player.getUniqueId(), new Session(null, loc, block, true));
            player.sendMessage("§a▶ Играет: §f" + name);
            return;
        }

        // Иначе — ванильный звук, привязанный к энтити игрока (идёт за ним)
        Sound sound = getDiscSound(disc.getType());
        if (sound == null) {
            player.sendMessage("§cЭто не пластинка.");
            return;
        }
        for (Player listener : player.getLocation().getNearbyPlayers(STOP_RADIUS)) {
            listener.playSound(player, sound, SoundCategory.RECORDS, SOUND_VOLUME, 1.0f);
        }
        active.put(player.getUniqueId(), new Session(sound, loc, block, false));
        String discName = disc.getType().name().replace("MUSIC_DISC_", "");
        player.sendMessage("§a▶ Играет: §f" + discName);
    }

    private void stopPlaying(Player player) {
        Session session = active.remove(player.getUniqueId());
        if (session == null) return;
        if (session.isCustom) {
            stopCustomDiscFollowing(player);
        } else {
            // Звук привязан к игроку — может уйти далеко, глушим во всём мире
            for (Player listener : player.getWorld().getPlayers()) {
                listener.stopSound(session.sound, SoundCategory.RECORDS);
            }
        }
    }

    private void stopIfPlaying(Player player) {
        if (active.containsKey(player.getUniqueId())) {
            stopPlaying(player);
        }
    }

    // ─── Интеграция с CustomDiscs (через рефлексию) ──────────────────────

    private boolean isCustomDisc(ItemStack disc) {
        if (disc == null) return false;
        ItemMeta meta = disc.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(CD_FILENAME_KEY,
                org.bukkit.persistence.PersistentDataType.STRING);
    }

    private String customDiscName(ItemStack disc) {
        ItemMeta meta = disc.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component dn = meta.displayName();
            if (dn != null) {
                return PlainTextComponentSerializer.plainText().serialize(dn);
            }
        }
        return "custom";
    }

    private boolean tryPlayCustomDisc(ItemStack disc, org.bukkit.block.Block block) {
        if (!isCustomDisc(disc)) return false;
        try {
            ItemMeta meta = disc.getItemMeta();
            String filename = meta.getPersistentDataContainer()
                    .get(CD_FILENAME_KEY, org.bukkit.persistence.PersistentDataType.STRING);
            if (filename == null) return false;

            Float rangeBoxed = meta.getPersistentDataContainer()
                    .get(CD_RANGE_KEY, org.bukkit.persistence.PersistentDataType.FLOAT);
            float distance = rangeBoxed != null ? rangeBoxed : 64.0f;

            java.nio.file.Path path = java.nio.file.Paths.get(
                    "plugins", "CustomDiscs", "musicdata", filename);
            if (!java.nio.file.Files.exists(path)) {
                plugin.getLogger().warning("CustomDiscs file not found: " + path);
                return false;
            }

            Class<?> voicePluginClass = Class.forName(
                    "me.Navoei.customdiscsplugin.VoicePlugin");
            Object voicechatApi = voicePluginClass
                    .getField("voicechatServerApi").get(null);
            if (voicechatApi == null) {
                plugin.getLogger().warning("VoicechatServerApi == null");
                return false;
            }

            Class<?> pmClass = Class.forName(
                    "me.Navoei.customdiscsplugin.PlayerManager");
            Object pm = pmClass.getMethod("instance").invoke(null);

            Class<?> apiClass = Class.forName(
                    "de.maxhenkel.voicechat.api.VoicechatServerApi");
            Component songName = Component.text(customDiscName(disc));

            java.lang.reflect.Method playAudio = pmClass.getMethod(
                    "playAudio", apiClass, java.nio.file.Path.class,
                    org.bukkit.block.Block.class, Component.class, float.class);
            playAudio.invoke(pm, voicechatApi, path, block, songName, distance);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning(
                    "CustomDiscs integration failed: " + t.getMessage());
            return false;
        }
    }

    private void stopCustomDisc(org.bukkit.block.Block block) {
        try {
            Class<?> pmClass = Class.forName(
                    "me.Navoei.customdiscsplugin.PlayerManager");
            Object pm = pmClass.getMethod("instance").invoke(null);
            pmClass.getMethod("stopDisc", org.bukkit.block.Block.class)
                    .invoke(pm, block);
        } catch (Throwable ignored) {}
    }

    // Кастомный плеер для voicechat — анкорит к энтити игрока (идёт за ним)
    private boolean tryPlayCustomDiscFollowing(Player player, ItemStack disc) {
        if (!isCustomDisc(disc)) return false;
        try {
            ItemMeta meta = disc.getItemMeta();
            String filename = meta.getPersistentDataContainer()
                    .get(CD_FILENAME_KEY, org.bukkit.persistence.PersistentDataType.STRING);
            if (filename == null) return false;

            Float rangeBoxed = meta.getPersistentDataContainer()
                    .get(CD_RANGE_KEY, org.bukkit.persistence.PersistentDataType.FLOAT);
            float distance = rangeBoxed != null ? rangeBoxed : 64.0f;

            java.nio.file.Path path = java.nio.file.Paths.get(
                    "plugins", "CustomDiscs", "musicdata", filename);
            if (!java.nio.file.Files.exists(path)) {
                plugin.getLogger().warning("CustomDiscs file not found: " + path);
                return false;
            }

            Class<?> voicePluginClass = Class.forName("me.Navoei.customdiscsplugin.VoicePlugin");
            Object serverApi = voicePluginClass.getField("voicechatServerApi").get(null);
            Object api = voicePluginClass.getField("voicechatApi").get(null);
            String musicCategory = (String) voicePluginClass.getField("MUSIC_DISC_CATEGORY").get(null);
            if (serverApi == null || api == null) {
                plugin.getLogger().warning("Voicechat API == null");
                return false;
            }

            // Обернуть игрока в voicechat Entity
            Object vcEntity = api.getClass().getMethod("fromEntity", Object.class).invoke(api, player);

            // Создать EntityAudioChannel
            Class<?> entityIfClass = Class.forName("de.maxhenkel.voicechat.api.Entity");
            Object channel = serverApi.getClass()
                    .getMethod("createEntityAudioChannel", java.util.UUID.class, entityIfClass)
                    .invoke(serverApi, java.util.UUID.randomUUID(), vcEntity);
            if (channel == null) return false;

            // Распарсить ссылки на интерфейсы канала и плеера
            Class<?> entChClass = Class.forName("de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel");
            Class<?> audioChClass = Class.forName("de.maxhenkel.voicechat.api.audiochannel.AudioChannel");
            entChClass.getMethod("setDistance", float.class).invoke(channel, distance);
            audioChClass.getMethod("setCategory", String.class).invoke(channel, musicCategory);

            // Открыть аудио стрим через CustomDiscs (он сам конвертит mp3/wav/flac в нужный формат)
            javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(
                    48000.0f, 16, 1, true, false);
            Class<?> pmClass = Class.forName("me.Navoei.customdiscsplugin.PlayerManager");
            Object pm = pmClass.getMethod("instance").invoke(null);
            javax.sound.sampled.AudioInputStream ais = (javax.sound.sampled.AudioInputStream)
                    pmClass.getMethod("getAudioInputStream", java.nio.file.Path.class, javax.sound.sampled.AudioFormat.class)
                            .invoke(pm, path, fmt);

            // Поставщик 960-сэмпловых фреймов (20мс @ 48кГц), читает из стрима
            final javax.sound.sampled.AudioInputStream stream = ais;
            java.util.function.Supplier<short[]> supplier = () -> {
                try {
                    byte[] buf = new byte[960 * 2];
                    int total = 0;
                    while (total < buf.length) {
                        int r = stream.read(buf, total, buf.length - total);
                        if (r < 0) break;
                        total += r;
                    }
                    if (total == 0) return null;
                    short[] samples = new short[960];
                    for (int i = 0; i < 960; i++) {
                        int lo = (i * 2 < total) ? (buf[i * 2] & 0xFF) : 0;
                        int hi = (i * 2 + 1 < total) ? (buf[i * 2 + 1] << 8) : 0;
                        samples[i] = (short) (hi | lo);
                    }
                    return samples;
                } catch (java.io.IOException e) {
                    return null;
                }
            };

            // Создать энкодер и плеер
            Object encoder = api.getClass().getMethod("createEncoder").invoke(api);
            Class<?> encoderClass = Class.forName("de.maxhenkel.voicechat.api.opus.OpusEncoder");
            Object audioPlayer = serverApi.getClass()
                    .getMethod("createAudioPlayer", audioChClass, encoderClass, java.util.function.Supplier.class)
                    .invoke(serverApi, channel, encoder, supplier);

            // Закрыть стрим когда плеер остановится
            Class<?> audioPlayerClass = Class.forName("de.maxhenkel.voicechat.api.audiochannel.AudioPlayer");
            audioPlayerClass.getMethod("setOnStopped", Runnable.class).invoke(audioPlayer, (Runnable) () -> {
                try { stream.close(); } catch (Exception ignored) {}
            });
            audioPlayerClass.getMethod("startPlaying").invoke(audioPlayer);

            activeAudioPlayers.put(player.getUniqueId(), audioPlayer);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Boombox custom-disc follow failed: " + t);
            return false;
        }
    }

    private void stopCustomDiscFollowing(Player player) {
        Object audioPlayer = activeAudioPlayers.remove(player.getUniqueId());
        if (audioPlayer == null) return;
        try {
            Class<?> audioPlayerClass = Class.forName("de.maxhenkel.voicechat.api.audiochannel.AudioPlayer");
            audioPlayerClass.getMethod("stopPlaying").invoke(audioPlayer);
        } catch (Throwable ignored) {}
    }

    // ─── Утилиты ─────────────────────────────────────────────────────────

    private boolean isBoombox(ItemStack item) {
        if (item == null || item.getType() != Material.JUKEBOX) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        Component name = meta.displayName();
        if (name == null) return false;
        String plain = PlainTextComponentSerializer.plainText().serialize(name).toLowerCase();
        return plain.contains(NAME_KEYWORD);
    }

    private boolean isMusicDisc(Material m) {
        return m.name().startsWith("MUSIC_DISC_");
    }

    private Sound getDiscSound(Material m) {
        try {
            return Sound.valueOf(m.name());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ItemStack makeButton(Material material, String legacyName, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component comp = Component.text(legacyName.replaceAll("§.", ""))
                    .decoration(TextDecoration.ITALIC, false);
            if (color != null) comp = comp.color(color);
            meta.displayName(comp);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ─── Внутренние типы ─────────────────────────────────────────────────

    public static class BoomboxHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private record Session(Sound sound, Location origin, org.bukkit.block.Block block, boolean isCustom) {}
}
