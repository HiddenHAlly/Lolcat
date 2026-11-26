package net.hiddenhally.client.containers;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Schermata tipo inventario con TUTTI gli oggetti dei contenitori noti, quanti sono e con quali incantesimi.
 * Click sinistro su una voce = evidenzia il contenitore più vicino; click destro = evidenzia quanti servono per averli tutti.
 */
public class ContainerDbScreen extends Screen {
    private static final int CELL = 20;

    private EditBox search;
    private boolean showPredicted = true;
    private int scrollRows = 0;
    private List<ContainerIndex.Agg> view = List.of();
    private long viewVersion = -1;
    private String viewQuery = null;
    private boolean viewPredicted = true;
    private int gridX, gridY, cols, rows;

    public ContainerDbScreen() {
        super(Component.literal("Lolcat - Contenitori"));
    }

    @Override
    protected void init() {
        this.search = new EditBox(this.font, this.width / 2 - 100, 16, 200, 18, Component.literal("Cerca"));
        this.search.setMaxLength(60);
        this.search.setHint(Component.literal("Cerca oggetto o incantesimo..."));
        this.addRenderableWidget(this.search);

        int bottom = this.height - 26;
        int cx = this.width / 2;
        this.addRenderableWidget(Button.builder(predictedLabel(), btn -> {
                    this.showPredicted = !this.showPredicted;
                    btn.setMessage(predictedLabel());
                })
                .bounds(cx - 205, bottom, 120, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Pulisci evidenziazioni"), _ -> {
                    HighlightRenderer.clearAll(Minecraft.getInstance());
                })
                .bounds(cx - 80, bottom, 130, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Chiudi"), _ -> this.onClose())
                .bounds(cx + 55, bottom, 60, 20).build());

        this.cols = Math.max(1, Math.min(14, (this.width - 40) / CELL));
        this.gridX = (this.width - this.cols * CELL) / 2;
        this.gridY = 44;
        this.rows = Math.max(1, (this.height - this.gridY - 34) / CELL);
    }

    private Component predictedLabel() {
        return Component.literal("Previsti dal seed: " + (this.showPredicted ? "§aSI" : "§cNO"));
    }

    private void rebuild() {
        String q = this.search.getValue().trim().toLowerCase().replace(' ', '_');
        long v = ContainerDb.INDEX.version();
        if (v == viewVersion && q.equals(viewQuery) && showPredicted == viewPredicted) return;
        viewVersion = v;
        viewQuery = q;
        viewPredicted = showPredicted;

        Minecraft mc = Minecraft.getInstance();
        double px = mc.player == null ? 0 : mc.player.getX();
        double py = mc.player == null ? 0 : mc.player.getY();
        double pz = mc.player == null ? 0 : mc.player.getZ();
        List<ContainerIndex.Agg> all = ContainerDb.INDEX.aggregate(ContainerDb.currentDim(), showPredicted, px, py, pz);
        List<ContainerIndex.Agg> out = new ArrayList<>();
        for (ContainerIndex.Agg a : all) {
            if (q.isEmpty() || a.signature.toLowerCase().replace(' ', '_').contains(q)) out.add(a);
        }
        out.sort(Comparator.comparingInt((ContainerIndex.Agg a) -> -a.total).thenComparing(a -> a.signature));
        this.view = out;
        int maxScroll = Math.max(0, (int) Math.ceil(out.size() / (double) cols) - rows);
        this.scrollRows = Math.min(this.scrollRows, maxScroll);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        rebuild();

        int known = ContainerDb.INDEX.visible(ContainerDb.currentDim(), showPredicted).size();
        graphics.centeredText(this.font, Component.literal("Contenitori noti: " + known + "  |  Tipi di oggetto: " + view.size()),
                this.width / 2, 4, 0xFFFFFFFF);

        // pannello dietro la griglia
        graphics.fill(gridX - 4, gridY - 4, gridX + cols * CELL + 4, gridY + rows * CELL + 4, 0xCC1B1B1B);

        ContainerIndex.Agg hovered = null;
        int hx = 0, hy = 0;
        int first = scrollRows * cols;
        for (int i = 0; i < cols * rows; i++) {
            int idx = first + i;
            if (idx >= view.size()) break;
            ContainerIndex.Agg a = view.get(idx);
            int x = gridX + (i % cols) * CELL;
            int y = gridY + (i / cols) * CELL;
            graphics.fill(x, y, x + CELL - 2, y + CELL - 2, a.anyPredicted ? 0x66334477 : 0x66555555);

            Item item = ItemRecs.itemById(a.id);
            if (item != null) {
                ItemStack stack = new ItemStack(item, Math.min(a.total, 99999));
                graphics.item(stack, x + 1, y + 1);
                graphics.itemDecorations(this.font, stack, x + 1, y + 1);
            }
            if (!a.enchants.isEmpty()) graphics.fill(x + 1, y + 1, x + 5, y + 4, 0xFFAA44FF); // marcatore "incantato"

            if (mouseX >= x && mouseX < x + CELL - 2 && mouseY >= y && mouseY < y + CELL - 2) {
                hovered = a;
                hx = mouseX;
                hy = mouseY;
            }
        }

        if (view.isEmpty()) {
            graphics.centeredText(this.font, Component.literal("Niente da mostrare. Apri dei contenitori o usa /lolcat scan."),
                    this.width / 2, gridY + 20, 0xFFAAAAAA);
        }
        graphics.centeredText(this.font, Component.literal("Click sinistro: evidenzia il piu' vicino  |  Click destro: evidenzia quanti servono per averli tutti"),
                this.width / 2, this.height - 38, 0xFF888888);

        if (hovered != null) drawTooltip(graphics, hovered, hx, hy);
    }

