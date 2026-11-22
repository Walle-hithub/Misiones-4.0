package dev.dailyquests.listener;

import dev.dailyquests.DailyQuestsRPG;
import dev.dailyquests.gui.QuestMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class NpcListener implements Listener {

    private final DailyQuestsRPG plugin;

    public NpcListener(DailyQuestsRPG plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------ interacción
    /**
     * Clic derecho en el NPC: abre el menú de misiones en modo entrega. Las recompensas NUNCA se dan
     * solas: el jugador tiene que ir al NPC y hacer clic en la misión lista (o en "Entregar todo").
     */
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;           // evita doble disparo (mano secundaria)
        if (!plugin.npcs().isQuestGiver(e.getRightClicked())) return;
        e.setCancelled(true);                                    // no abrir el comercio del aldeano
        Player p = e.getPlayer();
        if (!p.hasPermission("dailyquests.use")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }
        new QuestMenu(plugin, p, true).open(p);
    }

    // ------------------------------------------------------------ protección del NPC
    /** Cancela TODO daño (golpes, creativo, fuego, explosiones, caída, vacío, /kill...) a los NPC. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (plugin.npcs().isQuestGiver(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombust(EntityCombustEvent e) {
        if (plugin.npcs().isQuestGiver(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTransform(EntityTransformEvent e) {
        if (plugin.npcs().isQuestGiver(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLeash(PlayerLeashEntityEvent e) {
        if (plugin.npcs().isQuestGiver(e.getEntity())) e.setCancelled(true);
    }

    // ------------------------------------------------------------ menú
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof QuestMenu menu)) return;
        e.setCancelled(true);                                    // el menú nunca permite mover ítems
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        menu.handleClick(e.getRawSlot());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof QuestMenu) e.setCancelled(true);
    }
}
