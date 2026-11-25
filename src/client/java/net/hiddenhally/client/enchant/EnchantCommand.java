package net.hiddenhally.client.enchant;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;

import java.util.Set;
import java.util.TreeSet;

/** /lolcat enchant <incantesimo> [livello]   |   /lolcat enchant clear */
public final class EnchantCommand {

    private EnchantCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("lolcat")
                .then(ClientCommands.literal("enchant")
                        .then(ClientCommands.literal("clear").executes(context -> {
                            EnchantTarget.clear();
                            context.getSource().sendFeedback(Component.literal("§e[Lolcat] Obiettivi incantesimo rimossi."));
                            return 1;
                        }))
                        // /lolcat enchant rng [reset | cost <azione> <chiamate> | learn <azione> <quante>]
                        .then(ClientCommands.literal("rng")
                                .executes(context -> {
                                    var src = context.getSource();
                                    src.sendFeedback(Component.literal("§b[Lolcat] RNG: " + EnchantRngTracker.message()));
                                    src.sendFeedback(Component.literal("§7Costi azioni (chiamate RNG): " + EnchantRngTracker.costs()));
                                    return 1;
                                })
                                .then(ClientCommands.literal("reset").executes(context -> {
                                    EnchantRngTracker.reset();
                                    context.getSource().sendFeedback(Component.literal("§e[Lolcat] Tracker RNG azzerato."));
                                    return 1;
                                }))
                                .then(ClientCommands.literal("cost")
                                        .then(ClientCommands.argument("action", StringArgumentType.word())
                                                .then(ClientCommands.argument("calls", IntegerArgumentType.integer(0, 64))
                                                        .executes(context -> {
                                                            String name = StringArgumentType.getString(context, "action");
                                                            int calls = IntegerArgumentType.getInteger(context, "calls");
                                                            EnchantRngTracker.setCost(name, calls);
                                                            context.getSource().sendFeedback(Component.literal("§a[Lolcat] Costi: " + EnchantRngTracker.costs()));
                                                            return 1;
                                                        }))))
                                .then(ClientCommands.literal("learn")
                                        .then(ClientCommands.argument("action", StringArgumentType.word())
                                                .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 64))
                                                        .executes(context -> {
                                                            String name = StringArgumentType.getString(context, "action");
                                                            int count = IntegerArgumentType.getInteger(context, "count");
                                                            EnchantRngTracker.learn(name, count);
                                                            context.getSource().sendFeedback(Component.literal("§a[Lolcat] Fai " + count
                                                                    + "x '" + name + "' e poi incanta un oggetto: misuro quante chiamate consuma."));
                                                            return 1;
                                                        })))))
                        // /lolcat enchant add <incantesimo> [livello]  -> aggiunge un obiettivo
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("enchantment", StringArgumentType.word())
                                        .suggests(SUGGEST_ENCHANTMENTS)
                                        .executes(context -> change(context.getSource(),
                                                StringArgumentType.getString(context, "enchantment"), 1, true))
                                        .then(ClientCommands.argument("level", IntegerArgumentType.integer(1, 10))
                                                .executes(context -> change(context.getSource(),
                                                        StringArgumentType.getString(context, "enchantment"),
                                                        IntegerArgumentType.getInteger(context, "level"), true)))))
                        // /lolcat enchant <incantesimo> [livello]  -> sostituisce gli obiettivi
                        .then(ClientCommands.argument("enchantment", StringArgumentType.word())
                                .suggests(SUGGEST_ENCHANTMENTS)
                                .executes(context -> change(context.getSource(),
                                        StringArgumentType.getString(context, "enchantment"), 1, false))
                                .then(ClientCommands.argument("level", IntegerArgumentType.integer(1, 10))
                                        .executes(context -> change(context.getSource(),
                                                StringArgumentType.getString(context, "enchantment"),
                                                IntegerArgumentType.getInteger(context, "level"), false))))));
    }

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_ENCHANTMENTS = (_, builder) -> {
        String rem = builder.getRemaining().toLowerCase().replace("minecraft:", "");
        for (String name : enchantmentNames()) {
            if (name.startsWith(rem)) builder.suggest(name);
        }
        return builder.buildFuture();
    };

    private static int change(FabricClientCommandSource source, String raw, int level, boolean add) {
        String id = raw.toLowerCase();
        if (!id.contains(":")) id = "minecraft:" + id;
        Set<String> valid = enchantmentNames();
        if (!valid.contains(id.replace("minecraft:", "")) && !valid.contains(id)) {
            source.sendFeedback(Component.literal("§c[Lolcat] Incantesimo sconosciuto: " + raw));
            return 0;
        }
        if (add) EnchantTarget.add(id, level);
        else EnchantTarget.set(id, level);
        source.sendFeedback(Component.literal("§a[Lolcat] Obiettivi: " + EnchantTarget.describe()
                + ". Apri il tavolo degli incantesimi per vedere il piano."));
        return 1;
    }

    /** Nomi (senza "minecraft:") degli incantesimi presenti nel mondo corrente, inclusi quelli dei datapack. */
    private static Set<String> enchantmentNames() {
        Set<String> names = new TreeSet<>();
        var level = Minecraft.getInstance().level;
        if (level == null) return names;
        for (Object key : level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).keySet()) {
            String s = key.toString();
            names.add(s.startsWith("minecraft:") ? s.substring(10) : s);
        }
        return names;
    }
}
