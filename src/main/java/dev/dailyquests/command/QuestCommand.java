package dev.dailyquests.command;

import dev.dailyquests.DailyQuestsRPG;
import dev.dailyquests.gui.QuestMenu;
import dev.dailyquests.manager.RewardManager;
import dev.dailyquests.model.CollectMode;
import dev.dailyquests.model.Difficulty;
import dev.dailyquests.model.ObjectiveType;
import dev.dailyquests.model.QuestDefinition;
import dev.dailyquests.model.QuestPeriod;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.util.*;

public final class QuestCommand implements TabExecutor {

    private final DailyQuestsRPG plugin;

    public QuestCommand(DailyQuestsRPG plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> {
                if (!(s instanceof Player p)) { s.sendMessage(plugin.msg("players-only")); return true; }
                if (!p.hasPermission("dailyquests.use")) { s.sendMessage(plugin.msg("no-permission")); return true; }
                new QuestMenu(plugin, p, false).open(p);
            }
            case "npc" -> npc(s, args);
            case "create" -> createQuest(s, args);
            case "delete" -> deleteQuest(s, args);
            case "period" -> period(s, args);
            case "reward" -> reward(s, args);
            case "hard" -> hard(s, args);
            case "weeklyevent", "weklyevent" -> event(s, args);
            case "reload" -> {
                if (!admin(s)) return true;
                plugin.quests().reload();
                s.sendMessage(plugin.msg("reloaded"));
            }
            default -> s.sendMessage(plugin.msg("usage"));
        }
        return true;
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("dailyquests.admin")) return true;
        s.sendMessage(plugin.msg("no-permission"));
        return false;
    }

    // ============================================================ NPC
    /** Entidad a la que mira el jugador (máx. 8 bloques). onlyNpc = solo NPC de misiones. */
    private Entity target(Player p, boolean onlyNpc) {
        RayTraceResult r = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(),
                8, 0.3, en -> en instanceof LivingEntity && !(en instanceof Player)
                        && (!onlyNpc || plugin.npcs().isQuestGiver(en)));
        return r == null ? null : r.getHitEntity();
    }

    private void npc(CommandSender s, String[] args) {
        if (!admin(s)) return;
        if (!(s instanceof Player p)) { s.sendMessage(plugin.msg("players-only")); return; }
        if (args.length < 2) { s.sendMessage(plugin.msg("usage")); return; }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> {
                Entity t = target(p, false);
                if (t == null) { s.sendMessage(plugin.msg("npc-not-found")); return; }
                plugin.npcs().convert(t);
                s.sendMessage(plugin.msg("npc-created"));
            }
            case "spawn" -> {
                EntityType type = EntityType.VILLAGER;
                if (args.length > 2) {
                    try { type = EntityType.valueOf(args[2].toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException ex) { s.sendMessage(plugin.msg("usage")); return; }
                }
                if (!type.isSpawnable() || !type.isAlive()) { s.sendMessage(plugin.msg("usage")); return; }
                plugin.npcs().spawn(p.getLocation(), type);
                s.sendMessage(plugin.msg("npc-spawned"));
            }
            case "remove", "move", "rename", "release" -> {
                Entity t = target(p, true);
                if (t == null) { s.sendMessage(plugin.msg("npc-not-found-npc")); return; }
                switch (action) {
                    case "remove" -> {
                        plugin.npcs().remove(t);
                        s.sendMessage(plugin.msg("npc-removed"));
                    }
                    case "move" -> {
                        t.teleport(p.getLocation());
                        s.sendMessage(plugin.msg("npc-moved"));
                    }
                    case "release" -> {
                        plugin.npcs().release(t);
                        s.sendMessage(plugin.msg("npc-released"));
                    }
                    default -> {   // rename
                        if (args.length < 3) { s.sendMessage(plugin.msg("usage-rename")); return; }
                        plugin.npcs().rename(t, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                        s.sendMessage(plugin.msg("npc-renamed"));
                    }
                }
            }
            default -> s.sendMessage(plugin.msg("usage"));
        }
    }

    // ============================================================ crear / borrar misiones
    /** /quest create <id> <easy|normal|hard> <collect|pickup|kill|boss|player> <objetivo> <cantidad|min-max> [nombre] */
    private void createQuest(CommandSender s, String[] args) {
        if (!admin(s)) return;
        if (args.length < 6) { s.sendMessage(plugin.msg("usage-create")); return; }

        String id = args[1].toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_]+")) { s.sendMessage(plugin.msg("invalid-id")); return; }
        if (plugin.quests().get(id) != null) { s.sendMessage(plugin.msg("quest-exists", "id", id)); return; }

        Difficulty diff = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "easy", "facil", "fácil" -> Difficulty.EASY;
            case "normal" -> Difficulty.NORMAL;
            case "hard", "dificil", "difícil" -> Difficulty.HARD;
            default -> null;
        };
        String t = args[3].toLowerCase(Locale.ROOT);
        ObjectiveType type = switch (t) {
            case "collect", "pickup" -> ObjectiveType.COLLECT;
            case "kill" -> ObjectiveType.KILL;
            case "boss" -> ObjectiveType.BOSS;
            case "player" -> ObjectiveType.KILL_PLAYER;
            default -> null;
        };
        if (diff == null || type == null) { s.sendMessage(plugin.msg("usage-create")); return; }
        CollectMode mode = t.equals("pickup") ? CollectMode.PICKUP : CollectMode.BREAK;

        if (!args[5].matches("\\d+(-\\d+)?")) { s.sendMessage(plugin.msg("invalid-amount")); return; }
        int[] amt = RewardManager.range(args[5], 1);
        int min = Math.max(1, amt[0]);
        int max = Math.max(min, amt[1]);

        Set<String> targets = new LinkedHashSet<>();
        String display;
        if (type == ObjectiveType.KILL_PLAYER) {
            // "*" = cualquier jugador; si no, el nombre exacto del jugador
            targets.add(args[4].toUpperCase(Locale.ROOT));
            display = args[4].equals("*") ? "cualquier jugador" : args[4];
        } else {
            for (String tok : args[4].split(",")) {
                String u = tok.trim().toUpperCase(Locale.ROOT);
                if (u.isEmpty()) continue;
                boolean ok = switch (type) {
                    case COLLECT -> Material.matchMaterial(u) != null;
                    case KILL -> isEntityType(u);
                    default -> true;    // BOSS: tipo de entidad o ID de jefe custom
                };
                if (!ok) { s.sendMessage(plugin.msg("invalid-target", "target", tok)); return; }
                targets.add(u);
            }
            if (targets.isEmpty()) { s.sendMessage(plugin.msg("usage-create")); return; }
            display = targets.iterator().next().toLowerCase(Locale.ROOT).replace('_', ' ');
        }

        String name = args.length > 6 ? String.join(" ", Arrays.copyOfRange(args, 6, args.length)) : "&e" + id;
        QuestDefinition q = plugin.quests().createCustom(id, name, diff, type, mode, targets, display, min, max);
        s.sendMessage(plugin.msg("quest-created", "quest", q.name(), "id", id));
    }

    private boolean isEntityType(String name) {
        try { return EntityType.valueOf(name).isAlive(); } catch (IllegalArgumentException ex) { return false; }
    }

    private void deleteQuest(CommandSender s, String[] args) {
        if (!admin(s)) return;
        if (args.length < 2) { s.sendMessage(plugin.msg("usage")); return; }
        if (!plugin.quests().isCustom(args[1])) { s.sendMessage(plugin.msg("quest-not-custom")); return; }
        plugin.quests().deleteCustom(args[1]);
        s.sendMessage(plugin.msg("quest-deleted", "id", args[1].toLowerCase(Locale.ROOT)));
    }

    /** /quest period <id> <daily|weekly> : convierte una misión creada en el juego en diaria o semanal. */
    private void period(CommandSender s, String[] args) {
        if (!admin(s)) return;
        if (args.length < 3) { s.sendMessage(plugin.msg("usage-period")); return; }
        if (plugin.quests().get(args[1]) == null) { s.sendMessage(plugin.msg("unknown-quest", "quest", args[1])); return; }
        if (!plugin.quests().isCustom(args[1])) { s.sendMessage(plugin.msg("quest-not-custom")); return; }
        QuestPeriod per = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "daily", "diaria" -> QuestPeriod.DAILY;
            case "weekly", "semanal" -> QuestPeriod.WEEKLY;
            default -> null;
        };
        if (per == null) { s.sendMessage(plugin.msg("usage-period")); return; }
        plugin.quests().setPeriod(args[1], per);
        s.sendMessage(plugin.msg("quest-period-set", "id", args[1].toLowerCase(Locale.ROOT),
                "period", per == QuestPeriod.WEEKLY ? "semanal" : "diaria"));
    }

    /** /quest reward <id> item | xp <n> | clear  (solo misiones creadas con /quest create) */
    private void reward(CommandSender s, String[] args) {
        if (!admin(s)) return;
        if (args.length < 3) { s.sendMessage(plugin.msg("usage-reward")); return; }
        if (plugin.quests().get(args[1]) == null) { s.sendMessage(plugin.msg("unknown-quest", "quest", args[1])); return; }
        if (!plugin.quests().isCustom(args[1])) { s.sendMessage(plugin.msg("quest-not-custom")); return; }

        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "item" -> {
                if (!(s instanceof Player p)) { s.sendMessage(plugin.msg("players-only")); return; }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) { s.sendMessage(plugin.msg("hold-item")); return; }
                ItemStack copy = hand.clone();   // conserva cantidad, encantamientos, nombre, etc.
                plugin.quests().addRewardItem(args[1], copy);
                s.sendMessage(plugin.msg("reward-item-added", "reward", RewardManager.describeStack(copy)));
            }
            case "xp" -> {
                if (args.length < 4 || !args[3].matches("\\d+")) { s.sendMessage(plugin.msg("usage-reward")); return; }
                plugin.quests().setRewardXp(args[1], Integer.parseInt(args[3]));
                s.sendMessage(plugin.msg("reward-xp-set", "xp", args[3]));
            }
            case "coins" -> {
                if (args.length < 4 || !args[3].matches("\\d+(-\\d+)?")) { s.sendMessage(plugin.msg("usage-reward")); return; }
                plugin.quests().setCoins(args[1], args[3]);
                s.sendMessage(plugin.msg("reward-coins-set", "amount", args[3]));
            }
            case "clear" -> {
                plugin.quests().clearRewards(args[1]);
                s.sendMessage(plugin.msg("reward-cleared"));
            }
            default -> s.sendMessage(plugin.msg("usage-reward"));
        }
    }

    // ============================================================ difíciles / evento
    /** /quest hard [on|off] : activa o desactiva las misiones difíciles. */
    private void hard(CommandSender s, String[] args) {
        if (!admin(s)) return;
        if (args.length < 2) {
            s.sendMessage(plugin.msg("hard-status",
                    "state", plugin.quests().isHardEnabled() ? "ACTIVADAS" : "DESACTIVADAS"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                plugin.quests().setHardEnabled(true);
                Bukkit.broadcastMessage(plugin.msg("hard-enabled"));
            }
            case "off" -> {
                plugin.quests().setHardEnabled(false);
                Bukkit.broadcastMessage(plugin.msg("hard-disabled"));
            }
            default -> s.sendMessage(plugin.msg("usage"));
        }
    }

    /** /quest weeklyevent <id|off> */
    private void event(CommandSender s, String[] args) {
        if (!admin(s)) return;
        if (args.length < 2) { s.sendMessage(plugin.msg("usage")); return; }

        if (args[1].equalsIgnoreCase("off")) {
            plugin.quests().setEventQuest(null);
            Bukkit.broadcastMessage(plugin.msg("event-ended"));
            return;
        }
        QuestDefinition q = plugin.quests().get(args[1]);
        if (q == null) { s.sendMessage(plugin.msg("unknown-quest", "quest", args[1])); return; }
        plugin.quests().setEventQuest(q.id());
        Bukkit.broadcastMessage(plugin.msg("event-started", "quest", q.name()));
    }

    // ============================================================ tab
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean adm = s.hasPermission("dailyquests.admin");

        if (args.length == 1) {
            out.add("open");
            if (adm) out.addAll(List.of("npc", "create", "reward", "period", "delete", "weeklyevent", "hard", "reload"));
        } else if (adm) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "npc" -> {
                    if (args.length == 2) out.addAll(List.of("create", "remove", "move", "rename", "release", "spawn"));
                    else if (args.length == 3 && args[1].equalsIgnoreCase("spawn")) addEntityTypes(out);
                }
                case "create" -> {
                    if (args.length == 3) out.addAll(List.of("easy", "normal", "hard"));
                    else if (args.length == 4) out.addAll(List.of("collect", "pickup", "kill", "boss", "player"));
                    else if (args.length == 5) {
                        String t = args[3].toLowerCase(Locale.ROOT);
                        if (t.equals("player")) {
                            out.add("*");
                            for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
                        } else if (t.equals("kill") || t.equals("boss")) {
                            addEntityTypes(out);
                        }
                    }
                }
                case "reward" -> {
                    if (args.length == 2) out.addAll(plugin.quests().customIds());
                    else if (args.length == 3) out.addAll(List.of("item", "xp", "coins", "clear"));
                }
                case "delete" -> { if (args.length == 2) out.addAll(plugin.quests().customIds()); }
                case "period" -> {
                    if (args.length == 2) out.addAll(plugin.quests().customIds());
                    else if (args.length == 3) out.addAll(List.of("daily", "weekly"));
                }
                case "weeklyevent", "weklyevent" -> {
                    if (args.length == 2) { out.add("off"); out.addAll(plugin.quests().allIds()); }
                }
                case "hard" -> { if (args.length == 2) out.addAll(List.of("on", "off")); }
                default -> { }
            }
        }

        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    private void addEntityTypes(List<String> out) {
        for (EntityType t : EntityType.values()) if (t.isAlive() && t.isSpawnable()) out.add(t.name());
    }
}
