package net.hiddenhally.client.command;

import net.hiddenhally.client.config.SeedStorage;
import net.hiddenhally.client.finder.LootSearchEngine;
import net.hiddenhally.client.finder.WorldLootScanner;
import net.hiddenhally.client.util.WaypointManagerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Avvia la ricerca loot in background e mostra/salva i risultati. Usato da comando e GUI. */
public final class LootSearchRunner {
    private static final int RADIUS = 6000;
    private static volatile boolean running = false;

    private LootSearchRunner() {
    }

    /** Tutti i nomi di struttura accettati come filtro. */
    public static Set<String> allStructureNames() {
        Set<String> all = new LinkedHashSet<>(LootSearchEngine.targetNames());
        all.addAll(WorldLootScanner.targetNames());
        return all;
    }

    /** "village, ancient_city igloo" -> {village, ancient_city, igloo} */
    public static Set<String> parseFilter(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String s : raw.toLowerCase().split("[\\s,]+")) {
            if (!s.isBlank()) out.add(s.replace("minecraft:", ""));
        }
        return out;
    }

    /** @param structures filtro opzionale (vuoto = tutte le strutture supportate da Cubiomes). */
    public static boolean start(String itemRaw, int amount, Set<String> structures, Consumer<String> status) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            status.accept("§cEntra prima in un mondo.");
            return false;
        }
        if (!SeedStorage.hasSavedSeed()) {
            status.accept("§cNessun seed salvato! Aprilo dalla mappa di Xaero.");
            return false;
        }
        Set<String> valid = allStructureNames();
        for (String s : structures) {
            if (!valid.contains(s)) {
                status.accept("§cStruttura sconosciuta: " + s + ". Valide: " + String.join(", ", valid));
                return false;
            }
        }
        if (running) {
            status.accept("§eUna ricerca loot è già in corso...");
            return false;
        }
        running = true;

        long seed = SeedStorage.getSavedSeed();
        int startX = player.getBlockX();
        int startZ = player.getBlockZ();
        String shown = LootSearchEngine.normalizeItem(itemRaw).replace("minecraft:", "");

        Set<String> cubFilter = new LinkedHashSet<>();
        Set<String> worldFilter = new LinkedHashSet<>();
        for (String s : structures) {
            if (LootSearchEngine.isTarget(s)) cubFilter.add(s);
            if (WorldLootScanner.isTarget(s)) worldFilter.add(s);
        }
        boolean useCubiomes = structures.isEmpty() || !cubFilter.isEmpty();

        status.accept("§eRicerca di " + amount + "x " + shown
                + (structures.isEmpty() ? "" : " in " + String.join(", ", structures)) + "...");

        boolean[] usedCache = {false};
        long[] lastProgress = {0};
        Consumer<String> progress = msg -> {
            long now = System.currentTimeMillis();
            if (now - lastProgress[0] < 8000) return;
            lastProgress[0] = now;
            mc.execute(() -> status.accept(msg));
        };

        Thread worker = new Thread(() -> {
            LootSearchEngine.Result combined;
            String warning = null;
            try {
                LootSearchEngine.Result cub = useCubiomes
                        ? LootSearchEngine.search(seed, itemRaw, amount, startX, startZ, RADIUS, cubFilter)
                        : null;
                WorldLootScanner.Outcome world = worldFilter.isEmpty()
                        ? null
                        : WorldLootScanner.search(seed, itemRaw, amount, startX, startZ, worldFilter, progress);
                // Su un server non c'è un mondo da generare: si usano i dati salvati da un prescan in singleplayer.
                if (world != null && "no_singleplayer".equals(world.error())) {
                    WorldLootScanner.Outcome cached = WorldLootScanner.searchCache(seed, itemRaw, worldFilter, startX, startZ);
                    if (cached.error() == null) {
                        world = cached;
                        usedCache[0] = true;
                    }
                }

                List<LootSearchEngine.ChestHit> all = new ArrayList<>();
                Set<String> droppable = new LinkedHashSet<>();
                String error = null;
                if (cub != null) {
                    if (cub.error() == null) {
                        all.addAll(cub.hits());
                        droppable.addAll(cub.droppableFrom());
                    } else if (!"not_droppable".equals(cub.error())) {
                        error = cub.error();
                    }
                }
                if (world != null) {
                    if (world.error() == null) {
                        all.addAll(world.hits());
                        droppable.addAll(world.droppableFrom());
                    } else if (!"not_droppable".equals(world.error())) {
                        warning = world.error();
                    }
                }

                all.sort(Comparator.comparingDouble(h ->
                        Math.pow(h.pos().getX() - startX, 2) + Math.pow(h.pos().getZ() - startZ, 2)));
                List<LootSearchEngine.ChestHit> chosen = new ArrayList<>();
                int total = 0;
                for (LootSearchEngine.ChestHit h : all) {
                    chosen.add(h);
                    total += h.amount();
                    if (total >= amount) break;
                }
                if (chosen.isEmpty()) {
                    if (error == null) error = warning;
                    if (error == null && droppable.isEmpty()) error = "not_droppable";
                    warning = null;
                }
                if (usedCache[0] && !chosen.isEmpty() && warning == null) warning = "cache";
                combined = new LootSearchEngine.Result(chosen, total, total >= amount,
                        new ArrayList<>(droppable), error);
            } catch (Throwable t) {
                t.printStackTrace();
                combined = LootSearchEngine.Result.error("internal");
            }
            LootSearchEngine.Result finalResult = combined;
            String finalWarning = warning;
            mc.execute(() -> {
                running = false;
                present(player, shown, amount, finalResult, finalWarning, status);
            });
        }, "Lolcat-Loot-Search");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private static String describeError(String code) {
        return switch (code) {
            case "not_droppable" -> "§c'%s' non può uscire dai contenitori delle strutture scelte.";
            case "no_singleplayer" -> "§cLe strutture scelte (village, ancient_city, dungeon...) richiedono un mondo singleplayer "
                    + "con lo stesso seed, oppure dati salvati prima con /lolcat prescan <struttura> fatto in singleplayer "
                    + "(poi funziona anche sui server).";
            case "cache" -> "§eDati dalla cache del prescan: se qualcuno ha già aperto o saccheggiato i contenitori, il contenuto reale può differire.";
            case "seed_mismatch" -> "§cIl seed del mondo singleplayer non coincide con quello salvato.";
            default -> "§cErrore interno durante la ricerca (vedi log).";
        };
    }

    private static void present(LocalPlayer player, String item, int amount, LootSearchEngine.Result result,
                                String warning, Consumer<String> status) {
        if (result.error() != null) {
            String msg = describeError(result.error()).replace("%s", item);
            status.accept(msg);
            player.sendSystemMessage(Component.literal("§c[Lolcat] " + msg.substring(2)));
            if (result.error().equals("not_droppable")) {
                player.sendSystemMessage(Component.literal(
                        "§7Per villaggi, città antiche, trial chambers, mansion, rovine oceaniche e dungeon aggiungi il nome "
                                + "della struttura (serve un mondo singleplayer con lo stesso seed)."));
            }
            return;
        }

        if (result.hits().isEmpty()) {
            String msg = "§c" + item + " può uscire da " + String.join(", ", result.droppableFrom())
                    + " ma non ne ho trovato entro " + RADIUS + " blocchi.";
            status.accept(msg);
            player.sendSystemMessage(Component.literal("§c[Lolcat] " + msg.substring(2)));
            return;
        }

        // Un waypoint per contenitore, nella dimensione giusta.
        Map<ResourceKey<Level>, List<BlockPos>> byDimension = new LinkedHashMap<>();
        for (LootSearchEngine.ChestHit h : result.hits()) {
            byDimension.computeIfAbsent(LootSearchEngine.dimensionKey(h.structure()), k -> new ArrayList<>())
                    .add(h.pos());
        }
        boolean allOk = true;
        for (Map.Entry<ResourceKey<Level>, List<BlockPos>> e : byDimension.entrySet()) {
            allOk &= WaypointManagerHelper.addWaypoints(e.getValue(), item, e.getKey());
        }

        String summary = String.format("§a%d/%d %s in %d contenitori%s%s",
                result.total(), amount, item, result.hits().size(),
                result.fulfilled() ? "" : " §e(richiesta non completa)",
                allOk ? "" : " §c- waypoint non creati");
        status.accept(summary);
        player.sendSystemMessage(Component.literal("§a[Lolcat] " + summary.substring(2)));
        if (warning != null) {
            player.sendSystemMessage(Component.literal("§e[Lolcat] " + describeError(warning).replace("%s", item).substring(2)));
        }

        int shown = Math.min(result.hits().size(), 10);
        for (int i = 0; i < shown; i++) {
            LootSearchEngine.ChestHit h = result.hits().get(i);
            player.sendSystemMessage(Component.literal(String.format(
                    "§7 %d) %s x%d @ X: %d, Z: %d (Y circa %d)",
                    i + 1, h.structure(), h.amount(), h.pos().getX(), h.pos().getZ(), h.pos().getY())));
        }
        if (result.hits().size() > shown) {
            player.sendSystemMessage(Component.literal("§7 ... e altri " + (result.hits().size() - shown)));
        }
    }
}
