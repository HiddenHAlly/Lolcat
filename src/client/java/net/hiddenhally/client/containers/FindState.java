package net.hiddenhally.client.containers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Richieste "trovami N di X": quali contenitori evidenziare. Il numero ancora necessario scende quando
 * l'oggetto compare nel tuo inventario (rispetto a quando hai fatto la richiesta), quindi le
 * evidenziazioni spariscono da sole man mano che prendi gli oggetti.
 */
public final class FindState {

    public static final int[] PALETTE = {
            0xFFFF5555, 0xFF55FF55, 0xFF5599FF, 0xFFFFFF55, 0xFFFF55FF, 0xFF55FFFF, 0xFFFFAA00, 0xFFFFFFFF
    };
    public static final String[] COLOR_NAMES = {"rosso", "verde", "blu", "giallo", "magenta", "ciano", "arancione", "bianco"};

    public static final class Request {
        public final String item;
        public final String sig;     // chiave variante (può essere solo l'id = qualsiasi incantesimo)
        public final int need;
        public final int baseline;   // quanti ne avevi nell'inventario alla richiesta
        public final int colorIndex;

        Request(String item, String sig, int need, int baseline, int colorIndex) {
            this.item = item;
            this.sig = sig;
            this.need = need;
            this.baseline = baseline;
            this.colorIndex = colorIndex;
        }

        public int color() {
            return PALETTE[colorIndex % PALETTE.length];
        }
    }

    public record Highlight(String dim, int x, int y, int z, int color) {}

    public record Status(Request request, int remaining, int found) {}

    public record Result(List<Highlight> highlights, List<Status> statuses, List<Request> completed) {}

    public record Pick(ContainerRec container, int available) {}

    private final List<Request> requests = new ArrayList<>();

    public synchronized void clear() {
        requests.clear();
    }

    public synchronized List<Request> requests() {
        return new ArrayList<>(requests);
    }

    public synchronized Request add(String item, String sig, int need, int baseline) {
        // colore: il primo non usato
        int idx = 0;
        while (true) {
            boolean used = false;
            for (Request r : requests) if (r.colorIndex == idx) used = true;
            if (!used) break;
            idx++;
        }
        Request r = new Request(item, sig == null ? item : sig, need, baseline, idx);
        requests.add(r);
        return r;
    }

    public synchronized void remove(Request r) {
        requests.remove(r);
    }

    /** Contenitori più vicini che coprono {@code need} pezzi (o tutti quelli disponibili se non bastano). */
    public static List<Pick> pick(List<ContainerRec> containers, String item, String sig, int need,
                                  double px, double py, double pz) {
        List<ContainerRec> sorted = new ArrayList<>(containers);
        sorted.sort(Comparator.comparingDouble(c -> c.distSq(px, py, pz)));
        List<Pick> out = new ArrayList<>();
        int got = 0;
        for (ContainerRec c : sorted) {
            int n = c.count(item, sig);
            if (n <= 0) continue;
            out.add(new Pick(c, n));
            got += n;
            if (got >= need) break;
        }
        return out;
    }

    /** @param inventoryCount quanti pezzi (item, sig) hai ora nell'inventario */
    public synchronized Result compute(ContainerIndex index, String dim, boolean includePredicted,
                                       double px, double py, double pz,
                                       java.util.function.BiFunction<String, String, Integer> inventoryCount) {
        List<Highlight> highlights = new ArrayList<>();
        List<Status> statuses = new ArrayList<>();
        List<Request> completed = new ArrayList<>();
        List<ContainerRec> pool = index.visible(dim, includePredicted);

        for (Request r : requests) {
            int taken = Math.max(0, inventoryCount.apply(r.item, r.sig) - r.baseline);
            int remaining = r.need - taken;
            if (remaining <= 0) {
                completed.add(r);
                continue;
            }
            List<Pick> picks = pick(pool, r.item, r.sig, remaining, px, py, pz);
            int found = 0;
            for (Pick p : picks) {
                found += p.available();
                for (int[] pos : p.container().positions) {
                    highlights.add(new Highlight(dim, pos[0], pos[1], pos[2], r.color()));
                }
            }
            statuses.add(new Status(r, remaining, found));
        }
        requests.removeAll(completed);
        return new Result(highlights, statuses, completed);
    }
}
