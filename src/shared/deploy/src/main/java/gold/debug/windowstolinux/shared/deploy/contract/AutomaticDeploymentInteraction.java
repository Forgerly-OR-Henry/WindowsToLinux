package gold.debug.windowstolinux.shared.deploy.contract;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** UI-independent questions; implementations marshal dialogs onto their UI thread. / 独立于 UI 的问题契约，实现负责将对话框调度到自身 UI 线程。 */
public interface AutomaticDeploymentInteraction {
    /** Requires explicit confirmation of backup, downtime and replacement for the exact displayed instance. / 要求用户针对展示的精确实例，明确确认备份、停机和替换。 */
    default boolean confirmDatabaseReplacement(java.util.Map<String, ?> details) {
        return confirm("db.replaceConfirmed", details);
    }
    /** Requests non-secret input; empty means cancel the current operation. / 请求非秘密输入，空结果表示取消当前操作。 */
    Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields);
    /** Requests explicit approval for a concrete high-risk operation. / 请求对具体高风险操作的显式批准。 */
    boolean confirm(String messageKey, Map<String, ?> details);
    /** Requests a short-lived secret, which the caller must clear. / 请求短期秘密，由调用方负责清零。 */
    char[] requestSecret(String messageKey);
}
