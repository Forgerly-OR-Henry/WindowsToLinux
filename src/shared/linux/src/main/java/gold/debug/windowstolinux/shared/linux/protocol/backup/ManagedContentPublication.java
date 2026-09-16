package gold.debug.windowstolinux.shared.linux.protocol.backup;


import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Exact managed content bindings that one reviewed component publication must activate. / 一个经审阅组件发布必须激活的精确受管内容绑定。 */
public record ManagedContentPublication(
        String applicationId,
        String componentId,
        List<RemoteManagedFileBinding> fileBindings
) {
    /** Validates stable identities, canonical order and non-overlapping logical paths. / 校验稳定身份、规范顺序及互不重叠的逻辑路径。 */
    public ManagedContentPublication {
        applicationId = managedId(applicationId, "applicationId");
        componentId = managedId(componentId, "componentId");
        fileBindings = List.copyOf(Objects.requireNonNull(fileBindings, "fileBindings").stream()
                .map(value -> Objects.requireNonNull(value, "file binding"))
                .sorted(Comparator.comparing(RemoteManagedFileBinding::bindingId)).toList());
        if (fileBindings.size() > 32
                || fileBindings.stream().map(RemoteManagedFileBinding::bindingId).distinct().count() != fileBindings.size()) {
            throw new IllegalArgumentException("managed file bindings must be bounded and unique");
        }
        for (int first = 0; first < fileBindings.size(); first++) {
            String left = fileBindings.get(first).dataPath().path();
            for (int second = first + 1; second < fileBindings.size(); second++) {
                String right = fileBindings.get(second).dataPath().path();
                if (left.equals(right) || left.startsWith(right + "/") || right.startsWith(left + "/")) {
                    throw new IllegalArgumentException("managed logical data paths must not overlap");
                }
            }
        }
    }

    private static String managedId(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }
}
