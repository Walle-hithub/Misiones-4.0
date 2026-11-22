package dev.dailyquests.npc;

import dev.dailyquests.DailyQuestsRPG;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Cualquier entidad viva puede ser NPC: se marca con un tag PDC (persiste tras reinicios).
 * La protección contra daño está en NpcListener (se comprueba este tag).
 * Compatibilidad con Citizens sin dependencia: añade al NPC el comando
 *   /npc command add -p quest open
 */
public final class NpcManager {

    private final DailyQuestsRPG plugin;
    private final NamespacedKey key;

    public NpcManager(DailyQuestsRPG plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "quest_giver");
    }

    public boolean isQuestGiver(Entity e) {
        return e.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Convierte una entidad existente en NPC de misiones. */
    public void convert(Entity e) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        pdc.set(key, PersistentDataType.BYTE, (byte) 1);
        e.setPersistent(true);
        e.setInvulnerable(true);
        e.setSilent(true);
        rename(e, plugin.getConfig().getString("npc.name", "NPC"));
        if (e instanceof LivingEntity le) {
            le.setAI(false);            // no se mueve, no ataca
            le.setRemoveWhenFarAway(false);
            le.setCollidable(false);
        }
    }

    public Entity spawn(Location loc, EntityType type) {
        Entity e = loc.getWorld().spawnEntity(loc, type);
        convert(e);
        return e;
    }

    /** Cambia el nombre visible (admite códigos de color con &). */
    public void rename(Entity e, String name) {
        e.setCustomName(ChatColor.translateAlternateColorCodes('&', name));
        e.setCustomNameVisible(true);
    }

    /** Elimina el NPC del mundo por completo. */
    public void remove(Entity e) {
        e.remove();
    }

    /** Quita solo el rol de NPC: la entidad vuelve a ser un mob normal. */
    public void release(Entity e) {
        e.getPersistentDataContainer().remove(key);
        e.setInvulnerable(false);
        e.setCustomNameVisible(false);
        e.setCustomName(null);
        if (e instanceof LivingEntity le) le.setAI(true);
    }
}
