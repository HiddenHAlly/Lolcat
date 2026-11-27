package net.hiddenhally.client.sort;

import net.hiddenhally.client.containers.HighlightRenderer;
import net.minecraft.core.BlockPos;

/** Zona del mondo (due angoli) in cui il bot cerca e ordina i contenitori. */
public final class SortArea {
    private static int[] p1;
    private static int[] p2;
    private static String dim = "";

    private SortArea() {
    }

    public static void setCorner(int which, BlockPos pos, String dimension) {
        if (!dimension.equals(dim)) {
            p1 = null;
            p2 = null;
            dim = dimension;
        }
        int[] v = {pos.getX(), pos.getY(), pos.getZ()};
        if (which == 1) p1 = v;
        else p2 = v;
        updateRender();
    }

    public static void clear() {
        p1 = null;
        p2 = null;
        updateRender();
    }

    public static boolean complete() {
        return p1 != null && p2 != null;
    }

    public static String dim() {
        return dim;
    }

    public static int[] min() {
        return new int[]{Math.min(p1[0], p2[0]), Math.min(p1[1], p2[1]), Math.min(p1[2], p2[2])};
    }

    public static int[] max() {
        return new int[]{Math.max(p1[0], p2[0]), Math.max(p1[1], p2[1]), Math.max(p1[2], p2[2])};
    }

    public static long volume() {
        if (!complete()) return 0;
        int[] a = min(), b = max();
        return (long) (b[0] - a[0] + 1) * (b[1] - a[1] + 1) * (b[2] - a[2] + 1);
    }

    private static void updateRender() {
        if (complete()) HighlightRenderer.setArea(min(), max());
        else HighlightRenderer.setArea(null, null);
    }
}
