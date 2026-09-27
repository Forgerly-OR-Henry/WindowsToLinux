package gold.debug.windowstolinux.mod.deployment.secret;

import gold.debug.windowstolinux.appapi.task.TaskContext;

/** Supplies one caller-owned plaintext array only inside the accepted original task. / 仅在已接纳原任务内提供一次调用方所属明文字节数组。 */
@FunctionalInterface
public interface SecretValueSource {
    /** Obtains the original value without transferring ownership of the caller's array. / 获取原值而不转移调用方数组的所有权。
     * @param task original executing context / 原执行上下文
     * @return caller-owned bytes; the flow copies and clears only its own copy / 调用方所属字节；流程只复制并清零自己的副本
     * @throws Exception if plaintext cannot be supplied / 无法提供明文时 */
    byte[] obtain(TaskContext task) throws Exception;
}
