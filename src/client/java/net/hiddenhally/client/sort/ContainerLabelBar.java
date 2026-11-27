package net.hiddenhally.client.sort;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.hiddenhally.client.containers.ContainerCapture;
import net.hiddenhally.client.mixin.ScreenAccessor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;

/** Barra di testo sopra ogni baule/barile/shulker per dargli un'etichetta (= nome del preset che contiene). */
public final class ContainerLabelBar {
    private static EditBox bar = null;
    private static boolean suppress = false;

    private ContainerLabelBar() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            bar = null;
            if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
            int rows;
            if (acs.getMenu() instanceof ChestMenu chest) rows = chest.getRowCount();
            else if (acs.getMenu() instanceof ShulkerBoxMenu) rows = 3;
            else return;

            int imageHeight = 114 + rows * 18;
            int guiLeft = (screen.width - 176) / 2;
            int guiTop = (screen.height - imageHeight) / 2;
            int y = Math.max(2, guiTop - 20);

            EditBox box = new EditBox(client.font, guiLeft, y, 176, 16, Component.literal("Preset"));
            box.setMaxLength(32);
            box.setHint(Component.literal("Etichetta / preset di questo contenitore"));
            box.setResponder(text -> {
                if (!suppress) ContainerCapture.setLabel(text);
            });
            ((ScreenAccessor) screen).invokeAddRenderableWidget(box);
            bar = box;
        });
    }

    /** Mostra nella barra l'etichetta già salvata (chiamato quando si sa quale contenitore è aperto). */
    public static void applyLabel(String label) {
        if (bar == null) return;
        suppress = true;
        try {
            bar.setValue(label == null ? "" : label);
        } finally {
            suppress = false;
        }
    }
}
