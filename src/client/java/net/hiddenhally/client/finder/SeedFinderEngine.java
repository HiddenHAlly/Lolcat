package net.hiddenhally.client.finder;

import dev.xpple.cubiomes.Cubiomes;
import dev.xpple.cubiomes.CubiomesInit;
import dev.xpple.cubiomes.Generator;
import dev.xpple.cubiomes.Pos;
import dev.xpple.cubiomes.StructureConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class SeedFinderEngine {
    private static boolean initialized = false;

    private static final Map<String, Integer> BIOME_IDS = new LinkedHashMap<>();
    private static final Map<String, Integer> STRUCTURE_IDS = new LinkedHashMap<>();

    static {
        // Biomi principali (Overworld, Nether, End)
        BIOME_IDS.put("plains", Cubiomes.plains());
        BIOME_IDS.put("sunflower_plains", Cubiomes.sunflower_plains());
        BIOME_IDS.put("desert", Cubiomes.desert());
        BIOME_IDS.put("forest", Cubiomes.forest());
        BIOME_IDS.put("flower_forest", Cubiomes.flower_forest());
        BIOME_IDS.put("birch_forest", Cubiomes.birch_forest());
        BIOME_IDS.put("dark_forest", Cubiomes.dark_forest());
        BIOME_IDS.put("pale_garden", Cubiomes.pale_garden());
        BIOME_IDS.put("taiga", Cubiomes.taiga());
        BIOME_IDS.put("snowy_taiga", Cubiomes.snowy_taiga());
        BIOME_IDS.put("swamp", Cubiomes.swamp());
        BIOME_IDS.put("mangrove_swamp", Cubiomes.mangrove_swamp());
        BIOME_IDS.put("jungle", Cubiomes.jungle());
        BIOME_IDS.put("bamboo_jungle", Cubiomes.bamboo_jungle());
        BIOME_IDS.put("badlands", Cubiomes.badlands());
        BIOME_IDS.put("savanna", Cubiomes.savanna());
        BIOME_IDS.put("cherry_grove", Cubiomes.cherry_grove());
        BIOME_IDS.put("meadow", Cubiomes.meadow());
        BIOME_IDS.put("jagged_peaks", Cubiomes.jagged_peaks());
        BIOME_IDS.put("mushroom_fields", Cubiomes.mushroom_fields());
        BIOME_IDS.put("deep_dark", Cubiomes.deep_dark());
        BIOME_IDS.put("lush_caves", Cubiomes.lush_caves());
        BIOME_IDS.put("dripstone_caves", Cubiomes.dripstone_caves());
        BIOME_IDS.put("ice_spikes", Cubiomes.ice_spikes());
        BIOME_IDS.put("warm_ocean", Cubiomes.warm_ocean());
        BIOME_IDS.put("nether_wastes", Cubiomes.nether_wastes());
        BIOME_IDS.put("crimson_forest", Cubiomes.crimson_forest());
        BIOME_IDS.put("warped_forest", Cubiomes.warped_forest());
        BIOME_IDS.put("soul_sand_valley", Cubiomes.soul_sand_valley());
        BIOME_IDS.put("basalt_deltas", Cubiomes.basalt_deltas());
        BIOME_IDS.put("end_highlands", Cubiomes.end_highlands());

        // Strutture principali
        STRUCTURE_IDS.put("village", Cubiomes.Village());
        STRUCTURE_IDS.put("desert_pyramid", Cubiomes.Desert_Pyramid());
        STRUCTURE_IDS.put("jungle_temple", Cubiomes.Jungle_Temple());
        STRUCTURE_IDS.put("swamp_hut", Cubiomes.Swamp_Hut());
        STRUCTURE_IDS.put("igloo", Cubiomes.Igloo());
        STRUCTURE_IDS.put("ocean_monument", Cubiomes.Monument());
        STRUCTURE_IDS.put("woodland_mansion", Cubiomes.Mansion());
        STRUCTURE_IDS.put("pillager_outpost", Cubiomes.Outpost());
        STRUCTURE_IDS.put("ruined_portal", Cubiomes.Ruined_Portal());
        STRUCTURE_IDS.put("shipwreck", Cubiomes.Shipwreck());
        STRUCTURE_IDS.put("buried_treasure", Cubiomes.Treasure());
        STRUCTURE_IDS.put("mineshaft", Cubiomes.Mineshaft());
        STRUCTURE_IDS.put("ancient_city", Cubiomes.Ancient_City());
        STRUCTURE_IDS.put("trail_ruins", Cubiomes.Trail_Ruins());
        STRUCTURE_IDS.put("trial_chambers", Cubiomes.Trial_Chambers());
        STRUCTURE_IDS.put("fortress", Cubiomes.Fortress());
        STRUCTURE_IDS.put("bastion_remnant", Cubiomes.Bastion());
        STRUCTURE_IDS.put("end_city", Cubiomes.End_City());
    }

    public static void init() {
        if (!initialized) {
            CubiomesInit.load();
            initialized = true;
        }
    }

    public static Set<String> getAvailableBiomes() {
        return BIOME_IDS.keySet();
    }

    public static Set<String> getAvailableStructures() {
        return STRUCTURE_IDS.keySet();
    }

    /** Numero massimo di risultati per ricerca. */
    public static final int MAX_COUNT = 50;
    /** Distanza minima (blocchi) tra due biomi restituiti, per non avere 10 punti nella stessa macchia. */
    private static final int BIOME_MIN_SEPARATION = 192;

    public static Optional<BlockPos> findNearestBiome(long seed, String biomeName, int startX, int startZ, int maxRadiusBlocks) {
        return findNearestBiomes(seed, biomeName, startX, startZ, maxRadiusBlocks, 1).stream().findFirst();
    }

    public static Optional<BlockPos> findNearestStructure(long seed, String structureName, int startX, int startZ, int maxRegions) {
        return findNearestStructures(seed, structureName, startX, startZ, maxRegions, 1).stream().findFirst();
    }

    /** Dimensione Minecraft in cui vive il bioma/struttura (serve per mettere il waypoint nel mondo Xaero giusto). */
    public static ResourceKey<Level> getDimensionKey(boolean structure, String name) {
        String clean = name.replace("minecraft:", "").toLowerCase();
        int dim = structure ? getDimensionForStructure(clean) : getDimensionForBiome(clean);
        if (dim == Cubiomes.DIM_NETHER()) return Level.NETHER;
        if (dim == Cubiomes.DIM_END()) return Level.END;
        return Level.OVERWORLD;
    }

    /**
     * Cerca fino a {@code count} biomi distinti, dal più vicino al più lontano.
     */
    public static List<BlockPos> findNearestBiomes(long seed, String biomeName, int startX, int startZ, int maxRadiusBlocks, int count) {
        init();
        String cleanName = biomeName.replace("minecraft:", "").toLowerCase();
        Integer targetBiomeId = BIOME_IDS.get(cleanName);
        if (targetBiomeId == null) return List.of();

        int want = Math.max(1, Math.min(count, MAX_COUNT));
        int mcVersion = Cubiomes.MC_NEWEST();
        int dimension = getDimensionForBiome(cleanName);
        int sampleY = 64;
        if (cleanName.equals("deep_dark")) sampleY = -50;
        if (cleanName.equals("lush_caves") || cleanName.equals("dripstone_caves")) sampleY = 20;

        List<BlockPos> results = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment generator = Generator.allocate(arena);
            Cubiomes.setupGenerator(generator, mcVersion, 0);
            Cubiomes.applySeed(generator, dimension, seed);

            int step = 32;
            int maxSteps = maxRadiusBlocks / step;

            for (int r = 0; r <= maxSteps && results.size() < want; r++) {
                for (int[] off : ring(r)) {
                    int blockX = startX + off[0] * step;
                    int blockZ = startZ + off[1] * step;
                    int biome = Cubiomes.getBiomeAt(generator, 4, blockX >> 2, sampleY >> 2, blockZ >> 2);
                    if (biome != targetBiomeId) continue;

                    boolean tooClose = false;
                    for (BlockPos p : results) {
                        long dx = p.getX() - blockX, dz = p.getZ() - blockZ;
                        if (dx * dx + dz * dz < (long) BIOME_MIN_SEPARATION * BIOME_MIN_SEPARATION) {
                            tooClose = true;
                            break;
                        }
                    }
                    if (tooClose) continue;

                    results.add(new BlockPos(blockX, sampleY, blockZ));
                    if (results.size() >= want) break;
                }
            }
        }
        return sortedByDistance(results, startX, startZ, want);
    }

    /**
     * Cerca fino a {@code count} strutture valide, dalla più vicina alla più lontana.
     */
    public static List<BlockPos> findNearestStructures(long seed, String structureName, int startX, int startZ, int maxRegions, int count) {
        init();
        String cleanName = structureName.replace("minecraft:", "").toLowerCase();
        Integer structType = STRUCTURE_IDS.get(cleanName);
        if (structType == null) return List.of();

        int want = Math.max(1, Math.min(count, MAX_COUNT));
        int mcVersion = Cubiomes.MC_NEWEST();
        int dimension = getDimensionForStructure(cleanName);

        List<BlockPos> found = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment generator = Generator.allocate(arena);
            Cubiomes.setupGenerator(generator, mcVersion, 0);
            Cubiomes.applySeed(generator, dimension, seed);

            MemorySegment structConfig = StructureConfig.allocate(arena);
            if (Cubiomes.getStructureConfig(structType, mcVersion, structConfig) == 0) return List.of();

            int regionSizeBlocks = StructureConfig.regionSize(structConfig) * 16;
            if (regionSizeBlocks <= 0) return List.of();

            int startRegX = Math.floorDiv(startX, regionSizeBlocks);
            int startRegZ = Math.floorDiv(startZ, regionSizeBlocks);
            MemorySegment posSegment = Pos.allocate(arena);

            int ringWhenEnough = -1;
            for (int r = 0; r <= maxRegions; r++) {
                for (int[] off : ring(r)) {
                    int regX = startRegX + off[0];
                    int regZ = startRegZ + off[1];
                    if (Cubiomes.getStructurePos(structType, mcVersion, seed, regX, regZ, posSegment) == 0) continue;
                    int posX = Pos.x(posSegment);
                    int posZ = Pos.z(posSegment);
                    if (Cubiomes.isViableStructurePos(structType, generator, posX, posZ, 0) != 0) {
                        found.add(new BlockPos(posX, 64, posZ));
                    }
                }
                if (ringWhenEnough < 0 && found.size() >= want) ringWhenEnough = r;
                // un anello in più: una struttura nell'anello successivo può essere più vicina di una nell'ultimo
                if (ringWhenEnough >= 0 && r > ringWhenEnough) break;
            }
        }
        return sortedByDistance(found, startX, startZ, want);
    }

    /** Offset (dx, dz) del perimetro dell'anello r, senza visitare l'interno. */
    static List<int[]> ring(int r) {
        List<int[]> out = new ArrayList<>();
        if (r == 0) {
            out.add(new int[]{0, 0});
            return out;
        }
        for (int i = -r; i <= r; i++) {
            out.add(new int[]{i, -r});
            out.add(new int[]{i, r});
        }
        for (int i = -r + 1; i <= r - 1; i++) {
            out.add(new int[]{-r, i});
            out.add(new int[]{r, i});
        }
        return out;
    }

    private static List<BlockPos> sortedByDistance(List<BlockPos> list, int x, int z, int limit) {
        List<BlockPos> copy = new ArrayList<>(list);
        copy.sort(Comparator.comparingDouble(p -> Math.pow(p.getX() - x, 2) + Math.pow(p.getZ() - z, 2)));
        return copy.size() > limit ? new ArrayList<>(copy.subList(0, limit)) : copy;
    }

    private static int getDimensionForBiome(String name) {
        return switch (name) {
            case "nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas" -> Cubiomes.DIM_NETHER();
            case "end_highlands" -> Cubiomes.DIM_END();
            default -> Cubiomes.DIM_OVERWORLD();
        };
    }

    private static int getDimensionForStructure(String name) {
        return switch (name) {
            case "fortress", "bastion_remnant" -> Cubiomes.DIM_NETHER();
            case "end_city" -> Cubiomes.DIM_END();
            default -> Cubiomes.DIM_OVERWORLD();
        };
    }
}