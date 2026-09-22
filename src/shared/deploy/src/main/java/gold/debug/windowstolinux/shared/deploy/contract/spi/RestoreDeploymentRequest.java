package gold.debug.windowstolinux.shared.deploy.contract.spi;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.deployment.ReleaseSetDigest;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;

/**
 * Complete typed activation request for one already staged restore candidate. / 单个已暂存恢复候选的完整类型化激活请求。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param targetServerId target server id / 目标服务器标识
 * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
 * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
 * @param releaseSetSha256 release set sha 256 / 发布集合SHA256
 * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
 * @param remoteCandidateRoot remote candidate root / 远端候选根目录
 * @param candidateToken candidate token / 候选令牌
 * @param components reviewed components in the application graph / 应用图中的已审阅组件
 * @param applicationHealthComponentId application health component id / 应用健康组件标识
 * @param applicationHealthCheck independently reviewed whole-application probe / 独立审阅的整应用探测
 * @param isolatedDatabase isolated database / 隔离数据库
 */
public record RestoreDeploymentRequest(String applicationId, String targetServerId, String candidateId,
        String archiveSha256, String releaseSetSha256, List<SecretReference> secretReferences,
        String remoteCandidateRoot, String candidateToken, List<RestoreDeploymentComponent> components,
        String applicationHealthComponentId, HealthCheck applicationHealthCheck, boolean isolatedDatabase) {
    /**
     * SECRET ORDER.
     * <p>秘密顺序。
     */
    private static final Comparator<SecretReference> SECRET_ORDER = Comparator.comparing(SecretReference::identifier)
            .thenComparingLong(SecretReference::revision);

    /**
     * Validates digest binding, dependency order and whole-application health ownership. / 校验摘要绑定、依赖顺序和整应用健康归属。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param targetServerId target server id / 目标服务器标识
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
     * @param releaseSetSha256 release set sha 256 / 发布集合SHA256
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param remoteCandidateRoot remote candidate root / 远端候选根目录
     * @param candidateToken candidate token / 候选令牌
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param applicationHealthComponentId application health component id / 应用健康组件标识
     * @param applicationHealthCheck independently reviewed whole-application probe / 独立审阅的整应用探测
     * @param isolatedDatabase isolated database / 隔离数据库
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RestoreDeploymentRequest {
        applicationId = managedId(applicationId, "applicationId");
        targetServerId = managedId(targetServerId, "targetServerId");
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256").trim().toLowerCase(Locale.ROOT);
        if (!archiveSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("archiveSha256 must be canonical SHA-256");
        }
        releaseSetSha256 = Objects.requireNonNull(releaseSetSha256, "releaseSetSha256").trim().toLowerCase(Locale.ROOT);
        if (!releaseSetSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("releaseSetSha256 must be canonical SHA-256");
        }
        secretReferences = List.copyOf(Objects.requireNonNull(secretReferences, "secretReferences"));
        if (secretReferences.size() > 64 || secretReferences.stream().anyMatch(Objects::isNull)
                || !secretReferences.equals(secretReferences.stream().sorted(SECRET_ORDER).toList())
                || secretReferences.stream().distinct().count() != secretReferences.size()) {
            throw new IllegalArgumentException("secretReferences must be canonical and unique by exact revision");
        }
        candidateId = Objects.requireNonNull(candidateId, "candidateId").trim();
        if (!candidateId.equals(applicationId + "-" + archiveSha256.substring(0, 16))) {
            throw new IllegalArgumentException("candidateId is not bound to the archive digest");
        }
        remoteCandidateRoot = Objects.requireNonNull(remoteCandidateRoot, "remoteCandidateRoot").trim();
        if (!remoteCandidateRoot.equals("/var/lib/windowstolinux/work/" + candidateId + "/mutable/restore")) {
            throw new IllegalArgumentException("remoteCandidateRoot is outside the staged candidate");
        }
        candidateToken = Objects.requireNonNull(candidateToken, "candidateToken").trim();
        if (!candidateToken.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("candidateToken is invalid");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (components.isEmpty() || components.size() > 256) {
            throw new IllegalArgumentException("components must contain one to 256 reviewed values");
        }
        Set<String> seen = new LinkedHashSet<>();
        Set<String> managed = new HashSet<>();
        for (RestoreDeploymentComponent component : components) {
            if (!managed.add(component.managedApplicationId()) || !seen.containsAll(component.dependsOn())
                    || !seen.add(component.componentId())) {
                throw new IllegalArgumentException("restore components are duplicated or not dependency-first");
            }
        }
        String expectedReleaseSet = ReleaseSetDigest.sha256(components.stream().map(
                component -> new ReleaseSetDigest.ComponentRelease(component.componentId(), component.releaseSha256()))
                .toList());
        if (!releaseSetSha256.equals(expectedReleaseSet)) {
            throw new IllegalArgumentException("releaseSetSha256 differs from the dependency-ordered components");
        }
        Set<SecretReference> componentSecrets = new LinkedHashSet<>();
        components.forEach(component -> componentSecrets.addAll(component.secretReferences()));
        if (!componentSecrets.equals(new LinkedHashSet<>(secretReferences))) {
            throw new IllegalArgumentException("application secretReferences must equal the component reference union");
        }
        applicationHealthComponentId = managedId(applicationHealthComponentId, "applicationHealthComponentId");
        if (!seen.contains(applicationHealthComponentId)) {
            throw new IllegalArgumentException("application health is not owned by a restored component");
        }
        applicationHealthCheck = Objects.requireNonNull(applicationHealthCheck, "applicationHealthCheck");
    }

    /**
     * Initializes restore deployment request through its shared constructor contract.
     * <p>通过共享构造契约初始化恢复部署请求。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param targetServerId target server id / 目标服务器标识
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
     * @param releaseSetSha256 release set sha 256 / 发布集合SHA256
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param remoteCandidateRoot remote candidate root / 远端候选根目录
     * @param candidateToken candidate token / 候选令牌
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param applicationHealthComponentId application health component id / 应用健康组件标识
     * @param applicationHealthCheck independently reviewed whole-application probe / 独立审阅的整应用探测
     */
    public RestoreDeploymentRequest(String applicationId, String targetServerId, String candidateId,
            String archiveSha256, String releaseSetSha256, List<SecretReference> secretReferences,
            String remoteCandidateRoot, String candidateToken, List<RestoreDeploymentComponent> components,
            String applicationHealthComponentId, HealthCheck applicationHealthCheck) {
        this(applicationId, targetServerId, candidateId, archiveSha256, releaseSetSha256, secretReferences,
                remoteCandidateRoot, candidateToken, components, applicationHealthComponentId, applicationHealthCheck,
                false);
    }

    /**
     * Validates a managed identifier before it reaches a remote resource boundary.
     * <p>在标识到达远端资源边界前验证受管标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return managed id text / 受管标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String managedId(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }
}
