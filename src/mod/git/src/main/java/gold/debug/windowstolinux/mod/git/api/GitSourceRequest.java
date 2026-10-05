package gold.debug.windowstolinux.mod.git.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Credential-free original-task Git source intent. / 不含凭据的原任务 Git 来源意图。
 * @param operationId preselected original operation identity / 预选原操作身份
 * @param materialId preselected platform material identity / 预选平台素材身份
 * @param remote credential-free absolute URI text / 不含凭据的绝对 URI 文本
 * @param referenceKind explicit reference choice / 明确引用选择
 * @param reference reviewed branch, tag, commit or HEAD / 已审阅分支、标签、提交或 HEAD
 * @param allowedHosts explicitly permitted normalized hosts / 明确允许的规范主机
 * @param permitLocalFileRemote explicit controlled-local authorization / 明确受控本地授权
 * @param gitExecutable absolute native Git executable text / 绝对原生 Git 可执行文件文本 */
public record GitSourceRequest(UUID operationId, UUID materialId, String remote, GitReferenceKind referenceKind,
                               String reference, Set<String> allowedHosts, boolean permitLocalFileRemote,
                               String gitExecutable) {
    /** Retains an independent immutable intent before provider effects. / 在提供方效果前保留独立不可变意图。
     * @param operationId original operation / 原操作
     * @param materialId original material / 原素材
     * @param remote credential-free URI / 不含凭据 URI
     * @param referenceKind explicit choice / 明确选择
     * @param reference reviewed reference / 已审阅引用
     * @param allowedHosts explicit host allow list / 明确主机允许列表
     * @param permitLocalFileRemote controlled-local authorization / 受控本地授权
     * @param gitExecutable absolute native tool / 绝对原生工具 */
    public GitSourceRequest {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(materialId);
        Objects.requireNonNull(remote);
        Objects.requireNonNull(referenceKind);
        Objects.requireNonNull(reference);
        Objects.requireNonNull(gitExecutable);
        allowedHosts = Set.copyOf(allowedHosts);
        if (operationId.equals(materialId) || remote.isBlank() || remote.length() > 2048
            || reference.isBlank() || reference.length() > 255 || gitExecutable.isBlank())
            throw new IllegalArgumentException("Invalid original Git source intent");
    }
}
