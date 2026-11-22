package dev.dailyquests;

import dev.dailyquests.command.QuestCommand;
import dev.dailyquests.data.PlayerDataManager;
import dev.dailyquests.listener.NpcListener;
import dev.dailyquests.listener.PlayerListener;
import dev.dailyquests.listener.ProgressListener;
import dev.dailyquests.manager.QuestManager;
import dev.dailyquests.manager.RewardManager;
import dev.dailyquests.npc.NpcManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class DailyQuestsRPG extends JavaPlugin {

    private PlayerDataManager dataManager;
    private QuestManager questManager;
    private RewardManager rewardManager;
    private NpcManager npcManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        dataManager = new PlayerDataManager(this);
        dataManager.load();
        rewardManager = new RewardManager(this);
        npcManager = new NpcManager(this);
        questManager = new QuestManager(this);
        questManager.reload();

        var pm = getServer().getPluginManager();
        pm.registerEvents(new ProgressListener(this), this);
        pm.registerEvents(new NpcListener(this), this);
        pm.registerEvents(new PlayerListener(this), this);

        QuestCommand command = new QuestCommand(this);
        Objects.requireNonNull(getCommand("quest")).setExecutor(command);
        Objects.requireNonNull(getCommand("quest")).setTabCompleter(command);

        // Comprobación de reset diario cada 30 s (600 ticks)
        Bukkit.getScheduler().runTaskTimer(this, questManager::checkDailyReset, 600L, 600L);
        // Autoguardado cada 5 min
        Bukkit.getScheduler().runTaskTimer(this, dataManager::saveAsync, 6000L, 6000L);

        // Soporte para /reload: sincroniza jugadores ya conectados
        for (Player p : Bukkit.getOnlinePlayers()) {
            rewardManager.applyHealth(p, questManager.ensureToday(p).getExtraHearts());
        }
        getLogger().info("DailyQuestsRPG activado.");
    }

    @Override
    public void onDisable() {
        if (dataManager != null) dataManager.saveSync();   // síncrono: no se pueden crear tareas async al apagar
    }

    public PlayerDataManager data() { return dataManager; }
    public QuestManager quests() { return questManager; }
    public RewardManager rewards() { return rewardManager; }
    public NpcManager npcs() { return npcManager; }

    /** Mensaje con prefijo, colores (&) y placeholders %clave% pasados como pares clave,valor. */
    public String msg(String key, String... kv) {
        String s = getConfig().getString("messages." + key, "&cMensaje faltante: " + key);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("%" + kv[i] + "%", kv[i + 1]);
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "") + s);
    }
}
