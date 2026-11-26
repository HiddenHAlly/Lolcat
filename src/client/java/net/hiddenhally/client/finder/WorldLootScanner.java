package net.hiddenhally.client.finder;

import dev.xpple.cubiomes.Cubiomes;
import dev.xpple.cubiomes.Generator;
import dev.xpple.cubiomes.Pos;
import dev.xpple.cubiomes.StructureConfig;
import net.hiddenhally.client.containers.ContainerDb;
import net.hiddenhally.client.containers.ContainerRec;
import net.hiddenhally.client.containers.ItemRecs;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ESPERIMENTALE. Per le strutture che Cubiomes non sa ricostruire (villaggi, città antiche, trial
 * chambers, mansion, rovine oceaniche, dungeon con spawner) usa il codice VERO di Minecraft:
 * genera i chunk attorno alla struttura nel mondo del server integrato (singleplayer), poi "apre"
 * ogni contenitore con loot table non ancora generata e legge gli oggetti.
 *
 * Requisiti: mondo singleplayer con lo STESSO seed e la stessa versione del mondo che interessa
 * (le coordinate valgono poi anche sul server multiplayer con quel seed).
 * Effetti collaterali: i chunk vengono generati/salvati e le loot table dei contenitori toccati
 * vengono generate (luck 0). Usare un mondo di prova.
 */
public final class WorldLootScanner {

    public record Outcome(List<LootSearchEngine.ChestHit> hits, Set<String> droppableFrom, String error) {
        static Outcome error(String e) {
            return new Outcome(List.of(), Set.of(), e);
        }
    }

    /** @param type tipo Cubiomes per trovare le posizioni, oppure -1 = scansione a anelli attorno al giocatore */
    private record WTarget(String name, int type, List<String> tables, int chunkRadius) {}

    private static final List<WTarget> TARGETS = List.of(
            new WTarget("village", Cubiomes.Village(), List.of(
                    "chests/village/village_armorer", "chests/village/village_butcher",
                    "chests/village/village_cartographer", "chests/village/village_desert_house",
                    "chests/village/village_fisher", "chests/village/village_fletcher",
                    "chests/village/village_mason", "chests/village/village_plains_house",
                    "chests/village/village_savanna_house", "chests/village/village_shepherd",
                    "chests/village/village_snowy_house", "chests/village/village_taiga_house",
                    "chests/village/village_tannery", "chests/village/village_temple",
                    "chests/village/village_toolsmith", "chests/village/village_weaponsmith"), 5),
            new WTarget("ancient_city", Cubiomes.Ancient_City(),
                    List.of("chests/ancient_city", "chests/ancient_city_ice_box"), 5),
            new WTarget("trial_chambers", Cubiomes.Trial_Chambers(), List.of(
                    "chests/trial_chambers/corridor", "chests/trial_chambers/entrance",
                    "chests/trial_chambers/intersection", "chests/trial_chambers/intersection_barrel",
                    "chests/trial_chambers/supply"), 5),
            new WTarget("woodland_mansion", Cubiomes.Mansion(), List.of("chests/woodland_mansion"), 4),
            new WTarget("ocean_ruin", Cubiomes.Ocean_Ruin(),
                    List.of("chests/underwater_ruin_small", "chests/underwater_ruin_big"), 2),
            new WTarget("dungeon", -1, List.of("chests/simple_dungeon"), 0)
    );

    private static final int SEARCH_RADIUS_BLOCKS = 6000;
    private static final int MAX_INSTANCES = 24;
    private static final int DUNGEON_MAX_RING = 12;
    private static final long DEADLINE_MS = 240_000;
    private static final long CHUNK_TIMEOUT_MS = 120_000;

    private WorldLootScanner() {
    }

    public static Set<String> targetNames() {
        Set<String> names = new LinkedHashSet<>();
        for (WTarget t : TARGETS) names.add(t.name);
        return names;
    }

    public static boolean isTarget(String name) {
        return targetNames().contains(name);
    }

