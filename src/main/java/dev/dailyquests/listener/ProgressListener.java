package dev.dailyquests.listener;

import dev.dailyquests.DailyQuestsRPG;
import dev.dailyquests.model.CollectMode;
import dev.dailyquests.model.ObjectiveType;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;

public final class ProgressListener implements Listener {

    private static final Set<EntityType> BOSS_TYPES =
            Set.of(EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.WARDEN, EntityType.ELDER_GUARDIAN);

    private final DailyQuestsRPG plugin;
    /** Otros plugins (MythicMobs, etc.) pueden marcar jefes custom con este tag PDC (String). */
    private final NamespacedKey bossKey;

    public ProgressListener(DailyQuestsRPG plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "boss_id");
    }

    /** Picar bloque -> misiones COLLECT en modo BREAK. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        plugin.quests().progress(p, ObjectiveType.COLLECT, 1, CollectMode.BREAK,
                e.getBlock().getType().name());
    }

    /** Matar mob -> misiones KILL y BOSS. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player) return;

        String type = dead.getType().name();
        plugin.quests().progress(killer, ObjectiveType.KILL, 1, null, type);

        String bossId = dead.getPersistentDataContainer().get(bossKey, PersistentDataType.STRING);
        if (bossId != null) {
            plugin.quests().progress(killer, ObjectiveType.BOSS, 1, null, type, bossId);
        } else if (BOSS_TYPES.contains(dead.getType())) {
            plugin.quests().progress(killer, ObjectiveType.BOSS, 1, null, type);
        }
    }

    /** Matar a un jugador -> misiones KILL_PLAYER (objetivo = nombre exacto, o "*" = cualquiera). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        plugin.quests().progress(killer, ObjectiveType.KILL_PLAYER, 1, null, victim.getName(), "*");
    }

    /** Recoger ítem -> misiones COLLECT en modo PICKUP (ignora ítems tirados por jugadores: anti-exploit). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Item item = e.getItem();
        if (item.getThrower() != null) return;
        ItemStack stack = item.getItemStack();
        plugin.quests().progress(p, ObjectiveType.COLLECT, stack.getAmount(), CollectMode.PICKUP,
                stack.getType().name());
    }
}
