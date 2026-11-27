package net.hiddenhally.client.sort;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Tasti personalizzabili (Opzioni > Controlli) per cambiare la priorità nell'editor dei preset. */
public final class SortKeys {
    public static KeyMapping UP;
    public static KeyMapping DOWN;
    public static KeyMapping SUB_LEFT;
    public static KeyMapping SUB_RIGHT;

    private SortKeys() {
    }

    public static void register() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("lolcat", "lolcat"));
        UP = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lolcat.priority_up", InputConstants.KEY_UP, category));
        DOWN = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lolcat.priority_down", InputConstants.KEY_DOWN, category));
        SUB_LEFT = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lolcat.sub_earlier", InputConstants.KEY_LEFT, category));
        SUB_RIGHT = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lolcat.sub_later", InputConstants.KEY_RIGHT, category));
    }
}
