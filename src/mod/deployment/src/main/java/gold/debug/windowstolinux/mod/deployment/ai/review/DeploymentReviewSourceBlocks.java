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

/** Displays repeated source lines as references to earlier, literal source lines in the same member. */
final class DeploymentReviewSourceBlocks {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BLOCK = "@WTL_BLOCK";
    private static final String COPY = "@WTL_COPY";
    private static final Pattern DIRECTIVE = Pattern.compile("@WTL_(BLOCK|COPY) ([1-9][0-9]{0,4}) ([1-9][0-9]?)\n");
    private static final List<Integer> BLOCK_LENGTHS = List.of(16, 12, 8, 6, 4, 3, 2, 1);
    private static final int MINIMUM_BLOCK_WIRE_BYTES = 192;
    private static final int MAX_ORIGINAL_CHARACTERS = 1024 * 1024;
    private static final int MAX_ORIGINAL_LINES = 65_536;
    private static final int MAX_REFERENCES = 512;
    private static final int MAX_DIRECTIVE_CHARACTERS = 20;

    private DeploymentReviewSourceBlocks() {
    }

    record Encoding(String text, int originalLines, int references) { }

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
        for (int line = 0; line < lines.size();) {
            var anchor = anchors.get(line);
            if (anchor != null) represented.append(anchor.directive(BLOCK));
            var copy = copies.get(line);
            if (copy == null) represented.append(lines.get(line++));
            else {
                represented.append(copy.directive(COPY));
                line += copy.count();
            }
        }
        return Optional.of(new Encoding(represented.toString(), lines.size(), copies.size()));
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
                || represented.length() > originalCharacters + 2 * MAX_REFERENCES * MAX_DIRECTIVE_CHARACTERS
                || !StandardCharsets.UTF_8.newEncoder().canEncode(represented))
            throw new IOException("Repeated source blocks exceed their representation bounds");

        var expanded = new StringBuilder(originalCharacters);
        Map<Block, String> anchors = new HashMap<>();
        var used = new HashSet<Block>();
        Block pending = null;
        int pendingOffset = 0;
        int originalLines = 0;
        int references = 0;
        for (int offset = 0; offset < represented.length();) {
            int end = lineEnd(represented, offset);
            String line = represented.substring(offset, end);
            offset = end;
            if (!reservedMarker(line)) {
                if (++originalLines > MAX_ORIGINAL_LINES)
                    throw new IOException("Repeated source blocks exceed their line limit");
                append(expanded, line, originalCharacters);
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
                originalLines += block.count();
                used.add(block);
            }
        }
        if (pending != null || references == 0 || used.size() != anchors.size() || expanded.length() != originalCharacters)
            throw new IOException("Incomplete repeated source representation");
        return expanded.toString();
    }

    private static void append(StringBuilder expanded, String literal, int originalCharacters) throws IOException {
        if (literal.length() > originalCharacters - expanded.length())
            throw new IOException("Repeated source blocks exceed their declared original size");
        if (!expanded.isEmpty() && expanded.charAt(expanded.length() - 1) == '\r' && literal.charAt(0) == '\n')
            throw new IOException("Source block directive interrupts an original CRLF");
        expanded.append(literal);
    }

    private static boolean reservedMarker(String text) {
        return text.contains(BLOCK) || text.contains(COPY);
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
