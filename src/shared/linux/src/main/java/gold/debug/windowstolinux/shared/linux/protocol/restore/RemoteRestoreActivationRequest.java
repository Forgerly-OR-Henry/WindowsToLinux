package gold.debug.windowstolinux.shared.linux.protocol.restore;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Complete application-wide activation request bound to one staged archive. / 绑定到一个已暂存归档的完整整应用激活请求。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
 * @param candidateToken candidate token / 候选令牌
 * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
 * @param remoteCandidateRoot remote candidate root / 远端候选根目录
 * @param mode selected operating or storage mode / 所选运行或存储模式
 * @param components reviewed components in the application graph / 应用图中的已审阅组件
 * @param applicationHealthComponentId application health component id / 应用健康组件标识
 * @param applicationHealthCheck independently reviewed whole-application probe / 独立审阅的整应用探测
 */
public record RemoteRestoreActivationRequest(
        String applicationId,
        String candidateId,
        String candidateToken,
        String archiveSha256,
        String remoteCandidateRoot,
        RemoteRestoreActivationMode mode,
        List<RemoteRestoreActivationComponent> components,
        String applicationHealthComponentId,
        HealthCheck applicationHealthCheck
) {
    /**
     * Validates candidate identity, dependency order and port-mode closure. / 校验候选身份、依赖顺序及端口模式闭合。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param candidateToken candidate token / 候选令牌
     * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
     * @param remoteCandidateRoot remote candidate root / 远端候选根目录
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param applicationHealthComponentId application health component id / 应用健康组件标识
     * @param applicationHealthCheck independently reviewed whole-application probe / 独立审阅的整应用探测
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RemoteRestoreActivationRequest {
        applicationId = id(applicationId, "applicationId");
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256").trim().toLowerCase(Locale.ROOT);
        if (!archiveSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("archiveSha256 is invalid");
        candidateId = Objects.requireNonNull(candidateId, "candidateId").trim();
        if (!candidateId.equals(applicationId + "-" + archiveSha256.substring(0, 16))) {
            throw new IllegalArgumentException("candidateId differs from archive identity");
        }
        candidateToken = Objects.requireNonNull(candidateToken, "candidateToken").trim();
        if (!candidateToken.equals(archiveSha256.substring(0, 32))) {
            throw new IllegalArgumentException("candidateToken differs from archive identity");
        }
        remoteCandidateRoot = Objects.requireNonNull(remoteCandidateRoot, "remoteCandidateRoot").trim();
        if (!remoteCandidateRoot.equals("/var/lib/windowstolinux/work/" + candidateId + "/mutable/restore")) {
            throw new IllegalArgumentException("remoteCandidateRoot is invalid");
        }
        mode = Objects.requireNonNull(mode, "mode");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (components.isEmpty() || components.size() > 256) {
            throw new IllegalArgumentException("restore activation component count is invalid");
        }
        Set<String> seen = new HashSet<>();
        Set<String> candidatePorts = new HashSet<>();
        for (RemoteRestoreActivationComponent component : components) {
            if (!seen.containsAll(component.dependsOn()) || !seen.add(component.componentId())) {
                throw new IllegalArgumentException("restore activation components are not dependency-first");
            }
            if (mode != RemoteRestoreActivationMode.PARALLEL_LOOPBACK && !component.ports().isEmpty()
                    || component.ports().stream().anyMatch(binding -> !candidatePorts.add(binding.protocol() + ":" + binding.candidatePort()))) {
                throw new IllegalArgumentException("restore activation ports differ from the selected mode");
            }
        }
        applicationHealthComponentId = id(applicationHealthComponentId, "applicationHealthComponentId");
        if (!seen.contains(applicationHealthComponentId)) {
            throw new IllegalArgumentException("application health owner is absent");
        }
        applicationHealthCheck = Objects.requireNonNull(applicationHealthCheck, "applicationHealthCheck");
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
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
