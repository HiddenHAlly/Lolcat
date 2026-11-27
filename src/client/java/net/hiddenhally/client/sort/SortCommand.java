package net.hiddenhally.client.sort;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.hiddenhally.client.containers.ContainerCapture;
import net.hiddenhally.client.containers.ContainerDb;
import net.hiddenhally.client.containers.ContainerRec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;

/**
 * /lolcat sort preset <nome>      apre l'editor (crea il preset se non c'è)
 * /lolcat sort presets | delete <nome>
 * /lolcat sort pos1 | pos2 | area clear   angoli dell'area (blocco che guardi, o quello sotto di te)
 * /lolcat sort label <preset|clear>       etichetta il contenitore che stai guardando
 * /lolcat sort plan | start | stop
 */
public final class SortCommand {

    private SortCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("lolcat")
                .then(ClientCommands.literal("sort")
                        .then(ClientCommands.literal("preset")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .suggests((_, builder) -> {
                                            for (Preset p : PresetStore.all().values()) {
                                                if (p.name.toLowerCase().startsWith(builder.getRemaining().toLowerCase())) builder.suggest(p.name);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> openPreset(StringArgumentType.getString(context, "name")))))
                        .then(ClientCommands.literal("presets").executes(context -> listPresets(context.getSource())))
                        .then(ClientCommands.literal("delete")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String name = StringArgumentType.getString(context, "name");
                                            context.getSource().sendFeedback(Component.literal(PresetStore.delete(name)
                                                    ? "§e[Lolcat] Preset eliminato." : "§c[Lolcat] Preset inesistente."));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("pos1").executes(context -> corner(context.getSource(), 1)))
                        .then(ClientCommands.literal("pos2").executes(context -> corner(context.getSource(), 2)))
                        .then(ClientCommands.literal("area")
                                .then(ClientCommands.literal("clear").executes(context -> {
                                    SortArea.clear();
                                    context.getSource().sendFeedback(Component.literal("§e[Lolcat] Area rimossa."));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("label")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .suggests((_, builder) -> {
                                            builder.suggest("clear");
                                            builder.suggest("misc");
                                            for (Preset p : PresetStore.all().values()) {
                                                if (p.name.toLowerCase().startsWith(builder.getRemaining().toLowerCase())) builder.suggest(p.name);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> label(context.getSource(), StringArgumentType.getString(context, "name")))))
                        .then(ClientCommands.literal("plan").executes(context -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (!SortArea.complete()) {
                                context.getSource().sendFeedback(Component.literal("§c[Lolcat] Imposta prima l'area (/lolcat sort pos1 e pos2)."));
                                return 0;
                            }
                            for (String line : SortBot.describePlan(mc)) context.getSource().sendFeedback(Component.literal(line));
                            return 1;
                        }))
                        .then(ClientCommands.literal("start").executes(context -> {
                            String err = SortBot.start(Minecraft.getInstance());
                            if (err != null) {
                                context.getSource().sendFeedback(Component.literal("§c[Lolcat] " + err));
                                return 0;
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(context -> {
                            SortBot.stop(Minecraft.getInstance(), "§e[Lolcat] Ordinamento fermato.");
                            return 1;
                        }))));
    }

    private static int openPreset(String rawName) {
        String name = rawName.trim();
        if (name.isEmpty()) return 0;
        Preset p = PresetStore.get(name);
        Preset toEdit = p != null ? p : new Preset(name);
        ContainerCapture.runNextTick(() -> Minecraft.getInstance().gui.setScreen(new PresetScreen(toEdit)));
        return 1;
    }

    private static int listPresets(FabricClientCommandSource src) {
        if (PresetStore.all().isEmpty()) {
            src.sendFeedback(Component.literal("§7[Lolcat] Nessun preset. Creane uno con /lolcat sort preset <nome>."));
            return 1;
        }
        for (Preset p : PresetStore.all().values()) {
            src.sendFeedback(Component.literal("§7 " + p.name + " §8(" + p.itemCount() + " oggetti)"));
        }
        return 1;
    }

    private static BlockPos lookedOrUnderFeet(Minecraft mc) {
        if (mc.hitResult instanceof BlockHitResult bhr && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return bhr.getBlockPos();
        }
        return mc.player.blockPosition().below();
    }

    private static int corner(FabricClientCommandSource src, int which) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        BlockPos pos = lookedOrUnderFeet(mc);
        SortArea.setCorner(which, pos, ContainerDb.currentDim());
        src.sendFeedback(Component.literal("§a[Lolcat] Angolo " + which + ": " + pos.toShortString()
                + (SortArea.complete() ? " §7(area di " + SortArea.volume() + " blocchi, disegnata in verde)" : "")));
        return 1;
    }

    private static int label(FabricClientCommandSource src, String raw) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        BlockPos pos = lookedOrUnderFeet(mc);
        ContainerRec rec = ContainerDb.INDEX.findObserved(ContainerDb.currentDim(), pos.getX(), pos.getY(), pos.getZ());
        if (rec == null) {
            src.sendFeedback(Component.literal("§c[Lolcat] Guarda un contenitore che hai già aperto o scansionato (/lolcat scan)."));
            return 0;
        }
        String label = raw.trim().equalsIgnoreCase("clear") ? "" : raw.trim();
        rec.label = label;
        ContainerDb.INDEX.touch();
        src.sendFeedback(Component.literal(label.isEmpty() ? "§e[Lolcat] Etichetta rimossa." : "§a[Lolcat] Etichetta: " + label));
        return 1;
    }
}
