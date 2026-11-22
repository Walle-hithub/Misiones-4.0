package dev.dailyquests.manager;

import dev.dailyquests.DailyQuestsRPG;
import dev.dailyquests.data.PlayerData;
import dev.dailyquests.model.Difficulty;
import dev.dailyquests.model.QuestDefinition;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Recompensas: XP aleatoria + N premios aleatorios (con pesos) del pool de la dificultad. Sin dinero. */
public final class RewardManager {

    public record RewardEntry(Material material, int min, int max, int weight, Map<Enchantment, Integer> enchants) {}

    public record RewardTable(int xpMin, int xpMax, int count, String description, List<RewardEntry> entries) {}

    private final DailyQuestsRPG plugin;
    private final Map<Difficulty, RewardTable> tables = new EnumMap<>(Difficulty.class);

    public RewardManager(DailyQuestsRPG plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------ carga
    public void reload() {
        tables.clear();
        for (Difficulty d : Difficulty.values()) {
            ConfigurationSection s = plugin.getConfig().getConfigurationSection("rewards." + d.key());
            if (s == null) {
                tables.put(d, new RewardTable(0, 0, 0, "", List.of()));
                continue;
            }
            int[] xp = range(s.get("xp"), 0);
            List<RewardEntry> entries = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("pool")) parseEntry(d, m, entries);
            tables.put(d, new RewardTable(xp[0], xp[1], s.getInt("count", 1), s.getString("description", ""), entries));
        }
    }

