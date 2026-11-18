package gold.debug.windowstolinux.mod.deployment.ai.review;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Displays exact source using bounded references to text already reconstructed in the same frame. */
final class DeploymentReviewSourceBlocks {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BLOCK = "@WTL_BLOCK";
    private static final String COPY = "@WTL_COPY";
    private static final String EDIT = "@WTL_EDIT";
    private static final String INDENT = "@>";
    private static final Pattern DIRECTIVE = Pattern.compile("@WTL_(BLOCK|COPY) ([1-9][0-9]{0,4}) ([1-9][0-9]?)\n");
    private static final Pattern INDENTED_LINE = Pattern.compile("@>([1-9][0-9]{0,2}) (?=[^ \r\n])");
    private static final Pattern EDITED_LINE = Pattern.compile("@WTL_EDIT ([1-9][0-9]{0,4}) (0|[1-9][0-9]{0,6}) (0|[1-9][0-9]{0,6}) (\"[^\r\n]*\")\n");
    private static final List<Integer> BLOCK_LENGTHS = List.of(16, 12, 8, 6, 4, 3, 2, 1);
    private static final int MINIMUM_BLOCK_WIRE_BYTES = 64;
    private static final int MAX_ORIGINAL_CHARACTERS = 1024 * 1024;
    private static final int MAX_ORIGINAL_LINES = 65_536;
    private static final int MAX_REFERENCES = 2048;
    private static final int MAX_DIRECTIVE_CHARACTERS = 20;
    private static final int MINIMUM_INDENT = 5;
    private static final int MAXIMUM_INDENT = 999;
    private static final int MAX_EDITS = 16_384;
    private static final int EDIT_CANDIDATES = 32;

    private DeploymentReviewSourceBlocks() {
    }

    record Encoding(String text, int originalLines, int references, int edits) { }

    private record Block(int firstLine, int count) {
        int endLine() {
            return firstLine + count;
        }

        String directive(String marker) {
            return marker + " " + (firstLine + 1) + " " + count + "\n";
        }
    }

    static Optional<Encoding> encode(String original) throws IOException {
        if (original.length() > MAX_ORIGINAL_CHARACTERS || reservedMarker(original)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(original)) return Optional.empty();

        List<String> lines = new ArrayList<>();
        for (int offset = 0; offset < original.length();) {
            if (lines.size() == MAX_ORIGINAL_LINES) return Optional.empty();
            int end = lineEnd(original, offset);
            lines.add(original.substring(offset, end));
            offset = end;
        }
        int[] wireOffsets = new int[lines.size() + 1];
        for (int i = 0; i < lines.size(); i++)
            wireOffsets[i + 1] = wireOffsets[i] + JSON.writeValueAsBytes(lines.get(i)).length - 2;

        Map<List<String>, Block> literalBlocks = new HashMap<>();
        Map<Integer, Block> anchors = new HashMap<>();
        Map<Integer, Block> copies = new HashMap<>();
        var anchoredLines = new BitSet();
        int literalStart = 0;
        for (int line = 0; line < lines.size() && copies.size() < MAX_REFERENCES;) {
            var copy = bestCopy(lines, wireOffsets, line, literalBlocks, anchors, anchoredLines);
            if (copy == null) {
                int end = ++line;
                for (int count : BLOCK_LENGTHS) {
                    int start = end - count;
                    if (start >= literalStart && wireOffsets[end] - wireOffsets[start] >= MINIMUM_BLOCK_WIRE_BYTES)
                        literalBlocks.putIfAbsent(lines.subList(start, end), new Block(start, count));
                }
            } else {
                copies.put(line, copy);
                anchors.put(copy.firstLine(), copy);
                anchoredLines.set(copy.firstLine(), copy.endLine());
                line += copy.count();
                literalStart = line;
            }
        }
        if (copies.isEmpty()) return Optional.empty();

        var represented = new StringBuilder(original.length());
        var editCandidates = new HashMap<String, List<Integer>>();
        int edits = 0;
        for (int line = 0; line < lines.size();) {
            var anchor = anchors.get(line);
            if (anchor != null) represented.append(anchor.directive(BLOCK));
            var copy = copies.get(line);
            int next = copy == null ? line + 1 : line + copy.count();
            if (copy == null) {
                var literal = new StringBuilder();
                appendLiteral(literal, lines.get(line));
                String value = edits < MAX_EDITS ? editedLine(lines, line, literal.toString(), editCandidates) : literal.toString();
                if (value.startsWith(EDIT + " ")) edits++;
                represented.append(value);
            }
            else {
                represented.append(copy.directive(COPY));
            }
            for (int known = line; known < next; known++) {
                for (String key : editKeys(lines.get(known))) {
                    var candidates = editCandidates.computeIfAbsent(key, ignored -> new ArrayList<>());
                    candidates.add(known);
                    if (candidates.size() > EDIT_CANDIDATES) candidates.removeFirst();
                }
            }
            line = next;
        }
        return Optional.of(new Encoding(represented.toString(), lines.size(), copies.size(), edits));
    }

