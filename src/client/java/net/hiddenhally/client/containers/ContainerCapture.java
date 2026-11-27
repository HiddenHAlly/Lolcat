package net.hiddenhally.client.containers;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.hiddenhally.client.sort.ContainerLabelBar;
import net.hiddenhally.client.sort.SortBot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Salva il contenuto di ogni baule/barile/shulker che apri (anche con la scansione automatica), e tiene
 * in ordine tick, evidenziazioni e salvataggio.
 */
public final class ContainerCapture {

    private static String lastUseDim = "";
    private static BlockPos lastUsePos = null;
    private static long lastUseTick = -1000;
    private static long tickCounter = 0;

    private static AbstractContainerMenu activeMenu = null;
    private static List<int[]> activePositions = null;
    private static String activeDim = "";
    private static int lastHash = Integer.MIN_VALUE;
    private static String pendingLabel = null;

    /** Azione da eseguire al prossimo tick (es. aprire una schermata dopo la chat). */
    private static Runnable deferred = null;

    private ContainerCapture() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            Minecraft mc = Minecraft.getInstance();
            if (level == mc.level) {
                lastUsePos = hit.getBlockPos();
                lastUseDim = ContainerDb.currentDim();
                lastUseTick = tickCounter;
            }
            return InteractionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(ContainerCapture::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ContainerDb.onDisconnect();
            ContainerDb.FIND.clear();
            HighlightRenderer.clearAll(client);
        });
    }

    public static void runNextTick(Runnable r) {
        deferred = r;
    }

    private static void tick(Minecraft mc) {
        tickCounter++;
        if (deferred != null) {
            Runnable r = deferred;
            deferred = null;
            r.run();
        }
        if (mc.level == null || mc.player == null) return;

        ContainerDb.tick(mc);
        ContainerScanner.tick(mc);
        SortBot.tick(mc);
        captureOpenContainer(mc);
        HighlightRenderer.tick(mc, false);
    }

    // ------------------------------------------------------------------ cattura

    private static void captureOpenContainer(Minecraft mc) {
        Screen screen = mc.gui.screen();
        if (screen instanceof AbstractContainerScreen<?> acs
                && (acs.getMenu() instanceof ChestMenu || acs.getMenu() instanceof ShulkerBoxMenu)) {
            AbstractContainerMenu menu = acs.getMenu();
            if (activeMenu != menu) {
                activeMenu = menu;
                lastHash = Integer.MIN_VALUE;
                activePositions = resolvePositions(mc);
                activeDim = ContainerDb.currentDim();
                pendingLabel = null;
                if (activePositions != null && !activePositions.isEmpty()) {
                    int[] p0 = activePositions.get(0);
                    ContainerRec known = ContainerDb.INDEX.findObserved(activeDim, p0[0], p0[1], p0[2]);
                    ContainerLabelBar.applyLabel(known == null ? "" : known.label);
                }
            }
            if (activePositions != null && !activePositions.isEmpty()) snapshot(mc, menu);
        } else {
            activeMenu = null;
            activePositions = null;
        }
    }

    private static List<int[]> resolvePositions(Minecraft mc) {
        BlockPos pos = ContainerScanner.currentTarget();
        if (pos == null) pos = SortBot.currentTarget();
        if (pos == null && lastUsePos != null && tickCounter - lastUseTick < 100) pos = lastUsePos;
        if (pos == null) return null;
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
        int[] other = doubleChestNeighbor(mc.level, pos);
        if (other != null) out.add(other);
        return out;
    }

    private static int[] doubleChestNeighbor(ClientLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.getBlock() instanceof ChestBlock && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction d = ChestBlock.getConnectedDirection(st);
            BlockPos n = pos.relative(d);
            return new int[]{n.getX(), n.getY(), n.getZ()};
        }
        return null;
    }

    private static void snapshot(Minecraft mc, AbstractContainerMenu menu) {
        Map<String, ItemRec> merged = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container == mc.player.getInventory()) continue; // slot dell'inventario del giocatore
            ItemStack s = slot.getItem();
            if (s.isEmpty()) continue;
            ItemRecs.merge(merged, ItemRecs.fromStack(s));
        }
        List<ItemRec> items = new ArrayList<>(merged.values());
        int hash = items.hashCode();
        if (hash == lastHash) return;
        lastHash = hash;

        int[] p = activePositions.get(0);
        ContainerRec rec = new ContainerRec(activeDim, p[0], p[1], p[2]);
        for (int i = 1; i < activePositions.size(); i++) rec.positions.add(activePositions.get(i));
        rec.items = items;
        rec.time = System.currentTimeMillis();
        rec.predicted = false;
        ContainerRec prev = ContainerDb.INDEX.findObserved(activeDim, p[0], p[1], p[2]);
        if (prev != null) {
            rec.structure = prev.structure;
            rec.label = prev.label;
        }
        if (pendingLabel != null) rec.label = pendingLabel;
        ContainerDb.INDEX.putObserved(rec);
    }

    /** Chiamato dalla barra etichetta: salva subito il nome scelto per il contenitore aperto. */
    public static void setLabel(String text) {
        pendingLabel = text.trim();
        if (activePositions != null && !activePositions.isEmpty()) {
            int[] p = activePositions.get(0);
            ContainerRec prev = ContainerDb.INDEX.findObserved(activeDim, p[0], p[1], p[2]);
            if (prev != null) {
                prev.label = pendingLabel;
                ContainerDb.INDEX.touch();
            }
        }
    }

    /** L'altra metà di un baule doppio (o null). */
    public static BlockPos neighborPos(ClientLevel level, BlockPos pos) {
        int[] n = doubleChestNeighbor(level, pos);
        return n == null ? null : new BlockPos(n[0], n[1], n[2]);
    }

    public static boolean isContainerOpen(Minecraft mc) {
        return mc.gui.screen() instanceof AbstractContainerScreen<?> acs
                && (acs.getMenu() instanceof ChestMenu || acs.getMenu() instanceof ShulkerBoxMenu);
    }
}
