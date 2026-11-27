package net.hiddenhally.client.sort;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Preset salvati su disco (config/lolcat/sort_presets.json). */
public final class PresetStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Preset> PRESETS = new TreeMap<>();
    private static boolean loaded = false;

    private PresetStore() {
    }

    private static Path file() throws Exception {
        Path d = FabricLoader.getInstance().getConfigDir().resolve("lolcat");
        Files.createDirectories(d);
        return d.resolve("sort_presets.json");
    }

    private static synchronized void ensure() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("presets")) {
                JsonObject o = e.getAsJsonObject();
                Preset p = new Preset(o.get("name").getAsString());
                if (o.has("slots")) {
                    for (JsonElement g : o.getAsJsonArray("slots")) {
                        java.util.List<String> group = new java.util.ArrayList<>();
                        for (JsonElement it : g.getAsJsonArray()) group.add(it.getAsString());
                        if (!group.isEmpty()) p.slots.add(group);
                    }
                } else if (o.has("items")) { // formato vecchio: un oggetto per slot
                    for (JsonElement it : o.getAsJsonArray("items")) {
                        java.util.List<String> group = new java.util.ArrayList<>();
                        group.add(it.getAsString());
                        p.slots.add(group);
                    }
                }
                if (o.has("tie")) {
                    try {
                        p.tie = Preset.TieMode.valueOf(o.get("tie").getAsString());
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                PRESETS.put(Preset.normalize(p.name), p);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static synchronized void save() {
        try {
            JsonArray arr = new JsonArray();
            for (Preset p : PRESETS.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.name);
                o.addProperty("tie", p.tie.name());
                JsonArray slotsArr = new JsonArray();
                for (java.util.List<String> group : p.slots) {
                    JsonArray g = new JsonArray();
                    for (String id : group) g.add(id);
                    slotsArr.add(g);
                }
                o.add("slots", slotsArr);
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("presets", arr);
            Files.writeString(file(), GSON.toJson(root));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /** Copia dei preset, chiave = nome normalizzato (minuscolo). */
    public static synchronized Map<String, Preset> all() {
        ensure();
        return new LinkedHashMap<>(PRESETS);
    }

    public static synchronized Preset get(String name) {
        ensure();
        return PRESETS.get(Preset.normalize(name));
    }

    public static synchronized void put(Preset p) {
        ensure();
        PRESETS.put(Preset.normalize(p.name), p);
        save();
    }

    public static synchronized boolean delete(String name) {
        ensure();
        boolean removed = PRESETS.remove(Preset.normalize(name)) != null;
        if (removed) save();
        return removed;
    }
}