    private static List<String> editKeys(String line) {
        String stripped = line.stripLeading();
        return List.of("p" + stripped.substring(0, Math.min(12, stripped.length())),
            "s" + line.stripTrailing().substring(Math.max(0, line.stripTrailing().length() - 12)));
    }

    private static String editedLine(List<String> lines, int index, String literal,
                                     Map<String, List<Integer>> candidates) throws IOException {
        String source = lines.get(index), best = literal;
        int bytes = JSON.writeValueAsBytes(literal).length;
        var seen = new HashSet<Integer>();
        for (String key : editKeys(source)) {
            for (int previous : candidates.getOrDefault(key, List.of())) {
                if (!seen.add(previous)) continue;
                String prior = lines.get(previous);
                int prefix = 0, suffix = 0, limit = Math.min(prior.length(), source.length());
                while (prefix < limit && prior.charAt(prefix) == source.charAt(prefix)) prefix++;
                if (prefix > 0 && prefix < source.length() && Character.isLowSurrogate(source.charAt(prefix))) prefix--;
                while (suffix < limit - prefix && prior.charAt(prior.length() - suffix - 1) == source.charAt(source.length() - suffix - 1)) suffix++;
                if (suffix > 0 && suffix < source.length() && Character.isLowSurrogate(source.charAt(source.length() - suffix))) suffix--;
                String middle = source.substring(prefix, source.length() - suffix);
                String value = EDIT + " " + (previous + 1) + " " + prefix + " " + suffix + " " + JSON.writeValueAsString(middle) + "\n";
                int cost = JSON.writeValueAsBytes(value).length;
                if (cost < bytes) {
                    best = value;
                    bytes = cost;
                }
            }
        }
        return best;
    }

    private static Block bestCopy(List<String> lines, int[] wireOffsets, int line, Map<List<String>, Block> literalBlocks,
                                  Map<Integer, Block> anchors, BitSet anchoredLines) {
        Block best = null;
        int bestSaving = 0;
        for (int count : BLOCK_LENGTHS) {
            int end = line + count;
            if (end > lines.size() || wireOffsets[end] - wireOffsets[line] < MINIMUM_BLOCK_WIRE_BYTES) continue;
            var block = literalBlocks.get(lines.subList(line, end));
            if (block == null) continue;
            int saving = wireOffsets[end] - wireOffsets[line] - directiveWireBytes(COPY, block);
            if (!block.equals(anchors.get(block.firstLine()))) {
                int overlap = anchoredLines.nextSetBit(block.firstLine());
                if (overlap >= 0 && overlap < block.endLine()) continue;
                saving -= directiveWireBytes(BLOCK, block);
            }
            if (saving > bestSaving) {
                best = block;
                bestSaving = saving;
            }
        }
        return best;
    }

    private static int directiveWireBytes(String marker, Block block) {
        return block.directive(marker).length() + 1; // JSON escapes the directive's LF.
    }

