package dev.dailyquests.manager;

import dev.dailyquests.DailyQuestsRPG;
import dev.dailyquests.data.PlayerData;
import dev.dailyquests.model.*;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

public final class QuestManager {

    private final DailyQuestsRPG plugin;
    /** Misiones del config.yml (se sortean cada día). */
    private final Map<String, QuestDefinition> all = new LinkedHashMap<>();
    /** Misiones creadas en el juego (custom-quests.yml): fijas, aparecen siempre hasta que se borren. */
    private final Map<String, QuestDefinition> custom = new LinkedHashMap<>();
    private final List<QuestDefinition> today = new ArrayList<>();
    private final File stateFile;
    private final File customFile;
    private ZoneId zone = ZoneId.of("UTC");
    private String currentDay = "";
    private String currentWeek = "";
    private DayOfWeek weekStart = DayOfWeek.MONDAY;
    private String eventQuestId = null;
    private boolean hardEnabled = true;

    public QuestManager(DailyQuestsRPG plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "state.yml");
        this.customFile = new File(plugin.getDataFolder(), "custom-quests.yml");
    }

    // ------------------------------------------------------------ carga
    public void reload() {
        plugin.reloadConfig();
        plugin.rewards().reload();
        FileConfiguration cfg = plugin.getConfig();
        try {
            zone = ZoneId.of(cfg.getString("settings.timezone", "UTC"));
        } catch (Exception ex) {
            zone = ZoneId.of("UTC");
            plugin.getLogger().warning("Zona horaria inválida, usando UTC.");
        }

        try {
            weekStart = DayOfWeek.valueOf(cfg.getString("settings.week-start-day", "MONDAY").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            weekStart = DayOfWeek.MONDAY;
        }

        all.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("quests");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection q = sec.getConfigurationSection(id);
                if (q == null) continue;
                try {
                    QuestDefinition def = parseQuest(id, q, false);
                    all.put(def.id(), def);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Misión '" + id + "' inválida: " + ex.getMessage());
                }
            }
        }

        custom.clear();
        if (customFile.exists()) {
            ConfigurationSection cs = YamlConfiguration.loadConfiguration(customFile).getConfigurationSection("quests");
            if (cs != null) {
                for (String id : cs.getKeys(false)) {
                    ConfigurationSection q = cs.getConfigurationSection(id);
                    if (q == null) continue;
                    try {
                        QuestDefinition def = parseQuest(id, q, true);
                        custom.put(def.id(), def);
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("Misión personalizada '" + id + "' inválida: " + ex.getMessage());
                    }
                }
            }
        }

        // Estado persistente (evento semanal y misiones difíciles on/off). El estado guardado manda sobre el config.
        YamlConfiguration st = stateFile.exists() ? YamlConfiguration.loadConfiguration(stateFile) : new YamlConfiguration();
        eventQuestId = st.getString("event-quest", null);
        hardEnabled = st.getBoolean("hard-enabled", cfg.getBoolean("settings.hard-quests-enabled", true));
        refreshDaily();
    }

    private QuestDefinition parseQuest(String id, ConfigurationSection q, boolean isCustom) {
        String qid = id.toLowerCase(Locale.ROOT);

        Set<String> targets = new LinkedHashSet<>();
        List<String> list = q.isList("target") ? q.getStringList("target") : List.of(q.getString("target", ""));
        for (String t : list) if (!t.isBlank()) targets.add(t.trim().toUpperCase(Locale.ROOT));
        if (targets.isEmpty()) throw new IllegalArgumentException("sin 'target'");

        int[] amt = RewardManager.range(q.get("amount"), 1);
        String first = targets.iterator().next().toLowerCase(Locale.ROOT).replace('_', ' ');

        List<ItemStack> items = new ArrayList<>();
        if (isCustom) {
            for (Object o : q.getList("reward-items", List.of())) if (o instanceof ItemStack is) items.add(is);
        }

        return new QuestDefinition(
                qid,
                ChatColor.translateAlternateColorCodes('&', q.getString("name", id)),
                Difficulty.valueOf(q.getString("difficulty", "EASY").toUpperCase(Locale.ROOT)),
                ObjectiveType.valueOf(q.getString("type", "KILL").toUpperCase(Locale.ROOT)),
                targets,
                q.getString("display", first),
                q.getString("icon", null),
                Math.max(1, amt[0]), Math.max(1, amt[1]), Math.max(1, amt[0]),
                CollectMode.valueOf(q.getString("collect-mode", "BREAK").toUpperCase(Locale.ROOT)),
                QuestPeriod.valueOf(q.getString("period", "DAILY").toUpperCase(Locale.ROOT)),
                isCustom,
                isCustom ? q.getInt("reward-xp", -1) : -1,
                items,
                q.getString("coins", null));
    }

    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("event-quest", eventQuestId);
        y.set("hard-enabled", hardEnabled);
        try { y.save(stateFile); } catch (IOException ex) { plugin.getLogger().warning("No se pudo guardar state.yml"); }
    }

    private void saveCustom() {
        YamlConfiguration y = new YamlConfiguration();
        for (QuestDefinition q : custom.values()) {
            String b = "quests." + q.id() + ".";
            y.set(b + "name", q.name().replace(ChatColor.COLOR_CHAR, '&'));
            y.set(b + "difficulty", q.difficulty().name());
            y.set(b + "type", q.type().name());
            y.set(b + "collect-mode", q.mode().name());
            y.set(b + "period", q.period().name());
            y.set(b + "target", new ArrayList<>(q.targets()));
            y.set(b + "display", q.display());
            if (q.icon() != null) y.set(b + "icon", q.icon());
            y.set(b + "amount", q.amountMin() == q.amountMax()
                    ? String.valueOf(q.amountMin()) : q.amountMin() + "-" + q.amountMax());
            y.set(b + "reward-xp", q.rewardXp());
            y.set(b + "reward-items", q.rewardItems());
            if (q.coins() != null) y.set(b + "coins", q.coins());
        }
        try { y.save(customFile); } catch (IOException ex) { plugin.getLogger().warning("No se pudo guardar custom-quests.yml"); }
    }

    // ------------------------------------------------------------ reset diario
    private String computeDayKey() {
        int resetHour = plugin.getConfig().getInt("settings.reset-hour", 0);
        return ZonedDateTime.now(zone).minusHours(resetHour).toLocalDate().toString();
    }

    /**
     * Sorteo diario determinista: misma fecha => mismas misiones y mismas cantidades para todos,
     * incluso tras un reinicio del servidor. Las difíciles se omiten si están desactivadas.
     * Las misiones creadas en el juego se añaden siempre (fijas).
     */
    public void refreshDaily() {
        currentDay = computeDayKey();
        currentWeek = LocalDate.parse(currentDay).with(TemporalAdjusters.previousOrSame(weekStart)).toString();
        today.clear();
        long daySeed = LocalDate.parse(currentDay).toEpochDay();
        long weekSeed = LocalDate.parse(currentWeek).toEpochDay();

        for (Difficulty d : Difficulty.values()) {
            if (d == Difficulty.HARD && !hardEnabled) continue;

            // --- diarias
            int n = plugin.getConfig().getInt("daily-quests." + d.key(), 1);
            List<QuestDefinition> pool = new ArrayList<>();
            for (QuestDefinition q : all.values()) if (q.difficulty() == d && !q.weekly()) pool.add(q);
            pool.sort(Comparator.comparing(QuestDefinition::id));
            Collections.shuffle(pool, new Random(daySeed * 31 + d.ordinal()));
            for (QuestDefinition q : pool.subList(0, Math.min(n, pool.size()))) today.add(rollAmount(q, daySeed));
        }
        for (Difficulty d : Difficulty.values()) {
            if (d == Difficulty.HARD && !hardEnabled) continue;

            // --- semanales (misma selección toda la semana)
            int w = plugin.getConfig().getInt("weekly-quests." + d.key(), 0);
            List<QuestDefinition> wpool = new ArrayList<>();
            for (QuestDefinition q : all.values()) if (q.difficulty() == d && q.weekly()) wpool.add(q);
            wpool.sort(Comparator.comparing(QuestDefinition::id));
            Collections.shuffle(wpool, new Random(weekSeed * 37 + d.ordinal()));
            for (QuestDefinition q : wpool.subList(0, Math.min(w, wpool.size()))) today.add(rollAmount(q, weekSeed));
        }
        for (QuestDefinition q : custom.values()) {
            if (q.difficulty() == Difficulty.HARD && !hardEnabled) continue;
            today.add(rollAmount(q, q.weekly() ? weekSeed : daySeed));
        }
    }

    private QuestDefinition rollAmount(QuestDefinition q, long seed) {
        Random rnd = new Random(seed * 131 + q.id().hashCode());
        return q.withAmount(q.amountMin() + rnd.nextInt(q.amountMax() - q.amountMin() + 1));
    }

    /** Llamado cada 30 s por el scheduler: detecta cambio de día y de semana. */
    public void checkDailyReset() {
        String day = computeDayKey();
        String week = LocalDate.parse(day).with(TemporalAdjusters.previousOrSame(weekStart)).toString();
        if (day.equals(currentDay) && week.equals(currentWeek)) return;
        boolean weekChanged = !week.equals(currentWeek);
        refreshDaily();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ensureToday(p);
            p.sendMessage(plugin.msg("daily-reset"));
            if (weekChanged) p.sendMessage(plugin.msg("weekly-reset"));
        }
        plugin.data().saveAsync();
    }

    public PlayerData ensureToday(Player p) {
        PlayerData d = plugin.data().get(p.getUniqueId());
        if (!d.getDayKey().equals(currentDay)) d.resetDaily(currentDay);
        if (!d.getWeekKey().equals(currentWeek)) d.resetWeekly(currentWeek);
        return d;
    }

    // ------------------------------------------------------------ misiones difíciles on/off
    public boolean isHardEnabled() { return hardEnabled; }

    /** Al desactivar, las difíciles desaparecen hoy mismo; el progreso guardado se conserva por si se reactivan. */
    public void setHardEnabled(boolean enabled) {
        hardEnabled = enabled;
        saveState();
        refreshDaily();
    }

    // ------------------------------------------------------------ evento semanal
    public boolean isEvent(QuestDefinition q) { return eventQuestId != null && eventQuestId.equals(q.id()); }
    public String getEventQuestId() { return eventQuestId; }

    public void setEventQuest(String id) {
        eventQuestId = id;
        saveState();
    }

    // ------------------------------------------------------------ misiones personalizadas (creadas en el juego)
    public boolean isCustom(String id) { return custom.containsKey(id.toLowerCase(Locale.ROOT)); }
    public Set<String> customIds() { return Collections.unmodifiableSet(custom.keySet()); }

    public QuestDefinition createCustom(String id, String name, Difficulty d, ObjectiveType type, CollectMode mode,
                                        Set<String> targets, String display, int min, int max) {
        QuestDefinition q = new QuestDefinition(id, ChatColor.translateAlternateColorCodes('&', name), d, type,
                targets, display, null, min, max, min, mode, QuestPeriod.DAILY, true, -1, new ArrayList<>(), null);
        custom.put(id, q);
        saveCustom();
        refreshDaily();
        return q;
    }

    public boolean deleteCustom(String id) {
        id = id.toLowerCase(Locale.ROOT);
        if (custom.remove(id) == null) return false;
        if (id.equals(eventQuestId)) setEventQuest(null);
        saveCustom();
        refreshDaily();
        return true;
    }

    public boolean setRewardXp(String id, int xp) {
        QuestDefinition q = custom.get(id.toLowerCase(Locale.ROOT));
        if (q == null) return false;
        custom.put(q.id(), q.withRewards(xp, q.rewardItems()));
        saveCustom();
        refreshDaily();
        return true;
    }

    public boolean addRewardItem(String id, ItemStack stack) {
        QuestDefinition q = custom.get(id.toLowerCase(Locale.ROOT));
        if (q == null) return false;
        List<ItemStack> items = new ArrayList<>(q.rewardItems());
        items.add(stack);
        custom.put(q.id(), q.withRewards(q.rewardXp(), items));
        saveCustom();
        refreshDaily();
        return true;
    }

    /** Borra las recompensas fijas: la misión vuelve a usar el sorteo de su dificultad. */
    public boolean clearRewards(String id) {
        QuestDefinition q = custom.get(id.toLowerCase(Locale.ROOT));
        if (q == null) return false;
        custom.put(q.id(), q.withRewards(-1, new ArrayList<>()).withCoins(null));
        saveCustom();
        refreshDaily();
        return true;
    }

    /** Fija los Rincones de una misión creada en el juego ("5" o "3-6"); null = cantidad por dificultad. */
    public boolean setCoins(String id, String coins) {
        QuestDefinition q = custom.get(id.toLowerCase(Locale.ROOT));
        if (q == null) return false;
        custom.put(q.id(), q.withCoins(coins));
        saveCustom();
        refreshDaily();
        return true;
    }

    /** Cambia una misión personalizada a diaria o semanal. */
    public boolean setPeriod(String id, QuestPeriod period) {
        QuestDefinition q = custom.get(id.toLowerCase(Locale.ROOT));
        if (q == null) return false;
        custom.put(q.id(), q.withPeriod(period));
        saveCustom();
        refreshDaily();
        return true;
    }

    // ------------------------------------------------------------ consulta
    public List<QuestDefinition> getToday() { return Collections.unmodifiableList(today); }

    public QuestDefinition get(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        QuestDefinition q = custom.get(key);
        return q != null ? q : all.get(key);
    }

    public Set<String> allIds() {
        Set<String> ids = new LinkedHashSet<>(all.keySet());
        ids.addAll(custom.keySet());
        return ids;
    }

    /** Lista para entregar: objetivo cumplido y todavía sin entregar. */
    public boolean isReady(PlayerData data, QuestDefinition q) {
        return !data.isCompleted(q.key()) && data.getProgress(q.key()) >= q.amount();
    }

    // ------------------------------------------------------------ progreso
    /**
     * Suma progreso a todas las misiones de hoy que coincidan. Al llegar al objetivo la misión queda
     * "lista"; la recompensa solo se cobra al entregarla al NPC.
     * @param keys posibles identificadores del objetivo (Material, EntityType, ID de jefe, nombre de jugador...)
     */
    public void progress(Player p, ObjectiveType type, int amount, CollectMode mode, String... keys) {
        PlayerData data = ensureToday(p);
        for (QuestDefinition q : today) {
            if (q.type() != type) continue;
            if (type == ObjectiveType.COLLECT && q.mode() != mode) continue;
            if (data.isCompleted(q.key()) || data.getProgress(q.key()) >= q.amount()) continue;

            boolean match = false;
            for (String k : keys) if (q.targets().contains(k.toUpperCase(Locale.ROOT))) { match = true; break; }
            if (!match) continue;

            int now = data.addProgress(q.key(), amount, q.amount());
            if (now >= q.amount()) {
                p.sendMessage(plugin.msg("quest-ready", "quest", q.name()));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                plugin.data().saveAsync();
            } else {
                String bar = plugin.msg("quest-progress", "quest", q.name(),
                        "current", String.valueOf(now), "amount", String.valueOf(q.amount()));
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(bar));
            }
        }
    }

    // ------------------------------------------------------------ entrega al NPC
    /** Entrega una misión lista y da la recompensa. Devuelve false si no estaba lista. */
    public boolean deliver(Player p, QuestDefinition q) {
        PlayerData data = ensureToday(p);
        if (!isReady(data, q)) return false;
        data.markCompleted(q.key());
        plugin.rewards().give(p, data, q, isEvent(q));
        plugin.data().saveAsync();
        return true;
    }

    /** Entrega todas las misiones listas. Devuelve cuántas se entregaron. */
    public int deliverAll(Player p) {
        int n = 0;
        for (QuestDefinition q : new ArrayList<>(today)) if (deliver(p, q)) n++;
        return n;
    }
}
