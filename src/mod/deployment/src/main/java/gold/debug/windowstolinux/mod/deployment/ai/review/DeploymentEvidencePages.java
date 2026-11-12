package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

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
