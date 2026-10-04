package gold.debug.windowstolinux.appapi.task.log;

import java.util.UUID;

/**
 * Opaque registered destination for the current log owner. / 当前日志所有者的不透明已登记目的地。
 * @param key owner-local diagnostic category key / 所有者内部的诊断类别键
 */
public record LogTarget(UUID key) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param key owner-local diagnostic category key / 所有者内部的诊断类别键
     */
    public LogTarget {
        java.util.Objects.requireNonNull(key, "key");
    }
}
