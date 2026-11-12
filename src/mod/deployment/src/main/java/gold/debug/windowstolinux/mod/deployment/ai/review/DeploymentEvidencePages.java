package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;

/** Exact pages of locally captured review evidence. Cursors count UTF-16 code units. */
public final class DeploymentEvidencePages {
    private DeploymentEvidencePages() {
    }

    public static Map<String, Object> page(String text, int offset, int limit) {
        if (offset < 0 || offset > text.length() || limit < 1 || splitsPair(text, offset))
            throw new IllegalArgumentException("Evidence page cursor outside its bound");
        int end = Math.min(text.length(), offset + limit);
        if (splitsPair(text, end)) {
            if (end == offset + 1) throw new IllegalArgumentException("Evidence page limit cannot include the next character");
            end--;
        }
        return Map.of("offset", offset, "next", end, "total", text.length(), "hasMore", end < text.length(),
            "sha256", sha256(text), "text", text.substring(offset, end));
    }

    static Map<String, Object> retainAndDescribe(String text, Map<String, Object> page, NavigableMap<Integer, Integer> retained) {
        int start = (int) page.get("offset");
        int end = (int) page.get("next");
        if (start == end) return page;
        var missing = new ArrayList<Map<String, Object>>();
        int cursor = start;
        int newCharacters = 0;
        for (var known : retained.headMap(end, false).entrySet()) {
            if (known.getValue() <= cursor) continue;
            if (known.getKey() > cursor) {
                int boundary = known.getKey();
                missing.add(range(text, cursor, boundary));
                newCharacters += boundary - cursor;
            }
            cursor = Math.min(end, known.getValue());
        }
        if (cursor < end) {
            missing.add(range(text, cursor, end));
            newCharacters += end - cursor;
        }
        remember(retained, start, end);
        if (end - start - newCharacters <= 64 + 48 * missing.size()) return page;
        var compact = new LinkedHashMap<>(page);
        compact.remove("text");
        compact.put("newRanges", missing);
        return compact;
    }

    private static Map<String, Object> range(String text, int offset, int next) {
        return Map.of("offset", offset, "next", next, "text", text.substring(offset, next));
    }

    private static void remember(NavigableMap<Integer, Integer> retained, int start, int end) {
        var left = retained.floorEntry(start);
        if (left != null && left.getValue() >= start) start = left.getKey();
        var nearby = retained.tailMap(start, true).entrySet().iterator();
        while (nearby.hasNext()) {
            var entry = nearby.next();
            if (entry.getKey() > end) break;
            end = Math.max(end, entry.getValue());
            nearby.remove();
        }
        retained.put(start, end);
    }

    private static boolean splitsPair(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isSurrogatePair(text.charAt(offset - 1), text.charAt(offset));
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
        }
    }
}
