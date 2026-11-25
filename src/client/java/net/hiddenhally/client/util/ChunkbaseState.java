package net.hiddenhally.client.util;

public class ChunkbaseState {
    private static boolean chunkbaseModeEnabled = false;

    public static void toggleChunkbaseMode() {
        chunkbaseModeEnabled = !chunkbaseModeEnabled;
    }

    public static boolean isChunkbaseModeEnabled() {
        return chunkbaseModeEnabled;
    }
}