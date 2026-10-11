package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One review's delivery bookkeeping and model-authored DATA; never evidence or authorization. */
final class DeploymentAgentReviewMemory {
    static final int NOTES_CHARACTERS = 3000;

    static final int NOTES_BYTES = 6000;

    private static final int DELIVERY_CHARACTERS = 8000;

    private static final int DELIVERY_BYTES = 12000;

    private static final ObjectMapper JSON = new ObjectMapper();

    record Range(int start, int endExclusive) {
    }

    record Delivery(String tool, Map<String, String> arguments, String sha256, int total, List<Range> ranges) {
    }

    private final String binding;

    private List<Delivery> delivered = List.of();

    private boolean capacityExceeded;

    private String notes = "";

    DeploymentAgentReviewMemory(String binding) {
        this.binding = Objects.requireNonNull(binding);
    }

    /** Called only after the local result has passed the tool-response bound and is returned successfully. */
    void delivered(String tool, JsonNode arguments, Object value) {
        if (!tool.equals("request") && !tool.equals("evidence")) return;
        var page = JSON.valueToTree(value);
        if (!page.path("text").isTextual()) return;
        int start = page.path("offset").asInt(-1), end = page.path("next").asInt(-1), total = page.path("total").asInt(-1);
        String hash = page.path("sha256").asText();
        if (start < 0 || end <= start || end > total || !hash.matches("[0-9a-f]{64}")
            || page.path("text").textValue().length() != end - start) return;
        String member = tool.equals("request") ? "part" : "pointer";
        var route = Map.of(member, arguments.path(member).asText());
        var candidate = new ArrayList<>(delivered);
        int index = -1;
        for (int i = 0; i < candidate.size(); i++) {
            var item = candidate.get(i);
            if (item.tool().equals(tool) && item.arguments().equals(route) && item.sha256().equals(hash) && item.total() == total) {
                index = i;
                break;
            }
        }
        var ranges = new ArrayList<Range>(index < 0 ? List.of() : candidate.get(index).ranges());
        ranges.add(new Range(start, end));
        ranges.sort(Comparator.comparingInt(Range::start));
        var merged = new ArrayList<Range>();
        for (var range : ranges) {
            if (merged.isEmpty() || merged.getLast().endExclusive() < range.start()) merged.add(range);
            else {
                var previous = merged.removeLast();
                merged.add(new Range(previous.start(), Math.max(previous.endExclusive(), range.endExclusive())));
            }
        }
        var updated = new Delivery(tool, route, hash, total, List.copyOf(merged));
        if (index < 0) candidate.add(updated);
        else candidate.set(index, updated);
        String encoded = JSON.valueToTree(candidate).toString();
        if (candidate.size() > 24 || candidate.stream().mapToInt(item -> item.ranges().size()).sum() > 64
            || encoded.length() > DELIVERY_CHARACTERS || encoded.getBytes(StandardCharsets.UTF_8).length > DELIVERY_BYTES) {
            capacityExceeded = true;
            return;
        }
        delivered = List.copyOf(candidate);
    }

    Map<String, Object> saveNotes(String reviewBinding, String replacement) {
        if (!binding.equals(reviewBinding))
            throw new IllegalArgumentException("Notes require the current reviewBinding");
        Objects.requireNonNull(replacement);
        if (replacement.length() > NOTES_CHARACTERS || replacement.getBytes(StandardCharsets.UTF_8).length > NOTES_BYTES)
            throw new IllegalArgumentException("Notes exceed 3000 UTF-16 code units or 6000 UTF-8 bytes; previous notes remain unchanged");
        notes = replacement;
        return Map.of("trust", "MODEL_AUTHORED_DATA", "authority", "NONE", "reviewBinding", binding,
            "saved", true, "characters", notes.length(), "utf8Bytes", notes.getBytes(StandardCharsets.UTF_8).length);
    }

    Map<String, Object> projection() {
        return Map.of("reviewBinding", binding, "authority", "NONE",
            "deliveredRanges", Map.of("trust", "HOST_DELIVERY_METADATA", "cursorUnit", "UTF16_CODE_UNITS",
                "meaning", "Successfully returned ranges only; not proof of reading, safety review, complete evidence or permission",
                "trackingStatus", capacityExceeded ? "CAPACITY_EXCEEDED_INCOMPLETE" : "WITHIN_CAPACITY",
                "entries", delivered),
            "modelNotes", Map.of("trust", "MODEL_AUTHORED_DATA", "authority", "NONE", "text", notes));
    }
}
