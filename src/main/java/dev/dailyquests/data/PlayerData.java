package dev.dailyquests.data;

import dev.dailyquests.model.QuestDefinition;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

public final class PlayerData {

    private final UUID uuid;
    private String dayKey = "";
    private String weekKey = "";
    private int extraHearts;
    /** Progreso y entregadas. Las semanales usan la clave con prefijo QuestDefinition.WEEKLY_PREFIX. */
    private final Map<String, Integer> progress = new HashMap<>();
    private final Set<String> completed = new HashSet<>();

    public PlayerData(UUID uuid) { this.uuid = uuid; }

    public UUID getUuid() { return uuid; }
    public String getDayKey() { return dayKey; }
    public String getWeekKey() { return weekKey; }
    public int getExtraHearts() { return extraHearts; }
    public void addHeart() { extraHearts++; }
    public void setExtraHearts(int v) { extraHearts = Math.max(0, v); }
    public double getMaxHealth() { return 20.0 + 2.0 * extraHearts; }

    public int getProgress(String key) { return progress.getOrDefault(key, 0); }
    public boolean isCompleted(String key) { return completed.contains(key); }
    public void markCompleted(String key) { completed.add(key); }

    /** Suma progreso sin sobrepasar el objetivo y devuelve el nuevo valor. */
    public int addProgress(String key, int amount, int cap) {
        int value = Math.min(cap, getProgress(key) + amount);
        progress.put(key, value);
        return value;
    }

    /** Reinicio diario: borra el progreso DIARIO (las semanales se conservan). Los corazones NO se tocan. */
    public void resetDaily(String newDayKey) {
        progress.keySet().removeIf(k -> !k.startsWith(QuestDefinition.WEEKLY_PREFIX));
        completed.removeIf(k -> !k.startsWith(QuestDefinition.WEEKLY_PREFIX));
        dayKey = newDayKey;
    }

    /** Reinicio semanal: borra solo el progreso SEMANAL. */
    public void resetWeekly(String newWeekKey) {
        progress.keySet().removeIf(k -> k.startsWith(QuestDefinition.WEEKLY_PREFIX));
        completed.removeIf(k -> k.startsWith(QuestDefinition.WEEKLY_PREFIX));
        weekKey = newWeekKey;
    }

    public void save(ConfigurationSection s) {
        s.set("day", dayKey);
        s.set("week", weekKey);
        s.set("extra-hearts", extraHearts);
        s.set("max-health", getMaxHealth());
        s.set("completed", new ArrayList<>(completed));
        for (Map.Entry<String, Integer> e : progress.entrySet()) {
            s.set("progress." + e.getKey(), e.getValue());
        }
    }

    public static PlayerData load(UUID uuid, ConfigurationSection s) {
        PlayerData d = new PlayerData(uuid);
        if (s == null) return d;
        d.dayKey = s.getString("day", "");
        d.weekKey = s.getString("week", "");
        d.extraHearts = s.getInt("extra-hearts", 0);
        d.completed.addAll(s.getStringList("completed"));
        ConfigurationSection prog = s.getConfigurationSection("progress");
        if (prog != null) {
            for (String k : prog.getKeys(false)) d.progress.put(k, prog.getInt(k));
        }
        return d;
    }
}
