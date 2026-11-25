package net.hiddenhally.client.mixin;

import net.hiddenhally.client.config.SeedStorage;
import net.hiddenhally.client.finder.SeedFinderEngine;
import net.hiddenhally.client.util.ChunkbaseState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.gui.GuiMap;

import java.lang.reflect.Field;
import java.util.Optional;

@Mixin(GuiMap.class)
public class ChunkbaseOverlayMixin {

    @Unique private static Field cameraXField;
    @Unique private static Field cameraZField;
    @Unique private static Field scaleField;
    @Unique private static boolean reflectionInitialized = false;

    @Unique
    private static void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;
        try {
            cameraXField = GuiMap.class.getDeclaredField("cameraX");
            cameraXField.setAccessible(true);
            cameraZField = GuiMap.class.getDeclaredField("cameraZ");
            cameraZField.setAccessible(true);
            scaleField = GuiMap.class.getDeclaredField("scale");
            scaleField.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void renderChunkbaseGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!ChunkbaseState.isChunkbaseModeEnabled() || !SeedStorage.hasSavedSeed()) return;

        initReflection();
        Minecraft mc = Minecraft.getInstance();
        long seed = SeedStorage.getSavedSeed();

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        graphics.fill(10, 34, 195, 50, 0xCC111111);
        graphics.text(mc.font, Component.literal("§dChunkbase Overlay §7| §e" + seed), 14, 38, 0xFFFFFFFF, true);

        if (cameraXField == null || cameraZField == null || scaleField == null) return;

        try {
            double camX = cameraXField.getDouble(this);
            double camZ = cameraZField.getDouble(this);
            double scale = scaleField.getDouble(this);
            double guiScale = mc.getWindow().getGuiScale();

            double pixelsPerBlock = scale / guiScale;

            // Utilizza il metodo corretto basato su Cubiomes
            Optional<BlockPos> structureOpt = SeedFinderEngine.findNearestStructure(
                    seed, "village", (int) camX, (int) camZ, 10
            );

            if (structureOpt.isPresent()) {
                BlockPos pos = structureOpt.get();
                int drawX = (int) (screenWidth / 2.0 + (pos.getX() - camX) * pixelsPerBlock);
                int drawY = (int) (screenHeight / 2.0 + (pos.getZ() - camZ) * pixelsPerBlock);

                if (drawX >= 0 && drawX <= screenWidth && drawY >= 0 && drawY <= screenHeight) {
                    graphics.fill(drawX - 5, drawY - 5, drawX + 5, drawY + 5, 0xFF000000);
                    graphics.fill(drawX - 4, drawY - 4, drawX + 4, drawY + 4, 0xFFFF3366);
                    graphics.text(mc.font, Component.literal("Village"), drawX + 7, drawY - 4, 0xFFFFFFFF, true);
                }
            }
        } catch (Exception ignored) {
        }
    }
}