package net.hiddenhally.client.odds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Opzioni e testo del report dei drop (puro Java, nessuna dipendenza da Minecraft). */
public final class OddsReport {

    /** Opzioni lette dalla riga di comando. */
    public record Options(DropOdds.Ctx ctx, boolean showDistribution, boolean compareLevels) {}

    private OddsReport() {
    }

    /**
     * Token: fortune=N (f=N), looting=N (l=N), silk, shears, fire, noplayer, dist, e qualsiasi chiave=valore
     * (stato del blocco, es. age=7).
     */
    public static Options parse(String raw, boolean mobKilledByPlayerDefault) {
        int fortune = 0, looting = 0;
        boolean silk = false, shears = false, fire = false, player = mobKilledByPlayerDefault, dist = false;
        Map<String, String> state = new HashMap<>();
        if (raw != null) {
            for (String t : raw.toLowerCase().split("[\\s,]+")) {
                if (t.isBlank()) continue;
                switch (t) {
                    case "silk", "silktouch" -> silk = true;
                    case "shears" -> shears = true;
                    case "fire" -> fire = true;
                    case "noplayer" -> player = false;
                    case "player" -> player = true;
                    case "dist" -> dist = true;
                    default -> {
                        int eq = t.indexOf('=');
                        if (eq <= 0) continue;
                        String k = t.substring(0, eq), v = t.substring(eq + 1);
                        if (k.equals("fortune") || k.equals("f")) fortune = toInt(v);
                        else if (k.equals("looting") || k.equals("l")) looting = toInt(v);
                        else state.put(k, v);
                    }
                }
            }
        }
        return new Options(new DropOdds.Ctx(fortune, looting, silk, shears, fire, player, state), dist, true);
    }

    private static int toInt(String v) {
        try {
            return Math.max(0, Math.min(10, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** @param varyFortune true = confronto per livelli di Fortune, false = Looting, null = nessun confronto */
    public static List<String> report(String title, String table, Options opt, Boolean varyFortune) {
        List<String> lines = new ArrayList<>();
        DropOdds.Result r = DropOdds.evaluate(table, opt.ctx());
        if (r.error() != null) {
            lines.add("§c[Lolcat] Nessuna loot table trovata per '" + title + "' (" + table + ").");
            return lines;
        }
        DropOdds.Ctx c = opt.ctx();
        StringBuilder head = new StringBuilder("§e[Lolcat] Drop di " + title);
        List<String> flags = new ArrayList<>();
        if (c.fortune() > 0) flags.add("Fortune " + c.fortune());
        if (c.looting() > 0) flags.add("Looting " + c.looting());
        if (c.silk()) flags.add("Silk Touch");
        if (c.shears()) flags.add("Cesoie");
        if (c.fire()) flags.add("in fiamme");
        if (c.killedByPlayer()) flags.add("ucciso dal giocatore");
        for (var e : c.state().entrySet()) flags.add(e.getKey() + "=" + e.getValue());
        if (!flags.isEmpty()) head.append(" §7(").append(String.join(", ", flags)).append(")");
        lines.add(head.toString());

        List<Map.Entry<String, DropOdds.Dist>> sorted = new ArrayList<>(r.items().entrySet());
        sorted.removeIf(e -> e.getValue().max() == 0);
        sorted.sort((a, b) -> Double.compare(b.getValue().mean(), a.getValue().mean()));
        if (sorted.isEmpty()) {
            lines.add("§7 Nessun drop con queste condizioni.");
        }

        int shown = Math.min(sorted.size(), 8);
        for (int i = 0; i < shown; i++) {
            var e = sorted.get(i);
            DropOdds.Dist d = e.getValue();
            String name = e.getKey().replace("minecraft:", "");
            String range = d.min() == d.max() ? String.valueOf(d.max()) : d.min() + "-" + d.max();
            lines.add(String.format("§a %s§7: §f%s §7| media §f%.2f §7| almeno 1: §f%s", name, range, d.mean(), pct(d.probAtLeast(1))));
            if (opt.showDistribution() && d.values().size() <= 14) {
                StringBuilder sb = new StringBuilder("§8   ");
                for (int v : d.values()) sb.append(v).append(':').append(pct(d.probOf(v))).append(' ');
                lines.add(sb.toString().trim());
            }
        }
        if (sorted.size() > shown) lines.add("§7 ... e altri " + (sorted.size() - shown) + " oggetti");

        if (varyFortune != null && opt.compareLevels() && !sorted.isEmpty()) {
            String label = varyFortune ? "Fortune" : "Looting";
            int top = Math.min(3, sorted.size());
            for (int i = 0; i < top; i++) {
                String key = sorted.get(i).getKey();
                StringBuilder sb = new StringBuilder("§7 Media per " + label + " 0/1/2/3 di " + key.replace("minecraft:", "") + ": §f");
                for (int lvl = 0; lvl <= 3; lvl++) {
                    DropOdds.Ctx cc = varyFortune ? c.withLevels(lvl, c.looting()) : c.withLevels(c.fortune(), lvl);
                    DropOdds.Dist d = DropOdds.evaluate(table, cc).items().get(key);
                    sb.append(d == null ? "0" : String.format("%.2f", d.mean()));
                    if (lvl < 3) sb.append(" / ");
                }
                lines.add(sb.toString());
            }
        }

        int notes = Math.min(r.notes().size(), 4);
        for (int i = 0; i < notes; i++) lines.add("§8 nota: " + r.notes().get(i));
        return lines;
    }

    private static String pct(double p) {
        double v = p * 100;
        if (v >= 99.995) return "100%";
        if (v >= 10) return String.format("%.0f%%", v);
        if (v >= 1) return String.format("%.1f%%", v);
        return String.format("%.2f%%", v);
    }
}
