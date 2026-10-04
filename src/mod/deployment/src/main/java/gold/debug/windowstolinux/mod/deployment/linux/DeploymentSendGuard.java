package gold.debug.windowstolinux.mod.deployment.linux;

import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Checks a caller-owned binding immediately before remote dispatch. / 在远端发送紧前核对调用方持有的绑定。 */
@FunctionalInterface
public interface DeploymentSendGuard {
    /** No additional binding for the standard workflow. / 标准流程没有附加绑定。 */
    DeploymentSendGuard NONE = () -> {
    };

    /** Rejects a changed binding before any remote effect. / 在任何远端效果前拒绝已变化的绑定。
     * @throws ApiException if the binding changed / 绑定变化时 */
    void verify() throws ApiException;
}
