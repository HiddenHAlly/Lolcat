package net.hiddenhally.client.gui;

import net.hiddenhally.client.config.SeedStorage;
import net.hiddenhally.client.command.LootSearchRunner;
import net.hiddenhally.client.finder.LootSearchEngine;
import net.hiddenhally.client.finder.SeedFinderEngine;
import net.hiddenhally.client.util.WaypointManagerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;

public class SeedSearchScreen extends Screen {
    private final Screen parent;
    private EditBox seedBox;
    private EditBox targetBox;
    private EditBox countBox;
    private EditBox structuresBox;
    private int mode = 0; // 0 = bioma, 1 = struttura, 2 = loot
    private String feedbackMessage = "";

    public SeedSearchScreen(Screen parent) {
        super(Component.literal("Lolcat Seed Finder & Waypoints"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 2 - 55;

        this.seedBox = new EditBox(this.font, centerX - 100, startY, 200, 20, Component.literal("World Seed"));
        this.seedBox.setMaxLength(32);
        if (SeedStorage.hasSavedSeed()) {
            this.seedBox.setValue(String.valueOf(SeedStorage.getSavedSeed()));
        } else {
            this.seedBox.setHint(Component.literal("Inserisci il seed del mondo..."));
        }
        this.addRenderableWidget(this.seedBox);

        this.addRenderableWidget(
                Button.builder(getModeLabel(), btn -> {
                            this.mode = (this.mode + 1) % 3;
                            this.structuresBox.visible = this.mode == 2;
                            btn.setMessage(getModeLabel());
                            this.targetBox.setHint(Component.literal(switch (this.mode) {
                                case 1 -> "es. village";
                                case 2 -> "es. diamond";
                                default -> "es. cherry_grove";
                            }));
                        })
                        .bounds(centerX - 100, startY + 28, 200, 20)
                        .build()
        );

        this.targetBox = new EditBox(this.font, centerX - 100, startY + 56, 140, 20, Component.literal("Target"));
        this.targetBox.setMaxLength(64);
        this.targetBox.setHint(Component.literal("es. cherry_grove o village"));
        this.addRenderableWidget(this.targetBox);

        this.countBox = new EditBox(this.font, centerX + 45, startY + 56, 55, 20, Component.literal("Quantità"));
        this.countBox.setMaxLength(4);
        //this.countBox.setFilter(s -> s.matches("\\d{0,4}"));
        this.countBox.setValue("1");
        this.countBox.setHint(Component.literal("N"));
        this.addRenderableWidget(this.countBox);

        // Solo in modalità Loot: strutture in cui cercare (opzionale, separate da spazio)
        this.structuresBox = new EditBox(this.font, centerX - 100, startY + 84, 200, 20, Component.literal("Strutture"));
        this.structuresBox.setMaxLength(120);
        this.structuresBox.setHint(Component.literal("Strutture (opz.): village ancient_city..."));
        this.structuresBox.visible = this.mode == 2;
        this.addRenderableWidget(this.structuresBox);

        this.addRenderableWidget(
                Button.builder(Component.literal("Crea Waypoint e Cerca"), _ -> startSearch())
                        .bounds(centerX - 100, startY + 112, 130, 20)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("Indietro"), _ -> this.onClose())
                        .bounds(centerX + 35, startY + 112, 65, 20)
                        .build()
        );
    }

    private Component getModeLabel() {
        return Component.literal("Modalità: " + switch (this.mode) {
            case 1 -> "§bStruttura";
            case 2 -> "§6Loot (oggetto)";
            default -> "§aBioma";
        });
    }

    private void startSearch() {
        long parsedSeed;
        try {
            String raw = this.seedBox.getValue().trim();
            parsedSeed = raw.matches("-?\\d+") ? Long.parseLong(raw) : (long) raw.hashCode();
            SeedStorage.saveSeed(parsedSeed);
        } catch (NumberFormatException e) {
            this.feedbackMessage = "§cSeed non valido!";
            return;
        }

        String target = this.targetBox.getValue().trim();
        if (target.isEmpty()) {
            this.feedbackMessage = "§aSeed salvato! Inserisci un nome per cercare.";
            return;
        }

        int parsedCount = 1;
        try {
            String c = this.countBox.getValue().trim();
            if (!c.isEmpty()) parsedCount = Integer.parseInt(c);
        } catch (NumberFormatException ignored) {
        }
        final int cap = this.mode == 2 ? LootSearchEngine.MAX_AMOUNT : SeedFinderEngine.MAX_COUNT;
        final int count = Math.max(1, Math.min(parsedCount, cap));

        Minecraft mc = Minecraft.getInstance();
        int startX = mc.player != null ? mc.player.getBlockX() : 0;
        int startZ = mc.player != null ? mc.player.getBlockZ() : 0;
        if (this.mode == 2) {
            LootSearchRunner.start(target, count, LootSearchRunner.parseFilter(this.structuresBox.getValue()),
                    msg -> this.feedbackMessage = msg);
            return;
        }
        final boolean structure = this.mode == 1;
        final long finalSeed = parsedSeed;

        this.feedbackMessage = "§eRicerca di " + count + "x " + target + " in corso...";

        new Thread(() -> {
            List<BlockPos> found = structure
                    ? SeedFinderEngine.findNearestStructures(finalSeed, target, startX, startZ, 120, count)
                    : SeedFinderEngine.findNearestBiomes(finalSeed, target, startX, startZ, 8000, count);

            mc.execute(() -> {
                if (found.isEmpty()) {
                    this.feedbackMessage = "§cNessun risultato trovato.";
                    return;
                }
                var dimension = SeedFinderEngine.getDimensionKey(structure, target);
                boolean ok = WaypointManagerHelper.addWaypoints(found, target, dimension);
                BlockPos nearest = found.get(0);
                this.feedbackMessage = String.format("§a%d/%d trovati (più vicino X: %d, Z: %d)%s",
                        found.size(), count, nearest.getX(), nearest.getZ(), ok ? "" : " §e- waypoint non creati");
            });
        }, "Lolcat-GUI-Search").start();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 75, 0xFFFFFFFF);
        if (!this.feedbackMessage.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(this.feedbackMessage), this.width / 2, this.height / 2 + 85, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(this.parent);
    }
}