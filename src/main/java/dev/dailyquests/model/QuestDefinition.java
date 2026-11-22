package dev.dailyquests.model;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * @param targets     Materiales / EntityType / IDs de jefe / nombres de jugador en MAYÚSCULAS; basta con que coincida uno
 * @param amount      cantidad sorteada para hoy/esta semana (en la definición base es amountMin)
 * @param period      DAILY o WEEKLY
 * @param custom      true = misión creada en el juego con /quest create (siempre activa, "fijada")
 * @param rewardXp    XP fija de la recompensa personalizada (-1 = no definida)
 * @param rewardItems ítems fijos de la recompensa personalizada
 * @param coins       Rincones fijos para esta misión ("5" o "3-6"); null = usar la cantidad por dificultad
 */
public record QuestDefinition(
        String id,
        String name,
        Difficulty difficulty,
        ObjectiveType type,
        Set<String> targets,
        String display,
        String icon,
        int amountMin,
        int amountMax,
        int amount,
        CollectMode mode,
        QuestPeriod period,
        boolean custom,
        int rewardXp,
        List<ItemStack> rewardItems,
        String coins) {

    /** Prefijo para guardar el progreso semanal separado del diario en playerdata.yml. */
    public static final String WEEKLY_PREFIX = "weekly__";

    public boolean weekly() { return period == QuestPeriod.WEEKLY; }

    /** Clave con la que se guarda el progreso de esta misión en PlayerData. */
    public String key() { return weekly() ? WEEKLY_PREFIX + id : id; }

    public QuestDefinition withAmount(int newAmount) {
        return new QuestDefinition(id, name, difficulty, type, targets, display, icon,
                amountMin, amountMax, newAmount, mode, period, custom, rewardXp, rewardItems, coins);
    }

    public QuestDefinition withRewards(int xp, List<ItemStack> items) {
        return new QuestDefinition(id, name, difficulty, type, targets, display, icon,
                amountMin, amountMax, amount, mode, period, custom, xp, items, coins);
    }

    public QuestDefinition withPeriod(QuestPeriod newPeriod) {
        return new QuestDefinition(id, name, difficulty, type, targets, display, icon,
                amountMin, amountMax, amount, mode, newPeriod, custom, rewardXp, rewardItems, coins);
    }

    public QuestDefinition withCoins(String newCoins) {
        return new QuestDefinition(id, name, difficulty, type, targets, display, icon,
                amountMin, amountMax, amount, mode, period, custom, rewardXp, rewardItems, newCoins);
    }

    /** Si es true se dan estas recompensas fijas; si no, se sortean del pool de la dificultad. */
    public boolean hasCustomRewards() {
        return custom && (rewardXp >= 0 || !rewardItems.isEmpty());
    }
}
