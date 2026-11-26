package net.hiddenhally.client.containers;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conversioni tra ItemStack di Minecraft e i record salvati. */
public final class ItemRecs {

    private static Map<String, Item> itemsById;

    private ItemRecs() {
    }

    public static String idOf(ItemStack stack) {
        return ItemRec.stripNamespace(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    public static ItemRec fromStack(ItemStack stack) {
        return new ItemRec(idOf(stack), stack.getCount(), enchantsOf(stack));
    }

    public static List<String> enchantsOf(ItemStack stack) {
        List<String> out = new ArrayList<>();
        addAll(out, stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
        addAll(out, stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
        return out;
    }

    private static void addAll(List<String> out, ItemEnchantments enchants) {
        for (Object2IntMap.Entry<Holder<Enchantment>> e : enchants.entrySet()) {
            out.add(ItemRec.stripNamespace(e.getKey().getRegisteredName()) + " " + e.getIntValue());
        }
    }

    /** Contenuto di un contenitore con le varianti uguali unite. */
    public static List<ItemRec> fromContainer(Container container) {
        Map<String, ItemRec> merged = new LinkedHashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty()) continue;
            merge(merged, fromStack(s));
        }
        return new ArrayList<>(merged.values());
    }

    public static void merge(Map<String, ItemRec> into, ItemRec r) {
        into.merge(r.signature(), r, (a, b) -> new ItemRec(a.id(), a.count() + b.count(), a.enchants()));
    }

    /** Item di Minecraft dall'id senza namespace, o null. */
    public static synchronized Item itemById(String id) {
        if (itemsById == null) {
            itemsById = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) {
                itemsById.put(ItemRec.stripNamespace(BuiltInRegistries.ITEM.getKey(item).toString()), item);
            }
        }
        return itemsById.get(id);
    }

    public static synchronized java.util.Set<String> allItemIds() {
        itemById("air");
        return new java.util.TreeSet<>(itemsById.keySet());
    }
}
