package net.hiddenhally.client.sort;

import net.hiddenhally.client.containers.ContainerDb;
import net.hiddenhally.client.containers.ContainerCapture;
import net.hiddenhally.client.containers.ContainerRec;
import net.hiddenhally.client.containers.ContainerScanner;
import net.hiddenhally.client.containers.HighlightRenderer;
import net.hiddenhally.client.containers.ItemRec;
import net.hiddenhally.client.containers.ItemRecs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Il bot che ordina: scansiona i contenitori dell'area, sposta gli oggetti nel contenitore del loro preset
 * (a trasporti, usando il tuo inventario come carrello) e poi riordina ogni contenitore per priorità.
 *
 * Sicurezze: si ferma se subisci danni, non rompe/piazza blocchi (Baritone configurato di conseguenza),
 * apre i contenitori solo con una mano libera, sposta SOLO gli oggetti che ha preso lui e mai quelli
 * che avevi già nell'inventario, 1 azione ogni 2 tick.
 */
public final class SortBot {

    private enum State { IDLE, SCAN_PICK, TRANSFER_PICK, ORDER_PICK, MOVING, OPENING, WAIT_CONTENT, ACTING, FINISHING, COOLDOWN }

    private enum Purpose { SCAN, PICKUP, DEPOSIT, ORDER }

    /** kind: 0 = shift-click, 1 = pickup, 2 = pickup solo se hai un oggetto in mano (cursore). */
    private record Op(int kind, int slot) {}

    private static final double REACH = 4.3;
    private static final int MOVE_TIMEOUT = 2400;   // 2 minuti
    private static final int MAX_ROUNDS = 80;

    private static State state = State.IDLE;
    private static Purpose purpose = Purpose.SCAN;
    private static BlockPos target;
    private static int ticks;
    private static Navigator nav;
    private static InteractionHand hand = InteractionHand.MAIN_HAND;
    private static boolean moveStarted;
    private static boolean openSent;

    private static final List<BlockPos> containers = new ArrayList<>();
    private static final Set<BlockPos> scanned = new HashSet<>();
    private static final Set<BlockPos> ordered = new HashSet<>();
    private static final Map<String, Integer> carried = new HashMap<>();
    private static final Map<String, Integer> leftover = new HashMap<>();
    private static final Set<String> blocked = new HashSet<>();
    private static final Deque<Op> ops = new ArrayDeque<>();
    private static Map<String, Integer> invBefore = new HashMap<>();
    private static int opCooldown;
    private static int rounds;
    private static int movedStacks;
    private static int swapsDone;
    private static long startTime;

