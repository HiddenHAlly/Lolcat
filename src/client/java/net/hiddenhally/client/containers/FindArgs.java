package net.hiddenhally.client.containers;

import java.util.ArrayList;
import java.util.List;

/** Interpreta "diamond 5 \"iron ingot\" 64 cobblestone" in richieste (oggetto, quantità). */
public final class FindArgs {

    public record Req(String item, int count) {}

    private FindArgs() {
    }

    public static List<Req> parse(String raw) {
        List<String> tokens = tokenize(raw);
        List<Req> out = new ArrayList<>();
        String item = null;
        for (String t : tokens) {
            if (t.matches("\\d+")) {
                if (item != null) {
                    out.add(new Req(item, Math.max(1, Integer.parseInt(t))));
                    item = null;
                }
            } else {
                if (item != null) out.add(new Req(item, 1));
                item = normalize(t);
            }
        }
        if (item != null) out.add(new Req(item, 1));
        return out;
    }

    public static String normalize(String name) {
        String s = name.trim().toLowerCase().replace("minecraft:", "").replace(' ', '_');
        return s;
    }

    /** Divide per spazi rispettando le virgolette. */
    static List<String> tokenize(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char c : raw.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
