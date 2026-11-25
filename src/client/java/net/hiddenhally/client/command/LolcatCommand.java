package net.hiddenhally.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.hiddenhally.client.config.SeedStorage;
import net.hiddenhally.client.finder.LootSearchEngine;
import net.hiddenhally.client.finder.SeedFinderEngine;
import net.hiddenhally.client.util.WaypointManagerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class LolcatCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("lolcat");

        // /lolcat biome <nome> [quantità]   e   /lolcat structure <nome> [quantità]
        // (letterali invece di un argomento "type": niente ambiguità con "loot")
        root.then(searchBranch("biome", SeedFinderEngine::getAvailableBiomes));
        root.then(searchBranch("structure", SeedFinderEngine::getAvailableStructures));

        // /lolcat loot <oggetto> [quantità] [strutture...]
        root.then(lootBranch());

        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> searchBranch(String type, Supplier<Set<String>> options) {
        SuggestionProvider<FabricClientCommandSource> suggestions = (_, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String option : options.get()) {
                if (option.toLowerCase().startsWith(remaining)) builder.suggest(option);
            }
            return builder.buildFuture();
        };

        return ClientCommands.literal(type)
                .then(ClientCommands.argument("name", StringArgumentType.word())
                        .suggests(suggestions)
                        .executes(context -> executeSearch(context.getSource(), type,
                                StringArgumentType.getString(context, "name"), 1))
                        .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, SeedFinderEngine.MAX_COUNT))
                                .executes(context -> executeSearch(context.getSource(), type,
                                        StringArgumentType.getString(context, "name"),
                                        IntegerArgumentType.getInteger(context, "count")))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> lootBranch() {
        return ClientCommands.literal("loot")
                .then(ClientCommands.argument("item", StringArgumentType.word())
                        .suggests((_, builder) -> {
                            String rem = builder.getRemaining().toLowerCase().replace("minecraft:", "");
                            for (String item : LootSearchEngine.getDroppableItemsIfReady()) {
                                if (item.startsWith(rem)) builder.suggest(item);
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> executeLoot(context.getSource(),
                                StringArgumentType.getString(context, "item"), 1, ""))
                        .then(ClientCommands.argument("amount", IntegerArgumentType.integer(1, LootSearchEngine.MAX_AMOUNT))
                                .executes(context -> executeLoot(context.getSource(),
                                        StringArgumentType.getString(context, "item"),
                                        IntegerArgumentType.getInteger(context, "amount"), ""))
                                // opzionale: strutture in cui cercare, separate da spazio
                                .then(ClientCommands.argument("structures", StringArgumentType.greedyString())
                                        .suggests(LolcatCommand::suggestStructures)
                                        .executes(context -> executeLoot(context.getSource(),
                                                StringArgumentType.getString(context, "item"),
                                                IntegerArgumentType.getInteger(context, "amount"),
                                                StringArgumentType.getString(context, "structures"))))));
    }

    /** Suggerisce solo l'ultima parola della lista (le strutture sono separate da spazio o virgola). */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestStructures(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        String rem = builder.getRemaining();
        int cut = Math.max(rem.lastIndexOf(' '), rem.lastIndexOf(','));
        String last = rem.substring(cut + 1).toLowerCase();
        SuggestionsBuilder sb = builder.createOffset(builder.getStart() + cut + 1);
        for (String name : LootSearchRunner.allStructureNames()) {
            if (name.startsWith(last)) sb.suggest(name);
        }
        return sb.buildFuture();
    }

    private static int executeLoot(FabricClientCommandSource source, String item, int amount, String structures) {
        return LootSearchRunner.start(item, amount, LootSearchRunner.parseFilter(structures),
                msg -> source.sendFeedback(Component.literal("[Lolcat] " + msg))) ? 1 : 0;
    }

    private static int executeSearch(FabricClientCommandSource source, String type, String name, int count) {
        boolean structure = type.equalsIgnoreCase("structure");
        if (!SeedStorage.hasSavedSeed()) {
            source.sendFeedback(Component.literal("§c[Lolcat] Nessun seed salvato! Apri la mappa di Xaero per impostarlo."));
            return 0;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return 0;

        long seed = SeedStorage.getSavedSeed();
        int startX = player.getBlockX();
        int startZ = player.getBlockZ();

        source.sendFeedback(Component.literal("§e[Lolcat] Ricerca di " + count + "x '" + name + "' con seed " + seed + "..."));

        new Thread(() -> {
            List<BlockPos> found = structure
                    ? SeedFinderEngine.findNearestStructures(seed, name, startX, startZ, 120, count)
                    : SeedFinderEngine.findNearestBiomes(seed, name, startX, startZ, 8000, count);

            Minecraft.getInstance().execute(() -> {
                if (found.isEmpty()) {
                    player.sendSystemMessage(Component.literal("§c[Lolcat] Nessun risultato trovato (nome sconosciuto o fuori raggio)."));
                    return;
                }

                var dimension = SeedFinderEngine.getDimensionKey(structure, name);
                boolean ok = WaypointManagerHelper.addWaypoints(found, name, dimension);

                player.sendSystemMessage(Component.literal(String.format(
                        "§a[Lolcat] Trovati %d/%d %s%s", found.size(), count, name,
                        ok ? " - waypoint creati (dal più vicino)" : " - waypoint NON creati")));

                int shown = Math.min(found.size(), 10);
                for (int i = 0; i < shown; i++) {
                    BlockPos p = found.get(i);
                    player.sendSystemMessage(Component.literal(String.format("§7 %d) X: %d, Z: %d", i + 1, p.getX(), p.getZ())));
                }
                if (found.size() > shown) {
                    player.sendSystemMessage(Component.literal("§7 ... e altri " + (found.size() - shown)));
                }
            });
        }, "Lolcat-Search-Thread").start();

        return 1;
    }
}