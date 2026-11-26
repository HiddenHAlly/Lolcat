package net.hiddenhally.client.containers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Oggetto salvato in un contenitore: id (senza "minecraft:"), quantità ed eventuali incantesimi ("sharpness 5"). */
public record ItemRec(String id, int count, List<String> enchants) {

    public ItemRec {
        enchants = enchants == null ? List.of() : List.copyOf(enchants);
    }

    /** Chiave che distingue varianti incantate: "diamond_sword|sharpness 5,unbreaking 3". */
    public String signature() {
        if (enchants.isEmpty()) return id;
        List<String> e = new ArrayList<>(enchants);
        Collections.sort(e);
        return id + "|" + String.join(",", e);
    }

    public boolean isEnchanted() {
        return !enchants.isEmpty();
    }

    public static String stripNamespace(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
