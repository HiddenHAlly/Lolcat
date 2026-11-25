package net.hiddenhally.client.odds;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * /lolcat odds block <blocco> [opzioni]    es. /lolcat odds block lapis_ore fortune=3
 * /lolcat odds mob <mob> [opzioni]         es. /lolcat odds mob zombie looting=3
 * /lolcat odds vault [ominous]
 * /lolcat odds table <percorso> [opzioni]  es. /lolcat odds table chests/ancient_city
 *
 * Opzioni: fortune=N looting=N silk shears fire noplayer dist, più chiave=valore per lo stato del blocco (age=7).
 */
public final class OddsCommand {

    private OddsCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("lolcat")
                .then(ClientCommands.literal("odds")
                        .then(branch("block", OddsCommand::blockNames, "blocks/", true, false))
                        .then(branch("mob", OddsCommand::mobNames, "entities/", false, true))
                        .then(ClientCommands.literal("vault")
                                .executes(context -> run(context.getSource(), "vault", "chests/trial_chambers/reward", "", null, false))
                                .then(ClientCommands.literal("ominous")
                                        .executes(context -> run(context.getSource(), "vault ominous",
                                                "chests/trial_chambers/reward_ominous", "", null, false))))
                        .then(ClientCommands.literal("table")
                                .then(ClientCommands.argument("path", StringArgumentType.word())
                                        .executes(context -> run(context.getSource(),
                                                StringArgumentType.getString(context, "path"),
                                                StringArgumentType.getString(context, "path"), "", null, false))
                                        .then(ClientCommands.argument("options", StringArgumentType.greedyString())
                                                .suggests(OPTION_SUGGESTIONS)
                                                .executes(context -> run(context.getSource(),
                                                        StringArgumentType.getString(context, "path"),
                                                        StringArgumentType.getString(context, "path"),
                                                        StringArgumentType.getString(context, "options"), null, false)))))));
    }

    private static final SuggestionProvider<FabricClientCommandSource> OPTION_SUGGESTIONS = (_, builder) -> {
        String rem = builder.getRemaining();
        int cut = Math.max(rem.lastIndexOf(' '), rem.lastIndexOf(','));
        String last = rem.substring(cut + 1).toLowerCase();
        var sb = builder.createOffset(builder.getStart() + cut + 1);
        for (String o : new String[]{"fortune=3", "looting=3", "silk", "shears", "fire", "noplayer", "dist", "age=7"}) {
            if (o.startsWith(last)) sb.suggest(o);
        }
        return sb.buildFuture();
    };

    private static ArgumentBuilder<FabricClientCommandSource, ?> branch(String literal, Supplier<Set<String>> names,
                                                                         String prefix, boolean fortune, boolean mob) {
        return ClientCommands.literal(literal)
                .then(ClientCommands.argument("id", StringArgumentType.word())
                        .suggests((_, builder) -> {
                            String rem = builder.getRemaining().toLowerCase().replace("minecraft:", "");
                            for (String n : names.get()) if (n.startsWith(rem)) builder.suggest(n);
                            return builder.buildFuture();
                        })
                        .executes(context -> run(context.getSource(),
                                StringArgumentType.getString(context, "id"),
                                prefix + clean(StringArgumentType.getString(context, "id")), "", fortune, mob))
                        .then(ClientCommands.argument("options", StringArgumentType.greedyString())
                                .suggests(OPTION_SUGGESTIONS)
                                .executes(context -> run(context.getSource(),
                                        StringArgumentType.getString(context, "id"),
                                        prefix + clean(StringArgumentType.getString(context, "id")),
                                        StringArgumentType.getString(context, "options"), fortune, mob))));
    }

    private static String clean(String id) {
        return id.toLowerCase().replace("minecraft:", "");
    }

    private static int run(FabricClientCommandSource source, String title, String table, String rawOptions,
                           Boolean varyFortune, boolean mob) {
        OddsReport.Options opt = OddsReport.parse(rawOptions, mob);
        for (String line : OddsReport.report(clean(title), table, opt, varyFortune)) {
            source.sendFeedback(Component.literal(line));
        }
        return 1;
    }

    private static Set<String> blockNames() {
        Set<String> out = new TreeSet<>();
        for (Object key : BuiltInRegistries.BLOCK.keySet()) out.add(clean(key.toString()));
        return out;
    }

    private static Set<String> mobNames() {
        Set<String> out = new TreeSet<>();
        for (Object key : BuiltInRegistries.ENTITY_TYPE.keySet()) out.add(clean(key.toString()));
        return out;
    }
}
