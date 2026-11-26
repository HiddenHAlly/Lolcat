package net.hiddenhally.client.containers;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.hiddenhally.client.config.SeedStorage;
import net.hiddenhally.client.finder.WorldLootScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * /lolcat find <oggetto> <quantità> [<oggetto> <quantità> ...]   evidenzia i contenitori più vicini (colori diversi)
 * /lolcat find clear | /lolcat highlights clear                  toglie le evidenziazioni
 * /lolcat items                                                  schermata con tutti gli oggetti noti
 * /lolcat scan [force] | scan auto on|off                        legge i contenitori a portata di mano
 * /lolcat prescan <struttura> [quante]                           (singleplayer) salva il loot previsto per usarlo sui server
 */
public final class ContainerCommand {

    private ContainerCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("lolcat")
                .then(ClientCommands.literal("find")
                        .then(ClientCommands.literal("clear").executes(context -> clear(context.getSource())))
                        .then(ClientCommands.argument("request", StringArgumentType.greedyString())
                                .suggests(ContainerCommand::suggestItems)
                                .executes(context -> find(context.getSource(), StringArgumentType.getString(context, "request")))))
                .then(ClientCommands.literal("highlights")
                        .then(ClientCommands.literal("clear").executes(context -> clear(context.getSource()))))
                .then(ClientCommands.literal("items").executes(context -> {
                    // dopo la chat, altrimenti la chat chiude subito la schermata appena aperta
                    ContainerCapture.runNextTick(() -> Minecraft.getInstance().gui.setScreen(new ContainerDbScreen()));
                    return 1;
                }))
                .then(ClientCommands.literal("scan")
                        .executes(context -> scan(context.getSource(), false))
                        .then(ClientCommands.literal("force").executes(context -> scan(context.getSource(), true)))
                        .then(ClientCommands.literal("auto")
                                .then(ClientCommands.literal("on").executes(context -> auto(context.getSource(), true)))
                                .then(ClientCommands.literal("off").executes(context -> auto(context.getSource(), false)))))
                .then(ClientCommands.literal("prescan")
                        .then(ClientCommands.argument("structure", StringArgumentType.word())
                                .suggests((_, builder) -> {
                                    for (String n : WorldLootScanner.targetNames()) {
                                        if (n.startsWith(builder.getRemaining().toLowerCase())) builder.suggest(n);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> prescan(context.getSource(), StringArgumentType.getString(context, "structure"), 6))
                                .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 40))
                                        .executes(context -> prescan(context.getSource(),
                                                StringArgumentType.getString(context, "structure"),
                                                IntegerArgumentType.getInteger(context, "count")))))));
    }

    // ------------------------------------------------------------------ find / clear

    private static int find(FabricClientCommandSource source, String raw) {
        Minecraft mc = Minecraft.getInstance();
        List<FindArgs.Req> reqs = FindArgs.parse(raw);
        if (reqs.isEmpty()) {
            source.sendFeedback(Component.literal("§c[Lolcat] Uso: /lolcat find <oggetto> <quantità> [<oggetto> <quantità> ...]"));
            return 0;
        }
        for (FindArgs.Req r : reqs) {
            source.sendFeedback(Component.literal("[Lolcat] " + addFind(mc, r.item(), null, r.count())));
        }
        HighlightRenderer.tick(mc, true);
        return 1;
    }

    /** Aggiunge una richiesta di evidenziazione. Restituisce il messaggio per la chat. */
    public static String addFind(Minecraft mc, String item, String sig, int need) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "§cEntra prima in un mondo.";
        if (ItemRecs.itemById(item) == null) return "§cOggetto sconosciuto: " + item;
        if (SeedStorage.hasSavedSeed()) ContainerDb.ensurePredicted(SeedStorage.getSavedSeed());

        int total = 0;
        for (ContainerRec r : ContainerDb.INDEX.visible(ContainerDb.currentDim(), true)) total += r.count(item, sig);
        if (total == 0) {
            return "§eNessun contenitore noto contiene " + item + " (apri o scansiona dei contenitori, oppure /lolcat prescan).";
        }
        int baseline = HighlightRenderer.inventoryCount(p.getInventory(), item, sig);
        FindState.Request req = ContainerDb.FIND.add(item, sig, need, baseline);
        String color = FindState.COLOR_NAMES[req.colorIndex % FindState.COLOR_NAMES.length];
        return "§a" + item + " x" + need + ": evidenziato in " + color + (total < need ? " §e(noti solo " + total + ")" : "");
    }

    private static int clear(FabricClientCommandSource source) {
        HighlightRenderer.clearAll(Minecraft.getInstance());
        source.sendFeedback(Component.literal("§e[Lolcat] Evidenziazioni rimosse."));
        return 1;
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestItems(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        String rem = builder.getRemaining();
        int cut = rem.lastIndexOf(' ');
        String last = rem.substring(cut + 1).toLowerCase().replace("minecraft:", "");
        if (last.matches("\\d+")) return builder.buildFuture();
        SuggestionsBuilder sb = builder.createOffset(builder.getStart() + cut + 1);
        Set<String> ids = new TreeSet<>();
        for (ContainerRec r : ContainerDb.INDEX.visible(ContainerDb.currentDim(), true)) {
            for (ItemRec it : r.items) ids.add(it.id());
        }
        for (String id : ids) if (id.startsWith(last)) sb.suggest(id);
        return sb.buildFuture();
    }

    // ------------------------------------------------------------------ scan

    private static int scan(FabricClientCommandSource source, boolean force) {
        String err = ContainerScanner.start(Minecraft.getInstance(), force, false);
        if (err != null) {
            source.sendFeedback(Component.literal("§c[Lolcat] " + err));
            return 0;
        }
        return 1;
    }

    private static int auto(FabricClientCommandSource source, boolean on) {
        ContainerScanner.setAuto(on);
        source.sendFeedback(Component.literal(on
                ? "§e[Lolcat] Scansione automatica ON: legge i contenitori nuovi a portata di mano quando stai fermo con la mano vuota."
                : "§e[Lolcat] Scansione automatica OFF."));
        return 1;
    }

    // ------------------------------------------------------------------ prescan (singleplayer)

    private static int prescan(FabricClientCommandSource source, String structure, int count) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return 0;
        if (!SeedStorage.hasSavedSeed()) {
            source.sendFeedback(Component.literal("§c[Lolcat] Nessun seed salvato."));
            return 0;
        }
        if (mc.getSingleplayerServer() == null) {
            source.sendFeedback(Component.literal("§c[Lolcat] Il prescan genera i chunk veri: va fatto in un mondo singleplayer con lo stesso seed e versione del server."));
            return 0;
        }
        String name = structure.toLowerCase();
        if (!WorldLootScanner.isTarget(name)) {
            source.sendFeedback(Component.literal("§c[Lolcat] Struttura non valida. Valide: " + String.join(", ", WorldLootScanner.targetNames())));
            return 0;
        }
        long seed = SeedStorage.getSavedSeed();
        int sx = p.getBlockX(), sz = p.getBlockZ();
        source.sendFeedback(Component.literal("§e[Lolcat] Prescan di " + count + "x " + name + " in corso (può richiedere minuti, il gioco potrebbe rallentare)..."));

        long[] last = {0};
        Thread t = new Thread(() -> {
            int added = WorldLootScanner.prescan(seed, Set.of(name), sx, sz, count, msg -> {
                long now = System.currentTimeMillis();
                if (now - last[0] < 8000) return;
                last[0] = now;
                mc.execute(() -> {
                    if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[Lolcat] " + msg));
                });
            });
            mc.execute(() -> {
                if (mc.player == null) return;
                String msg = added == -1 ? "§cServe un mondo singleplayer."
                        : added == -2 ? "§cIl seed del mondo singleplayer non coincide con quello salvato."
                        : "§a[Lolcat] Prescan finito: " + added + " nuovi contenitori salvati. Su un server con questo seed usa /lolcat find e /lolcat loot.";
                mc.player.sendSystemMessage(Component.literal(msg));
            });
        }, "Lolcat-Prescan");
        t.setDaemon(true);
        t.start();
        return 1;
    }
}
