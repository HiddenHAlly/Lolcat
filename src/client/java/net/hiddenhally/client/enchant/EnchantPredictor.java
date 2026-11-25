package net.hiddenhally.client.enchant;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Riproduce ESATTAMENTE la logica del tavolo degli incantesimi (EnchantmentMenu) usando le stesse
 * funzioni del gioco. Il seed degli incantesimi è sincronizzato col client, quindi il risultato è
 * prevedibile al 100% finché non incanti qualcosa (poi il giocatore genera un nuovo seed casuale).
 */
public final class EnchantPredictor {

    public record Pred(String id, int level, String text) {}

    /** @param matched quanti degli obiettivi sono soddisfatti da questa combinazione */
    public record Plan(String itemName, int shelves, int slot, int cost, List<Pred> list, int matched) {}

    private EnchantPredictor() {
    }

    /** Livelli richiesti dei 3 slot per un dato numero di scaffali (0 = slot non disponibile). */
    public static int[] costsFor(ItemStack stack, int seed, int shelves) {
        RandomSource random = RandomSource.create();
        random.setSeed(seed);
        int[] costs = new int[3];
        for (int slot = 0; slot < 3; slot++) {
            costs[slot] = EnchantmentHelper.getEnchantmentCost(random, slot, shelves, stack);
            if (costs[slot] < slot + 1) costs[slot] = 0;
        }
        return costs;
    }

    /** Ricava quanti scaffali ci sono dai costi mostrati dal tavolo; -1 se non coincide nessun valore. */
    public static int detectShelves(ItemStack stack, int seed, int[] shownCosts) {
        if (stack.isEmpty()) return -1;
        for (int shelves = 0; shelves <= 15; shelves++) {
            int[] c = costsFor(stack, seed, shelves);
            if (c[0] == shownCosts[0] && c[1] == shownCosts[1] && c[2] == shownCosts[2]) return shelves;
        }
        return -1;
    }

    /** Lista completa di incantesimi che otterresti nello slot (stessa routine di EnchantmentMenu). */
    public static List<Pred> listFor(RegistryAccess access, ItemStack stack, int seed, int slot, int cost) {
        RandomSource random = RandomSource.create();
        random.setSeed((long) seed + slot);

        Registry<Enchantment> registry = access.lookupOrThrow(Registries.ENCHANTMENT);
        Optional<HolderSet.Named<Enchantment>> pool = registry.get(EnchantmentTags.IN_ENCHANTING_TABLE);
        if (pool.isEmpty()) return List.of();

        List<EnchantmentInstance> list = new ArrayList<>(
                EnchantmentHelper.selectEnchantment(random, stack, cost, pool.get().stream()));
        if (stack.is(Items.BOOK) && list.size() > 1) {
            list.remove(random.nextInt(list.size())); // i libri perdono un incantesimo
        }

        List<Pred> out = new ArrayList<>();
        for (EnchantmentInstance inst : list) {
            Holder<Enchantment> holder = inst.enchantment();
            out.add(new Pred(holder.getRegisteredName(), inst.level(),
                    Enchantment.getFullname(holder, inst.level()).getString()));
        }
        return out;
    }

    public static boolean matches(List<Pred> list, String targetId, int minLevel) {
        for (Pred p : list) {
            if (p.id().equals(targetId) && p.level() >= minLevel) return true;
        }
        return false;
    }

    /** Quanti degli obiettivi sono presenti (con il livello minimo) nella lista. */
    public static int matchCount(List<Pred> list, List<EnchantTarget.Want> wants) {
        int n = 0;
        for (EnchantTarget.Want w : wants) if (matches(list, w.id(), w.minLevel())) n++;
        return n;
    }

    /** Copie degli oggetti incantabili (un tipo per volta): sicure da usare in un altro thread. */
    public static List<ItemStack> collectCandidates(Inventory inventory, ItemStack tableStack) {
        List<ItemStack> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (!tableStack.isEmpty()) addCandidate(candidates, seen, tableStack);
        for (int i = 0; i < inventory.getContainerSize(); i++) addCandidate(candidates, seen, inventory.getItem(i));
        return candidates;
    }

    /**
     * Cerca tra gli oggetti incantabili dell'inventario (e per 0-15 scaffali) le combinazioni che con
     * QUESTO seed danno tutti gli incantesimi desiderati insieme. Se nessuna li dà tutti, restituisce
     * le migliori combinazioni parziali. Un tipo di oggetto conta una volta sola.
     */
    public static List<Plan> plan(RegistryAccess access, Inventory inventory, ItemStack tableStack, int seed,
                                  List<EnchantTarget.Want> wants) {
        return planFor(access, collectCandidates(inventory, tableStack), seed, wants, -1);
    }

    /** @param onlyShelves se >= 0 considera solo quel numero di scaffali (quelli che hai davvero) */
    public static List<Plan> planFor(RegistryAccess access, List<ItemStack> candidates, int seed,
                                     List<EnchantTarget.Want> wants, int onlyShelves) {
        List<Plan> plans = new ArrayList<>();
        int best = 0;
        for (ItemStack stack : candidates) {
            String name = itemId(stack);
            for (int shelves = 0; shelves <= 15; shelves++) {
                if (onlyShelves >= 0 && shelves != onlyShelves) continue;
                int[] costs = costsFor(stack, seed, shelves);
                for (int slot = 0; slot < 3; slot++) {
                    if (costs[slot] <= 0) continue;
                    List<Pred> list = listFor(access, stack, seed, slot, costs[slot]);
                    int matched = matchCount(list, wants);
                    if (matched == 0) continue;
                    best = Math.max(best, matched);
                    plans.add(new Plan(name, shelves, slot, costs[slot], list, matched));
                }
            }
        }
        final int keep = best;
        plans.removeIf(p -> p.matched() < keep);
        plans.sort((a, b) -> a.cost() != b.cost() ? Integer.compare(a.cost(), b.cost())
                : Integer.compare(a.shelves(), b.shelves()));
        return plans;
    }

    private static void addCandidate(List<ItemStack> out, Set<String> seen, ItemStack stack) {
        if (stack.isEmpty() || stack.isEnchanted()) return;
        String id = itemId(stack);
        if (!seen.add(id)) return;
        // incantabile? (con 15 scaffali almeno uno slot deve avere un costo)
        int[] c = costsFor(stack, 0, 15);
        if (c[0] == 0 && c[1] == 0 && c[2] == 0) return;
        out.add(stack.copy());
    }

    public static String itemId(ItemStack stack) {
        String s = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }
}
