package net.hiddenhally.client.sort;

import java.util.ArrayList;
import java.util.List;

/**
 * Un preset: nome + SLOT ordinati. Ogni slot può contenere più oggetti (un "gruppo") con sotto-priorità:
 * gli oggetti dello stesso slot finiscono uno accanto all'altro, nell'ordine in cui sono nell'elenco dello slot,
 * e gli slot si dispongono compatti (senza buchi) anche se nel baule mancano alcuni oggetti.
 */
public final class Preset {

    /** Come ordinare gli oggetti con la stessa priorità (compresi quelli non presenti nel preset). */
    public enum TieMode {
        /** blocchi, poi lingotti/gemme, poi grezzi/minerali, poi attrezzi; dentro ogni categoria per materiale. */
        CATEGORY,
        /** prima per materiale (tutto il ferro insieme, poi l'oro...), poi per categoria. */
        MATERIAL,
        /** solo alfabetico. */
        ALPHA;

        public TieMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Priorità = indice slot * 100 + posizione nello slot. */
    private static final int SUB = 100;

    public String name;
    public final List<List<String>> slots = new ArrayList<>();
    public TieMode tie = TieMode.CATEGORY;

    public Preset(String name) {
        this.name = name;
    }

    /** Priorità (più basso = più in alto), o Integer.MAX_VALUE se l'oggetto non è nel preset. */
    public int priorityOf(String itemId) {
        for (int s = 0; s < slots.size(); s++) {
            int k = slots.get(s).indexOf(itemId);
            if (k >= 0) return s * SUB + Math.min(k, SUB - 1);
        }
        return Integer.MAX_VALUE;
    }

    public boolean contains(String itemId) {
        return priorityOf(itemId) != Integer.MAX_VALUE;
    }

    public int itemCount() {
        int n = 0;
        for (List<String> g : slots) n += g.size();
        return n;
    }

    /** Toglie l'oggetto da qualunque slot (e rimuove lo slot se resta vuoto). */
    public void remove(String itemId) {
        for (int s = 0; s < slots.size(); s++) {
            if (slots.get(s).remove(itemId)) {
                if (slots.get(s).isEmpty()) slots.remove(s);
                return;
            }
        }
    }

    /** Aggiunge l'oggetto in coda allo slot indicato (se è già altrove lo sposta). */
    public void addToSlot(int slot, String itemId) {
        remove(itemId);
        if (slot < 0 || slot >= slots.size()) {
            List<String> g = new ArrayList<>();
            g.add(itemId);
            slots.add(g);
        } else {
            slots.get(slot).add(itemId);
        }
    }

    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }
}
