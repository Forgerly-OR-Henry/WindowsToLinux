package gold.debug.windowstolinux.api.task;

import java.util.Optional;

/**
 * Owned diagnostic category with an optional author log table. / 包含可选作者日志表的所属诊断类别。
 * @param category owner-local diagnostic category / 所有者内部的诊断类别
 * @param displayName human-readable category name / 可读的类别名称
 * @param businessTable optional author-owned log table / 可选的作者所属日志表
 */
public record LogCategory(String category, String displayName, Optional<String> businessTable) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param category owner-local diagnostic category / 所有者内部的诊断类别
     * @param displayName human-readable category name / 可读的类别名称
     * @param businessTable optional author-owned log table / 可选的作者所属日志表
     */
    public LogCategory {
        java.util.Objects.requireNonNull(category, "category");
        java.util.Objects.requireNonNull(displayName, "displayName");
        java.util.Objects.requireNonNull(businessTable, "businessTable");
        if (!category.matches("[a-z0-9_]{1,64}"))
            throw new IllegalArgumentException("Invalid log category");
        if (businessTable.isPresent() && !businessTable.get().matches("[a-z][a-z0-9_]*_log"))
            throw new IllegalArgumentException("Invalid business log table");
    }
}