    public static Outcome search(long seed, String rawItem, int amount, int startX, int startZ,
                                 Set<String> only, Consumer<String> progress) {
        String item = LootSearchEngine.normalizeItem(rawItem);
        int want = Math.max(1, amount);

        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return Outcome.error("no_singleplayer");
        ServerLevel level = server.overworld();
        if (level.getSeed() != seed) return Outcome.error("seed_mismatch");

        // 1) La loot table può generare l'oggetto? (JSON vanilla, nessuna generazione di chunk)
        List<WTarget> candidates = new ArrayList<>();
        Set<String> droppableFrom = new LinkedHashSet<>();
        for (WTarget t : TARGETS) {
            if (!only.contains(t.name)) continue;
            boolean possible = false;
            for (String table : t.tables) {
                if (LootTableIndex.check(table, item) != LootTableIndex.Verdict.NO) {
                    possible = true;
                    break;
                }
            }
            if (possible) {
                candidates.add(t);
                droppableFrom.add(t.name);
            }
        }
        if (candidates.isEmpty()) return Outcome.error("not_droppable");

        // 2) Generazione reale dei chunk attorno alle strutture più vicine, apertura virtuale dei contenitori.
        List<LootSearchEngine.ChestHit> hits = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        int total = 0;

        try (Arena arena = Arena.ofConfined()) {
            for (WTarget t : candidates) {
                if (total >= want || System.currentTimeMillis() > deadline) break;

                if (t.type < 0) {
                    total += scanRings(server, level, item, t, startX, startZ, seen, hits, want - total, deadline, progress);
                    continue;
                }

                List<int[]> instances = candidatePositions(arena, seed, t, startX, startZ);
                int idx = 0;
                for (int[] inst : instances) {
                    if (total >= want || System.currentTimeMillis() > deadline) break;
                    idx++;
                    progress.accept("§eScansione " + t.name + " " + idx + "/" + instances.size()
                            + " (X: " + inst[0] + ", Z: " + inst[1] + ")...");
                    int cx = inst[0] >> 4;
                    int cz = inst[1] >> 4;
                    for (int dz = -t.chunkRadius; dz <= t.chunkRadius; dz++) {
                        for (int dx = -t.chunkRadius; dx <= t.chunkRadius; dx++) {
                            List<LootSearchEngine.ChestHit> found = scanChunk(server, level, cx + dx, cz + dz, item, t.name, seen);
                            for (LootSearchEngine.ChestHit h : found) {
                                hits.add(h);
                                total += h.amount();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (hits.isEmpty()) return Outcome.error("internal");
        }

        hits.sort(Comparator.comparingDouble(h -> Math.pow(h.pos().getX() - startX, 2) + Math.pow(h.pos().getZ() - startZ, 2)));
        return new Outcome(hits, droppableFrom, null);
    }

    /**
     * Genera i chunk attorno alle strutture più vicine e SALVA il contenuto previsto di ogni contenitore
     * (senza cercare un oggetto in particolare). Va fatto in singleplayer con lo stesso seed del server.
     *
     * @return contenitori nuovi salvati; -1 = niente singleplayer, -2 = seed diverso
     */
    public static int prescan(long seed, Set<String> only, int startX, int startZ, int instances,
                              Consumer<String> progress) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return -1;
        ServerLevel level = server.overworld();
        if (level.getSeed() != seed) return -2;

        ContainerDb.ensurePredicted(seed);
        int before = ContainerDb.INDEX.allPredicted().size();
        Set<Long> seen = new HashSet<>();
        long deadline = System.currentTimeMillis() + 20 * 60_000L;

        try (Arena arena = Arena.ofConfined()) {
            for (WTarget t : TARGETS) {
                if (!only.contains(t.name)) continue;
                if (t.type < 0) {
                    scanRings(server, level, "", t, startX, startZ, seen, new ArrayList<>(), Integer.MAX_VALUE, deadline, progress);
                    continue;
                }
                List<int[]> instancesList = candidatePositions(arena, seed, t, startX, startZ);
                int n = Math.min(instances, instancesList.size());
                for (int i = 0; i < n && System.currentTimeMillis() < deadline; i++) {
                    int[] inst = instancesList.get(i);
                    progress.accept("Prescan " + t.name + " " + (i + 1) + "/" + n + " (X: " + inst[0] + ", Z: " + inst[1] + ")...");
                    int cx = inst[0] >> 4;
                    int cz = inst[1] >> 4;
                    for (int dz = -t.chunkRadius; dz <= t.chunkRadius; dz++) {
                        for (int dx = -t.chunkRadius; dx <= t.chunkRadius; dx++) {
                            scanChunk(server, level, cx + dx, cz + dz, "", t.name, seen);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        ContainerDb.save();
        return ContainerDb.INDEX.allPredicted().size() - before;
    }

    /**
     * Cerca un oggetto nei contenitori PREVISTI già salvati (da un prescan fatto in singleplayer):
     * è quello che permette di usare /lolcat loot per città antiche, villaggi ecc. anche sui server.
     */
    public static Outcome searchCache(long seed, String rawItem, Set<String> only, int startX, int startZ) {
        String item = LootSearchEngine.normalizeItem(rawItem).replace("minecraft:", "");
        ContainerDb.ensurePredicted(seed);

        List<LootSearchEngine.ChestHit> hits = new ArrayList<>();
        Set<String> from = new LinkedHashSet<>();
        for (ContainerRec r : ContainerDb.INDEX.allPredicted()) {
            if (!only.isEmpty() && !only.contains(r.structure)) continue;
            int n = r.count(item, null);
            if (n <= 0) continue;
            int[] p = r.positions.get(0);
            hits.add(new LootSearchEngine.ChestHit(new BlockPos(p[0], p[1], p[2]), r.structure, "cache", n));
            from.add(r.structure);
        }
        if (hits.isEmpty()) return Outcome.error("no_cache");
        hits.sort(Comparator.comparingDouble(h -> Math.pow(h.pos().getX() - startX, 2) + Math.pow(h.pos().getZ() - startZ, 2)));
        return new Outcome(hits, from, null);
    }

    // ------------------------------------------------------------------ helpers

    /** Dungeon: nessuna posizione nota, quindi si generano anelli di chunk attorno al giocatore. */
    private static int scanRings(MinecraftServer server, ServerLevel level, String item, WTarget t,
                                 int startX, int startZ, Set<Long> seen,
                                 List<LootSearchEngine.ChestHit> hits, int remaining, long deadline,
                                 Consumer<String> progress) throws Exception {
        int cx = startX >> 4;
        int cz = startZ >> 4;
        int gained = 0;
        for (int r = 0; r <= DUNGEON_MAX_RING && gained < remaining; r++) {
            if (System.currentTimeMillis() > deadline) break;
            progress.accept("§eScansione dungeon: anello " + r + "/" + DUNGEON_MAX_RING + "...");
            for (int[] off : SeedFinderEngine.ring(r)) {
                for (LootSearchEngine.ChestHit h : scanChunk(server, level, cx + off[0], cz + off[1], item, t.name, seen)) {
                    hits.add(h);
                    gained += h.amount();
                }
            }
        }
        return gained;
    }

    /** Posizioni (X, Z) delle strutture più vicine secondo Cubiomes, ordinate per distanza. */
    private static List<int[]> candidatePositions(Arena arena, long seed, WTarget t, int startX, int startZ) {
        int version = Cubiomes.MC_NEWEST();
        MemorySegment gen = Generator.allocate(arena);
        Cubiomes.setupGenerator(gen, version, 0);
        Cubiomes.applySeed(gen, Cubiomes.DIM_OVERWORLD(), seed);

        MemorySegment cfg = StructureConfig.allocate(arena);
        if (Cubiomes.getStructureConfig(t.type, version, cfg) == 0) return List.of();
        int regionBlocks = StructureConfig.regionSize(cfg) * 16;
        if (regionBlocks <= 0) return List.of();

        int startRegX = Math.floorDiv(startX, regionBlocks);
        int startRegZ = Math.floorDiv(startZ, regionBlocks);
        int maxRing = SEARCH_RADIUS_BLOCKS / regionBlocks + 1;
        MemorySegment posSeg = Pos.allocate(arena);

        List<int[]> found = new ArrayList<>();
        int ringWhenEnough = -1;
        for (int r = 0; r <= maxRing; r++) {
            for (int[] off : SeedFinderEngine.ring(r)) {
                if (Cubiomes.getStructurePos(t.type, version, seed, startRegX + off[0], startRegZ + off[1], posSeg) == 0) continue;
                int px = Pos.x(posSeg);
                int pz = Pos.z(posSeg);
                if (Cubiomes.isViableStructurePos(t.type, gen, px, pz, 0) != 0) found.add(new int[]{px, pz});
            }
            if (ringWhenEnough < 0 && found.size() >= MAX_INSTANCES) ringWhenEnough = r;
            if (ringWhenEnough >= 0 && r > ringWhenEnough) break;
        }
        found.sort(Comparator.comparingDouble(p -> Math.pow(p[0] - startX, 2) + Math.pow(p[1] - startZ, 2)));
        return found.size() > MAX_INSTANCES ? new ArrayList<>(found.subList(0, MAX_INSTANCES)) : found;
    }

    /** Genera (se serve) un chunk sul thread del server e apre i contenitori con loot ancora da generare. */
    private static List<LootSearchEngine.ChestHit> scanChunk(MinecraftServer server, ServerLevel level, int cx, int cz,
                                                             String item, String structure, Set<Long> seen) throws Exception {
        return onServer(server, () -> {
            List<LootSearchEngine.ChestHit> out = new ArrayList<>();
            ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, true);
            if (!(chunk instanceof LevelChunk levelChunk)) return out;

            for (BlockEntity be : new ArrayList<>(levelChunk.getBlockEntities().values())) {
                if (!(be instanceof RandomizableContainer randomizable) || randomizable.getLootTable() == null) continue;
                BlockPos pos = be.getBlockPos();
                if (!seen.add(pos.asLong())) continue;

                String table = tableName(randomizable.getLootTable().toString());
                randomizable.unpackLootTable(null); // stessa routine del gioco quando un giocatore apre il contenitore
                if (!(be instanceof Container container)) continue;

                // contenuto completo previsto: serve a /lolcat prescan e a /lolcat loot / find sui server
                ContainerRec predicted = new ContainerRec(level.dimension().toString(), pos.getX(), pos.getY(), pos.getZ());
                predicted.predicted = true;
                predicted.structure = structure;
                predicted.time = System.currentTimeMillis();
                predicted.items = ItemRecs.fromContainer(container);
                ContainerDb.INDEX.putPredicted(predicted);

                int found = 0;
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) {
                        found += stack.getCount();
                    }
                }
                if (found > 0) out.add(new LootSearchEngine.ChestHit(pos, structure, table, found));
            }
            return out;
        });
    }

    /** "ResourceKey[minecraft:loot_table / minecraft:chests/x]" -> "chests/x" (indipendente dai nomi dei metodi). */
    private static String tableName(String keyToString) {
        int slash = keyToString.lastIndexOf(" / ");
        String s = slash >= 0 ? keyToString.substring(slash + 3) : keyToString;
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    private static <T> T onServer(MinecraftServer server, Callable<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(CHUNK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
}
