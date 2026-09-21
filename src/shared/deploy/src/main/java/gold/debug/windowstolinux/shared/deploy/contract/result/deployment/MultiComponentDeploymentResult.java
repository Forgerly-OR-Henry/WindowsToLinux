package gold.debug.windowstolinux.shared.deploy.contract.result.deployment;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Application-level transaction result that always preserves every component outcome.
 *
 *  <p>始终保留每个组件结果的应用级事务结果。
 *
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param applicationEvents application events / 应用事件集合
 * @param componentResults component results / 组件结果集合
 * @param applicationReleaseIdentity application release identity / 应用发布身份
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param nonFatalFailures non fatal failures / 非致命失败集合
 * @param componentReleaseIdentities component release identities / 组件发布身份集合
 */
public record MultiComponentDeploymentResult(
        DeploymentStatus status,
        List<DeploymentEvent> applicationEvents,
        List<ComponentDeploymentResult> componentResults,
        Optional<String> applicationReleaseIdentity,
        OperationIdentity operationIdentity,
        List<FailureDescriptor> nonFatalFailures,
        java.util.Map<String, String> componentReleaseIdentities
) {
    /**
     * Validates application and component terminal consistency. / 验证应用与组件终态一致性。
     *
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param applicationEvents application events / 应用事件集合
     * @param componentResults component results / 组件结果集合
     * @param applicationReleaseIdentity application release identity / 应用发布身份
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param nonFatalFailures non fatal failures / 非致命失败集合
     * @param componentReleaseIdentities component release identities / 组件发布身份集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentDeploymentResult {
        componentReleaseIdentities = java.util.Map.copyOf(componentReleaseIdentities);
        if (!componentReleaseIdentities.isEmpty() && (status != DeploymentStatus.SUCCEEDED
                || !componentReleaseIdentities.keySet().equals(componentResults.stream().map(ComponentDeploymentResult::componentId).collect(java.util.stream.Collectors.toSet()))
                || componentReleaseIdentities.values().stream().anyMatch(value -> !value.matches("[0-9a-f]{64}"))))
            throw new IllegalArgumentException("published component identities must exactly cover a successful transaction");
        status = Objects.requireNonNull(status, "status");
        applicationEvents = List.copyOf(Objects.requireNonNull(applicationEvents, "applicationEvents"));
        componentResults = List.copyOf(Objects.requireNonNull(componentResults, "componentResults").stream()
                .sorted(java.util.Comparator.comparing(ComponentDeploymentResult::componentId)).toList());
        applicationReleaseIdentity = Objects.requireNonNull(applicationReleaseIdentity, "applicationReleaseIdentity");
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        OperationIdentity normalizedIdentity = operationIdentity;
        applicationEvents = applicationEvents.stream().map(event -> event.withOperationIdentity(normalizedIdentity)).toList();
        componentResults = componentResults.stream().map(component -> new ComponentDeploymentResult(
                component.componentId(), component.state(), component.events().stream()
                .map(event -> event.withOperationIdentity(normalizedIdentity)).toList(), component.observation())).toList();
        nonFatalFailures = List.copyOf(Objects.requireNonNull(nonFatalFailures, "nonFatalFailures").stream()
                .map(failure -> failure.withOperationIdentity(normalizedIdentity)).toList());
        if (nonFatalFailures.stream().anyMatch(failure -> failure.definition().severity() != FailureSeverityLevel.WARNING)) {
            throw new IllegalArgumentException("nonFatalFailures may contain warning definitions only");
        }
        if (status == DeploymentStatus.SUCCEEDED != applicationReleaseIdentity.isPresent()) {
            throw new IllegalArgumentException("only a successful application transaction may expose a release identity");
        }
        applicationReleaseIdentity.ifPresent(value -> {
            if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid application release identity");
        });
        if (status == DeploymentStatus.SUCCEEDED && componentResults.stream()
                .anyMatch(result -> result.state() != ComponentTransactionState.SUCCEEDED)) {
            throw new IllegalArgumentException("successful application transactions require every component to succeed");
        }
        if (componentResults.stream().anyMatch(result -> result.state() == ComponentTransactionState.MANUAL_RECOVERY_REQUIRED)
                && status != DeploymentStatus.MANUAL_RECOVERY_REQUIRED) {
            throw new IllegalArgumentException("a component recovery failure must make the application manual-recovery required");
        }
    }

    /**
     * Creates a result and derives one identity for application and component evidence. / 创建结果并为应用及组件证据派生同一标识。
     *
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param applicationEvents application events / 应用事件集合
     * @param componentResults component results / 组件结果集合
     * @param applicationReleaseIdentity application release identity / 应用发布身份
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param nonFatalFailures non fatal failures / 非致命失败集合
     */
    public MultiComponentDeploymentResult(DeploymentStatus status, List<DeploymentEvent> applicationEvents,
            List<ComponentDeploymentResult> componentResults, Optional<String> applicationReleaseIdentity,
            OperationIdentity operationIdentity, List<FailureDescriptor> nonFatalFailures) {
        this(status, applicationEvents, componentResults, applicationReleaseIdentity, operationIdentity, nonFatalFailures, java.util.Map.of());
    }

    /**
     * Returns the contract with the supplied component release identities applied.
     * <p>返回应用所提供组件发布身份集合后的契约。
     *
     * @param identities identities / 身份集合
     * @return the contract with the supplied component release identities applied / 应用所提供组件发布身份集合后的契约
     */
    public MultiComponentDeploymentResult withComponentReleaseIdentities(java.util.Map<String, String> identities) {
        return new MultiComponentDeploymentResult(status, applicationEvents, componentResults,
                applicationReleaseIdentity, operationIdentity, nonFatalFailures, identities);
    }

    /**
     * Initializes multi component deployment result through its shared constructor contract.
     * <p>通过共享构造契约初始化多组件部署结果。
     *
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param applicationEvents application events / 应用事件集合
     * @param componentResults component results / 组件结果集合
     * @param applicationReleaseIdentity application release identity / 应用发布身份
     */
    public MultiComponentDeploymentResult(DeploymentStatus status, List<DeploymentEvent> applicationEvents,
                                          List<ComponentDeploymentResult> componentResults,
                                          Optional<String> applicationReleaseIdentity) {
        this(status, applicationEvents, componentResults, applicationReleaseIdentity,
                identity(applicationEvents, componentResults), List.of());
    }

    /**
     * Adds a non-fatal warning without changing the authoritative remote result. / 添加非致命警告且不改变权威远端结果。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentDeploymentResult withNonFatalFailure(FailureDescriptor failure) {
        Objects.requireNonNull(failure, "failure");
        List<FailureDescriptor> warnings = new java.util.ArrayList<>(nonFatalFailures);
        warnings.add(failure.withOperationIdentity(operationIdentity));
        return new MultiComponentDeploymentResult(status, applicationEvents, componentResults,
                applicationReleaseIdentity, operationIdentity, warnings, componentReleaseIdentities);
    }

    /**
     * Reuses an operation identity already carried by failure evidence, creating one only when none exists.
     * <p>复用失败证据已携带的操作标识，仅在不存在时创建新标识。
     *
     * @param applicationEvents application events / 应用事件集合
     * @param componentResults component results / 组件结果集合
     * @return constructed or resolved operation identity / 构造或解析得到的操作身份
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static OperationIdentity identity(List<DeploymentEvent> applicationEvents,
                                              List<ComponentDeploymentResult> componentResults) {
        java.util.stream.Stream<DeploymentEvent> events = java.util.stream.Stream.concat(
                Objects.requireNonNull(applicationEvents, "applicationEvents").stream(),
                Objects.requireNonNull(componentResults, "componentResults").stream()
                        .flatMap(component -> component.events().stream()));
        return events.map(DeploymentEvent::failure).flatMap(Optional::stream)
                .map(FailureDescriptor::operationIdentity).findFirst().orElseGet(OperationIdentity::create);
    }
}
