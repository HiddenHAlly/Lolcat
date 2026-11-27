package net.hiddenhally.client.sort;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Ordine "sensato" per gli oggetti con la stessa priorità, invece di un ordine casuale/alfabetico:
 * categoria (blocchi, lingotti/gemme, grezzi/minerali, attrezzi, altro) e materiale (ferro, oro, rame...).
 */
public final class SmartOrder {

    private static final Set<String> GEMS = Set.of("diamond", "emerald", "lapis_lazuli", "quartz", "amethyst_shard",
            "coal", "charcoal", "redstone", "netherite_scrap", "glowstone_dust", "echo_shard", "prismarine_shard",
            "prismarine_crystals", "nether_star", "heart_of_the_sea", "copper_ingot", "iron_ingot", "gold_ingot");

    /** Materiali in ordine "di progressione": è l'ordine in cui compaiono quando si ordina per materiale. */
    private static final List<String> MATERIAL_ORDER = List.of("coal", "charcoal", "copper", "iron", "gold", "redstone",
            "lapis", "emerald", "diamond", "quartz", "amethyst", "netherite");

    /** Parole che indicano un materiale ma solo se non ce n'è uno migliore (es. deepslate_iron_ore -> iron). */
    private static final Set<String> WEAK_MATERIALS = Set.of("stone", "deepslate", "cobblestone", "wooden", "leather", "chainmail");

    private static final List<String> TOOL_SUFFIX = List.of("_sword", "_pickaxe", "_axe", "_shovel", "_hoe",
            "_helmet", "_chestplate", "_leggings", "_boots", "_horse_armor", "_spear");

    private static final Set<String> CATEGORY_WORDS = Set.of("raw", "ingot", "nugget", "ore", "block", "deepslate");

    private SmartOrder() {
    }

    /** 0 = blocchi, 1 = lingotti/gemme, 2 = grezzi/minerali, 3 = attrezzi/armature, 4 = altro. */
    static int rank(String id, Predicate<String> isBlock) {
        if (id.startsWith("raw_") || id.endsWith("_ore")) return 2;
        if (id.endsWith("_ingot") || id.endsWith("_nugget") || GEMS.contains(id)) return 1;
        if (isBlock.test(id)) return 0;
        for (String s : TOOL_SUFFIX) if (id.endsWith(s)) return 3;
        return 4;
    }

    /** Materiale dell'oggetto ("iron" per raw_iron, iron_ore, deepslate_iron_ore, iron_block...). */
    static String family(String id) {
        String weak = null;
        for (String token : id.split("_")) {
            String t = token.equals("golden") ? "gold" : token.equals("lapis") ? "lapis" : token;
            if (MATERIAL_ORDER.contains(t)) return t;
            if (WEAK_MATERIALS.contains(token) && weak == null) weak = token.equals("wooden") ? "wood" : token;
        }
        if (weak != null) return weak;
        StringBuilder sb = new StringBuilder();
        for (String t : id.split("_")) {
            if (CATEGORY_WORDS.contains(t)) continue;
            if (sb.length() > 0) sb.append('_');
            sb.append(t);
        }
        return sb.length() == 0 ? id : sb.toString();
    }

    /** Posizione del materiale nell'ordine di progressione (i materiali sconosciuti vengono dopo, in ordine alfabetico). */
    private static int familyIndex(String family) {
        int i = MATERIAL_ORDER.indexOf(family);
        return i >= 0 ? i : 1000;
    }

    /** Dentro la stessa categoria e materiale: grezzo, poi minerale, poi minerale di deepslate. */
    private static int subRank(String id) {
        if (id.startsWith("raw_")) return 0;
        if (id.endsWith("_ore")) return id.startsWith("deepslate_") ? 2 : 1;
        return 0;
    }

    public static Comparator<String> comparator(Preset.TieMode mode, Predicate<String> isBlock) {
        Comparator<String> byId = Comparator.naturalOrder();
        Comparator<String> byRank = Comparator.comparingInt((String id) -> rank(id, isBlock));
        Comparator<String> byFamily = Comparator.comparingInt((String id) -> familyIndex(family(id)))
                .thenComparing(SmartOrder::family);
        Comparator<String> bySub = Comparator.comparingInt(SmartOrder::subRank);
        return switch (mode) {
            case CATEGORY -> byRank.thenComparing(byFamily).thenComparing(bySub).thenComparing(byId);
            case MATERIAL -> byFamily.thenComparing(byRank).thenComparing(bySub).thenComparing(byId);
            case ALPHA -> byId;
        };
    }

    /** Euristica senza registro di Minecraft (per i test): blocchi riconoscibili dal nome. */
    public static boolean guessIsBlock(String id) {
        return id.endsWith("_block") || id.endsWith("_planks") || id.endsWith("_log") || id.endsWith("_stone")
                || id.endsWith("_bricks") || id.endsWith("_slab") || id.endsWith("_stairs") || id.endsWith("_wood")
                || Set.of("stone", "cobblestone", "dirt", "sand", "gravel", "deepslate", "cobbled_deepslate",
                "granite", "diorite", "andesite", "tuff", "netherrack", "obsidian").contains(id);
    }
}
