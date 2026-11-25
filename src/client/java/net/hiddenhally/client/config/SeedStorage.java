package net.hiddenhally.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SeedStorage {
    private static final File CONFIG_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("lolcat_seeds.json")
            .toFile();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, Long> WORLD_SEEDS = new HashMap<>();
    private static Long lastUsedSeed = null;

    public static void load() {
        if (!CONFIG_FILE.exists()) return;
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("lastSeed") && !root.get("lastSeed").isJsonNull()) {
                lastUsedSeed = root.get("lastSeed").getAsLong();
            }
            if (root.has("worlds") && root.get("worlds").isJsonObject()) {
                JsonObject worlds = root.getAsJsonObject("worlds");
                for (String key : worlds.keySet()) {
                    WORLD_SEEDS.put(key, worlds.get(key).getAsLong());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void saveSeed(long seed) {
        lastUsedSeed = seed;
        String worldKey = getCurrentWorldKey();
        if (worldKey != null) {
            WORLD_SEEDS.put(worldKey, seed);
        }
        persistToDisk();
    }

    public static boolean hasSavedSeed() {
        String worldKey = getCurrentWorldKey();
        if (worldKey != null && WORLD_SEEDS.containsKey(worldKey)) {
            return true;
        }
        return lastUsedSeed != null;
    }

    public static long getSavedSeed() {
        String worldKey = getCurrentWorldKey();
        if (worldKey != null && WORLD_SEEDS.containsKey(worldKey)) {
            return WORLD_SEEDS.get(worldKey);
        }
        return lastUsedSeed != null ? lastUsedSeed : 0L;
    }

    private static String getCurrentWorldKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) {
            return "server_" + mc.getCurrentServer().ip;
        }
        if (mc.getSingleplayerServer() != null) {
            return "single_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        return null;
    }

    private static void persistToDisk() {
        JsonObject root = new JsonObject();
        if (lastUsedSeed != null) {
            root.addProperty("lastSeed", lastUsedSeed);
        }
        JsonObject worlds = new JsonObject();
        for (Map.Entry<String, Long> entry : WORLD_SEEDS.entrySet()) {
            worlds.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("worlds", worlds);

        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}