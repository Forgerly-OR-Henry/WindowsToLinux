package gold.debug.windowstolinux.acceptance.complex.model;

import java.util.List;
import java.util.stream.Collectors;

public record Summary(String status, List<Integer> items, int total) {
    public Summary(List<Integer> items) {
        this("ok", List.copyOf(items), items.stream().mapToInt(Integer::intValue).sum());
    }

    public String toJson() {
        return "{\"status\":\"ok\",\"items\":[" + items.stream().map(String::valueOf).collect(Collectors.joining(","))
                + "],\"total\":" + total + "}";
    }
}
