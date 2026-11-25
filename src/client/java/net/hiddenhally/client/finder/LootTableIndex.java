package net.hiddenhally.client.finder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controllo preliminare "questo oggetto può uscire da questa loot table?" usando i JSON vanilla
 * contenuti nel jar di Minecraft (data/minecraft/loot_table/...). Non genera nulla: legge solo
 * i nomi degli oggetti (seguendo anche i riferimenti ad altre tabelle).
 */
public final class LootTableIndex {

    public enum Verdict { YES, NO, UNKNOWN }

    private record Entry(Set<String> items, boolean incomplete) {}

    private static final Pattern ITEM_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TABLE_REF = Pattern.compile("\"value\"\\s*:\\s*\"(minecraft:[a-z0-9_./]+)\"");

    private static final Map<String, Entry> CACHE = new HashMap<>();

    private LootTableIndex() {
    }

    /** @param table es. "chests/ancient_city" (senza namespace e senza .json) */
    public static synchronized Verdict check(String table, String itemId) {
        Entry e = load(table, new HashSet<>());
        if (e == null) return Verdict.UNKNOWN;
        if (e.items.contains(itemId)) return Verdict.YES;
        return e.incomplete ? Verdict.UNKNOWN : Verdict.NO;
    }

    private static Entry load(String table, Set<String> visiting) {
        if (CACHE.containsKey(table)) return CACHE.get(table);
        if (!visiting.add(table)) return new Entry(Set.of(), false); // ciclo: niente di nuovo da aggiungere

        String json = read("/data/minecraft/loot_table/" + table + ".json");
        if (json == null) {
            CACHE.put(table, null);
            return null;
        }

        Set<String> items = new HashSet<>();
        boolean incomplete = false;

        Matcher m = ITEM_NAME.matcher(json);
        while (m.find()) {
            String name = m.group(1);
            if (name.startsWith("#")) incomplete = true; // tag di oggetti: non risolvibile qui
            else if (name.startsWith("minecraft:")) items.add(name);
        }

        Matcher refs = TABLE_REF.matcher(json);
        while (refs.find()) {
            String ref = refs.group(1).substring("minecraft:".length());
            Entry sub = load(ref, visiting);
            if (sub == null) {
                incomplete = true;
            } else {
                items.addAll(sub.items);
                incomplete |= sub.incomplete;
            }
        }

        Entry entry = new Entry(items, incomplete);
        CACHE.put(table, entry);
        return entry;
    }

    private static String read(String resource) {
        try (InputStream in = LootTableIndex.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
