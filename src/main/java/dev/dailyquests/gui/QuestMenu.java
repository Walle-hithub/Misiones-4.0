package dev.dailyquests.gui;

import dev.dailyquests.DailyQuestsRPG;
import dev.dailyquests.data.PlayerData;
import dev.dailyquests.manager.QuestManager;
import dev.dailyquests.manager.RewardManager;
import dev.dailyquests.model.CollectMode;
import dev.dailyquests.model.Difficulty;
import dev.dailyquests.model.QuestDefinition;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GUI de misiones. canDeliver = true solo cuando se abre haciendo clic derecho en el NPC:
 * ahí se puede hacer clic en una misión lista (o en el botón central) para entregarla y cobrar.
 * Con /quest open es solo de consulta.
 */
public final class QuestMenu implements InventoryHolder {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};   // diarias
    private static final int[] WEEKLY_SLOTS = {28, 29, 30, 31, 32, 33, 34};                         // semanales
    private static final int INFO_SLOT = 40;

    private final DailyQuestsRPG plugin;
    private final Player player;
    private final boolean canDeliver;
    private final Inventory inventory;
    private final Map<Integer, String> slotToQuest = new HashMap<>();

    public QuestMenu(DailyQuestsRPG plugin, Player player, boolean canDeliver) {
        this.plugin = plugin;
        this.player = player;
        this.canDeliver = canDeliver;
        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("messages.gui-title", "Misiones"));
        this.inventory = Bukkit.createInventory(this, 45, title);
        refresh();
    }

    /** Vuelve a dibujar todo el menú con el progreso actual. */
    public void refresh() {
        inventory.clear();
        slotToQuest.clear();
        PlayerData data = plugin.quests().ensureToday(player);
        int ready = 0;
        int d = 0;
        int w = 0;
        for (QuestDefinition q : plugin.quests().getToday()) {
            int slot;
            if (q.weekly()) {
                if (w >= WEEKLY_SLOTS.length) continue;
                slot = WEEKLY_SLOTS[w++];
            } else {
                if (d >= SLOTS.length) continue;
                slot = SLOTS[d++];
            }
            slotToQuest.put(slot, q.id());
            inventory.setItem(slot, icon(data, q));
            if (plugin.quests().isReady(data, q)) ready++;
        }
        inventory.setItem(9, label(Material.SUNFLOWER, "&e&lMisiones diarias", "&7Se reinician cada día."));
        inventory.setItem(27, label(Material.CLOCK, "&b&lMisiones semanales",
                "&7Se reinician cada semana.", "&7Más XP y más premios."));
        inventory.setItem(INFO_SLOT, infoItem(ready));
    }

    /** Clic en la parte superior del menú (solo entrega si se abrió desde el NPC). */
    public void handleClick(int rawSlot) {
        if (!canDeliver) return;
        QuestManager qm = plugin.quests();

        if (rawSlot == INFO_SLOT) {
            if (qm.deliverAll(player) == 0) player.sendMessage(plugin.msg("nothing-to-deliver"));
        } else {
            String id = slotToQuest.get(rawSlot);
            if (id == null) return;
            for (QuestDefinition q : qm.getToday()) {
                if (!q.id().equals(id)) continue;
                PlayerData data = qm.ensureToday(player);
                if (data.isCompleted(q.key())) break;
                if (!qm.deliver(player, q)) player.sendMessage(plugin.msg("quest-not-ready"));
                break;
            }
        }
        refresh();
    }

    private ItemStack label(Material mat, String name, String... lines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(c(name));
        List<String> lore = new ArrayList<>();
        for (String l : lines) lore.add(c(l));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem(int ready) {
        ItemStack item;
        ItemMeta meta;
        List<String> lore = new ArrayList<>();
        if (canDeliver) {
            item = new ItemStack(ready > 0 ? Material.EMERALD_BLOCK : Material.COAL_BLOCK);
            meta = item.getItemMeta();
            if (ready > 0) {
                meta.setDisplayName(c("&a&lEntregar todo &7(" + ready + ")"));
                lore.add(c("&7Haz clic para entregar todas las"));
                lore.add(c("&7misiones listas y cobrar."));
            } else {
                meta.setDisplayName(c("&7Nada que entregar"));
                lore.add(c("&7Completa los objetivos y vuelve."));
            }
        } else {
            item = new ItemStack(Material.BOOK);
            meta = item.getItemMeta();
            meta.setDisplayName(c("&6¿Cómo funciona?"));
            lore.add(c("&7Completa el objetivo y luego"));
            lore.add(c("&7entrega la misión al NPC de misiones"));
            lore.add(c("&7para cobrar la recompensa."));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Material material(QuestDefinition q) {
        Material m = q.icon() != null ? Material.matchMaterial(q.icon()) : null;
        if (m != null) return m;
        return switch (q.type()) {
            case COLLECT -> {
                Material t = Material.matchMaterial(q.targets().iterator().next());
                yield t != null && t.isItem() ? t : Material.PAPER;
            }
            case KILL -> Material.IRON_SWORD;
            case KILL_PLAYER -> Material.PLAYER_HEAD;
            case BOSS -> Material.NETHER_STAR;
        };
    }

    private ItemStack icon(PlayerData data, QuestDefinition q) {
        boolean delivered = data.isCompleted(q.key());
        boolean ready = plugin.quests().isReady(data, q);

        ItemStack item = new ItemStack(delivered ? Material.EMERALD : material(q));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(q.name());

        List<String> lore = new ArrayList<>();
        lore.add(c("&7Dificultad: " + q.difficulty().color() + q.difficulty().label()));
        lore.add(c(q.weekly() ? "&7Tipo: &bSemanal" : "&7Tipo: &fDiaria"));
        lore.add(c("&7Objetivo: &f" + objective(q)));
        int cur = Math.min(data.getProgress(q.key()), q.amount());
        lore.add(c("&7Progreso: &f" + cur + "/" + q.amount() + " " + bar(cur, q.amount())));
        lore.add("");

        lore.add(c("&6Recompensas:"));
        int[] cr = plugin.rewards().coinRange(q);
        if (cr[1] > 0) {
            lore.add(c("&e  ✦ " + (cr[0] == cr[1] ? cr[0] : cr[0] + "-" + cr[1]) + " " + plugin.rewards().coinName()));
        }
        if (q.hasCustomRewards()) {
            if (q.rewardXp() > 0) lore.add(c("&e  ✦ " + q.rewardXp() + " XP"));
            for (ItemStack it : q.rewardItems()) lore.add(c("&e  ✦ " + RewardManager.describeStack(it)));
        } else {
            RewardManager.RewardTable t = plugin.rewards().table(q.difficulty());
            double xm = plugin.rewards().xpMultiplier(q);
            int lo = (int) Math.round(t.xpMin() * xm);
            int hi = (int) Math.round(t.xpMax() * xm);
            lore.add(c("&e  ✦ " + (lo == hi ? lo : lo + "-" + hi) + " XP"));
            lore.add(c("&e  ✦ " + (t.count() + plugin.rewards().extraCount(q)) + " premios aleatorios"));
            if (!t.description().isBlank()) lore.add(c("&7    " + t.description()));
        }
        if (plugin.quests().isEvent(q)) lore.add(c("&d  ★ Evento: ¡corazón extra garantizado!"));
        else if (q.difficulty() == Difficulty.HARD) lore.add(c("&c  ❤ Posible corazón extra"));
        lore.add("");

        if (delivered) {
            lore.add(c("&a✔ Entregada"));
        } else if (ready) {
            lore.add(canDeliver ? c("&e➜ ¡Lista! Haz clic para entregarla")
                                : c("&e➜ ¡Lista! Entrégala al NPC de misiones"));
            meta.setEnchantmentGlintOverride(true);
        } else {
            lore.add(c("&7En curso"));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String objective(QuestDefinition q) {
        return switch (q.type()) {
            case COLLECT -> (q.mode() == CollectMode.PICKUP ? "Recoger " : "Picar ") + q.amount() + " " + q.display();
            case KILL -> "Matar " + q.amount() + " " + q.display();
            case KILL_PLAYER -> "Matar a " + q.display() + (q.amount() > 1 ? " (" + q.amount() + " veces)" : "");
            case BOSS -> "Derrotar a " + q.display();
        };
    }

    private String bar(int cur, int max) {
        int filled = (int) Math.round(10.0 * cur / max);
        return "&a" + "▮".repeat(filled) + "&8" + "▮".repeat(10 - filled);
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    public void open(Player p) { p.openInventory(inventory); }

    @Override
    public Inventory getInventory() { return inventory; }
}
