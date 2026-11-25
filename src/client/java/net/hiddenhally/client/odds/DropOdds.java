package net.hiddenhally.client.odds;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * Calcola le PROBABILITÀ dei drop (non il risultato esatto) leggendo le loot table vanilla dal jar:
 * minimo, massimo, media e distribuzione del numero di oggetti per ogni drop, con Fortune / Looting /
 * Silk Touch / fuoco ecc. Funziona per blocchi, mob, vault e chest.
 *
 * Assunzioni dichiarate nelle note del risultato (condizioni che non sa valutare = considerate vere).
 */
public final class DropOdds {

    /** Distribuzione di probabilità sul numero di oggetti. */
    public static final class Dist {
        final TreeMap<Integer, Double> p = new TreeMap<>();

        static Dist point(int k) {
            Dist d = new Dist();
            d.p.put(k, 1.0);
            return d;
        }

        void add(int k, double pr) {
            if (pr > 0) p.merge(k, pr, Double::sum);
        }

        Dist conv(Dist o) {
            Dist r = new Dist();
            for (var a : p.entrySet())
                for (var b : o.p.entrySet()) r.add(a.getKey() + b.getKey(), a.getValue() * b.getValue());
            return r.prune();
        }

        Dist prune() {
            p.values().removeIf(v -> v < 1e-13);
            return this;
        }

        /** Sostituisce ogni conteggio c con la distribuzione f(c). */
        Dist bind(IntFunction<Dist> f) {
            Dist r = new Dist();
            for (var e : p.entrySet())
                for (var x : f.apply(e.getKey()).p.entrySet()) r.add(x.getKey(), e.getValue() * x.getValue());
            return r.prune();
        }

        static Dist mix(List<Dist> ds, List<Double> w) {
            Dist r = new Dist();
            for (int i = 0; i < ds.size(); i++)
                for (var e : ds.get(i).p.entrySet()) r.add(e.getKey(), w.get(i) * e.getValue());
            return r.prune();
        }

        public double mean() {
            double m = 0;
            for (var e : p.entrySet()) m += e.getKey() * e.getValue();
            return m;
        }

        public int min() {
            return p.firstKey();
        }

        public int max() {
            return p.lastKey();
        }

        public double probAtLeast(int k) {
            double s = 0;
            for (var e : p.entrySet()) if (e.getKey() >= k) s += e.getValue();
            return s;
        }

        public double probOf(int k) {
            return p.getOrDefault(k, 0.0);
        }

        public Set<Integer> values() {
            return p.keySet();
        }

        Dist copy() {
            Dist d = new Dist();
            d.p.putAll(p);
            return d;
        }
    }

    /** Parametri della situazione di drop. */
    public record Ctx(int fortune, int looting, boolean silk, boolean shears, boolean fire,
                      boolean killedByPlayer, Map<String, String> state) {
        public Ctx withLevels(int fortune, int looting) {
            return new Ctx(fortune, looting, silk, shears, fire, killedByPlayer, state);
        }
    }

    public record Result(Map<String, Dist> items, List<String> notes, String error) {}

    private DropOdds() {
    }

    // ------------------------------------------------------------------ API

    public static Result evaluate(String tablePath, Ctx ctx) {
        Set<String> notes = new LinkedHashSet<>();
        Map<String, Dist> items = evalTable(tablePath, ctx, notes, 0);
        if (items == null) return new Result(Map.of(), List.of(), "table_not_found");
        return new Result(items, new ArrayList<>(notes), null);
    }

    // ------------------------------------------------------------------ tabelle / pool