    static String expand(String represented, int originalCharacters) throws IOException {
        if (originalCharacters < 1 || originalCharacters > MAX_ORIGINAL_CHARACTERS
                || represented.length() > 2 * MAX_ORIGINAL_CHARACTERS
                || !StandardCharsets.UTF_8.newEncoder().canEncode(represented))
            throw new IOException("Repeated source blocks exceed their representation bounds");

        var expanded = new StringBuilder(originalCharacters);
        Map<Block, String> anchors = new HashMap<>();
        var used = new HashSet<Block>();
        Block pending = null;
        int pendingOffset = 0;
        int originalLines = 0;
        int references = 0;
        int edits = 0;
        var restoredLines = new ArrayList<String>();
        for (int offset = 0; offset < represented.length();) {
            int end = lineEnd(represented, offset);
            String line = represented.substring(offset, end);
            offset = end;
            if (!line.contains(BLOCK) && !line.contains(COPY)) {
                if (++originalLines > MAX_ORIGINAL_LINES)
                    throw new IOException("Repeated source blocks exceed their line limit");
                String literal;
                if (line.contains(EDIT)) {
                    if (++edits > MAX_EDITS) throw new IOException("Source line edits exceed their limit");
                    literal = restoreEditedLine(line, restoredLines);
                } else literal = literalLine(line);
                if (lineEnd(literal, 0) != literal.length() || literal.isEmpty()
                        || offset < represented.length() && !literal.endsWith("\n") && !literal.endsWith("\r"))
                    throw new IOException("A source line edit does not restore one complete original line");
                append(expanded, literal, originalCharacters);
                restoredLines.add(literal);
                if (pending != null && originalLines == pending.endLine()) {
                    anchors.put(pending, expanded.substring(pendingOffset));
                    pending = null;
                }
                continue;
            }
            if (pending != null) throw new IOException("A source block contains a directive");
            var directive = DIRECTIVE.matcher(line);
            if (!directive.matches()) throw new IOException("Invalid repeated source directive");
            var block = new Block(Integer.parseInt(directive.group(2)) - 1, Integer.parseInt(directive.group(3)));
            if (!BLOCK_LENGTHS.contains(block.count())) throw new IOException("Invalid repeated source block length");
            if ("BLOCK".equals(directive.group(1))) {
                if (block.firstLine() != originalLines || block.endLine() > MAX_ORIGINAL_LINES
                        || anchors.size() >= MAX_REFERENCES)
                    throw new IOException("Invalid or duplicate source block anchor");
                pending = block;
                pendingOffset = expanded.length();
            } else {
                String literal = anchors.get(block);
                if (literal == null || ++references > MAX_REFERENCES || originalLines + block.count() > MAX_ORIGINAL_LINES)
                    throw new IOException("Source block copy does not refer to available prior literal text within the bounds");
                append(expanded, literal, originalCharacters);
                for (int start = 0; start < literal.length();) {
                    int next = lineEnd(literal, start);
                    restoredLines.add(literal.substring(start, next));
                    start = next;
                }
                originalLines += block.count();
                used.add(block);
            }
        }
        if (pending != null || references == 0 || used.size() != anchors.size() || expanded.length() != originalCharacters)
            throw new IOException("Incomplete repeated source representation");
        return expanded.toString();
    }

    private static String restoreEditedLine(String line, List<String> restored) throws IOException {
        var directive = EDITED_LINE.matcher(line);
        if (!directive.matches()) throw new IOException("Invalid source line edit");
        int previous = Integer.parseInt(directive.group(1)) - 1;
        int prefix = Integer.parseInt(directive.group(2)), suffix = Integer.parseInt(directive.group(3));
        if (previous >= restored.size()) throw new IOException("Source line edit requires an earlier line in this frame");
        String prior = restored.get(previous);
        if (prefix > prior.length() || suffix > prior.length() - prefix
                || prefix < prior.length() && Character.isLowSurrogate(prior.charAt(prefix))
                || suffix > 0 && suffix < prior.length() && Character.isLowSurrogate(prior.charAt(prior.length() - suffix)))
            throw new IOException("Invalid source line edit boundary");
        var middle = JSON.readTree(directive.group(4));
        if (!middle.isTextual() || !JSON.writeValueAsString(middle.textValue()).equals(directive.group(4)))
            throw new IOException("Source line edit requires one exact JSON string");
        String value = prior.substring(0, prefix) + middle.textValue() + prior.substring(prior.length() - suffix);
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw new IOException("Invalid Unicode in source line edit");
        return value;
    }

    private static void append(StringBuilder expanded, String literal, int originalCharacters) throws IOException {
        if (literal.length() > originalCharacters - expanded.length())
            throw new IOException("Repeated source blocks exceed their declared original size");
        if (!expanded.isEmpty() && expanded.charAt(expanded.length() - 1) == '\r' && literal.charAt(0) == '\n')
            throw new IOException("Source block directive interrupts an original CRLF");
        expanded.append(literal);
    }

    private static void appendLiteral(StringBuilder represented, String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') indent++;
        if (indent >= MINIMUM_INDENT && indent <= MAXIMUM_INDENT && indent < line.length()
                && line.charAt(indent) != '\r' && line.charAt(indent) != '\n') {
            represented.append(INDENT).append(indent).append(' ').append(line, indent, line.length());
        } else {
            represented.append(line);
        }
    }

    private static String literalLine(String line) throws IOException {
        if (!line.contains(INDENT)) return line;
        var marker = INDENTED_LINE.matcher(line);
        if (!marker.lookingAt() || line.indexOf(INDENT, marker.end()) >= 0)
            throw new IOException("Invalid leading-space source directive");
        int indent = Integer.parseInt(marker.group(1));
        if (indent < MINIMUM_INDENT) throw new IOException("Leading-space source directive is below its minimum length");
        return " ".repeat(indent) + line.substring(marker.end());
    }

    private static boolean reservedMarker(String text) {
        return text.contains(BLOCK) || text.contains(COPY) || text.contains(EDIT) || text.contains(INDENT);
    }

    private static int lineEnd(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\n') return i + 1;
            if (character == '\r')
                return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? i + 2 : i + 1;
        }
        return text.length();
    }
}
