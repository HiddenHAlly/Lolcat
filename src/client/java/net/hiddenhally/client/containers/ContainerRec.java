package net.hiddenhally.client.containers;

import java.util.ArrayList;
import java.util.List;

/** Un contenitore (baule, barile, shulker; un baule doppio ha 2 posizioni) e il suo contenuto. */
public final class ContainerRec {
    public String dim = "";
    public final List<int[]> positions = new ArrayList<>();
    public long time;
    /** true = contenuto PREVISTO dal seed (prescan); false = visto davvero dal giocatore. */
    public boolean predicted;
    public String structure = "";
    /** Etichetta scelta dal giocatore (nome del preset di ordinamento che questo contenitore deve contenere). */
    public String label = "";
    public List<ItemRec> items = new ArrayList<>();

    public ContainerRec() {
    }

    public ContainerRec(String dim, int x, int y, int z) {
        this.dim = dim;
        this.positions.add(new int[]{x, y, z});
    }

    public String key() {
        int[] p = positions.get(0);
        return dim + "@" + p[0] + "," + p[1] + "," + p[2];
    }

    public boolean has(String dim, int x, int y, int z) {
        if (!this.dim.equals(dim)) return false;
        for (int[] p : positions) if (p[0] == x && p[1] == y && p[2] == z) return true;
        return false;
    }

    public boolean overlaps(ContainerRec o) {
        if (!dim.equals(o.dim)) return false;
        for (int[] p : o.positions) if (has(dim, p[0], p[1], p[2])) return true;
        return false;
    }

    /** Quanti pezzi: se sig contiene '|' conta solo quella variante esatta, altrimenti ogni variante di quell'oggetto. */
    public int count(String itemId, String sig) {
        int n = 0;
        for (ItemRec it : items) {
            if (!it.id().equals(itemId)) continue;
            if (sig != null && sig.contains("|") && !it.signature().equals(sig)) continue;
            n += it.count();
        }
        return n;
    }

    /** Distanza al quadrato dal punto (px,py,pz) alla posizione più vicina (centro del blocco). */
    public double distSq(double px, double py, double pz) {
        double best = Double.MAX_VALUE;
        for (int[] p : positions) {
            double dx = p[0] + 0.5 - px, dy = p[1] + 0.5 - py, dz = p[2] + 0.5 - pz;
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return best;
    }
}
