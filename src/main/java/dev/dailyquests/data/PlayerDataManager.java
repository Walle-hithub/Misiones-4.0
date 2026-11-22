package dev.dailyquests.data;

import dev.dailyquests.DailyQuestsRPG;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Persistencia en playerdata.yml con caché en memoria.
 * Los datos se mutan SOLO en el hilo principal; el snapshot se serializa en el hilo principal
 * y la escritura a disco se hace async (o sync en onDisable).
 * Para servidores grandes: extraer una interfaz y cambiar esta clase por una versión SQLite.
 */
public final class PlayerDataManager {

    private final DailyQuestsRPG plugin;
    private final File file;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final Object ioLock = new Object();

    public PlayerDataManager(DailyQuestsRPG plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playerdata.yml");
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                cache.put(id, PlayerData.load(id, players.getConfigurationSection(key)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("UUID inválido en playerdata.yml: " + key);
            }
        }
    }

    public PlayerData get(UUID id) {
        return cache.computeIfAbsent(id, PlayerData::new);
    }

    private String snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerData> e : cache.entrySet()) {
            e.getValue().save(yaml.createSection("players." + e.getKey()));
        }
        return yaml.saveToString();
    }

    public void saveAsync() {
        String data = snapshot();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data));
    }

    public void saveSync() {
        write(snapshot());
    }

    private void write(String data) {
        synchronized (ioLock) {
            try {
                Files.createDirectories(file.getParentFile().toPath());
                Path tmp = file.toPath().resolveSibling("playerdata.yml.tmp");
                Files.writeString(tmp, data, StandardCharsets.UTF_8);
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "No se pudo guardar playerdata.yml", ex);
            }
        }
    }
}
