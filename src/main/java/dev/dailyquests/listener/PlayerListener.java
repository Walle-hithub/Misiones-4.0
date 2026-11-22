package dev.dailyquests.listener;

import dev.dailyquests.DailyQuestsRPG;
import dev.dailyquests.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class PlayerListener implements Listener {

    private final DailyQuestsRPG plugin;

    public PlayerListener(DailyQuestsRPG plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { sync(e.getPlayer()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> sync(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { plugin.data().saveAsync(); }

    private void sync(Player p) {
        PlayerData d = plugin.quests().ensureToday(p);
        plugin.rewards().applyHealth(p, d.getExtraHearts());
    }
}
