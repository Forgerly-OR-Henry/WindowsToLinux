package gold.debug.windowstolinux.shared.deploy.contract.result.deployment;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Terminal deployment outcome, including the distinct rollback result.
 *
 *  <p>部署终态结果，包括独立的回滚结果。
 *
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param events ordered progress or transaction events / 有序进度或事务事件
 * @param finalObservation final observation / 最终观测
 * @param publishedReleaseSha256 the published release identity digest / 已发布的发布身份摘要
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param nonFatalFailures non fatal failures / 非致命失败集合
 */
public record DeploymentResult(
        DeploymentStatus status,
        List<DeploymentEvent> events,
        Optional<LifecycleObservation> finalObservation,
        Optional<String> publishedReleaseSha256,
        OperationIdentity operationIdentity,
        List<FailureDescriptor> nonFatalFailures
) {
    /**
     * Validates and binds the inputs required by deployment result.
     * <p>校验并绑定部署结果所需输入。
     *
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param finalObservation final observation / 最终观测
     * @param publishedReleaseSha256 the published release identity digest / 已发布的发布身份摘要
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param nonFatalFailures non fatal failures / 非致命失败集合
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentResult {
        status = Objects.requireNonNull(status, "status");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        finalObservation = Objects.requireNonNull(finalObservation, "finalObservation");
        publishedReleaseSha256 = Objects.requireNonNull(publishedReleaseSha256, "publishedReleaseSha256");
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        OperationIdentity normalizedIdentity = operationIdentity;
        events = events.stream().map(event -> event.withOperationIdentity(normalizedIdentity)).toList();
        nonFatalFailures = List.copyOf(Objects.requireNonNull(nonFatalFailures, "nonFatalFailures").stream()
                .map(failure -> failure.withOperationIdentity(normalizedIdentity)).toList());
        if (nonFatalFailures.stream().anyMatch(failure -> failure.definition().severity() != FailureSeverityLevel.WARNING)) {
            throw new IllegalArgumentException("nonFatalFailures may contain warning definitions only");
        }
        if (status == DeploymentStatus.SUCCEEDED != publishedReleaseSha256.isPresent()) {
            throw new IllegalArgumentException("only a successful deployment may report its published release identity");
        }
        publishedReleaseSha256.ifPresent(digest -> {
            if (!digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("publishedReleaseSha256 must be a lowercase SHA-256");
            }
        });
    }

    /**
     * Creates a result and derives one operation identity for all carried failures. / 创建结果并为全部携带失败派生同一操作标识。
     *
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param finalObservation final observation / 最终观测
     * @param publishedReleaseSha256 the published release identity digest / 已发布的发布身份摘要
     */
    public DeploymentResult(DeploymentStatus status, List<DeploymentEvent> events,
                            Optional<LifecycleObservation> finalObservation,
                            Optional<String> publishedReleaseSha256) {
        this(status, events, finalObservation, publishedReleaseSha256, identity(events), List.of());
    }

    /**
     * Adds a non-fatal warning without changing the authoritative remote result. / 添加非致命警告且不改变权威远端结果。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return constructed or resolved deployment result / 构造或解析得到的部署结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentResult withNonFatalFailure(FailureDescriptor failure) {
        Objects.requireNonNull(failure, "failure");
        List<FailureDescriptor> warnings = new java.util.ArrayList<>(nonFatalFailures);
        warnings.add(failure.withOperationIdentity(operationIdentity));
        return new DeploymentResult(status, events, finalObservation, publishedReleaseSha256,
                operationIdentity, warnings);
    }

    /**
     * Reuses an operation identity already carried by failure evidence, creating one only when none exists.
     * <p>复用失败证据已携带的操作标识，仅在不存在时创建新标识。
     *
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @return constructed or resolved operation identity / 构造或解析得到的操作身份
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static OperationIdentity identity(List<DeploymentEvent> events) {
        return Objects.requireNonNull(events, "events").stream().map(DeploymentEvent::failure)
                .flatMap(Optional::stream).map(FailureDescriptor::operationIdentity).findFirst()
                .orElseGet(OperationIdentity::create);
    }
}
