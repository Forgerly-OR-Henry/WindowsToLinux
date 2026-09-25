package gold.debug.windowstolinux.shared.linux.protocol.backup;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Exact managed content bindings that one reviewed component publication must activate. / 一个经审阅组件发布必须激活的精确受管内容绑定。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param fileBindings file bindings / 文件绑定集合
 */
public record ManagedContentPublication(String applicationId, String componentId,
        List<RemoteManagedFileBinding> fileBindings) {
    /**
     * Validates stable identities, canonical order and non-overlapping logical paths. / 校验稳定身份、规范顺序及互不重叠的逻辑路径。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param fileBindings file bindings / 文件绑定集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedContentPublication {
        applicationId = managedId(applicationId, "applicationId");
        componentId = managedId(componentId, "componentId");
        fileBindings = List.copyOf(Objects.requireNonNull(fileBindings, "fileBindings").stream()
                .map(value -> Objects.requireNonNull(value, "file binding"))
                .sorted(Comparator.comparing(RemoteManagedFileBinding::bindingId)).toList());
        if (fileBindings.size() > 32 || fileBindings.stream().map(RemoteManagedFileBinding::bindingId).distinct()
                .count() != fileBindings.size()) {
            throw new IllegalArgumentException("managed file bindings must be bounded and unique");
        }
        for (int first = 0; first < fileBindings.size(); first++) {
            String left = fileBindings.get(first).dataPath().path();
            for (int second = first + 1; second < fileBindings.size(); second++) {
                String right = fileBindings.get(second).dataPath().path();
                boolean directories = fileBindings.get(first)
                        .resourceType() != gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageResourceType.DATABASE
                        && fileBindings.get(second)
                                .resourceType() != gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageResourceType.DATABASE;
                if (left.equals(right)
                        || directories && (left.startsWith(right + "/") || right.startsWith(left + "/"))) {
                    throw new IllegalArgumentException("managed logical data paths must not overlap");
                }
            }
        }
    }

    /**
     * Validates a managed identifier before it reaches a remote resource boundary.
     * <p>在标识到达远端资源边界前验证受管标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return managed id text / 受管标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String managedId(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }
}
