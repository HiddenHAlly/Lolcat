package net.hiddenhally.client.sort;

import net.hiddenhally.client.containers.ItemRec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decide quali oggetti vanno spostati dove, in base ai preset e alle etichette dei contenitori:
 * ogni contenitore con etichetta = nome di un preset "possiede" gli oggetti di quel preset.
 * L'etichetta "misc" (o "*") raccoglie gli oggetti che non appartengono a nessun preset presente.
 */
public final class SortPlanner {

    public static final String MISC = "misc";

    /** Contenitore visto dal pianificatore. */
    public record Cont(String key, String label, double x, double y, double z, List<ItemRec> items) {
        public String labelKey() {
            String l = Preset.normalize(label);
            return l.equals("*") ? MISC : l;
        }
    }

    public record Move(String item, int count, String from, String to) {}

    private SortPlanner() {
    }

    private static Map<String, List<Cont>> byLabel(List<Cont> containers) {
        Map<String, List<Cont>> m = new LinkedHashMap<>();
        for (Cont c : containers) {
            String l = c.labelKey();
            if (!l.isEmpty()) m.computeIfAbsent(l, k -> new ArrayList<>()).add(c);
        }
        return m;
    }

    /** Preset (con almeno un contenitore etichettato disponibile) a cui appartiene l'oggetto; null se nessuno. */
    static String presetFor(String itemId, Map<String, Preset> presets, Map<String, List<Cont>> byLabel,
                            Set<String> blocked) {
        String best = null;
        int bestPrio = Integer.MAX_VALUE;
        for (var e : presets.entrySet()) {
            Preset p = e.getValue();
            if (!p.contains(itemId)) continue;
            if (!hasAvailable(byLabel.get(e.getKey()), blocked)) continue;
            int prio = p.priorityOf(itemId);
            if (best == null || prio < bestPrio || (prio == bestPrio && e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
                bestPrio = prio;
            }
        }
        return best;
    }

    private static boolean hasAvailable(List<Cont> list, Set<String> blocked) {
        if (list == null) return false;
        for (Cont c : list) if (!blocked.contains(c.key())) return true;
        return false;
    }

    private static Cont nearest(List<Cont> list, Cont from, Set<String> blocked) {
        Cont best = null;
        double bd = Double.MAX_VALUE;
        for (Cont c : list) {
            if (blocked.contains(c.key()) || c.key().equals(from.key())) continue;
            double d = dist2(c, from);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    private static double dist2(Cont a, Cont b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Il contenitore {@code c} è la destinazione giusta per {@code itemId}? (serve al deposito) */
    public static boolean accepts(Cont c, String itemId, Map<String, Preset> presets, List<Cont> all, Set<String> blocked) {
        Map<String, List<Cont>> byLabel = byLabel(all);
        String l = c.labelKey();
        if (l.isEmpty()) return false;
        if (l.equals(MISC)) return presetFor(itemId, presets, byLabel, blocked) == null;
        Preset p = presets.get(l);
        return p != null && p.contains(itemId);
    }

    /** Dove deve andare l'oggetto che si trova in {@code from}; null = resta dov'è. */
    public static Cont destinationFor(String itemId, Cont from, Map<String, Preset> presets, List<Cont> all,
                                      Set<String> blocked) {
        Map<String, List<Cont>> byLabel = byLabel(all);
        String fromLabel = from.labelKey();
        Preset own = presets.get(fromLabel);
        if (own != null && own.contains(itemId)) return null; // già al posto giusto

        String target = presetFor(itemId, presets, byLabel, blocked);
        if (target != null) {
            if (target.equals(fromLabel)) return null;
            return nearest(byLabel.get(target), from, blocked);
        }
        // nessun preset lo vuole: se il baule è di un preset (e l'oggetto non c'entra) va a "misc"
        if (own != null && byLabel.containsKey(MISC)) return nearest(byLabel.get(MISC), from, blocked);
        return null;
    }

    public static List<Move> plan(List<Cont> containers, Map<String, Preset> presets, Set<String> blocked) {
        List<Move> moves = new ArrayList<>();
        for (Cont c : containers) {
            for (ItemRec it : c.items()) {
                Cont dest = destinationFor(it.id(), c, presets, containers, blocked);
                if (dest != null) moves.add(new Move(it.id(), it.count(), c.key(), dest.key()));
            }
        }
        return moves;
    }

    // ------------------------------------------------------------------ ordinamento dentro un baule

    /** Una pila nello slot di un contenitore (sig = incantesimi: pile con sig diversa non si uniscono). */
    public record Stack(String id, int count, String sig) {
        public String key() {
            return id + "|" + sig;
        }
    }

    /** Operazione di click: kind 1 = pickup, kind 2 = pickup solo se hai qualcosa sul cursore. */
    public record OrderOp(int kind, int slot) {}

    public record OrderPlan(List<OrderOp> ops, List<Stack> result, int merges, int swaps) {}

    /**
     * Piano per (1) unire le pile parziali dello stesso oggetto e (2) disporre tutto in ordine di priorità,
     * compatto dallo slot 0 e senza buchi. A parità di priorità vale l'ordine intelligente del preset.
     */
    public static OrderPlan planOrder(List<Stack> slots, Preset preset, java.util.function.ToIntFunction<String> maxStack,
                                      java.util.function.Predicate<String> isBlock) {
        List<Stack> cur = new ArrayList<>(slots);
        List<OrderOp> ops = new ArrayList<>();
        int merges = 0;

        // 1) unione delle pile parziali (solo oggetti impilabili e con la stessa sig)
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < cur.size(); i++) {
            Stack st = cur.get(i);
            if (st == null || maxStack.applyAsInt(st.id()) <= 1) continue;
            groups.computeIfAbsent(st.key(), k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> idxs : groups.values()) {
            int max = maxStack.applyAsInt(cur.get(idxs.get(0)).id());
            List<Integer> partial = new ArrayList<>();
            for (int i : idxs) if (cur.get(i).count() < max) partial.add(i);
            while (partial.size() >= 2) {
                partial.sort((x, y) -> cur.get(x).count() != cur.get(y).count()
                        ? Integer.compare(cur.get(y).count(), cur.get(x).count()) : Integer.compare(x, y));
                int dst = partial.get(0);
                int src = partial.get(partial.size() - 1);
                int move = Math.min(cur.get(src).count(), max - cur.get(dst).count());
                ops.add(new OrderOp(1, src));
                ops.add(new OrderOp(1, dst));
                ops.add(new OrderOp(2, src));
                merges++;
                Stack d = cur.get(dst), sr = cur.get(src);
                cur.set(dst, new Stack(d.id(), d.count() + move, d.sig()));
                if (sr.count() - move <= 0) {
                    cur.set(src, null);
                    partial.remove((Integer) src);
                } else {
                    cur.set(src, new Stack(sr.id(), sr.count() - move, sr.sig()));
                }
                if (cur.get(dst).count() >= max) partial.remove((Integer) dst);
            }
        }

        // 2) ordinamento (selezione con scambi), pile con stessa chiave sono equivalenti
        Comparator<String> tie = SmartOrder.comparator(preset == null ? Preset.TieMode.CATEGORY : preset.tie, isBlock);
        Comparator<Stack> cmp = Comparator
                .comparingInt((Stack st) -> preset == null ? Integer.MAX_VALUE : preset.priorityOf(st.id()))
                .thenComparing(Stack::id, tie)
                .thenComparing(Stack::sig);
        List<Stack> desired = new ArrayList<>();
        for (Stack st : cur) if (st != null) desired.add(st);
        desired.sort(cmp);

        int swaps = 0;
        for (int i = 0; i < desired.size(); i++) {
            Stack want = desired.get(i);
            Stack have = cur.get(i);
            if (have != null && have.key().equals(want.key())) continue;
            int j = -1;
            for (int k = i + 1; k < cur.size(); k++) {
                Stack c = cur.get(k);
                if (c != null && c.key().equals(want.key())) {
                    j = k;
                    break;
                }
            }
            if (j < 0) continue;
            ops.add(new OrderOp(1, i));
            ops.add(new OrderOp(1, j));
            ops.add(new OrderOp(2, i));
            Stack tmp = cur.get(i);
            cur.set(i, cur.get(j));
            cur.set(j, tmp);
            swaps++;
        }
        return new OrderPlan(ops, cur, merges, swaps);
    }
}
