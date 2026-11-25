package net.hiddenhally.client.enchant;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pannello accanto al tavolo degli incantesimi: previsione dei 3 slot e piano per l'incantesimo scelto. */
public final class EnchantOverlay {

    private record Line(String text, int color) {}

    private static volatile String manipKey = "";
    private static volatile List<Line> manipLines = List.of();
    private static volatile boolean manipComputing = false;
    private static String cacheKey = "";
    private static List<Line> cachedLines = List.of();

    private EnchantOverlay() {
    }

    public static void register() {
        // Fabric ricrea gli eventi per-schermo a ogni init: va registrato a ogni AFTER_INIT.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof EnchantmentScreen enchantScreen) {
                ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta) ->
                        draw(enchantScreen, graphics));
            }
        });
    }

    private static void draw(EnchantmentScreen screen, GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        EnchantmentMenu menu = screen.getMenu();

        ItemStack tableStack = menu.getSlot(0).getItem();
        int seed = menu.getEnchantmentSeed();
        EnchantRngTracker.observe(mc.player, seed);
        int[] shown = {menu.costs[0], menu.costs[1], menu.costs[2]};

        String key = seed + "|" + EnchantTarget.describe() + "|" + EnchantRngTracker.signature() + "|"
                + (tableStack.isEmpty() ? "-" : EnchantPredictor.itemId(tableStack)) + "|"
                + shown[0] + "," + shown[1] + "," + shown[2] + "|" + inventorySignature(mc);
        if (!key.equals(cacheKey)) {
            cacheKey = key;
            cachedLines = buildLines(mc, tableStack, seed, shown);
        }

        int guiLeft = (screen.width - 176) / 2;
        int guiTop = (screen.height - 166) / 2;
        int panelWidth = 0;
        for (Line l : cachedLines) panelWidth = Math.max(panelWidth, mc.font.width(l.text()));
        panelWidth += 8;
        int x = guiLeft + 176 + 6;
        if (x + panelWidth > screen.width) x = Math.max(2, guiLeft - panelWidth - 6);
        int y = guiTop;

        graphics.fill(x - 3, y - 3, x + panelWidth - 1, y + cachedLines.size() * 10 + 1, 0xCC111111);
        for (Line l : cachedLines) {
            graphics.text(mc.font, Component.literal(l.text()), x, y, l.color(), true);
            y += 10;
        }
    }

    private static List<Line> buildLines(Minecraft mc, ItemStack tableStack, int seed, int[] shown) {
        var access = mc.level.registryAccess();
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("Lolcat - Incantesimi (seed " + seed + ")", 0xFFFFFF55));

        var wants = EnchantTarget.wants();

        if (tableStack.isEmpty()) {
            lines.add(new Line("Metti un oggetto nel tavolo per vedere i 3 slot.", 0xFFAAAAAA));
        } else if (tableStack.isEnchanted()) {
            lines.add(new Line("L'oggetto e' gia' incantato.", 0xFFAAAAAA));
        } else {
            int shelves = EnchantPredictor.detectShelves(tableStack, seed, shown);
            lines.add(new Line("Scaffali: " + (shelves >= 0 ? shelves : "?"), 0xFFAAAAAA));
            for (int slot = 0; slot < 3; slot++) {
                if (shown[slot] <= 0) {
                    lines.add(new Line("[" + (slot + 1) + "] non disponibile", 0xFF777777));
                    continue;
                }
                List<EnchantPredictor.Pred> list = EnchantPredictor.listFor(access, tableStack, seed, slot, shown[slot]);
                StringBuilder sb = new StringBuilder("[" + (slot + 1) + "] Lv " + shown[slot] + ": ");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(list.get(i).text());
                }
                int matched = wants.isEmpty() ? 0 : EnchantPredictor.matchCount(list, wants);
                int color = 0xFFFFFFFF;
                if (matched > 0) color = matched == wants.size() ? 0xFFFFAA00 : 0xFFFFDD55;
                lines.add(new Line(sb.toString(), color));
            }
        }

        if (wants.isEmpty()) {
            lines.add(new Line("Obiettivo: nessuno  (/lolcat enchant <nome> [livello])", 0xFF777777));
            lines.add(new Line("Piu' obiettivi: /lolcat enchant add <nome> [livello]", 0xFF777777));
        } else {
            lines.add(new Line("Obiettivo: " + EnchantTarget.describe(), 0xFF55FFFF));
            List<EnchantPredictor.Plan> plans =
                    EnchantPredictor.plan(access, mc.player.getInventory(), tableStack, seed, wants);
            if (plans.isEmpty()) {
                lines.add(new Line("Nessuna combo con questo seed: incanta un oggetto qualsiasi", 0xFFFF5555));
                lines.add(new Line("(1 livello + 1 lapis) per generare un nuovo seed.", 0xFFFF5555));
            } else {
                boolean full = plans.get(0).matched() == wants.size();
                lines.add(full
                        ? new Line("Come ottenerli TUTTI con questo seed:", 0xFF55FF55)
                        : new Line("Nessuna combo completa. Migliori parziali (" + plans.get(0).matched() + "/" + wants.size() + "):", 0xFFFFAA00));
                int shownPlans = Math.min(plans.size(), 5);
                for (int i = 0; i < shownPlans; i++) {
                    EnchantPredictor.Plan p = plans.get(i);
                    StringBuilder sb = new StringBuilder(" - " + p.itemName() + " + " + p.shelves() + " scaffali, slot "
                            + (p.slot() + 1) + " (Lv " + p.cost() + "): ");
                    for (int j = 0; j < p.list().size(); j++) {
                        if (j > 0) sb.append(", ");
                        sb.append(p.list().get(j).text());
                    }
                    lines.add(new Line(sb.toString(), 0xFFFFFFFF));
                }
                if (!full) lines.add(new Line("Per averli tutti: incanta un oggetto per cambiare seed.", 0xFFAAAAAA));
            }
        }
        lines.addAll(rngLines(mc, access, tableStack, seed, shown, wants));
        return lines;
    }

    /** Stato dell'RNG del giocatore e, se sincronizzato, come manipolare il prossimo seed. */
    private static List<Line> rngLines(Minecraft mc, net.minecraft.core.RegistryAccess access, ItemStack tableStack,
                                       int seed, int[] shown, List<EnchantTarget.Want> wants) {
        List<Line> out = new ArrayList<>();
        out.add(new Line("RNG: " + EnchantRngTracker.message(), 0xFF8888FF));
        String learned = EnchantRngTracker.learnResult();
        if (!learned.isEmpty()) out.add(new Line(learned, 0xFF8888FF));

        Long state = EnchantRngTracker.state();
        if (wants.isEmpty() || state == null) return out;

        int shelves = tableStack.isEmpty() ? -1 : EnchantPredictor.detectShelves(tableStack, seed, shown);
        String key = state + "|" + EnchantTarget.describe() + "|" + shelves + "|" + EnchantRngTracker.costs()
                + "|" + inventorySignature(mc);
        if (!key.equals(manipKey) && !manipComputing) {
            manipComputing = true;
            manipKey = key;
            List<ItemStack> candidates = EnchantPredictor.collectCandidates(mc.player.getInventory(), tableStack);
            Thread t = new Thread(() -> {
                List<Line> res = new ArrayList<>();
                try {
                    List<EnchantManip.Manip> plans = EnchantManip.plan(access, candidates, state, wants, shelves);
                    if (plans.isEmpty()) {
                        res.add(new Line("Manipolazione: nessuna soluzione entro " + EnchantManip.MAX_CALLS
                                + " chiamate (cambia scaffali/oggetti o aggiungi azioni).", 0xFFFF5555));
                    } else {
                        res.add(new Line("Manipolazione (prima dell'incantesimo di scarto):", 0xFF55FF55));
                        for (EnchantManip.Manip m : plans) {
                            res.add(new Line(" * " + EnchantManip.describe(m.actions()) + "  (" + m.calls() + " chiamate)", 0xFFFFFFFF));
                            StringBuilder sb = new StringBuilder("   poi incanta un oggetto di scarto; dopo: " + m.plan().itemName()
                                    + " + " + m.plan().shelves() + " scaffali, slot " + (m.plan().slot() + 1)
                                    + " (Lv " + m.plan().cost() + "): ");
                            for (int j = 0; j < m.plan().list().size(); j++) {
                                if (j > 0) sb.append(", ");
                                sb.append(m.plan().list().get(j).text());
                            }
                            res.add(new Line(sb.toString(), 0xFFFFDD55));
                        }
                    }
                } catch (Throwable ex) {
                    ex.printStackTrace();
                    res.add(new Line("Manipolazione: errore (vedi log).", 0xFFFF5555));
                }
                manipLines = res;
                manipComputing = false;
                cacheKey = ""; // forza il ridisegno con il risultato
            }, "Lolcat-Enchant-Manip");
            t.setDaemon(true);
            t.start();
            manipLines = List.of(new Line("Manipolazione: calcolo in corso...", 0xFFAAAAAA));
        }
        out.addAll(manipLines);
        return out;
    }

    private static String inventorySignature(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) sb.append(EnchantPredictor.itemId(s)).append(s.isEnchanted() ? '*' : '.').append(';');
        }
        return sb.toString();
    }
}
