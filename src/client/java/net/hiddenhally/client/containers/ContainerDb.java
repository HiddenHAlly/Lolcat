package net.hiddenhally.client.containers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.hiddenhally.client.config.SeedStorage;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Contenitori noti (osservati per mondo/server, previsti per seed) e richieste "find" attive, con salvataggio su disco. */
public final class ContainerDb {
    public static final ContainerIndex INDEX = new ContainerIndex();
    public static final FindState FIND = new FindState();

    private static final Gson GSON = new GsonBuilder().create();
    private static String loadedWorld = null;
    private static Long loadedSeed = null;
    private static long savedVersion = -1;
    private static int saveTimer = 0;

    private ContainerDb() {
    }

    // ------------------------------------------------------------------ chiavi

    public static String currentWorldKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) return "server_" + mc.getCurrentServer().ip;
        if (mc.getSingleplayerServer() != null) return "single_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        return null;
    }

    public static String currentDim() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : mc.level.dimension().toString();
    }

    private static Path dir() throws IOException {
        Path d = FabricLoader.getInstance().getConfigDir().resolve("lolcat");
        Files.createDirectories(d);
        return d;
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ------------------------------------------------------------------ ciclo di vita (chiamato ogni tick)

    public static void tick(Minecraft mc) {
        String key = mc.level == null ? null : currentWorldKey();
        if (key != null && !key.equals(loadedWorld)) {
            save();
            INDEX.clearObserved();
            loadObserved(key);
            loadedWorld = key;
            savedVersion = INDEX.version();
        }
        if (key != null && SeedStorage.hasSavedSeed()) ensurePredicted(SeedStorage.getSavedSeed());
        if (++saveTimer >= 200) { // ogni ~10 secondi, solo se è cambiato qualcosa
            saveTimer = 0;
            if (INDEX.version() != savedVersion) save();
        }
    }

    public static void onDisconnect() {
        save();
        loadedWorld = null;
        INDEX.clearObserved();
    }

    /** Carica i contenitori previsti per questo seed (se non già caricati). */
    public static synchronized void ensurePredicted(long seed) {
        if (loadedSeed != null && loadedSeed == seed) return;
        INDEX.clearPredicted();
        loadedSeed = seed;
        try {
            Path f = dir().resolve("predicted_" + seed + ".json");
            if (Files.exists(f)) for (ContainerRec r : readFile(f)) INDEX.putPredicted(r);
        } catch (Exception e) {
            e.printStackTrace();
        }
        savedVersion = -1;
    }

    // ------------------------------------------------------------------ disco

    private static void loadObserved(String worldKey) {
        try {
            Path f = dir().resolve("containers_" + safe(worldKey) + ".json");
            if (Files.exists(f)) for (ContainerRec r : readFile(f)) INDEX.putObserved(r);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static synchronized void save() {
        try {
            if (loadedWorld != null) {
                writeFile(dir().resolve("containers_" + safe(loadedWorld) + ".json"), INDEX.allObserved());
            }
            if (loadedSeed != null) {
                writeFile(dir().resolve("predicted_" + loadedSeed + ".json"), INDEX.allPredicted());
            }
            savedVersion = INDEX.version();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void writeFile(Path file, List<ContainerRec> recs) throws IOException {
        JsonArray arr = new JsonArray();
        for (ContainerRec r : recs) arr.add(toJson(r));
        Files.writeString(file, GSON.toJson(arr));
    }

    private static List<ContainerRec> readFile(Path file) throws IOException {
        List<ContainerRec> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(Files.readString(file));
        for (JsonElement e : root.getAsJsonArray()) out.add(fromJson(e.getAsJsonObject()));
        return out;
    }

    private static JsonObject toJson(ContainerRec r) {
        JsonObject o = new JsonObject();
        o.addProperty("dim", r.dim);
        o.addProperty("t", r.time);
        o.addProperty("pred", r.predicted);
        o.addProperty("struct", r.structure);
        JsonArray pos = new JsonArray();
        for (int[] p : r.positions) for (int v : p) pos.add(v);
        o.add("pos", pos);
        JsonArray items = new JsonArray();
        for (ItemRec it : r.items) {
            JsonObject io = new JsonObject();
            io.addProperty("id", it.id());
            io.addProperty("n", it.count());
            if (it.isEnchanted()) {
                JsonArray ea = new JsonArray();
                for (String s : it.enchants()) ea.add(s);
                io.add("e", ea);
            }
            items.add(io);
        }
        o.add("items", items);
        return o;
    }

    private static ContainerRec fromJson(JsonObject o) {
        ContainerRec r = new ContainerRec();
        r.dim = o.get("dim").getAsString();
        r.time = o.get("t").getAsLong();
        r.predicted = o.get("pred").getAsBoolean();
        r.structure = o.has("struct") ? o.get("struct").getAsString() : "";
        JsonArray pos = o.getAsJsonArray("pos");
        for (int i = 0; i + 2 < pos.size(); i += 3) {
            r.positions.add(new int[]{pos.get(i).getAsInt(), pos.get(i + 1).getAsInt(), pos.get(i + 2).getAsInt()});
        }
        for (JsonElement ie : o.getAsJsonArray("items")) {
            JsonObject io = ie.getAsJsonObject();
            List<String> ench = new ArrayList<>();
            if (io.has("e")) for (JsonElement e : io.getAsJsonArray("e")) ench.add(e.getAsString());
            r.items.add(new ItemRec(io.get("id").getAsString(), io.get("n").getAsInt(), ench));
        }
        return r;
    }
}
