package net.hiddenhally.client.sort;

import net.hiddenhally.client.containers.ItemRecs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Editor di un preset. A sinistra un "baule doppio" virtuale (6x9): ogni cella è uno SLOT che può contenere
 * più oggetti (un gruppo, con sotto-priorità). A destra tutti gli oggetti del gioco con ricerca.
 *
 *  - click su una cella = la seleziona; click su un oggetto a destra = lo aggiunge allo slot selezionato
 *    (se nessuno è selezionato crea un nuovo slot in fondo)
 *  - sotto la griglia compare il gruppo dello slot selezionato: i tasti sinistra/destra (cambiabili in Controlli)
 *    cambiano la sotto-priorità dell'oggetto su cui hai il mouse; click destro lo toglie
 *  - su/giu' (cambiabili) spostano l'intero slot; click destro su uno slot lo elimina
 * Gli oggetti sono solo disegnati: non esistono nell'inventario né nel mondo.
 */
public class PresetScreen extends Screen {
    private static final int CELL = 18;
    private static final int COLS = 9;
    private static final int GRID_ROWS = 6;
    private static final int MAX_SLOTS = COLS * GRID_ROWS;
    private static final int MAX_GROUP = 18;

    private final String originalKey;
    private final Preset preset;
    private EditBox nameBox;
    private EditBox searchBox;
    private Button tieButton;
    private int paletteScroll = 0;
    private List<Item> palette = List.of();
    private String paletteQuery = null;
    private int gridX, gridY, palX, palY, palRows, stripX, stripY;
    private int selected = -1;
    private int hoverSlot = -1;
    private int hoverStrip = -1;

    public PresetScreen(Preset preset) {
        super(Component.literal("Lolcat - Preset di ordinamento"));
        this.preset = preset;
        this.originalKey = Preset.normalize(preset.name);
    }

    @Override
    protected void init() {
        this.gridX = this.width / 2 - COLS * CELL - 14;
        this.gridY = 56;
        this.palX = this.width / 2 + 14;
        this.palY = 56;
        this.palRows = Math.max(4, Math.min(12, (this.height - this.palY - 40) / CELL));

        this.nameBox = new EditBox(this.font, gridX, 30, COLS * CELL, 18, Component.literal("Nome"));
        this.nameBox.setMaxLength(32);
        this.nameBox.setValue(preset.name);
        this.nameBox.setHint(Component.literal("Nome del preset (= etichetta del baule)"));
        this.addRenderableWidget(this.nameBox);

        this.searchBox = new EditBox(this.font, palX, 30, COLS * CELL, 18, Component.literal("Cerca"));
        this.searchBox.setMaxLength(40);
        this.searchBox.setHint(Component.literal("Cerca un oggetto..."));
        this.addRenderableWidget(this.searchBox);

        int by = gridY + GRID_ROWS * CELL + 8;
        this.addRenderableWidget(Button.builder(Component.literal("Salva"), _ -> save())
                .bounds(gridX, by, 50, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Svuota"), _ -> {
                    preset.slots.clear();
                    selected = -1;
                })
                .bounds(gridX + 54, by, 50, 20).build());
        this.tieButton = this.addRenderableWidget(Button.builder(tieLabel(), btn -> {
                    preset.tie = preset.tie.next();
                    btn.setMessage(tieLabel());
                })
                .bounds(gridX + 108, by, COLS * CELL - 108, 20).build());

        this.stripX = gridX;
        this.stripY = by + 40;

