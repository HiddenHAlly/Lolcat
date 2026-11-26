package net.hiddenhally.client.containers;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Apre e richiude uno alla volta i contenitori a portata di mano (come faresti tu col tasto destro) per
 * salvarne il contenuto senza doverli aprire manualmente.
 *
 * Sicurezze: solo a mano VUOTA (così non si piazzano blocchi), solo entro la portata normale (4.5 blocchi),
 * niente bauli con trappola (innescano redstone), e rifiuta di partire nel Deep Dark (sensori sculk/Warden).
 */
public final class ContainerScanner {

    private enum Phase { IDLE, WAIT_OPEN, WAIT_CONTENT, COOLDOWN }

    private static final double REACH = 4.5;
    private static final int RADIUS = 5;

    private static Phase phase = Phase.IDLE;
    private static final Deque<BlockPos> queue = new ArrayDeque<>();
    private static BlockPos target = null;
    private static int ticks = 0;
    private static int done = 0;
    private static int failed = 0;
    private static boolean auto = false;
    private static int autoTimer = 0;

    private ContainerScanner() {
    }

    public static BlockPos currentTarget() {
        return phase == Phase.WAIT_OPEN || phase == Phase.WAIT_CONTENT ? target : null;
    }

    public static boolean isAuto() {
        return auto;
    }

    public static void setAuto(boolean on) {
        auto = on;
        autoTimer = 0;
    }

    public static boolean isBusy() {
        return phase != Phase.IDLE || !queue.isEmpty();
    }

    /** Avvia una scansione. @return messaggio di errore o null se partita. */
    public static String start(Minecraft mc, boolean force, boolean onlyUnknown) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "Entra prima in un mondo.";
        if (isBusy()) return "Scansione già in corso.";
        if (mc.gui.screen() != null) return "Chiudi prima la schermata aperta.";
        if (!p.getMainHandItem().isEmpty()) return "Svuota la mano principale (serve per non piazzare blocchi per sbaglio).";
        if (!force && inDeepDark(mc)) {
            return "Sei nel Deep Dark: aprire contenitori può attivare i sensori sculk e il Warden. Usa /lolcat scan force se vuoi.";
        }

        int n = enqueueNearby(mc, onlyUnknown);
        if (n == 0) return onlyUnknown ? null : "Nessun contenitore a portata di mano.";
        done = 0;
        failed = 0;
        if (!auto) p.sendSystemMessage(Component.literal("§e[Lolcat] Scansione di " + n + " contenitori..."));
        return null;
    }

    private static boolean inDeepDark(Minecraft mc) {
        return mc.level.getBiome(mc.player.blockPosition()).unwrapKey()
                .map(Object::toString).orElse("").contains("deep_dark");
    }

    private static int enqueueNearby(Minecraft mc, boolean onlyUnknown) {
        LocalPlayer p = mc.player;
        BlockPos base = p.blockPosition();
        Vec3 eye = p.getEyePosition();
        String dim = ContainerDb.currentDim();
        int count = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    BlockEntity be = mc.level.getBlockEntity(pos);
                    if (!isScannable(be)) continue;
                    if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) continue;
                    ContainerRec known = ContainerDb.INDEX.findObserved(dim, pos.getX(), pos.getY(), pos.getZ());
                    if (known != null && (onlyUnknown || System.currentTimeMillis() - known.time < 3000)) continue;
                    if (alreadyQueuedHalf(mc, pos)) continue;
                    queue.add(pos);
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isScannable(BlockEntity be) {
        if (be instanceof TrappedChestBlockEntity) return false;
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity;
    }

    /** Un baule doppio si apre da una sola metà: salta l'altra. */
    private static boolean alreadyQueuedHalf(Minecraft mc, BlockPos pos) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos n = pos.relative(d);
            if (queue.contains(n) && mc.level.getBlockState(n).getBlock() == mc.level.getBlockState(pos).getBlock()
                    && mc.level.getBlockEntity(pos) instanceof ChestBlockEntity) return true;
        }
        return false;
    }

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;

        if (auto && phase == Phase.IDLE && queue.isEmpty() && mc.gui.screen() == null && ++autoTimer >= 40) {
            autoTimer = 0;
            if (p.getMainHandItem().isEmpty() && p.getDeltaMovement().horizontalDistanceSqr() < 0.0004 && !inDeepDark(mc)) {
                enqueueNearby(mc, true);
            }
        }

        switch (phase) {
            case IDLE -> {
                if (queue.isEmpty() || mc.gui.screen() != null) return;
                if (!p.getMainHandItem().isEmpty()) {
                    queue.clear();
                    p.sendSystemMessage(Component.literal("§c[Lolcat] Scansione interrotta: hai preso un oggetto in mano."));
                    return;
                }
                target = queue.poll();
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                phase = Phase.WAIT_OPEN;
                ticks = 0;
            }
            case WAIT_OPEN -> {
                if (ContainerCapture.isContainerOpen(mc)) {
                    phase = Phase.WAIT_CONTENT;
                    ticks = 0;
                } else if (++ticks > 25) { // bloccato (es. blocco sopra) o non apribile
                    failed++;
                    finishOne(mc);
                }
            }
            case WAIT_CONTENT -> {
                if (++ticks >= 4) { // il contenuto arriva col pacchetto successivo
                    p.closeContainer();
                    done++;
                    finishOne(mc);
                }
            }
            case COOLDOWN -> {
                if (++ticks >= 5) phase = Phase.IDLE;
            }
        }
    }

    private static void finishOne(Minecraft mc) {
        target = null;
        phase = Phase.COOLDOWN;
        ticks = 0;
        if (queue.isEmpty() && !auto && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§a[Lolcat] Scansione finita: " + done + " letti"
                    + (failed > 0 ? ", " + failed + " non apribili" : "") + ". /lolcat items per vedere tutto."));
        }
    }
}