    private static final Map<String, Map<String, Object>> CACHE = new HashMap<>();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String path) {
        if (CACHE.containsKey(path)) return CACHE.get(path);
        Map<String, Object> json = null;
        try (InputStream in = DropOdds.class.getResourceAsStream("/data/minecraft/loot_table/" + path + ".json")) {
            if (in != null) json = (Map<String, Object>) MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            json = null;
        }
        CACHE.put(path, json);
        return json;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Dist> evalTable(String path, Ctx ctx, Set<String> notes, int depth) {
        Map<String, Object> table = load(path.replace("minecraft:", ""));
        if (table == null) return null;
        if (depth > 8) {
            notes.add("tabelle annidate troppo profonde: " + path);
            return new HashMap<>();
        }
        Map<String, Dist> total = new HashMap<>();
        List<Object> pools = (List<Object>) table.getOrDefault("pools", List.of());
        for (Object po : pools) {
            Map<String, Dist> r = evalPool((Map<String, Object>) po, ctx, notes, depth);
            for (var e : r.entrySet()) total.merge(e.getKey(), e.getValue(), Dist::conv);
        }
        return total;
    }

    /** Un'entry "eleggibile" nel pool: peso e oggetti (marginali) che produce se scelta. */
    private record Eligible(double weight, Map<String, Dist> items) {}

    /** Possibile esito dell'espansione di un'entry: probabilità e lista di entry eleggibili (0 o 1). */
    private record Scenario(double prob, List<Eligible> eligible) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Dist> evalPool(Map<String, Object> pool, Ctx ctx, Set<String> notes, int depth) {
        double poolCond = prob(pool.get("condition"), ctx, notes);
        if (poolCond <= 0) return Map.of();

        Dist rolls = intDist(pool.get("rolls"), notes);
        List<Object> poolMods = modifiers(pool);

        // espansione di ogni entry in scenari (per condizioni probabilistiche)
        List<List<Scenario>> perEntry = new ArrayList<>();
        for (Object eo : (List<Object>) pool.getOrDefault("entries", List.of())) {
            perEntry.add(expandEntry((Map<String, Object>) eo, ctx, notes, depth, poolMods));
        }

        // prodotto cartesiano degli scenari (con limite)
        List<Scenario> combined = new ArrayList<>();
        combined.add(new Scenario(1.0, List.of()));
        for (List<Scenario> opts : perEntry) {
            List<Scenario> next = new ArrayList<>();
            for (Scenario a : combined) {
                for (Scenario b : opts) {
                    double pr = a.prob() * b.prob();
                    if (pr < 1e-12) continue;
                    List<Eligible> l = new ArrayList<>(a.eligible());
                    l.addAll(b.eligible());
                    next.add(new Scenario(pr, l));
                }
            }
            if (next.size() > 4000) {
                notes.add("pool con troppe condizioni casuali: risultato approssimato");
                break;
            }
            combined = next;
        }

        // per ogni oggetto: mistura sugli scenari
        Set<String> names = new LinkedHashSet<>();
        for (Scenario s : combined) for (Eligible e : s.eligible()) names.addAll(e.items().keySet());

        Map<String, Dist> out = new HashMap<>();
        for (String name : names) {
            List<Dist> ds = new ArrayList<>();
            List<Double> ws = new ArrayList<>();
            for (Scenario s : combined) {
                double totalW = 0;
                for (Eligible e : s.eligible()) totalW += e.weight();
                Dist perRoll;
                if (totalW <= 0) {
                    perRoll = Dist.point(0);
                } else {
                    List<Dist> pd = new ArrayList<>();
                    List<Double> pw = new ArrayList<>();
                    for (Eligible e : s.eligible()) {
                        pd.add(e.items().getOrDefault(name, Dist.point(0)));
                        pw.add(e.weight() / totalW);
                    }
                    perRoll = Dist.mix(pd, pw);
                }
                ds.add(sumOfRolls(perRoll, rolls));
                ws.add(s.prob());
            }
            Dist d = Dist.mix(ds, ws);
            if (poolCond < 1) d = Dist.mix(List.of(d, Dist.point(0)), List.of(poolCond, 1 - poolCond));
            out.put(name, d);
        }
        return out;
    }

    private static Dist sumOfRolls(Dist perRoll, Dist rolls) {
        int maxR = rolls.max();
        List<Dist> powers = new ArrayList<>();
        Dist cur = Dist.point(0);
        powers.add(cur);
        for (int r = 1; r <= maxR; r++) {
            cur = cur.conv(perRoll);
            powers.add(cur);
        }
        List<Dist> ds = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        for (var e : rolls.p.entrySet()) {
            ds.add(powers.get(e.getKey()));
            ws.add(e.getValue());
        }
        return Dist.mix(ds, ws);
    }

    // ------------------------------------------------------------------ entry

    @SuppressWarnings("unchecked")
    private static List<Scenario> expandEntry(Map<String, Object> entry, Ctx ctx, Set<String> notes, int depth,
                                              List<Object> poolMods) {
        String type = str(entry.get("type"));
        double q = prob(entry.get("condition"), ctx, notes);
        double weight = entry.containsKey("weight") ? num(entry.get("weight")) : 1.0;
        List<Object> mods = modifiers(entry);

        switch (type) {
            case "minecraft:alternatives" -> {
                List<Scenario> out = new ArrayList<>();
                double stillGoing = q;
                List<Object> children = (List<Object>) entry.getOrDefault("children", List.of());
                for (Object co : children) {
                    List<Scenario> childScenarios = expandEntry((Map<String, Object>) co, ctx, notes, depth, poolMods);
                    // un figlio "riesce" se produce almeno un'entry eleggibile
                    double success = 0;
                    for (Scenario s : childScenarios) if (!s.eligible().isEmpty()) success += s.prob();
                    for (Scenario s : childScenarios) {
                        if (!s.eligible().isEmpty()) out.add(new Scenario(stillGoing * s.prob(), s.eligible()));
                    }
                    stillGoing *= (1 - success);
                    if (stillGoing < 1e-12) break;
                }
                out.add(new Scenario(1 - sumProb(out), List.of()));
                return out;
            }
            case "minecraft:item", "minecraft:loot_table", "minecraft:empty", "minecraft:tag", "minecraft:dynamic" -> {
                Map<String, Dist> items = switch (type) {
                    case "minecraft:item" -> {
                        List<Object> all = new ArrayList<>(mods);
                        all.addAll(poolMods);
                        yield applyModifiers(str(entry.get("name")), all, ctx, notes);
                    }
                    case "minecraft:loot_table" -> {
                        String ref = str(entry.get("value"));
                        Map<String, Dist> nested = evalTable(ref, ctx, notes, depth + 1);
                        if (nested == null) {
                            notes.add("tabella non trovata: " + ref);
                            yield new HashMap<>();
                        }
                        yield nested;
                    }
                    case "minecraft:tag" -> {
                        notes.add("entry di tipo tag non valutata (" + str(entry.get("name")) + ")");
                        yield new HashMap<>();
                    }
                    case "minecraft:dynamic" -> {
                        notes.add("entry dinamica non valutata (" + str(entry.get("name")) + ")");
                        yield new HashMap<>();
                    }
                    default -> new HashMap<>();
                };
                List<Scenario> out = new ArrayList<>();
                if (q > 0) out.add(new Scenario(q, List.of(new Eligible(weight, items))));
                if (q < 1) out.add(new Scenario(1 - q, List.of()));
                return out;
            }
            default -> {
                notes.add("tipo di entry non supportato: " + type);
                return List.of(new Scenario(1.0, List.of()));
            }
        }
    }

    private static double sumProb(List<Scenario> l) {
        double s = 0;
        for (Scenario x : l) s += x.prob();
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> modifiers(Map<String, Object> m) {
        Object o = m.get("modifier");
        if (o == null) o = m.get("functions");
        if (o instanceof Map<?, ?>) return List.of(o); // una sola funzione senza lista
        return o instanceof List<?> ? (List<Object>) o : List.of();
    }

    // ------------------------------------------------------------------ funzioni (modifier)

    /** Branch di uno stack: nome, probabilità e distribuzione del conteggio (dato quel nome). */
    private record Branch(String name, double prob, Dist count) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Dist> applyModifiers(String name, List<Object> mods, Ctx ctx, Set<String> notes) {
        List<Branch> branches = new ArrayList<>();
        branches.add(new Branch(name, 1.0, Dist.point(1)));

        for (Object mo : mods) {
            Map<String, Object> f = (Map<String, Object>) mo;
            String type = str(f.get("type") != null ? f.get("type") : f.get("function"));
            double c = prob(f.get("condition"), ctx, notes);
            if (c <= 0) continue;

            List<Branch> next = new ArrayList<>();
            for (Branch b : branches) {
                if (type.equals("minecraft:furnace_smelt")) {
                    if (c > 0) next.add(new Branch(cooked(b.name()), b.prob() * c, b.count()));
                    if (c < 1) next.add(new Branch(b.name(), b.prob() * (1 - c), b.count()));
                    continue;
                }
                Dist changed = applyCountFunction(type, f, b.count(), ctx, notes);
                if (changed == null) { // funzione che non tocca il conteggio
                    next.add(b);
                } else if (c >= 1) {
                    next.add(new Branch(b.name(), b.prob(), changed));
                } else {
                    next.add(new Branch(b.name(), b.prob() * c, changed));
                    next.add(new Branch(b.name(), b.prob() * (1 - c), b.count()));
                }
            }
            branches = next;
        }

        Map<String, Dist> out = new HashMap<>();
        Set<String> names = new LinkedHashSet<>();
        for (Branch b : branches) names.add(b.name());
        for (String n : names) {
            List<Dist> ds = new ArrayList<>();
            List<Double> ws = new ArrayList<>();
            for (Branch b : branches) {
                ds.add(b.name().equals(n) ? b.count() : Dist.point(0));
                ws.add(b.prob());
            }
            out.put(n, Dist.mix(ds, ws));
        }
        return out;
    }

    private static String cooked(String name) {
        String n = name.replace("minecraft:", "");
        return "minecraft:" + switch (n) {
            case "beef" -> "cooked_beef";
            case "chicken" -> "cooked_chicken";
            case "porkchop" -> "cooked_porkchop";
            case "mutton" -> "cooked_mutton";
            case "rabbit" -> "cooked_rabbit";
            case "cod" -> "cooked_cod";
            case "salmon" -> "cooked_salmon";
            default -> n;
        };
    }

    @SuppressWarnings("unchecked")
    private static Dist applyCountFunction(String type, Map<String, Object> f, Dist count, Ctx ctx, Set<String> notes) {
        switch (type) {
            case "minecraft:set_count" -> {
                Dist v = intDist(f.get("count"), notes);
                return Boolean.TRUE.equals(f.get("add")) ? count.conv(v) : v;
            }
            case "minecraft:enchanted_count_increase" -> {
                int level = levelOf(str(f.get("enchantment")), ctx);
                if (level == 0) return count;
                Map<String, Object> cnt = f.get("count") instanceof Map ? (Map<String, Object>) f.get("count") : null;
                double a = 0, b = 0;
                if (cnt != null && "minecraft:uniform".equals(str(cnt.get("type")))) {
                    a = num(cnt.get("min"));
                    b = num(cnt.get("max"));
                } else {
                    a = b = num(f.get("count"));
                }
                // f = livello * valore (float uniforme), arrotondato
                Dist inc = new Dist();
                double lo = level * a, hi = level * b;
                if (hi - lo < 1e-9) {
                    inc.add((int) Math.round(lo), 1.0);
                } else {
                    for (int k = (int) Math.floor(lo) - 1; k <= (int) Math.ceil(hi) + 1; k++) {
                        double l = Math.max(lo, k - 0.5), h = Math.min(hi, k + 0.5);
                        if (h > l) inc.add(k, (h - l) / (hi - lo));
                    }
                }
                double limit = f.containsKey("limit") ? num(f.get("limit")) : 0;
                Dist r = count.conv(inc);
                if (limit > 0) r = r.bind(x -> Dist.point((int) Math.min(x, limit)));
                return r;
            }
            case "minecraft:apply_bonus" -> {
                int level = levelOf(str(f.get("enchantment")), ctx);
                String formula = str(f.get("formula"));
                Map<String, Object> params = f.get("parameters") instanceof Map ? (Map<String, Object>) f.get("parameters") : Map.of();
                switch (formula) {
                    case "minecraft:ore_drops" -> {
                        int n = level + 2;
                        return count.bind(c -> {
                            Dist d = new Dist();
                            for (int v = 0; v < n; v++) d.add(c * (Math.max(v - 1, 0) + 1), 1.0 / n);
                            return d;
                        });
                    }
                    case "minecraft:uniform_bonus_count" -> {
                        int span = (int) num(params.get("bonusMultiplier")) * level + 1;
                        return count.bind(c -> {
                            Dist d = new Dist();
                            for (int v = 0; v < span; v++) d.add(c + v, 1.0 / span);
                            return d;
                        });
                    }
                    case "minecraft:binomial_with_bonus_count" -> {
                        int trials = level + (int) num(params.get("extra"));
                        double pr = num(params.get("probability"));
                        Dist bin = binomial(trials, pr);
                        return count.conv(bin);
                    }
                    default -> {
                        notes.add("formula bonus non supportata: " + formula);
                        return null;
                    }
                }
            }
            case "minecraft:limit_count" -> {
                Map<String, Object> lim = f.get("limit") instanceof Map ? (Map<String, Object>) f.get("limit") : Map.of();
                int min = lim.containsKey("min") ? (int) num(lim.get("min")) : Integer.MIN_VALUE;
                int max = lim.containsKey("max") ? (int) num(lim.get("max")) : Integer.MAX_VALUE;
                return count.bind(c -> Dist.point(Math.max(min, Math.min(max, c))));
            }
            default -> {
                return null; // explosion_decay, copy_components, set_damage, enchant_*... non cambiano il numero
            }
        }
    }

    private static int levelOf(String enchantment, Ctx ctx) {
        String e = enchantment.replace("minecraft:", "");
        return switch (e) {
            case "fortune" -> ctx.fortune();
            case "looting" -> ctx.looting();
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ numeri

    private static Dist binomial(int n, double p) {
        Dist d = new Dist();
        double c = 1;
        for (int k = 0; k <= n; k++) {
            d.add(k, c * Math.pow(p, k) * Math.pow(1 - p, n - k));
            c = c * (n - k) / (k + 1);
        }
        return d.prune();
    }

    /** Distribuzione intera di un "number provider" (costante, uniform, binomial). */
    @SuppressWarnings("unchecked")
    private static Dist intDist(Object o, Set<String> notes) {
        if (o instanceof Double d) return Dist.point((int) Math.floor(d));
        if (o instanceof Map<?, ?> raw) {
            Map<String, Object> m = (Map<String, Object>) raw;
            String type = str(m.get("type"));
            switch (type) {
                case "minecraft:uniform" -> {
                    int a = (int) Math.floor(scalar(m.get("min")));
                    int b = (int) Math.floor(scalar(m.get("max")));
                    Dist d = new Dist();
                    for (int k = a; k <= b; k++) d.add(k, 1.0 / (b - a + 1));
                    return d;
                }
                case "minecraft:binomial" -> {
                    return binomial((int) scalar(m.get("n")), scalar(m.get("p")));
                }
                case "minecraft:constant" -> {
                    return Dist.point((int) Math.floor(scalar(m.get("value"))));
                }
                default -> {
                    notes.add("number provider non supportato: " + type);
                    return Dist.point(1);
                }
            }
        }
        return Dist.point(1);
    }

    private static double scalar(Object o) {
        return o instanceof Double d ? d : 0;
    }

    private static double num(Object o) {
        return o instanceof Double d ? d : 0;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : "";
    }

    // ------------------------------------------------------------------ condizioni

    /** Probabilità che la condizione sia vera (condizioni indipendenti). null/assente = 1. */
    @SuppressWarnings("unchecked")
    private static double prob(Object cond, Ctx ctx, Set<String> notes) {
        if (cond == null) return 1.0;
        if (cond instanceof List<?> l) {
            double p = 1;
            for (Object c : l) p *= prob(c, ctx, notes);
            return p;
        }
        if (cond instanceof String s) {
            return switch (s) {
                case "minecraft:tool/can_silk_touch" -> ctx.silk() ? 1 : 0;
                case "minecraft:tool/can_shear" -> ctx.shears() ? 1 : 0;
                default -> {
                    notes.add("condizione non valutata: " + s);
                    yield 1.0;
                }
            };
        }
        Map<String, Object> m = (Map<String, Object>) cond;
        String type = str(m.get("type") != null ? m.get("type") : m.get("condition"));
        switch (type) {
            case "minecraft:survives_explosion" -> {
                return 1.0;
            }
            case "minecraft:killed_by_player" -> {
                return ctx.killedByPlayer() ? 1 : 0;
            }
            case "minecraft:inverted" -> {
                Object t = m.containsKey("term") ? m.get("term") : m.get("terms");
                return 1 - prob(t, ctx, notes);
            }
            case "minecraft:all_of" -> {
                double p = 1;
                for (Object t : (List<Object>) m.getOrDefault("terms", List.of())) p *= prob(t, ctx, notes);
                return p;
            }
            case "minecraft:any_of" -> {
                double none = 1;
                for (Object t : (List<Object>) m.getOrDefault("terms", List.of())) none *= 1 - prob(t, ctx, notes);
                return 1 - none;
            }
            case "minecraft:random_chance" -> {
                Object ch = m.get("chance");
                return ch instanceof Double d ? d : 1.0;
            }
            case "minecraft:random_chance_with_enchanted_bonus" -> {
                int level = levelOf(str(m.get("enchantment")), ctx);
                if (level > 0 && m.get("enchanted_chance") instanceof Map<?, ?> ec) {
                    Map<String, Object> e = (Map<String, Object>) ec;
                    return num(e.get("base")) + num(e.get("per_level_above_first")) * (level - 1);
                }
                return m.get("unenchanted_chance") instanceof Double d ? d : 0.0;
            }
            case "minecraft:table_bonus" -> {
                List<Object> ch = (List<Object>) m.getOrDefault("chances", List.of());
                if (ch.isEmpty()) return 1.0;
                int idx = Math.min(levelOf(str(m.get("enchantment")), ctx), ch.size() - 1);
                return num(ch.get(idx));
            }
            case "minecraft:match_block" -> {
                Object st = m.get("state");
                if (!(st instanceof Map<?, ?> stm)) return 1.0;
                double p = 1;
                for (var e : ((Map<String, Object>) stm).entrySet()) {
                    String have = ctx.state().get(e.getKey());
                    if (have == null) {
                        notes.add("stato blocco assunto vero: " + e.getKey() + "=" + describe(e.getValue())
                                + " (puoi indicarlo, es. " + e.getKey() + "=" + describe(e.getValue()) + ")");
                        continue;
                    }
                    p *= matchesState(e.getValue(), have) ? 1 : 0;
                }
                return p;
            }
            case "minecraft:entity_properties", "minecraft:damage_source_properties" -> {
                Object pred = m.get("predicate");
                if (!(pred instanceof Map<?, ?> pm)) return 1.0;
                return entityPredicate((Map<String, Object>) pm, ctx, notes);
            }
            default -> {
                notes.add("condizione non valutata: " + type);
                return 1.0;
            }
        }
    }

    private static String describe(Object v) {
        if (v instanceof Double d) return d == Math.floor(d) ? String.valueOf(d.intValue()) : String.valueOf(d);
        if (v instanceof Map<?, ?> m) return m.toString();
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesState(Object expected, String have) {
        if (expected instanceof Map<?, ?> r) {
            Map<String, Object> range = (Map<String, Object>) r;
            try {
                double h = Double.parseDouble(have);
                return (!range.containsKey("min") || h >= num(range.get("min")))
                        && (!range.containsKey("max") || h <= num(range.get("max")));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return describe(expected).equals(have);
    }

    @SuppressWarnings("unchecked")
    private static double entityPredicate(Map<String, Object> pred, Ctx ctx, Set<String> notes) {
        double p = 1;
        for (var e : pred.entrySet()) {
            switch (e.getKey()) {
                case "minecraft:flags" -> {
                    Map<String, Object> flags = (Map<String, Object>) e.getValue();
                    if (flags.containsKey("is_on_fire")) p *= (ctx.fire() == Boolean.TRUE.equals(flags.get("is_on_fire"))) ? 1 : 0;
                    if (flags.containsKey("is_baby")) p *= (!Boolean.TRUE.equals(flags.get("is_baby"))) ? 1 : 0; // si assume adulto
                }
                case "minecraft:equipment" -> p *= ctx.fire() ? 1 : 0; // Fire Aspect, trattato come "fuoco"
                case "minecraft:vehicle" -> {
                    p = 0; // es. zombie su cavallo zombie
                    notes.add("assunto: il mob non cavalca nulla");
                }
                default -> notes.add("proprieta' entita' non valutata: " + e.getKey() + " (assunta vera)");
            }
        }
        return p;
    }
}
