package gold.debug.windowstolinux.shared.linux.protocol.restore;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.linux.protocol.RemoteDeploymentInputs;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/**
 * One exact staged component for bounded restore activation. / 用于有界恢复激活的一个精确暂存组件。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param managedApplicationId managed application id / 受管应用标识
 * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
 * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
 * @param releaseArchivePath release archive path / 发布归档路径
 * @param persistentArchivePaths persistent archive paths / 持久化归档路径集合
 * @param ociArchivePath oci archive path / oci归档路径
 * @param dependsOn depends on / 依赖对应
 * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
 * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
 * @param ports bound host ports / 绑定的宿主机端口
 */
public record RemoteRestoreActivationComponent(String componentId, String managedApplicationId,
        String ownershipManifestSha256, String releaseSha256, String releaseArchivePath,
        List<String> persistentArchivePaths, Optional<String> ociArchivePath, List<String> dependsOn,
        DeploymentRuntimeSpecification runtime, RemoteDeploymentInputs inputs, List<RemoteRestorePortBinding> ports) {
    /**
     * Validates exact managed identities and candidate-relative members. / 校验精确受管身份及候选相对成员。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param managedApplicationId managed application id / 受管应用标识
     * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
     * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
     * @param releaseArchivePath release archive path / 发布归档路径
     * @param persistentArchivePaths persistent archive paths / 持久化归档路径集合
     * @param ociArchivePath oci archive path / oci归档路径
     * @param dependsOn depends on / 依赖对应
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param ports bound host ports / 绑定的宿主机端口
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RemoteRestoreActivationComponent {
        componentId = id(componentId, "componentId");
        managedApplicationId = id(managedApplicationId, "managedApplicationId");
        ownershipManifestSha256 = digest(ownershipManifestSha256, "ownershipManifestSha256");
        releaseSha256 = digest(releaseSha256, "releaseSha256");
        releaseArchivePath = member(releaseArchivePath, "releases/");
        String persistentPrefix = "data/" + componentId + "/";
        persistentArchivePaths = List.copyOf(Objects.requireNonNull(persistentArchivePaths, "persistentArchivePaths"));
        if (persistentArchivePaths.size() > 256
                || persistentArchivePaths.stream().anyMatch(value -> !member(value, persistentPrefix).equals(value))
                || persistentArchivePaths.stream().distinct().count() != persistentArchivePaths.size()) {
            throw new IllegalArgumentException("persistent restore archives are invalid");
        }
        ociArchivePath = Objects.requireNonNull(ociArchivePath, "ociArchivePath")
                .map(value -> member(value, "runtime/"));
        dependsOn = Objects.requireNonNull(dependsOn, "dependsOn").stream().map(value -> id(value, "dependsOn"))
                .toList();
        runtime = Objects.requireNonNull(runtime, "runtime");
        inputs = Objects.requireNonNull(inputs, "inputs");
        ports = List.copyOf(Objects.requireNonNull(ports, "ports"));
    }

    /**
     * Checks id syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查标识语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return id text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String id(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    /**
     * Computes or retrieves content identity for independent evidence checks.
     * <p>计算或取得用于独立证据检查的内容身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return or retrieves content identity for independent evidence checks / 或取得用于独立证据检查的内容身份
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String digest(String value, String field) {
        value = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    /**
     * Resolves a canonical archive-member path within the owning storage boundary.
     * <p>在所属存储边界内解析规范归档成员路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param prefix prefix / 前缀
     * @return a canonical archive-member path within the owning storage boundary / 在所属存储边界内解析规范归档成员路径
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String member(String value, String prefix) {
        value = Objects.requireNonNull(value, "member").trim();
        if (!value.startsWith(prefix) || value.startsWith("/") || value.contains("\\") || value.contains("//")
                || java.util.Arrays.stream(value.split("/"))
                        .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("restore activation member path is invalid");
        }
        return value;
    }
}
