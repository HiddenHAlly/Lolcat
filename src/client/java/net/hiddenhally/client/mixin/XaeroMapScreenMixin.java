package net.hiddenhally.client.mixin;

import net.hiddenhally.client.gui.SeedSearchScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.gui.GuiMap;

@Mixin(GuiMap.class)
public abstract class XaeroMapScreenMixin {

	@Inject(method = "init", at = @At("TAIL"))
	private void addLolcatButton(CallbackInfo ci) {
		if ((Object) this instanceof ScreenAccessor accessor) {
			Button seedFinderButton = Button.builder(Component.literal("Seed Finder"), _ -> {
						Minecraft mc = Minecraft.getInstance();
						Screen currentScreen = mc.gui.screen();
						mc.gui.setScreen(new SeedSearchScreen(currentScreen));
					})
					.bounds(10, 10, 90, 20)
					.build();

			accessor.invokeAddRenderableWidget(seedFinderButton);
		}
	}
}