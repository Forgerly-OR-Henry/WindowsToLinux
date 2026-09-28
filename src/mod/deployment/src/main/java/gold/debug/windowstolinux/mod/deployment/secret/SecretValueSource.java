package gold.debug.windowstolinux.mod.deployment.secret;

import gold.debug.windowstolinux.appapi.task.TaskContext;

/** Supplies plaintext only inside the accepted original task; the chosen submit contract determines ownership. / 仅在已接纳原任务内提供明文；具体提交契约决定数组所有权。 */
@FunctionalInterface
public interface SecretValueSource {
    /** Obtains one array; submit retains caller ownership while submitOwned transfers it. / 获取一个数组；submit 保留调用方所有权，submitOwned 转移所有权。
     * @param task original executing context / 原执行上下文
     * @return bytes with ownership defined by the selected flow entrypoint / 所选流程入口定义所有权的字节
     * @throws Exception if plaintext cannot be supplied / 无法提供明文时 */
    byte[] obtain(TaskContext task) throws Exception;
}