    private void parseEntry(Difficulty d, Map<?, ?> m, List<RewardEntry> out) {
        Object itemObj = m.get("item");
        Material mat = itemObj == null ? null : Material.matchMaterial(itemObj.toString());
        if (mat == null) {
            plugin.getLogger().warning("Recompensa " + d.key() + " con ítem inválido: " + itemObj);
            return;
        }
        int[] amt = range(m.get("amount"), 1);
        int weight = m.get("weight") instanceof Number n ? Math.max(1, n.intValue()) : 1;

        Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
        if (m.get("enchants") instanceof List<?> list) {
            for (Object o : list) {
                String[] p = o.toString().split(":");
                Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(p[0].trim().toLowerCase(Locale.ROOT)));
                if (e == null) {
                    plugin.getLogger().warning("Encantamiento inválido en recompensa " + d.key() + ": " + o);
                    continue;
                }
                int lvl = p.length > 1 ? parseInt(p[1], 1) : 1;
                enchants.put(e, lvl);
            }
        }
        out.add(new RewardEntry(mat, amt[0], amt[1], weight, enchants));
    }

    /** Acepta 5, "5" o "5-10" y devuelve {min, max}. */
    public static int[] range(Object o, int def) {
        if (o == null) return new int[]{def, def};
        String s = o.toString().trim();
        int dash = s.indexOf('-', 1);
        try {
            if (dash > 0) {
                int a = Integer.parseInt(s.substring(0, dash).trim());
                int b = Integer.parseInt(s.substring(dash + 1).trim());
                return new int[]{Math.min(a, b), Math.max(a, b)};
            }
            int v = Integer.parseInt(s);
            return new int[]{v, v};
        } catch (NumberFormatException ex) {
            return new int[]{def, def};
        }
    }

    public RewardTable table(Difficulty d) { return tables.get(d); }

    /** Las misiones semanales dan más XP y más premios (config: weekly.xp-multiplier / weekly.extra-rewards). */
    public double xpMultiplier(QuestDefinition q) {
        return q.weekly() ? plugin.getConfig().getDouble("weekly.xp-multiplier", 3.0) : 1.0;
    }

    public int extraCount(QuestDefinition q) {
        return q.weekly() ? plugin.getConfig().getInt("weekly.extra-rewards", 2) : 0;
    }

    // ------------------------------------------------------------ entrega
    public void give(Player p, PlayerData data, QuestDefinition q, boolean eventActive) {
        if (q.hasCustomRewards()) { giveCustom(p, data, q, eventActive); return; }
        RewardTable table = tables.get(q.difficulty());
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        int xp = table.xpMax() > 0
                ? (int) Math.round(rnd.nextInt(table.xpMin(), table.xpMax() + 1) * xpMultiplier(q)) : 0;
        if (xp > 0) p.giveExp(xp);
        p.sendMessage(plugin.msg("quest-complete", "quest", q.name(), "xp", String.valueOf(xp)));

        List<RewardEntry> picked = pick(table, table.count() + extraCount(q));
        int coins = rollCoins(q);
        if (coins > 0 || !picked.isEmpty()) p.sendMessage(plugin.msg("rewards-header"));
        if (coins > 0) giveCoinItem(p, coins);
        for (RewardEntry e : picked) {
            int amount = rnd.nextInt(e.min(), e.max() + 1);
            ItemStack stack = new ItemStack(e.material(), amount);
            if (!e.enchants().isEmpty()) {
                ItemMeta meta = stack.getItemMeta();
                for (Map.Entry<Enchantment, Integer> en : e.enchants().entrySet()) {
                    if (meta instanceof EnchantmentStorageMeta esm) esm.addStoredEnchant(en.getKey(), en.getValue(), true);
                    else meta.addEnchant(en.getKey(), en.getValue(), true);   // true = ignora el nivel máximo vanilla
                }
                stack.setItemMeta(meta);
            }
            Map<Integer, ItemStack> left = p.getInventory().addItem(stack);
            left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
            p.sendMessage(plugin.msg("reward-line", "reward", describe(e, amount)));
        }

        rollHeart(p, data, q, eventActive);
    }

    // ------------------------------------------------------------ moneda "Rincones"
    /**
     * Rango de Rincones de una misión. Prioridad: valor fijo de la misión (coins) > cantidad por dificultad
     * (currency.amount.*). Las semanales multiplican la cantidad por dificultad (weekly.currency-multiplier).
     */
    public int[] coinRange(QuestDefinition q) {
        if (!plugin.getConfig().getBoolean("currency.enabled", true)) return new int[]{0, 0};
        if (q.coins() != null) return range(q.coins(), 0);
        int[] r = range(plugin.getConfig().getString("currency.amount." + q.difficulty().key(), "1"), 1);
        if (q.weekly()) {
            double m = plugin.getConfig().getDouble("weekly.currency-multiplier", 3.0);
            return new int[]{(int) Math.round(r[0] * m), (int) Math.round(r[1] * m)};
        }
        return r;
    }

    public int rollCoins(QuestDefinition q) {
        int[] r = coinRange(q);
        if (r[1] <= 0) return 0;
        return Math.max(0, ThreadLocalRandom.current().nextInt(r[0], r[1] + 1));
    }

    public String coinName() {
        return ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("currency.name", "&d&lRincones"));
    }

    /** El ítem moneda: material y nombre configurables; lleva un tag PDC por si otros plugins quieren reconocerlo. */
    public ItemStack coinItem(int amount) {
        Material mat = Material.matchMaterial(plugin.getConfig().getString("currency.material", "AMETHYST_SHARD"));
        if (mat == null) mat = Material.AMETHYST_SHARD;
        ItemStack item = new ItemStack(mat, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(coinName());
        List<String> lore = new ArrayList<>();
        for (String l : plugin.getConfig().getStringList("currency.lore")) {
            lore.add(ChatColor.translateAlternateColorCodes('&', l));
        }
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "currency"), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void giveCoinItem(Player p, int amount) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(coinItem(amount));
        left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        p.sendMessage(plugin.msg("reward-line", "reward", amount + "x " + coinName()));
    }

    /** Recompensa fija elegida por el admin (/quest reward): XP + ítems exactos. */
    private void giveCustom(Player p, PlayerData data, QuestDefinition q, boolean eventActive) {
        int xp = Math.max(0, q.rewardXp());
        if (xp > 0) p.giveExp(xp);
        p.sendMessage(plugin.msg("quest-complete", "quest", q.name(), "xp", String.valueOf(xp)));

        int coins = rollCoins(q);
        if (coins > 0 || !q.rewardItems().isEmpty()) p.sendMessage(plugin.msg("rewards-header"));
        if (coins > 0) giveCoinItem(p, coins);
        for (ItemStack it : q.rewardItems()) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it.clone());
            left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
            p.sendMessage(plugin.msg("reward-line", "reward", describeStack(it)));
        }
        rollHeart(p, data, q, eventActive);
    }

    public static String describeStack(ItemStack s) {
        ItemMeta m = s.getItemMeta();
        String name = (m != null && m.hasDisplayName())
                ? m.getDisplayName()
                : s.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        boolean ench = !s.getEnchantments().isEmpty()
                || (m instanceof EnchantmentStorageMeta esm && esm.hasStoredEnchants());
        return s.getAmount() + "x " + name + (ench ? " [encantado]" : "");
    }

    /** Elige hasta 'count' entradas distintas, ponderadas por weight. */
    private List<RewardEntry> pick(RewardTable table, int count) {
        List<RewardEntry> pool = new ArrayList<>(table.entries());
        List<RewardEntry> picked = new ArrayList<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            int total = 0;
            for (RewardEntry e : pool) total += e.weight();
            int roll = rnd.nextInt(total);
            for (Iterator<RewardEntry> it = pool.iterator(); it.hasNext(); ) {
                RewardEntry e = it.next();
                roll -= e.weight();
                if (roll < 0) { picked.add(e); it.remove(); break; }
            }
        }
        return picked;
    }

    private String describe(RewardEntry e, int amount) {
        StringBuilder sb = new StringBuilder();
        sb.append(amount).append("x ").append(e.material().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        if (!e.enchants().isEmpty()) {
            StringJoiner j = new StringJoiner(", ", " [", "]");
            e.enchants().forEach((en, lvl) -> j.add(en.getKey().getKey().replace('_', ' ') + " " + lvl));
            sb.append(j);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ corazones
    /** Probabilidad: HARD => hearts.hard-chance. Si la misión es la del evento => max(chance, hearts.event-chance). */
    private void rollHeart(Player p, PlayerData data, QuestDefinition q, boolean eventActive) {
        double chance = 0.0;
        if (q.difficulty() == Difficulty.HARD) chance = plugin.getConfig().getDouble("hearts.hard-chance", 0.15);
        if (eventActive) chance = Math.max(chance, plugin.getConfig().getDouble("hearts.event-chance", 1.0));
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) return;

        int max = plugin.getConfig().getInt("hearts.max-extra", 10);
        if (data.getExtraHearts() >= max) {
            p.sendMessage(plugin.msg("heart-max"));
            return;
        }
        data.addHeart();
        applyHealth(p, data.getExtraHearts());
        p.setHealth(Math.min(p.getAttribute(Attribute.MAX_HEALTH).getValue(), p.getHealth() + 2.0));
        p.sendMessage(plugin.msg("heart-gained", "hearts", String.valueOf(data.getExtraHearts())));
    }

    /**
     * Aplica la salud máxima = 20 + 2 * corazones extra.
     * NOTA: en 1.21.3+ el atributo es Attribute.MAX_HEALTH; en 1.20.x - 1.21.2 usa Attribute.GENERIC_MAX_HEALTH.
     */
    public void applyHealth(Player p, int extraHearts) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) attr.setBaseValue(20.0 + 2.0 * extraHearts);
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
