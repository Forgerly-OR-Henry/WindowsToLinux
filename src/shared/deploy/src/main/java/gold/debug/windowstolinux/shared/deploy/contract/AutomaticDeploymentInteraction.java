package gold.debug.windowstolinux.shared.deploy.contract;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;

/**
 * UI-independent questions; implementations marshal dialogs onto their UI thread. / 独立于 UI 的问题契约，实现负责将对话框调度到自身 UI 线程。
 */
public interface AutomaticDeploymentInteraction {
    /**
     * Requires explicit confirmation of backup, downtime and replacement for the exact displayed instance. / 要求用户针对展示的精确实例，明确确认备份、停机和替换。
     *
     * @param details details / 详情
     * @return true when requires explicit confirmation of backup, downtime and replacement for the exact displayed instance, false otherwise / 要求用户针对展示的精确实例，明确确认备份、停机和替换时为 true，否则为 false
     */
    default boolean confirmDatabaseReplacement(java.util.Map<String, ?> details) {
        return confirm("db.replaceConfirmed", details);
    }

    /**
     * Requests non-secret input; empty means cancel the current operation. / 请求非秘密输入，空结果表示取消当前操作。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields);

    /**
     * Requests explicit approval for a concrete high-risk operation. / 请求对具体高风险操作的显式批准。
     *
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param details details / 详情
     * @return true when requests explicit approval for a concrete high-risk operation, false otherwise / 请求对具体高风险操作的显式批准时为 true，否则为 false
     */
    boolean confirm(String messageKey, Map<String, ?> details);

    /**
     * Requests a short-lived secret, which the caller must clear. / 请求短期秘密，由调用方负责清零。
     *
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    char[] requestSecret(String messageKey);
}
