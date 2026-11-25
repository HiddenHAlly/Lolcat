package net.hiddenhally.client.enchant;

import java.util.ArrayList;
import java.util.List;

/**
 * Generatore casuale del giocatore (LCG a 48 bit, identico a java.util.Random / LegacyRandomSource).
 * Il seed degli incantesimi è un singolo nextInt() = i 32 bit alti dello stato: ne mancano 16, che si
 * ricostruiscono confrontando DUE seed consecutivi.
 */
public final class PlayerRng {
    private static final long MULT = 0x5DEECE66DL;
    private static final long ADD = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    /** @param state stato DOPO aver generato il seed; k = numero di passi dal seed precedente (1 = consecutivi) */
    public record Crack(long state, int k) {}

    private PlayerRng() {
    }

    public static long step(long state) {
        return (state * MULT + ADD) & MASK;
    }

    /** Valore di nextInt() se lo stato appena raggiunto è {@code state}. */
    public static int out(long state) {
        return (int) (state >>> 16);
    }

    public static long advance(long state, int n) {
        for (int i = 0; i < n; i++) state = step(state);
        return state;
    }

    /** Tutti gli stati compatibili con s1 -> s2 a distanza k <= maxK (di solito ne esiste uno solo). */
    public static List<Crack> crack(int s1, int s2, int maxK) {
        List<Crack> found = new ArrayList<>();
        long high = (s1 & 0xFFFFFFFFL) << 16;
        for (int low = 0; low < 65536; low++) {
            long st = high | low;
            for (int k = 1; k <= maxK; k++) {
                st = step(st);
                if (out(st) == s2) found.add(new Crack(st, k));
            }
        }
        return found;
    }

    /** Dopo quanti passi (1..maxK) da {@code state} esce il valore {@code seed}? -1 se mai. */
    public static int findK(long state, int seed, int maxK) {
        long st = state;
        for (int k = 1; k <= maxK; k++) {
            st = step(st);
            if (out(st) == seed) return k;
        }
        return -1;
    }
}
