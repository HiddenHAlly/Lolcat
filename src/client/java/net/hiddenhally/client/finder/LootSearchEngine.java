package net.hiddenhally.client.finder;

import dev.xpple.cubiomes.Cubiomes;
import dev.xpple.cubiomes.Generator;
import dev.xpple.cubiomes.ItemStack;
import dev.xpple.cubiomes.LootTableContext;
import dev.xpple.cubiomes.Piece;
import dev.xpple.cubiomes.Pos;
import dev.xpple.cubiomes.Pos3;
import dev.xpple.cubiomes.StructureConfig;
import dev.xpple.cubiomes.StructureSaltConfig;
import dev.xpple.cubiomes.StructureVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Cerca nelle strutture vicine i contenitori che (secondo la generazione del loot di Cubiomes)
 * contengono un certo oggetto, finché la quantità richiesta è raggiunta.
 *
 * Supporta solo le strutture per cui Cubiomes sa ricostruire i pezzi e i seed del loot.
 * NON supportati: villaggi, città antiche, trial chambers, dungeon (monster room con spawner),
 * mansion, stronghold.
 */
public class LootSearchEngine {

    public static final int MAX_AMOUNT = 10_000;
    private static final int MAX_PIECES = 1024;
    private static final int SAMPLE_Y = 64;
    /** Un contenitore è "completo" nel raggio R solo se è almeno così più vicino del bordo. */
    private static final int EDGE_MARGIN = 192;

    private record Target(String name, int type, int dimension, List<String> tables) {}

    public record ChestHit(BlockPos pos, String structure, String lootTable, int amount) {}

    public record Result(List<ChestHit> hits, int total, boolean fulfilled,
                         List<String> droppableFrom, String error) {
        public static Result error(String message) {
            return new Result(List.of(), 0, false, List.of(), message);
        }
    }

    private static final List<Target> TARGETS = List.of(
            new Target("desert_pyramid", Cubiomes.Desert_Pyramid(), Cubiomes.DIM_OVERWORLD(),
                    List.of("chests/desert_pyramid")),
            new Target("jungle_temple", Cubiomes.Jungle_Temple(), Cubiomes.DIM_OVERWORLD(),
                    List.of("chests/jungle_temple", "chests/jungle_temple_dispenser")),
            new Target("igloo", Cubiomes.Igloo(), Cubiomes.DIM_OVERWORLD(),
                    List.of("chests/igloo_chest")),
            new Target("pillager_outpost", Cubiomes.Outpost(), Cubiomes.DIM_OVERWORLD(),
                    List.of("chests/pillager_outpost")),
            new Target("shipwreck", Cubiomes.Shipwreck(), Cubiomes.DIM_OVERWORLD(),
                    List.of("chests/shipwreck_supply", "chests/shipwreck_map", "chests/shipwreck_treasure")),
            new Target("buried_treasure", Cubiomes.Treasure(), Cubiomes.DIM_OVERWORLD(),
                    List.of("chests/buried_treasure")),
            new Target("ruined_portal", Cubiomes.Ruined_Portal(), Cubiomes.DIM_OVERWORLD(),
                    List.of("chests/ruined_portal")),
            new Target("ruined_portal_nether", Cubiomes.Ruined_Portal_N(), Cubiomes.DIM_NETHER(),
                    List.of("chests/ruined_portal")),
            new Target("mineshaft", Cubiomes.Mineshaft(), Cubiomes.DIM_OVERWORLD(),
                    List.of("chests/abandoned_mineshaft")),
            new Target("abandoned_camp", Cubiomes.Abandoned_Camp(), Cubiomes.DIM_OVERWORLD(),
                    List.of("chests/abandoned_camp_common_chest", "chests/abandoned_camp_secret_chest",
                            "barrels/abandoned_camp_barrel")),
            new Target("fortress", Cubiomes.Fortress(), Cubiomes.DIM_NETHER(),
                    List.of("chests/nether_bridge")),
            new Target("bastion_remnant", Cubiomes.Bastion(), Cubiomes.DIM_NETHER(),
                    List.of("chests/bastion_bridge", "chests/bastion_other")),
            new Target("end_city", Cubiomes.End_City(), Cubiomes.DIM_END(),
                    List.of("chests/end_city_treasure"))
    );

    /**
     * I contesti di loot di Cubiomes sono singleton statici nella libreria nativa (stato PRNG incluso),
     * quindi una sola ricerca alla volta e nessuna copia: si riutilizzano i puntatori.
     */
    private static final Map<String, MemorySegment> CONTEXTS = new HashMap<>();
    private static volatile Set<String> droppableItemsCache = null;

