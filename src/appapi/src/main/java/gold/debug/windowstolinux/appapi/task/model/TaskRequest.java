package gold.debug.windowstolinux.appapi.task.model;

import java.util.List;

/**
 * Initial task admission with the complete server resource set. / 包含完整初始服务器资源集合的任务准入请求。
 * @param type owner-local task type / 所有者内部的任务类型
 * @param title human-readable task title / 可读的任务标题
 * @param serverIds copied distinct server identities admitted with the task / 随任务接纳的互不重复服务器身份副本
 * @param cancellable whether cooperative cancellation is available / 是否允许协作取消
 */
public record TaskRequest(String type, String title, List<String> serverIds, boolean cancellable) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param type owner-local task type / 所有者内部的任务类型
     * @param title human-readable task title / 可读的任务标题
     * @param serverIds copied distinct server identities admitted with the task / 随任务接纳的互不重复服务器身份副本
     * @param cancellable whether cooperative cancellation is available / 是否允许协作取消
     */
    public TaskRequest {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(title, "title");
        serverIds = List.copyOf(serverIds);
        if (new java.util.HashSet<>(serverIds).size() != serverIds.size())
            throw new IllegalArgumentException("Duplicate server resource");
    }
}