    private SortBot() {
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    /** Contenitore su cui il bot sta lavorando (serve per salvare dove sono i contenuti). */
    public static BlockPos currentTarget() {
        return switch (state) {
            case OPENING, WAIT_CONTENT, ACTING, FINISHING -> target;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ avvio / arresto

    /** @return messaggio di errore o null se partito. */
    public static String start(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "Entra prima in un mondo.";
        if (isRunning()) return "Il bot è già in esecuzione (/lolcat sort stop).";
        if (ContainerScanner.isBusy()) return "Una scansione è in corso, attendi.";
        if (!SortArea.complete()) return "Imposta l'area con /lolcat sort pos1 e /lolcat sort pos2.";
        if (!SortArea.dim().equals(ContainerDb.currentDim())) return "L'area è in un'altra dimensione.";
        if (PresetStore.all().isEmpty()) return "Non hai preset: /lolcat sort preset <nome>.";
        //if (mc.gui.screen() != null) return "Chiudi prima la schermata aperta.";
        if (SortArea.volume() > 400_000) return "Area troppo grande (max ~400k blocchi).";

        containers.clear();
        collectContainers(mc);
        if (containers.isEmpty()) return "Nessun contenitore (baule/barile/shulker) caricato nell'area.";

        scanned.clear();
        ordered.clear();
        carried.clear();
        leftover.clear();
        blocked.clear();
        ops.clear();
        rounds = 0;
        movedStacks = 0;
        swapsDone = 0;
        startTime = System.currentTimeMillis();
        nav = Navigator.create();
        state = State.SCAN_PICK;
        say(mc, "§e[Lolcat] Ordinamento avviato: " + containers.size() + " contenitori, movimento: " + nav.name()
                + ". Interrompi con /lolcat sort stop.");
        return null;
    }

    public static void stop(Minecraft mc, String reason) {
        if (state == State.IDLE) return;
        if (nav != null) nav.stop();
        HighlightRenderer.setTarget(null);
        if (mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?>) mc.player.closeContainer();
        state = State.IDLE;
        ops.clear();
        say(mc, reason);
    }

    private static void collectContainers(Minecraft mc) {
        int[] a = SortArea.min(), b = SortArea.max();
        Set<BlockPos> seen = new HashSet<>();
        for (int x = a[0]; x <= b[0]; x++) {
            for (int y = a[1]; y <= b[1]; y++) {
                for (int z = a[2]; z <= b[2]; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockEntity be = mc.level.getBlockEntity(pos);
                    if (!ContainerScanner.isScannable(be)) continue;
                    BlockPos other = ContainerCapture.neighborPos(mc.level, pos);
                    if (other != null && seen.contains(other)) continue; // baule doppio: una sola metà
                    seen.add(pos);
                    containers.add(pos);
                }
            }
        }
    }

    // ------------------------------------------------------------------ pianificazione (anche per /lolcat sort plan)

    /** Contenitori dell'area già noti, nel formato del pianificatore. */
    public static List<SortPlanner.Cont> knownContainers(Minecraft mc) {
        List<SortPlanner.Cont> out = new ArrayList<>();
        String dim = ContainerDb.currentDim();
        for (BlockPos pos : containers.isEmpty() ? collectNow(mc) : containers) {
            ContainerRec rec = ContainerDb.INDEX.findObserved(dim, pos.getX(), pos.getY(), pos.getZ());
            if (rec == null) continue;
            int[] p = rec.positions.get(0);
            out.add(new SortPlanner.Cont(rec.key(), rec.label, p[0] + 0.5, p[1] + 0.5, p[2] + 0.5, rec.items));
        }
        return out;
    }

    private static List<BlockPos> collectNow(Minecraft mc) {
        List<BlockPos> saved = new ArrayList<>(containers);
        containers.clear();
        collectContainers(mc);
        List<BlockPos> now = new ArrayList<>(containers);
        containers.clear();
        containers.addAll(saved);
        return now;
    }

    private static BlockPos posOfKey(String key) {
        // chiave = "dim@x,y,z"
        String c = key.substring(key.indexOf('@') + 1);
        String[] s = c.split(",");
        return new BlockPos(Integer.parseInt(s[0]), Integer.parseInt(s[1]), Integer.parseInt(s[2]));
    }

    // ------------------------------------------------------------------ tick

    public static void tick(Minecraft mc) {
        if (state == State.IDLE) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            state = State.IDLE;
            return;
        }
        if (p.hurtTime > 0) {
            stop(mc, "§c[Lolcat] Ordinamento interrotto: hai subito danni.");
            return;
        }

        switch (state) {
            case SCAN_PICK -> {
                BlockPos next = nextUnscanned(mc);
                if (next == null) {
                    state = State.TRANSFER_PICK;
                    say(mc, "§e[Lolcat] Scansione finita. Pianifico gli spostamenti...");
                    return;
                }
                beginVisit(mc, next, Purpose.SCAN);
            }
            case TRANSFER_PICK -> transferPick(mc);
            case ORDER_PICK -> orderPick(mc);
            case MOVING -> {
                ticks++;
                Vec3 center = Vec3.atCenterOf(target);
                if (p.getEyePosition().distanceToSqr(center) <= REACH * REACH) {
                    nav.stop();
                    HighlightRenderer.setTarget(null);
                    state = State.OPENING;
                    ticks = 0;
                    openSent = false;
                } else if (nav.autonomous() && !moveStarted) {
                    nav.goNear(mc, target);
                    moveStarted = true;
                } else if (nav.autonomous() && ticks % 100 == 0) {
                    nav.goNear(mc, target); // rilancia il percorso se si è fermato
                }
                if (ticks > MOVE_TIMEOUT) {
                    say(mc, "§c[Lolcat] Non riesco a raggiungere " + target.toShortString() + ": lo salto.");
                    skipTarget();
                }
            }
            case OPENING -> {
                if (!openSent) {
                    hand = p.getMainHandItem().isEmpty() ? InteractionHand.MAIN_HAND
                            : p.getOffhandItem().isEmpty() ? InteractionHand.OFF_HAND : null;
                    if (hand == null) {
                        stop(mc, "§c[Lolcat] Ordinamento interrotto: servono una mano libera per aprire i contenitori.");
                        return;
                    }
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false);
                    mc.gameMode.useItemOn(p, hand, hit);
                    openSent = true;
                    ticks = 0;
                } else if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
                    state = State.WAIT_CONTENT;
                    ticks = 0;
                } else if (++ticks > 30) {
                    say(mc, "§c[Lolcat] " + target.toShortString() + " non si apre: lo salto.");
                    skipTarget();
                }
            }
            case WAIT_CONTENT -> {
                if (++ticks >= 5) { // contenuto completo + etichetta caricata
                    buildOps(mc);
                    invBefore = inventoryCounts(p.getInventory());
                    opCooldown = 0;
                    state = State.ACTING;
                    ticks = 0;
                }
            }
            case ACTING -> {
                if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> acs)) {
                    skipTarget();
                    return;
                }
                if (--opCooldown > 0) return;
                if (ops.isEmpty()) {
                    state = State.FINISHING;
                    ticks = 0;
                    return;
                }
                Op op = ops.poll();
                AbstractContainerMenu menu = acs.getMenu();
                if (op.kind() == 0) {
                    mc.gameMode.handleContainerInput(menu.containerId, op.slot(), 0, ContainerInput.QUICK_MOVE, p);
                } else if (op.kind() == 1 || !menu.getCarried().isEmpty()) {
                    mc.gameMode.handleContainerInput(menu.containerId, op.slot(), 0, ContainerInput.PICKUP, p);
                }
                opCooldown = 2;
            }
            case FINISHING -> {
                if (++ticks >= 4) { // lascia sincronizzare il server prima di chiudere
                    finishVisit(mc);
                    p.closeContainer();
                    state = State.COOLDOWN;
                    ticks = 0;
                }
            }
            case COOLDOWN -> {
                if (++ticks >= 6) {
                    state = switch (purpose) {
                        case SCAN -> State.SCAN_PICK;
                        case PICKUP, DEPOSIT -> State.TRANSFER_PICK;
                        case ORDER -> State.ORDER_PICK;
                    };
                }
            }
            default -> {
            }
        }
    }

    private static void beginVisit(Minecraft mc, BlockPos pos, Purpose why) {
        target = pos;
        purpose = why;
        ticks = 0;
        moveStarted = false;
        state = State.MOVING;
        if (!nav.autonomous()) nav.goNear(mc, pos);
    }

    private static void skipTarget() {
        if (nav != null) nav.stop();
        HighlightRenderer.setTarget(null);
        if (target != null) {
            scanned.add(target);
            ordered.add(target);
        }
        if (mcPlayerHasContainerOpen()) Minecraft.getInstance().player.closeContainer();
        ops.clear();
        state = State.COOLDOWN;
        ticks = 0;
        if (purpose == Purpose.PICKUP || purpose == Purpose.DEPOSIT) rounds += 3;
    }

    private static boolean mcPlayerHasContainerOpen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?>;
    }

    // ------------------------------------------------------------------ scelta del prossimo contenitore

    private static BlockPos nextUnscanned(Minecraft mc) {
        String dim = ContainerDb.currentDim();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos pos : containers) {
            if (scanned.contains(pos)) continue;
            ContainerRec known = ContainerDb.INDEX.findObserved(dim, pos.getX(), pos.getY(), pos.getZ());
            if (known != null && known.time >= startTime - 60_000) { // letto da poco: basta
                scanned.add(pos);
                continue;
            }
            double d = mc.player.distanceToSqr(Vec3.atCenterOf(pos));
            if (d < bd) {
                bd = d;
                best = pos;
            }
        }
        return best;
    }

    private static void transferPick(Minecraft mc) {
        LocalPlayer p = mc.player;
        List<SortPlanner.Cont> all = knownContainers(mc);
        Map<String, Preset> presets = PresetStore.all();

        if (!carried.isEmpty()) { // qualcosa da consegnare
            SortPlanner.Cont best = null;
            double bd = Double.MAX_VALUE;
            for (var e : carried.entrySet()) {
                if (e.getValue() <= 0) continue;
                for (SortPlanner.Cont c : all) {
                    if (!SortPlanner.accepts(c, e.getKey(), presets, all, blocked)) continue;
                    if (blocked.contains(c.key())) continue;
                    double d = p.distanceToSqr(c.x(), c.y(), c.z());
                    if (d < bd) {
                        bd = d;
                        best = c;
                    }
                }
            }
            if (best != null && rounds < MAX_ROUNDS) {
                rounds++;
                beginVisit(mc, posOfKey(best.key()), Purpose.DEPOSIT);
                return;
            }
            for (var e : carried.entrySet()) if (e.getValue() > 0) leftover.merge(e.getKey(), e.getValue(), Integer::sum);
            carried.clear();
        }

        List<SortPlanner.Move> moves = SortPlanner.plan(all, presets, blocked);
        if (moves.isEmpty() || rounds >= MAX_ROUNDS || freeSlots(p.getInventory()) <= 0) {
            state = State.ORDER_PICK;
            return;
        }
        // contenitore sorgente più vicino con qualcosa da portare via
        String srcKey = null;
        double bd = Double.MAX_VALUE;
        for (SortPlanner.Move m : moves) {
            BlockPos pos = posOfKey(m.from());
            double d = p.distanceToSqr(Vec3.atCenterOf(pos));
            if (d < bd) {
                bd = d;
                srcKey = m.from();
            }
        }
        rounds++;
        beginVisit(mc, posOfKey(srcKey), Purpose.PICKUP);
    }

    private static void orderPick(Minecraft mc) {
        Map<String, Preset> presets = PresetStore.all();
        String dim = ContainerDb.currentDim();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos pos : containers) {
            if (ordered.contains(pos)) continue;
            ContainerRec rec = ContainerDb.INDEX.findObserved(dim, pos.getX(), pos.getY(), pos.getZ());
            if (rec == null) continue;
            if (!presets.containsKey(Preset.normalize(rec.label))) {
                ordered.add(pos);
                continue;
            }
            double d = mc.player.distanceToSqr(Vec3.atCenterOf(pos));
            if (d < bd) {
                bd = d;
                best = pos;
            }
        }
        if (best == null) {
            finish(mc);
            return;
        }
        beginVisit(mc, best, Purpose.ORDER);
    }

    private static void finish(Minecraft mc) {
        long secs = (System.currentTimeMillis() - startTime) / 1000;
        StringBuilder sb = new StringBuilder("§a[Lolcat] Ordinamento finito in " + secs + " s: "
                + movedStacks + " pile spostate, " + swapsDone + " scambi di riordino.");
        Map<String, Integer> rest = new HashMap<>(leftover);
        if (!rest.isEmpty()) sb.append(" §eRimasti nell'inventario: ").append(rest);
        if (!blocked.isEmpty()) sb.append(" §eContenitori pieni: ").append(blocked.size()).append(".");
        stopQuiet(mc);
        say(mc, sb.toString());
    }

    private static void stopQuiet(Minecraft mc) {
        if (nav != null) nav.stop();
        HighlightRenderer.setTarget(null);
        state = State.IDLE;
    }

    // ------------------------------------------------------------------ azioni dentro il contenitore

    private static void buildOps(Minecraft mc) {
        ops.clear();
        LocalPlayer p = mc.player;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> acs)) return;
        AbstractContainerMenu menu = acs.getMenu();
        String dim = ContainerDb.currentDim();
        ContainerRec rec = ContainerDb.INDEX.findObserved(dim, target.getX(), target.getY(), target.getZ());
        Map<String, Preset> presets = PresetStore.all();
        List<SortPlanner.Cont> all = knownContainers(mc);

        List<Slot> chestSlots = new ArrayList<>();
        List<Slot> invSlots = new ArrayList<>();
        for (Slot s : menu.slots) {
            if (s.container == p.getInventory()) {
                if (s.getContainerSlot() < 36) invSlots.add(s);
            } else {
                chestSlots.add(s);
            }
        }

        switch (purpose) {
            case SCAN -> {
            }
            case PICKUP -> {
                if (rec == null) return;
                Set<String> take = new HashSet<>();
                for (SortPlanner.Move m : SortPlanner.plan(all, presets, blocked)) if (m.from().equals(rec.key())) take.add(m.item());
                int free = freeSlots(p.getInventory());
                for (Slot s : chestSlots) {
                    ItemStack st = s.getItem();
                    if (st.isEmpty() || free <= 0) continue;
                    if (take.contains(ItemRecs.idOf(st))) {
                        ops.add(new Op(0, s.index));
                        free--;
                    }
                }
            }
            case DEPOSIT -> {
                if (rec == null) return;
                SortPlanner.Cont here = null;
                for (SortPlanner.Cont c : all) if (c.key().equals(rec.key())) here = c;
                if (here == null) return;
                Map<String, Integer> budget = new HashMap<>(carried);
                for (Slot s : invSlots) {
                    ItemStack st = s.getItem();
                    if (st.isEmpty()) continue;
                    String id = ItemRecs.idOf(st);
                    int left = budget.getOrDefault(id, 0);
                    if (left <= 0 || st.getCount() > left) continue;      // solo ciò che ha preso il bot
                    if (!SortPlanner.accepts(here, id, presets, all, blocked)) continue;
                    ops.add(new Op(0, s.index));
                    budget.put(id, left - st.getCount());
                }
            }
            case ORDER -> {
                if (rec == null) return;
                Preset preset = presets.get(Preset.normalize(rec.label));
                if (preset == null) return;
                List<SortPlanner.Stack> stacks = new ArrayList<>();
                for (Slot s : chestSlots) {
                    ItemStack st = s.getItem();
                    stacks.add(st.isEmpty() ? null : new SortPlanner.Stack(ItemRecs.idOf(st), st.getCount(), sigOf(st)));
                }
                SortPlanner.OrderPlan plan = SortPlanner.planOrder(stacks, preset, SortBot::maxStackOf, SortBot::isBlockItem);
                for (SortPlanner.OrderOp op : plan.ops()) ops.add(new Op(op.kind(), chestSlots.get(op.slot()).index));
                swapsDone += plan.swaps() + plan.merges();
            }
        }
    }

    private static String sigOf(ItemStack st) {
        ItemRec r = ItemRecs.fromStack(st);
        if (!r.isEnchanted()) return "";
        List<String> e = new ArrayList<>(r.enchants());
        java.util.Collections.sort(e);
        return String.join(",", e);
    }

    private static int maxStackOf(String id) {
        net.minecraft.world.item.Item it = ItemRecs.itemById(id);
        return it == null ? 64 : it.getDefaultMaxStackSize();
    }

    private static boolean isBlockItem(String id) {
        return ItemRecs.itemById(id) instanceof net.minecraft.world.item.BlockItem;
    }

    private static void finishVisit(Minecraft mc) {
        LocalPlayer p = mc.player;
        Map<String, Integer> after = inventoryCounts(p.getInventory());
        switch (purpose) {
            case SCAN -> scanned.add(target);
            case ORDER -> ordered.add(target);
            case PICKUP -> {
                scanned.add(target);
                int gained = 0;
                for (var e : after.entrySet()) {
                    int g = e.getValue() - invBefore.getOrDefault(e.getKey(), 0);
                    if (g > 0) {
                        carried.merge(e.getKey(), g, Integer::sum);
                        gained += g;
                    }
                }
                if (gained > 0) movedStacks++;
            }
            case DEPOSIT -> {
                int moved = 0;
                for (var e : invBefore.entrySet()) {
                    int m = e.getValue() - after.getOrDefault(e.getKey(), 0);
                    if (m > 0 && carried.containsKey(e.getKey())) {
                        int left = carried.get(e.getKey()) - m;
                        if (left > 0) carried.put(e.getKey(), left);
                        else carried.remove(e.getKey());
                        moved += m;
                    }
                }
                if (moved == 0) { // niente è entrato: contenitore pieno o che non accetta
                    ContainerRec rec = ContainerDb.INDEX.findObserved(ContainerDb.currentDim(),
                            target.getX(), target.getY(), target.getZ());
                    if (rec != null) blocked.add(rec.key());
                    say(mc, "§e[Lolcat] " + target.toShortString() + " non accetta altri oggetti (pieno?).");
                } else {
                    movedStacks++;
                }
            }
        }
    }

    // ------------------------------------------------------------------ utilità

    private static int freeSlots(Inventory inv) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) n++;
        return n;
    }

    private static Map<String, Integer> inventoryCounts(Inventory inv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) m.merge(ItemRecs.idOf(s), s.getCount(), Integer::sum);
        }
        return m;
    }

    private static void say(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(msg));
    }

    /** Sommario testuale del piano (per /lolcat sort plan). */
    public static List<String> describePlan(Minecraft mc) {
        List<String> out = new ArrayList<>();
        List<SortPlanner.Cont> all = knownContainers(mc);
        List<BlockPos> inArea = collectNow(mc);
        int unknown = inArea.size() - all.size();
        out.add("§e[Lolcat] Contenitori nell'area: " + inArea.size() + " (letti: " + all.size()
                + (unknown > 0 ? ", §cda scansionare: " + unknown + "§e" : "") + ")");
        Map<String, Preset> presets = PresetStore.all();
        int labeled = 0;
        for (SortPlanner.Cont c : all) if (presets.containsKey(Preset.normalize(c.label()))) labeled++;
        out.add("§7Con etichetta di un preset: " + labeled + ". Etichette = nome del preset, scrivile nella barra sopra il baule.");
        Map<String, Integer> byRoute = new HashMap<>();
        for (SortPlanner.Move m : SortPlanner.plan(all, presets, blocked)) {
            byRoute.merge(m.item() + ": " + shortKey(m.from()) + " -> " + shortKey(m.to()), m.count(), Integer::sum);
        }
        if (byRoute.isEmpty()) out.add("§aNessuno spostamento necessario (restano solo i riordini interni).");
        List<String> lines = new ArrayList<>();
        for (var e : byRoute.entrySet()) lines.add("§7 " + e.getKey() + " x" + e.getValue());
        lines.sort(Comparator.naturalOrder());
        for (int i = 0; i < Math.min(12, lines.size()); i++) out.add(lines.get(i));
        if (lines.size() > 12) out.add("§7 ... e altri " + (lines.size() - 12) + " spostamenti");
        return out;
    }

    private static String shortKey(String key) {
        return key.substring(key.indexOf('@') + 1);
    }
}
