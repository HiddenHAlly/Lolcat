package net.hiddenhally.client.enchant;

import java.util.ArrayList;
import java.util.List;

/** Incantesimi che il giocatore vuole ottenere insieme (impostati con /lolcat enchant ...). */
public final class EnchantTarget {

    public record Want(String id, int minLevel) {}

    private static volatile List<Want> wants = List.of();

    private EnchantTarget() {
    }

    /** Sostituisce tutti gli obiettivi con uno solo. */
    public static void set(String id, int level) {
        wants = List.of(new Want(id, Math.max(1, level)));
    }

    /** Aggiunge un obiettivo (se c'è già lo stesso incantesimo, ne aggiorna il livello). */
    public static void add(String id, int level) {
        List<Want> next = new ArrayList<>();
        for (Want w : wants) if (!w.id().equals(id)) next.add(w);
        next.add(new Want(id, Math.max(1, level)));
        wants = List.copyOf(next);
    }

    public static void clear() {
        wants = List.of();
    }

    public static List<Want> wants() {
        return wants;
    }

    public static boolean isSet() {
        return !wants.isEmpty();
    }

    /** Testo per cache e messaggi, es. "fortune>=3 + unbreaking>=3". */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (Want w : wants) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(w.id().replace("minecraft:", "")).append(">=").append(w.minLevel());
        }
        return sb.toString();
    }
}
