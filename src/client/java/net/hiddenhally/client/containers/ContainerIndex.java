package net.hiddenhally.client.containers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Insieme dei contenitori noti: quelli visti dal giocatore (osservati) e quelli previsti dal seed. */
public final class ContainerIndex {
    private final Map<String, ContainerRec> observed = new LinkedHashMap<>();
    private final Map<String, ContainerRec> predicted = new LinkedHashMap<>();
    private volatile long version = 0;

    public long version() {
        return version;
    }

    /** Segna che qualcosa è cambiato (per salvataggio e aggiornamento delle schermate). */
    public synchronized void touch() {
        version++;
    }

    public synchronized void clearObserved() {
        observed.clear();
        version++;
    }

    public synchronized void clearPredicted() {
        predicted.clear();
        version++;
    }

    /** Inserisce/aggiorna un contenitore osservato (sostituisce quelli che occupano le stesse posizioni). */
    public synchronized void putObserved(ContainerRec rec) {
        observed.values().removeIf(o -> o.overlaps(rec));
        observed.put(rec.key(), rec);
        version++;
    }

    public synchronized void putPredicted(ContainerRec rec) {
        predicted.put(rec.key(), rec);
        version++;
    }

    public synchronized ContainerRec findObserved(String dim, int x, int y, int z) {
        for (ContainerRec r : observed.values()) if (r.has(dim, x, y, z)) return r;
        return null;
    }

    /** Contenitori visibili in una dimensione: i previsti sono nascosti se ne esiste uno osservato nello stesso posto. */
    public synchronized List<ContainerRec> visible(String dim, boolean includePredicted) {
        List<ContainerRec> out = new ArrayList<>();
        for (ContainerRec r : observed.values()) if (r.dim.equals(dim)) out.add(r);
        if (includePredicted) {
            for (ContainerRec p : predicted.values()) {
                if (!p.dim.equals(dim)) continue;
                boolean seen = false;
                for (ContainerRec o : observed.values()) if (o.overlaps(p)) { seen = true; break; }
                if (!seen) out.add(p);
            }
        }
        return out;
    }

    public synchronized List<ContainerRec> allObserved() {
        return new ArrayList<>(observed.values());
    }

    public synchronized List<ContainerRec> allPredicted() {
        return new ArrayList<>(predicted.values());
    }

    /** Voce aggregata per la schermata: tutte le copie della stessa variante di oggetto. */
    public static final class Agg {
        public final String signature;
        public final String id;
        public final List<String> enchants;
        public int total;
        public int containers;
        public ContainerRec nearest;
        public double nearestDistSq = Double.MAX_VALUE;
        public boolean anyPredicted;

        Agg(ItemRec it) {
            this.signature = it.signature();
            this.id = it.id();
            this.enchants = it.enchants();
        }
    }

    public List<Agg> aggregate(String dim, boolean includePredicted, double px, double py, double pz) {
        Map<String, Agg> map = new LinkedHashMap<>();
        for (ContainerRec r : visible(dim, includePredicted)) {
            double d = r.distSq(px, py, pz);
            Map<String, Boolean> countedHere = new LinkedHashMap<>();
            for (ItemRec it : r.items) {
                Agg a = map.computeIfAbsent(it.signature(), k -> new Agg(it));
                a.total += it.count();
                if (countedHere.put(it.signature(), true) == null) a.containers++;
                if (r.predicted) a.anyPredicted = true;
                if (d < a.nearestDistSq) {
                    a.nearestDistSq = d;
                    a.nearest = r;
                }
            }
        }
        return new ArrayList<>(map.values());
    }
}
