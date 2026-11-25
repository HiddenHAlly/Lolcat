package net.hiddenhally.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.minimap.world.MinimapWorldManager;
import xaero.hud.minimap.world.state.MinimapWorldState;
import xaero.hud.path.XaeroPath;

import java.io.IOException;
import java.util.List;

public class WaypointManagerHelper {

    // Indice di WaypointColor (14 = giallo)
    private static final int WAYPOINT_COLOR = 14;

    /** Aggiunge i waypoint nella dimensione in cui si trova il giocatore. */
    public static boolean addWaypoints(List<BlockPos> positions, String baseName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        return addWaypoints(positions, baseName, mc.level.dimension());
    }

    /** Aggiunge i waypoint nella dimensione indicata (es. Level.NETHER per un bioma del Nether). */
    public static boolean addWaypoints(List<BlockPos> positions, String baseName, ResourceKey<Level> dimension) {
        Minecraft mc = Minecraft.getInstance();
        try {
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session == null) {
                msg(mc, "§c[Lolcat] Xaero's Minimap non è attivo (sessione assente).");
                return false;
            }

            MinimapWorld world = getWaypointWorld(session, dimension);
            if (world == null) {
                msg(mc, "§c[Lolcat] Mondo Xaero non trovato per questa dimensione.");
                return false;
            }

            WaypointSet set = world.getWaypointSet("gui.xaero_default");
            if (set == null) set = world.getCurrentWaypointSet();

            String clean = baseName.toUpperCase().replace(" ", "_");
            String initial = clean.isEmpty() ? "X" : clean.substring(0, 1);

            int index = 1;
            for (BlockPos pos : positions) {
                set.add(new Waypoint(pos.getX(), pos.getY(), pos.getZ(),
                        clean + "_" + index++, initial, WAYPOINT_COLOR));
            }

            session.getWorldManagerIO().saveWorld(world);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            msg(mc, "§c[Lolcat] Waypoint aggiunti ma non salvati su disco.");
            return true; // sono comunque presenti in memoria
        } catch (Throwable t) {
            t.printStackTrace();
            msg(mc, "§c[Lolcat] Errore nell'aggiunta dei waypoint: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static MinimapWorld getWaypointWorld(MinimapSession session, ResourceKey<Level> dimension) {
        MinimapWorldManager worldManager = session.getWorldManager();
        MinimapWorldState state = session.getWorldState();

        XaeroPath path = state.getAutoRootContainerPath()
                .resolve(session.getDimensionHelper().getDimensionDirectoryName(dimension));

        // autoWorldPath è già assoluto: si aggiunge solo l'ultimo nodo
        // (evita l'overload ricorsivo di resolve(XaeroPath) di Xaero 26.5.3)
        XaeroPath autoWorld = state.getAutoWorldPath();
        if (autoWorld != null) {
            path = path.resolve(autoWorld.getLastNode());
        }
        return worldManager.getWorld(path);
    }

    private static void msg(Minecraft mc, String text) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
    }
}