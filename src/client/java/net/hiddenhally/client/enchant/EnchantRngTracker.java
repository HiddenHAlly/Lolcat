package net.hiddenhally.client.enchant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Segue il generatore casuale del giocatore osservando i seed del tavolo degli incantesimi.
 * Servono DUE incantesimi nella stessa sessione per sincronizzarsi; poi ogni nuovo seed verifica/aggiorna
 * lo stato e dice quante chiamate casuali sono state consumate dall'ultimo.
 */
public final class EnchantRngTracker {

    public enum Status { IDLE, SEED_SEEN, ONE_OUTPUT, CRACKING, TRACKING, FAILED }

    public static final int MAX_K = 2000;

    /** Chiamate al generatore consumate da ciascuna azione (modificabile dal giocatore). */
    private static final Map<String, Integer> COSTS = new LinkedHashMap<>();

    static {
        COSTS.put("drop", 4);  // butti un oggetto con Q
        COSTS.put("click", 2); // butti un oggetto cliccando fuori dall'inventario
    }

    private static Object playerRef;
    private static Integer lastSeed;
    private static Integer lastOutput;
    private static List<PlayerRng.Crack> candidates = List.of();
    private static volatile Status status = Status.IDLE;
    private static int lastConsumed = -1;
    private static String message = "";
    private static boolean crackRunning;
    private static long generation = 0;
    private static String learnName;
    private static int learnCount;
    private static String learnResult = "";

    private EnchantRngTracker() {
    }

    // ------------------------------------------------------------------ osservazione

    /** Da chiamare con il seed mostrato dal tavolo (ogni frame va bene). */
    public static synchronized void observe(Object player, int seed) {
        if (player != playerRef) { // nuova sessione/rinascita: il generatore del giocatore è nuovo
            resetState();
            playerRef = player;
        }
        if (lastSeed == null) {
            lastSeed = seed;
            status = Status.SEED_SEEN;
            message = "Seed iniziale visto. Incanta 2 oggetti per sincronizzare l'RNG.";
            return;
        }
        if (seed == lastSeed) return;
        lastSeed = seed;
        newOutput(seed);
    }

    private static void newOutput(int seed) {
        if (lastOutput == null) {
            lastOutput = seed;
            status = Status.ONE_OUTPUT;
            message = "Un incantesimo fatto. Ne serve un altro per sincronizzare l'RNG.";
            return;
        }
        int prev = lastOutput;
        lastOutput = seed;

        if (!candidates.isEmpty()) { // stato già noto: si verifica quanti passi sono stati consumati
            List<PlayerRng.Crack> next = new ArrayList<>();
            for (PlayerRng.Crack c : candidates) {
                int k = PlayerRng.findK(c.state(), seed, MAX_K);
                if (k >= 0) next.add(new PlayerRng.Crack(PlayerRng.advance(c.state(), k), k));
            }
            if (!next.isEmpty()) {
                candidates = next;
                lastConsumed = next.get(0).k() - 1;
                status = Status.TRACKING;
                message = "RNG sincronizzato. Chiamate consumate dall'ultimo seed: " + lastConsumed + ".";
                applyLearn();
                return;
            }
            candidates = List.of(); // perso (qualcosa di imprevisto ha toccato l'RNG): si ricalcola
        }
        startCrack(prev, seed);
    }

