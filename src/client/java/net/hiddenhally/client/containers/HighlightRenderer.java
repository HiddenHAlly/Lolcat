package net.hiddenhally.client.containers;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** Disegna i riquadri colorati attorno ai contenitori richiesti con /lolcat find e li aggiorna. */
public final class HighlightRenderer {
    private static final RenderStateDataKey<List<Line>> LINES_KEY = RenderStateDataKey.create(() -> "Lolcat highlights");
    private static volatile List<Line> lines = List.of();
    private static long lastVersion = -1;
    private static int timer = 0;
    private static final Set<FindState.Request> warnedMissing = new HashSet<>();

    private HighlightRenderer() {
    }

    public static void registerEvents() {
        LevelExtractionEvents.END_EXTRACTION.register(HighlightRenderer::extract);
        LevelRenderEvents.BEFORE_GIZMOS.register(HighlightRenderer::render);
    }

    // ------------------------------------------------------------------ aggiornamento (dal tick)

    public static void tick(Minecraft mc, boolean force) {
        if (mc.player == null || mc.level == null) return;
        if (!force && ++timer < 10 && ContainerDb.INDEX.version() == lastVersion) return;
        timer = 0;
        lastVersion = ContainerDb.INDEX.version();
        refresh(mc);
    }

    public static void refresh(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        FindState.Result res = ContainerDb.FIND.compute(ContainerDb.INDEX, ContainerDb.currentDim(), true,
                p.getX(), p.getY(), p.getZ(), (item, sig) -> inventoryCount(p.getInventory(), item, sig));

        for (FindState.Request done : res.completed()) {
            p.sendSystemMessage(Component.literal("§a[Lolcat] Completato: " + done.item + " (" + done.need + ")"));
            warnedMissing.remove(done);
        }
        for (FindState.Status st : res.statuses()) {
            if (st.found() < st.remaining() && warnedMissing.add(st.request())) {
                p.sendSystemMessage(Component.literal("§e[Lolcat] " + st.request().item + ": nei contenitori noti ne ho trovati solo "
                        + st.found() + " su " + st.remaining() + " che ti servono ancora."));
            }
        }

        List<Line> built = new ArrayList<>();
        double e = 0.003;
        for (FindState.Highlight h : res.highlights()) {
            double x0 = h.x() - e, y0 = h.y() - e, z0 = h.z() - e;
            double x1 = h.x() + 1 + e, y1 = h.y() + 1 + e, z1 = h.z() + 1 + e;
            int c = h.color();
            // 4 spigoli in basso, 4 in alto, 4 verticali
            built.add(new Line(new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), c));
            built.add(new Line(new Vec3(x0, y0, z0), new Vec3(x0, y0, z1), c));
            built.add(new Line(new Vec3(x1, y0, z1), new Vec3(x1, y0, z0), c));
            built.add(new Line(new Vec3(x1, y0, z1), new Vec3(x0, y0, z1), c));
            built.add(new Line(new Vec3(x0, y1, z0), new Vec3(x1, y1, z0), c));
            built.add(new Line(new Vec3(x0, y1, z0), new Vec3(x0, y1, z1), c));
            built.add(new Line(new Vec3(x1, y1, z1), new Vec3(x1, y1, z0), c));
            built.add(new Line(new Vec3(x1, y1, z1), new Vec3(x0, y1, z1), c));
            built.add(new Line(new Vec3(x0, y0, z0), new Vec3(x0, y1, z0), c));
            built.add(new Line(new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), c));
            built.add(new Line(new Vec3(x0, y0, z1), new Vec3(x0, y1, z1), c));
            built.add(new Line(new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), c));
        }
        lines = built;
    }

    public static void clearAll(Minecraft mc) {
        ContainerDb.FIND.clear();
        warnedMissing.clear();
        lines = List.of();
        lastVersion = -1;
    }

    static int inventoryCount(Inventory inv, String item, String sig) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !ItemRecs.idOf(s).equals(item)) continue;
            if (sig != null && sig.contains("|") && !ItemRecs.fromStack(s).signature().equals(sig)) continue;
            n += s.getCount();
        }
        return n;
    }

    // ------------------------------------------------------------------ rendering (come SeedMapper)

    private static void extract(LevelExtractionContext ctx) {
        List<Line> relative = new ArrayList<>();
        Vec3 cam = ctx.camera().position().scale(-1);
        for (Line l : lines) relative.add(l.offset(cam));
        ctx.levelState().setData(LINES_KEY, relative);
    }

    private static void render(LevelRenderContext context) {
        List<Line> extracted = context.levelState().getData(LINES_KEY);
        if (extracted == null) return;
        for (Line line : extracted) {
            Vec3 start = line.start();
            Vec3 end = line.end();
            Vec3 normal = end.subtract(start).normalize();
            int color = line.color();
            float red = ARGB.redFloat(color);
            float green = ARGB.greenFloat(color);
            float blue = ARGB.blueFloat(color);

            if (context.submitNodeCollector().order(0) instanceof SubmitNodeCollection collection) {
                collection.afterTerrain.submit(new CustomFeatureRenderer.Submit(context.poseStack().last().copy(),
                        NoDepthLayer.LINES_NO_DEPTH_LAYER, (pose, buffer) -> {
                    buffer.addVertex(pose, (float) start.x, (float) start.y, (float) start.z)
                            .setColor(red, green, blue, 1.0F)
                            .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z)
                            .setLineWidth(3);
                    buffer.addVertex(pose, (float) end.x, (float) end.y, (float) end.z)
                            .setColor(red, green, blue, 1.0F)
                            .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z)
                            .setLineWidth(3);
                }));
            }
        }
    }
}