        this.addRenderableWidget(Button.builder(Component.literal("Salva e chiudi"), _ -> {
                    save();
                    onClose();
                })
                .bounds(this.width / 2 - 60, this.height - 28, 120, 20).build());
    }

    private Component tieLabel() {
        return Component.literal("Pari priorita': " + switch (preset.tie) {
            case CATEGORY -> "categoria";
            case MATERIAL -> "materiale";
            case ALPHA -> "alfabetico";
        });
    }

    private void save() {
        String newName = nameBox.getValue().trim();
        if (!newName.isEmpty()) {
            if (!Preset.normalize(newName).equals(originalKey)) PresetStore.delete(originalKey);
            preset.name = newName;
        }
        PresetStore.put(preset);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§a[Lolcat] Preset '" + preset.name + "' salvato ("
                    + preset.slots.size() + " slot, " + preset.itemCount() + " oggetti). Dai questo nome a un baule per usarlo."));
        }
    }

    private void rebuildPalette() {
        String q = searchBox.getValue().trim().toLowerCase().replace(' ', '_');
        if (q.equals(paletteQuery)) return;
        paletteQuery = q;
        TreeMap<String, Item> sorted = new TreeMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            String id = idOf(item);
            if (id.equals("air")) continue;
            if (q.isEmpty() || id.contains(q)) sorted.put(id, item);
        }
        palette = new ArrayList<>(sorted.values());
        paletteScroll = 0;
    }

    private static String idOf(Item item) {
        String s = BuiltInRegistries.ITEM.getKey(item).toString();
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    private static Item itemById(String id) {
        return ItemRecs.itemById(id);
    }

    private static boolean inside(int mx, int my, int x, int y) {
        return mx >= x && mx < x + CELL - 1 && my >= y && my < y + CELL - 1;
    }

    private void tooltip(GuiGraphicsExtractor graphics, int mx, int my, List<String> lines) {
        List<ClientTooltipComponent> out = new ArrayList<>();
        for (String l : lines) out.add(ClientTooltipComponent.create(Component.literal(l).getVisualOrderText()));
        graphics.tooltip(this.font, out, mx, my, DefaultTooltipPositioner.INSTANCE, null, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        rebuildPalette();
        if (selected >= preset.slots.size()) selected = -1;

        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal("Slot in ordine di priorita' (alto-sinistra = massima)"), gridX, 20, 0xFFAAAAAA, false);
        graphics.text(this.font, Component.literal("Tutti gli oggetti (click = aggiungi)"), palX, 20, 0xFFAAAAAA, false);

        List<String> tip = null;
        int tipX = mouseX, tipY = mouseY;

        // griglia degli slot
        graphics.fill(gridX - 2, gridY - 2, gridX + COLS * CELL + 2, gridY + GRID_ROWS * CELL + 2, 0xCC222222);
        hoverSlot = -1;
        for (int i = 0; i < MAX_SLOTS; i++) {
            int x = gridX + (i % COLS) * CELL;
            int y = gridY + (i / COLS) * CELL;
            graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0xFF4A4A4A);
            if (i < preset.slots.size()) {
                List<String> group = preset.slots.get(i);
                Item first = itemById(group.get(0));
                if (first != null) graphics.item(new ItemStack(first), x + 1, y + 1);
                if (group.size() > 1) {
                    graphics.text(this.font, Component.literal(String.valueOf(group.size())), x + 11, y + 9, 0xFFFFFF55, true);
                }
            }
            if (i == selected) { // contorno arancione sullo slot selezionato
                graphics.fill(x - 1, y - 1, x + CELL, y, 0xFFFFAA00);
                graphics.fill(x - 1, y + CELL - 1, x + CELL, y + CELL, 0xFFFFAA00);
                graphics.fill(x - 1, y, x, y + CELL - 1, 0xFFFFAA00);
                graphics.fill(x + CELL - 1, y, x + CELL, y + CELL - 1, 0xFFFFAA00);
            }
            if (inside(mouseX, mouseY, x, y)) {
                graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0x55FFFFFF);
                if (i < preset.slots.size()) {
                    hoverSlot = i;
                    tip = new ArrayList<>();
                    tip.add("§fSlot " + (i + 1) + " di " + preset.slots.size());
                    List<String> group = preset.slots.get(i);
                    for (int k = 0; k < group.size(); k++) tip.add("§7" + (k + 1) + ". " + group.get(k));
                    tip.add("§8click: seleziona | destro: elimina slot");
                }
            }
        }

        // gruppo dello slot selezionato (sotto-priorità)
        hoverStrip = -1;
        if (selected >= 0) {
            List<String> group = preset.slots.get(selected);
            graphics.text(this.font, Component.literal("Slot " + (selected + 1) + ": ordine interno (sinistra = prima)"),
                    stripX, stripY - 12, 0xFFFFAA00, false);
            graphics.fill(stripX - 2, stripY - 2, stripX + COLS * CELL + 2, stripY + 2 * CELL + 2, 0xCC222222);
            for (int k = 0; k < MAX_GROUP; k++) {
                int x = stripX + (k % COLS) * CELL;
                int y = stripY + (k / COLS) * CELL;
                graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0xFF3A3A3A);
                if (k < group.size()) {
                    Item it = itemById(group.get(k));
                    if (it != null) graphics.item(new ItemStack(it), x + 1, y + 1);
                    graphics.text(this.font, Component.literal(String.valueOf(k + 1)), x + 1, y + 9, 0xFFFFFF55, true);
                    if (inside(mouseX, mouseY, x, y)) {
                        graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0x55FFFFFF);
                        hoverStrip = k;
                        tip = new ArrayList<>();
                        tip.add("§f" + (it != null ? new ItemStack(it).getHoverName().getString() : group.get(k)));
                        tip.add("§7Sotto-priorita' " + (k + 1) + " di " + group.size());
                        tip.add("§8" + SortKeys.SUB_LEFT.getTranslatedKeyMessage().getString() + " / "
                                + SortKeys.SUB_RIGHT.getTranslatedKeyMessage().getString() + ": sposta | click destro: togli");
                    }
                }
            }
        } else {
            graphics.text(this.font, Component.literal("Seleziona uno slot per aggiungervi piu' oggetti insieme."),
                    stripX, stripY - 12, 0xFF777777, false);
        }

        // palette
        graphics.fill(palX - 2, palY - 2, palX + COLS * CELL + 2, palY + palRows * CELL + 2, 0xCC222222);
        int first = paletteScroll * COLS;
        for (int i = 0; i < COLS * palRows; i++) {
            int idx = first + i;
            if (idx >= palette.size()) break;
            int x = palX + (i % COLS) * CELL;
            int y = palY + (i / COLS) * CELL;
            Item item = palette.get(idx);
            boolean already = preset.contains(idOf(item));
            graphics.fill(x, y, x + CELL - 1, y + CELL - 1, already ? 0xFF2F5F2F : 0xFF4A4A4A);
            graphics.item(new ItemStack(item), x + 1, y + 1);
            if (inside(mouseX, mouseY, x, y)) {
                graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0x55FFFFFF);
                tip = new ArrayList<>();
                tip.add("§f" + new ItemStack(item).getHoverName().getString());
                tip.add(selected >= 0
                        ? "§7Click: aggiungi allo slot " + (selected + 1) + (already ? " (lo sposta qui)" : "")
                        : "§7Click: nuovo slot in fondo" + (already ? " (lo sposta)" : ""));
            }
        }

        graphics.centeredText(this.font, Component.literal("Slot: "
                + SortKeys.UP.getTranslatedKeyMessage().getString() + "/" + SortKeys.DOWN.getTranslatedKeyMessage().getString()
                + " con il mouse sullo slot. Gruppo: "
                + SortKeys.SUB_LEFT.getTranslatedKeyMessage().getString() + "/" + SortKeys.SUB_RIGHT.getTranslatedKeyMessage().getString()
                + " con il mouse sull'oggetto. Tasti cambiabili in Controlli."), this.width / 2, this.height - 44, 0xFF888888);

        if (tip != null) tooltip(graphics, tipX, tipY, tip);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Debug:
        // System.out.println("[Lolcat] click " + event.x() + "," + event.y() + " btn=" + event.button()
        //         + " slots=" + preset.slots.size() + " selected=" + selected);
        if (super.mouseClicked(event, doubleClick)) return true;
        int mx = (int) event.x(), my = (int) event.y();
        int button = event.button();

        // slot della griglia
        for (int i = 0; i < preset.slots.size(); i++) {
            int x = gridX + (i % COLS) * CELL;
            int y = gridY + (i / COLS) * CELL;
            if (!inside(mx, my, x, y)) continue;
            if (button == 1) selected = (selected == i) ? -1 : i;
            else if (button == 3) {
                preset.slots.remove(i);
                if (selected == i) selected = -1;
                else if (selected > i) selected--;
            }
            return true;
        }
        // gruppo selezionato: click destro toglie l'oggetto
        if (selected >= 0 && button == 3) {
            List<String> group = preset.slots.get(selected);
            for (int k = 0; k < group.size(); k++) {
                int x = stripX + (k % COLS) * CELL;
                int y = stripY + (k / COLS) * CELL;
                if (inside(mx, my, x, y)) {
                    group.remove(k);
                    if (group.isEmpty()) {
                        preset.slots.remove(selected);
                        selected = -1;
                    }
                    return true;
                }
            }
        }
        // palette: click sinistro aggiunge
        if (button == 1) {
            int first = paletteScroll * COLS;
            for (int i = 0; i < COLS * palRows; i++) {
                int idx = first + i;
                if (idx >= palette.size()) break;
                int x = palX + (i % COLS) * CELL;
                int y = palY + (i / COLS) * CELL;
                if (inside(mx, my, x, y)) {
                    addItem(idOf(palette.get(idx)));
                    return true;
                }
            }
        }
        return false;
    }

    private void addItem(String id) {
        if (selected >= 0 && selected < preset.slots.size()) {
            List<String> target = preset.slots.get(selected);
            if (target.contains(id) || target.size() >= MAX_GROUP) return;
            preset.remove(id); // se l'oggetto era altrove (e quello slot si svuota) gli indici cambiano
            target.add(id);
            selected = -1;
            for (int i = 0; i < preset.slots.size(); i++) {
                if (preset.slots.get(i) == target) selected = i; // stesso oggetto lista, non solo uguale
            }
        } else if (preset.slots.size() < MAX_SLOTS || preset.contains(id)) {
            preset.addToSlot(-1, id);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (super.keyPressed(event)) return true;

        // sposta l'intero slot su/giu'
        int s = hoverSlot >= 0 ? hoverSlot : -1;
        if (s >= 0) {
            if (SortKeys.UP.matches(event) && s > 0) {
                swapSlots(s, s - 1);
                return true;
            }
            if (SortKeys.DOWN.matches(event) && s < preset.slots.size() - 1) {
                swapSlots(s, s + 1);
                return true;
            }
        }
        // sposta un oggetto dentro il gruppo selezionato
        if (selected >= 0 && hoverStrip >= 0) {
            List<String> g = preset.slots.get(selected);
            if (SortKeys.SUB_LEFT.matches(event) && hoverStrip > 0) {
                java.util.Collections.swap(g, hoverStrip, hoverStrip - 1);
                return true;
            }
            if (SortKeys.SUB_RIGHT.matches(event) && hoverStrip < g.size() - 1) {
                java.util.Collections.swap(g, hoverStrip, hoverStrip + 1);
                return true;
            }
        }
        return false;
    }

    private void swapSlots(int a, int b) {
        java.util.Collections.swap(preset.slots, a, b);
        if (selected == a) selected = b;
        else if (selected == b) selected = a;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, (int) Math.ceil(palette.size() / (double) COLS) - palRows);
        paletteScroll = Math.max(0, Math.min(maxScroll, paletteScroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }
}