    private void drawTooltip(GuiGraphicsExtractor graphics, ContainerIndex.Agg a, int mx, int my) {
        List<ClientTooltipComponent> lines = new ArrayList<>();
        Item item = ItemRecs.itemById(a.id);
        String name = item != null ? new ItemStack(item).getHoverName().getString() : a.id;
        lines.add(line("§f" + name));
        lines.add(line("§7Totale: §f" + a.total + " §7in §f" + a.containers + " §7contenitori"));
        for (String e : a.enchants) lines.add(line("§d" + e));
        if (a.nearest != null) {
            int[] p = a.nearest.positions.get(0);
            lines.add(line("§7Piu' vicino: §f" + p[0] + " " + p[1] + " " + p[2] + " §7(" + (int) Math.sqrt(a.nearestDistSq) + " blocchi)"));
            lines.add(line(a.nearest.predicted ? "§9Previsto dal seed" + (a.nearest.structure.isEmpty() ? "" : " (" + a.nearest.structure + ")") : "§aVisto di persona"));
        }
        graphics.tooltip(this.font, lines, mx, my, DefaultTooltipPositioner.INSTANCE, null, false);
    }

    private static ClientTooltipComponent line(String s) {
        return ClientTooltipComponent.create(Component.literal(s).getVisualOrderText());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        int button = event.button();
        if (button != 0 && button != 1) return false;
        double mx = event.x(), my = event.y();
        int first = scrollRows * cols;
        for (int i = 0; i < cols * rows; i++) {
            int idx = first + i;
            if (idx >= view.size()) break;
            int x = gridX + (i % cols) * CELL;
            int y = gridY + (i / cols) * CELL;
            if (mx >= x && mx < x + CELL - 2 && my >= y && my < y + CELL - 2) {
                ContainerIndex.Agg a = view.get(idx);
                int need = button == 0 ? 1 : a.total;
                String msg = ContainerCommand.addFind(Minecraft.getInstance(), a.id, a.enchants.isEmpty() ? null : a.signature, need);
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(Component.literal("[Lolcat] " + msg));
                }
                HighlightRenderer.tick(Minecraft.getInstance(), true);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, (int) Math.ceil(view.size() / (double) cols) - rows);
        this.scrollRows = Math.max(0, Math.min(maxScroll, this.scrollRows - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }
}
