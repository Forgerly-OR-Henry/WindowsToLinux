package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Independent test decoder: it never calls the production block or evidence reader. */
public final class DeploymentSourceFrameDecoder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private DeploymentSourceFrameDecoder() { }

    public static String expand(String display) throws Exception {
        var encoded = lines(display);
        var restored = new ArrayList<String>();
        var blocks = new HashMap<Integer, List<String>>();
        var used = new HashSet<Integer>();
        int copies = 0, edits = 0;
        for (int i = 0; i < encoded.size(); i++) {
            String line = encoded.get(i);
            if (line.startsWith("@WTL_BLOCK ")) {
                String[] fields = line.stripTrailing().split(" ");
                assertEquals(3, fields.length);
                int first = Integer.parseInt(fields[1]), count = Integer.parseInt(fields[2]);
                assertEquals(restored.size() + 1, first);
                assertTrue(List.of(16, 12, 8, 6, 4, 3, 2, 1).contains(count));
                var literal = new ArrayList<String>();
                for (int n = 0; n < count; n++) {
                    String value = encoded.get(++i);
                    if (value.startsWith("@WTL_EDIT ")) edits++;
                    value = literal(value, restored);
                    restored.add(value);
                    literal.add(value);
                }
                assertNull(blocks.put(first, List.copyOf(literal)));
            } else if (line.startsWith("@WTL_COPY ")) {
                String[] fields = line.stripTrailing().split(" ");
                assertEquals(3, fields.length);
                int first = Integer.parseInt(fields[1]), count = Integer.parseInt(fields[2]);
                var prior = blocks.get(first);
                assertNotNull(prior, "Copy must use a prior block in this frame");
                assertEquals(count, prior.size());
                restored.addAll(prior);
                used.add(first);
                assertTrue(++copies <= 2048);
            } else {
                if (line.startsWith("@WTL_EDIT ")) edits++;
                restored.add(literal(line, restored));
            }
            assertTrue(restored.size() <= 65536);
            assertTrue(edits <= 16384);
        }
        assertEquals(blocks.keySet(), used);
        String source = String.join("", restored);
        assertTrue(source.length() <= 1024 * 1024);
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(source));
        return source;
    }

    private static String literal(String line, List<String> restored) throws Exception {
        if (line.startsWith("@>")) {
            int space = line.indexOf(' ');
            int count = Integer.parseInt(line.substring(2, space));
            assertTrue(count >= 5 && count <= 999);
            return " ".repeat(count) + line.substring(space + 1);
        }
        if (!line.startsWith("@WTL_EDIT ")) {
            assertFalse(line.contains("@WTL_"), "Unexpected directive inside a literal block");
            return line;
        }
        String[] fields = line.split(" ", 5);
        assertEquals(5, fields.length);
        int previous = Integer.parseInt(fields[1]) - 1;
        int prefix = Integer.parseInt(fields[2]), suffix = Integer.parseInt(fields[3]);
        assertTrue(previous >= 0 && previous < restored.size(), "No forward or other-frame line reference");
        String base = restored.get(previous);
        assertTrue(prefix >= 0 && suffix >= 0 && prefix + suffix <= base.length());
        String json = fields[4].substring(0, fields[4].length() - 1);
        JsonNode middle = JSON.readTree(json);
        assertTrue(middle.isTextual());
        assertEquals(JSON.writeValueAsString(middle.textValue()), json);
        String value = base.substring(0, prefix) + middle.textValue() + base.substring(base.length() - suffix);
        assertEquals(1, lines(value).size());
        return value;
    }

    private static List<String> lines(String text) {
        var result = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                result.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) result.add(text.substring(start));
        return result;
    }

    public static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    public record Frame(JsonNode facts, JsonNode header, Map<String, NavigableMap<Integer, String>> parts) {
        public String whole(String pointer, String expected) throws Exception {
            var ranges = parts.getOrDefault(pointer, new TreeMap<>());
            for (var part : ranges.entrySet()) assertTrue(part.getKey() >= 0 && part.getKey() + part.getValue().length() <= expected.length());
            return range(pointer, 0, expected);
        }

        public String range(String pointer, int offset, String expected) throws Exception {
            var ranges = parts.getOrDefault(pointer, new TreeMap<>());
            char[] restored = new char[expected.length()];
            var present = new BitSet(expected.length());
            for (var part : ranges.entrySet()) {
                int start = Math.max(offset, part.getKey()), end = Math.min(offset + expected.length(), part.getKey() + part.getValue().length());
                for (int at = start; at < end; at++) {
                    char value = part.getValue().charAt(at - part.getKey());
                    if (present.get(at - offset)) assertEquals(restored[at - offset], value, "Conflicting repeated source");
                    restored[at - offset] = value;
                }
                if (end > start) present.set(start - offset, end - offset);
            }
            assertEquals(expected.length(), present.cardinality(), "Missing current source: " + pointer);
            String result = new String(restored);
            assertEquals(sha(expected), sha(result), pointer);
            assertEquals(expected, result, pointer);
            return result;
        }
    }

    public static Frame decode(byte[] wire) throws Exception {
        return decode(wire, "buildReviewEvidence");
    }

    public static Frame decode(byte[] wire, String manifestName) throws Exception {
        var input = JSON.readTree(wire).path("input");
        JsonNode facts = JSON.readTree(input.get(input.size() - 1).at("/content/0/text").asText());
        String snapshot = facts.path(manifestName).path("snapshotSha256").asText();
        assertTrue(snapshot.matches("[0-9a-f]{64}"));
        var parts = new LinkedHashMap<String, NavigableMap<Integer, String>>();
        JsonNode retained = null;
        for (var message : input) {
            String text = message.at("/content/0/text").asText();
            if (text.startsWith("WTL-TOOLCHAIN-REVIEW-SOURCE-1\n")) {
                int end = text.indexOf('\n', text.indexOf('\n') + 1);
                var metadata = JSON.readTree(text.substring(text.indexOf('\n') + 1, end));
                assertEquals(snapshot, metadata.path("snapshotSha256").asText());
                String source = text.substring(end + 1);
                assertEquals(metadata.path("characters").asInt(), source.length());
                assertEquals(metadata.path("sha256").asText(), sha(source));
                parts.computeIfAbsent(metadata.path("sourcePointer").asText(), unused -> new TreeMap<>()).put(0, source);
            }
            if (!text.startsWith("WTL-COMMAND-RETAINED-SOURCE-1\n")) continue;
            assertNull(retained, "Exactly one current retained frame");
            int end = text.indexOf('\n', text.indexOf('\n') + 1);
            retained = JSON.readTree(text.substring(text.indexOf('\n') + 1, end));
            assertEquals(snapshot, retained.path("snapshotSha256").asText());
            int position = end + 1;
            var blocks = retained.path("repeatedOriginalBlocks");
            boolean expanded = false;
            for (var range : retained.path("ranges")) {
                String pointer = range.path("pointer").asText();
                int offset = range.path("offset").asInt(), next = range.path("next").asInt();
                boolean encoded = !expanded && blocks.path("pointer").asText().equals(pointer)
                    && offset == 0 && next == blocks.path("originalCharacters").asInt();
                int characters = encoded ? blocks.path("displayCharacters").asInt() : next - offset;
                String value = text.substring(position, position + characters);
                position += characters;
                if (encoded) {
                    value = expand(value);
                    assertEquals(blocks.path("sha256").asText(), sha(value));
                    expanded = true;
                }
                assertEquals(next - offset, value.length());
                var prior = parts.computeIfAbsent(pointer, unused -> new TreeMap<>()).putIfAbsent(offset, value);
                if (prior != null) assertEquals(prior.substring(0, Math.min(prior.length(), value.length())),
                    value.substring(0, Math.min(prior.length(), value.length())));
            }
            assertEquals(text.length(), position, "Every displayed byte must be accounted for");
            assertTrue(retained.path("pendingRequired").isEmpty());
        }
        assertNotNull(retained);
        return new Frame(facts, retained, parts);
    }
}