    private static void startCrack(int a, int b) {
        if (crackRunning) return;
        crackRunning = true;
        status = Status.CRACKING;
        message = "Sincronizzazione dell'RNG in corso...";
        final long gen = generation;
        Thread t = new Thread(() -> {
            List<PlayerRng.Crack> found = PlayerRng.crack(a, b, MAX_K);
            synchronized (EnchantRngTracker.class) {
                crackRunning = false;
                if (gen != generation) return;
                if (found.isEmpty()) {
                    status = Status.FAILED;
                    message = "Impossibile sincronizzare: troppe azioni tra i due incantesimi o RNG diverso da quello atteso.";
                } else {
                    candidates = found;
                    lastConsumed = found.get(0).k() - 1;
                    status = Status.TRACKING;
                    message = found.size() == 1
                            ? "RNG sincronizzato. Chiamate consumate tra gli ultimi due seed: " + lastConsumed + "."
                            : "RNG ambiguo (" + found.size() + " candidati): incanta ancora un oggetto per risolverlo.";
                    applyLearn();
                }
            }
        }, "Lolcat-RNG-Crack");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ stato

    public static synchronized void reset() {
        resetState();
    }

    private static void resetState() {
        generation++;
        playerRef = null;
        lastSeed = null;
        lastOutput = null;
        candidates = List.of();
        status = Status.IDLE;
        lastConsumed = -1;
        message = "";
        crackRunning = false;
    }

    public static Status status() {
        return status;
    }

    public static synchronized String message() {
        return message;
    }

    public static synchronized int lastConsumed() {
        return lastConsumed;
    }

    /** Stato certo del generatore dopo l'ultimo seed, oppure null se non (ancora) univoco. */
    public static synchronized Long state() {
        return status == Status.TRACKING && candidates.size() == 1 ? candidates.get(0).state() : null;
    }

    /** Per la cache dell'overlay. */
    public static synchronized String signature() {
        Long s = state();
        return status + ":" + (s == null ? "-" : Long.toHexString(s)) + ":" + COSTS;
    }

    // ------------------------------------------------------------------ costi delle azioni

    public static synchronized Map<String, Integer> costs() {
        return new LinkedHashMap<>(COSTS);
    }

    /** calls <= 0 rimuove l'azione. */
    public static synchronized void setCost(String name, int calls) {
        if (calls <= 0) COSTS.remove(name);
        else COSTS.put(name, calls);
    }

    /** Dopo il prossimo seed osservato: costo = chiamate consumate / count (es. butti 5 oggetti poi incanti). */
    public static synchronized void learn(String name, int count) {
        learnName = name;
        learnCount = Math.max(1, count);
        learnResult = "";
    }

    public static synchronized String learnResult() {
        return learnResult;
    }

    private static void applyLearn() {
        if (learnName == null) return;
        int consumed = lastConsumed;
        if (consumed > 0 && consumed % learnCount == 0) {
            COSTS.put(learnName, consumed / learnCount);
            learnResult = "Appreso: '" + learnName + "' consuma " + (consumed / learnCount) + " chiamate.";
        } else {
            learnResult = "Misurazione non valida: " + consumed + " chiamate per " + learnCount + " azioni.";
        }
        learnName = null;
    }

    // ------------------------------------------------------------------ ricette

    /**
     * Combinazione con il minor numero di azioni che consuma ESATTAMENTE n chiamate, per n = 0..maxCalls;
     * null per i valori non raggiungibili con i costi noti.
     */
    public static synchronized List<Map<String, Integer>> recipes(int maxCalls) {
        int[] best = new int[maxCalls + 1];
        String[] via = new String[maxCalls + 1];
        java.util.Arrays.fill(best, Integer.MAX_VALUE);
        best[0] = 0;
        for (int n = 1; n <= maxCalls; n++) {
            for (var e : COSTS.entrySet()) {
                int c = e.getValue();
                if (c <= n && best[n - c] != Integer.MAX_VALUE && best[n - c] + 1 < best[n]) {
                    best[n] = best[n - c] + 1;
                    via[n] = e.getKey();
                }
            }
        }
        List<Map<String, Integer>> out = new ArrayList<>();
        for (int n = 0; n <= maxCalls; n++) {
            if (best[n] == Integer.MAX_VALUE) {
                out.add(null);
                continue;
            }
            Map<String, Integer> r = new LinkedHashMap<>();
            int rem = n;
            while (rem > 0) {
                String a = via[rem];
                r.merge(a, 1, Integer::sum);
                rem -= COSTS.get(a);
            }
            out.add(r);
        }
        return out;
    }
}
