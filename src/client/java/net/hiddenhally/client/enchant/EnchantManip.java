package net.hiddenhally.client.enchant;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cerca quante chiamate casuali (butta oggetti ecc.) servono PRIMA di fare l'incantesimo "di scarto"
 * che genera il nuovo seed, in modo che il nuovo seed dia gli incantesimi desiderati sull'oggetto vero.
 */
public final class EnchantManip {

    public static final int MAX_CALLS = 240;

    /** @param calls chiamate casuali da consumare prima dell'incantesimo di scarto */
    public record Manip(Map<String, Integer> actions, int calls, int seed, EnchantPredictor.Plan plan) {}

    private EnchantManip() {
    }

    public static List<Manip> plan(RegistryAccess access, List<ItemStack> candidates, long state,
                                   List<EnchantTarget.Want> wants, int onlyShelves) {
        List<Map<String, Integer>> recipes = EnchantRngTracker.recipes(MAX_CALLS);
        List<Manip> found = new ArrayList<>();
        long cur = state;
        for (int n = 0; n <= MAX_CALLS; n++) {
            // dopo n chiamate consumate, l'incantesimo di scarto genera nextInt() = un passo in più
            int seed = PlayerRng.out(PlayerRng.step(cur));
            Map<String, Integer> recipe = recipes.get(n);
            if (recipe != null) {
                List<EnchantPredictor.Plan> plans = EnchantPredictor.planFor(access, candidates, seed, wants, onlyShelves);
                if (!plans.isEmpty() && plans.get(0).matched() == wants.size()) {
                    found.add(new Manip(recipe, n, seed, plans.get(0)));
                }
            }
            cur = PlayerRng.step(cur);
        }
        // meno azioni prima, poi meno livelli
        found.sort((a, b) -> {
            int aa = a.actions().values().stream().mapToInt(Integer::intValue).sum();
            int bb = b.actions().values().stream().mapToInt(Integer::intValue).sum();
            return aa != bb ? Integer.compare(aa, bb) : Integer.compare(a.plan().cost(), b.plan().cost());
        });
        return found.size() > 5 ? new ArrayList<>(found.subList(0, 5)) : found;
    }

    /** Testo leggibile di una ricetta, es. "butta 3 oggetti con Q + 1 oggetto cliccando fuori". */
    public static String describe(Map<String, Integer> actions) {
        if (actions.isEmpty()) return "nessuna azione";
        StringBuilder sb = new StringBuilder();
        for (var e : actions.entrySet()) {
            if (sb.length() > 0) sb.append(" + ");
            switch (e.getKey()) {
                case "drop" -> sb.append("butta ").append(e.getValue()).append(" oggetti con Q");
                case "click" -> sb.append("butta ").append(e.getValue()).append(" oggetti cliccando fuori dall'inventario");
                default -> sb.append(e.getKey()).append(" x").append(e.getValue());
            }
        }
        return sb.toString();
    }
}