    public static String normalizeItem(String raw) {
        String s = raw.trim().toLowerCase();
        return s.contains(":") ? s : "minecraft:" + s;
    }

    public static Set<String> targetNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Target t : TARGETS) names.add(t.name);
        return names;
    }

    public static boolean isTarget(String name) {
        return targetNames().contains(name);
    }

    public static ResourceKey<Level> dimensionKey(String structureName) {
        for (Target t : TARGETS) {
            if (t.name.equals(structureName)) {
                if (t.dimension == Cubiomes.DIM_NETHER()) return Level.NETHER;
                if (t.dimension == Cubiomes.DIM_END()) return Level.END;
            }
        }
        return Level.OVERWORLD;
    }

    // ------------------------------------------------------------------ loot tables

    /** Solo per i test: prova prima il nome senza prefisso. */
    static boolean TEST_STRIPPED_FIRST = false;
    private static final Set<String> MISSING_TABLES = new TreeSet<>();

    /** "minecraft:chests/desert_pyramid" / "chests/desert_pyramid" / "desert_pyramid" -> "desert_pyramid". */
    static String key(String table) {
        String t = table.startsWith("minecraft:") ? table.substring(10) : table;
        if (t.startsWith("chests/")) return t.substring(7);
        if (t.startsWith("barrels/")) return t.substring(8);
        return t;
    }

    /**
     * Versioni diverse della libreria nativa vogliono nomi diversi per la stessa tabella
     * ("chests/desert_pyramid" oppure "desert_pyramid"): si provano tutte le forme.
     */
    private static List<String> nameVariants(String table) {
        String k = key(table);
        String dir = table.contains("barrels/") ? "barrels/" : "chests/";
        List<String> out = new ArrayList<>();
        if (TEST_STRIPPED_FIRST) {
            out.add(k);
            out.add(dir + k);
        } else {
            out.add(dir + k);
            out.add(k);
        }
        return out;
    }

    private static MemorySegment context(Arena arena, String table, int version) {
        String k = key(table);
        if (CONTEXTS.containsKey(k)) return CONTEXTS.get(k);
        MemorySegment ctx = null;
        for (String name : nameVariants(table)) {
            MemorySegment ptr = arena.allocate(Cubiomes.C_POINTER);
            if (Cubiomes.init_loot_table_name(ptr, arena.allocateFrom(name), version) != 0) {
                MemorySegment addr = ptr.get(ValueLayout.ADDRESS, 0);
                if (!addr.equals(MemorySegment.NULL)) {
                    ctx = addr.reinterpret(LootTableContext.sizeof());
                    break;
                }
            }
        }
        if (ctx == null) MISSING_TABLES.add(k);
        CONTEXTS.put(k, ctx);
        return ctx;
    }

    /** Precalcola in background la lista degli oggetti per l'auto-completamento (non blocca il gioco). */
    public static void warmUp() {
        Thread t = new Thread(LootSearchEngine::getDroppableItems, "Lolcat-Loot-Warmup");
        t.setDaemon(true);
        t.start();
    }

    /** Per i suggerimenti dei comandi: mai bloccante, vuoto finché il warm-up non è finito. */
    public static Set<String> getDroppableItemsIfReady() {
        Set<String> ready = droppableItemsCache;
        return ready == null ? Set.of() : ready;
    }

    /** Tutti gli oggetti (senza "minecraft:") che almeno una struttura supportata può contenere. */
    public static synchronized Set<String> getDroppableItems() {
        if (droppableItemsCache != null) return droppableItemsCache;
        SeedFinderEngine.init();
        Set<String> out = new TreeSet<>();
        try (Arena arena = Arena.ofConfined()) {
            int version = Cubiomes.MC_NEWEST();
            for (Target t : TARGETS) {
                for (String table : t.tables) {
                    MemorySegment ctx = context(arena, table, version);
                    if (ctx == null) continue;
                    int n = LootTableContext.item_count(ctx);
                    for (int i = 0; i < n; i++) {
                        MemorySegment nm = Cubiomes.get_item_name(ctx, i);
                        if (nm.equals(MemorySegment.NULL)) continue;
                        String s = nm.reinterpret(Long.MAX_VALUE).getString(0);
                        out.add(s.startsWith("minecraft:") ? s.substring(10) : s);
                    }
                }
            }
        }
        if (!MISSING_TABLES.isEmpty()) {
            System.out.println("[Lolcat] Loot table non disponibili nella libreria Cubiomes in uso: " + MISSING_TABLES);
        }
        droppableItemsCache = out;
        return out;
    }

    /** Id globale dell'oggetto nella tabella, oppure -1 se la tabella non può generarlo. */
    private static int globalIdIn(Arena arena, MemorySegment ctx, String itemName) {
        int idx = Cubiomes.get_item_id(ctx, arena.allocateFrom(itemName));
        if (idx < 0) return -1;
        return Cubiomes.get_global_item_id(ctx, idx);
    }

    // ------------------------------------------------------------------ search

    public static Result search(long seed, String rawItem, int amount,
                                int startX, int startZ, int maxRadiusBlocks) {
        return search(seed, rawItem, amount, startX, startZ, maxRadiusBlocks, Set.of());
    }

    /** @param only nomi di struttura a cui limitare la ricerca (vuoto = tutte quelle supportate da Cubiomes). */
    public static synchronized Result search(long seed, String rawItem, int amount,
                                             int startX, int startZ, int maxRadiusBlocks, Set<String> only) {
        Cubiomes_init();
        String item = normalizeItem(rawItem);
        int want = Math.max(1, Math.min(amount, MAX_AMOUNT));
        int version = Cubiomes.MC_NEWEST();

        try (Arena arena = Arena.ofConfined()) {
            // 1) La loot table può generare l'oggetto? Se no, inutile cercare nel mondo.
            List<Target> candidates = new ArrayList<>();
            Set<String> droppableFrom = new LinkedHashSet<>();
            Map<String, Integer> globalIds = new HashMap<>(); // tabella -> id globale (solo tabelle che lo contengono)
            for (Target t : TARGETS) {
                if (!only.isEmpty() && !only.contains(t.name)) continue;
                boolean any = false;
                for (String table : t.tables) {
                    MemorySegment ctx = context(arena, table, version);
                    if (ctx == null) continue;
                    int gid = globalIdIn(arena, ctx, item);
                    if (gid >= 0) {
                        globalIds.put(key(table), gid);
                        any = true;
                    }
                }
                if (any) {
                    candidates.add(t);
                    droppableFrom.add(t.name);
                }
            }
            if (candidates.isEmpty()) {
                return Result.error("not_droppable");
            }

            // 2) Scansione a raggio crescente, valutando il loot di ogni contenitore.
            Map<Integer, MemorySegment> generators = new HashMap<>();
            MemorySegment posSeg = Pos.allocate(arena);
            MemorySegment saltCfg = StructureSaltConfig.allocate(arena);
            MemorySegment variant = StructureVariant.allocate(arena);
            MemorySegment structCfg = StructureConfig.allocate(arena);
            MemorySegment pieces = Piece.allocateArray(MAX_PIECES, arena);

            Map<String, Set<Long>> processed = new HashMap<>();
            List<ChestHit> hits = new ArrayList<>();

            int[] radii = radiusSteps(maxRadiusBlocks);
            for (int radius : radii) {
                for (Target t : candidates) {
                    MemorySegment gen = generators.computeIfAbsent(t.dimension, dim -> {
                        MemorySegment g = Generator.allocate(arena);
                        Cubiomes.setupGenerator(g, version, 0);
                        Cubiomes.applySeed(g, dim, seed);
                        return g;
                    });
                    if (Cubiomes.getStructureConfig(t.type, version, structCfg) == 0) continue;
                    int regionBlocks = StructureConfig.regionSize(structCfg) * 16;
                    if (regionBlocks <= 0) continue;
                    if (Cubiomes.getStructureSaltConfig(t.type, version, -1, saltCfg) == 0) continue;

                    Set<Long> done = processed.computeIfAbsent(t.name, k -> new HashSet<>());
                    int minRX = Math.floorDiv(startX - radius, regionBlocks);
                    int maxRX = Math.floorDiv(startX + radius, regionBlocks);
                    int minRZ = Math.floorDiv(startZ - radius, regionBlocks);
                    int maxRZ = Math.floorDiv(startZ + radius, regionBlocks);

                    for (int rx = minRX; rx <= maxRX; rx++) {
                        for (int rz = minRZ; rz <= maxRZ; rz++) {
                            long key = ((long) rx << 32) ^ (rz & 0xffffffffL);
                            if (!done.add(key)) continue;

                            if (Cubiomes.getStructurePos(t.type, version, seed, rx, rz, posSeg) == 0) continue;
                            int px = Pos.x(posSeg);
                            int pz = Pos.z(posSeg);
                            if (Cubiomes.isViableStructurePos(t.type, gen, px, pz, 0) == 0) continue;

                            collectChests(arena, t, gen, version, seed, px, pz, pieces, saltCfg, variant,
                                    globalIds, item, hits);
                        }
                    }
                }

                // 3) Quantità raggiunta con i contenitori più vicini, e nessun contenitore più vicino ancora fuori scansione?
                hits.sort(Comparator.comparingDouble(h -> dist2(h.pos(), startX, startZ)));
                int sum = 0;
                double lastDist = 0;
                for (ChestHit h : hits) {
                    sum += h.amount();
                    lastDist = Math.sqrt(dist2(h.pos(), startX, startZ));
                    if (sum >= want) break;
                }
                if (sum >= want && lastDist <= radius - EDGE_MARGIN) break;
            }

            // 4) Prefisso minimo di contenitori che soddisfa la richiesta.
            List<ChestHit> chosen = new ArrayList<>();
            int total = 0;
            for (ChestHit h : hits) {
                chosen.add(h);
                total += h.amount();
                if (total >= want) break;
            }
            return new Result(chosen, total, total >= want, new ArrayList<>(droppableFrom), null);
        }
    }

    private static void collectChests(Arena arena, Target t, MemorySegment gen, int version, long seed,
                                      int px, int pz, MemorySegment pieces, MemorySegment saltCfg,
                                      MemorySegment variant, Map<String, Integer> globalIds,
                                      String item, List<ChestHit> out) {
        pieces.fill((byte) 0);
        variant.fill((byte) 0);

        int biome = Cubiomes.getBiomeAt(gen, 4, px >> 2, SAMPLE_Y >> 2, pz >> 2);
        // Non tutte le strutture hanno una variante: se manca, si prosegue con la variante vuota.
        if (Cubiomes.getVariant(variant, t.type, version, seed, px, pz, biome) != 0) {
            int variantBiome = StructureVariant.biome(variant);
            if (variantBiome != -1) biome = variantBiome;
        }
        if (Cubiomes.getStructureSaltConfig(t.type, version, biome, saltCfg) == 0) return;

        int numPieces = Cubiomes.getStructurePieces(pieces, MAX_PIECES, t.type, saltCfg, variant, version, seed, px, pz);
        if (numPieces <= 0) return;
        numPieces = Math.min(numPieces, MAX_PIECES);

        for (int i = 0; i < numPieces; i++) {
            MemorySegment piece = Piece.asSlice(pieces, i);
            int chests = Math.min(Piece.chestCount(piece), 4);
            if (chests <= 0) continue;

            int pieceY = Pos3.y(Piece.pos(piece));
            for (int c = 0; c < chests; c++) {
                MemorySegment tableSeg = Piece.lootTables(piece).getAtIndex(ValueLayout.ADDRESS, c);
                if (tableSeg.equals(MemorySegment.NULL)) continue;
                String table = tableSeg.reinterpret(Long.MAX_VALUE).getString(0);

                Integer wantedGid = globalIds.get(key(table));
                if (wantedGid == null) continue; // questa tabella non può contenere l'oggetto

                MemorySegment ctx = context(arena, table, version);
                if (ctx == null) continue;

                long lootSeed = Piece.lootSeeds(piece).getAtIndex(Cubiomes.C_LONG_LONG, c);
                Cubiomes.set_loot_seed(ctx, lootSeed);
                Cubiomes.generate_loot(ctx);

                int found = 0;
                int count = LootTableContext.generated_item_count(ctx);
                MemorySegment stacks = LootTableContext.generated_items(ctx);
                for (int s = 0; s < count; s++) {
                    MemorySegment stack = ItemStack.asSlice(stacks, s);
                    if (Cubiomes.get_global_item_id(ctx, ItemStack.item(stack)) == wantedGid) {
                        found += ItemStack.count(stack);
                    }
                }
                if (found <= 0) continue;

                MemorySegment cp = Pos.asSlice(Piece.chestPoses(piece), c);
                int y = pieceY > 0 ? pieceY : SAMPLE_Y;
                out.add(new ChestHit(new BlockPos(Pos.x(cp), y, Pos.z(cp)), t.name, table, found));
            }
        }
    }

    private static int[] radiusSteps(int max) {
        int cap = Math.max(512, max);
        List<Integer> steps = new ArrayList<>();
        for (int r = 1024; r < cap; r *= 2) steps.add(r);
        steps.add(cap);
        return steps.stream().mapToInt(Integer::intValue).toArray();
    }

    private static double dist2(BlockPos p, int x, int z) {
        double dx = p.getX() - x, dz = p.getZ() - z;
        return dx * dx + dz * dz;
    }

    @SuppressWarnings("java:S100")
    private static void Cubiomes_init() {
        SeedFinderEngine.init();
    }
}