package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.util.List;
import java.util.Objects;

/**
 * Reviewed inputs and stable managed identity for one application component.
 *
 *  <p>一个应用组件的经审阅输入与稳定受管身份。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
 * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
 * @param resolvedSecrets resolved secrets / 已解析秘密集合
 * @param resourceBindings resource bindings / 资源绑定集合
 */
public record ReviewedComponentDeployment(
        String componentId,
        ReviewedDeploymentRequest request,
        ManagedApplication application,
        List<ResolvedSecretRevision> resolvedSecrets,
        ManagedComponentResourceBindings resourceBindings
) {
    /**
     * Validates exact identity and secret-revision binding. / 验证精确身份与秘密修订绑定。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param resolvedSecrets resolved secrets / 已解析秘密集合
     * @param resourceBindings resource bindings / 资源绑定集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ReviewedComponentDeployment {
        componentId = Objects.requireNonNull(componentId, "componentId").trim();
        if (!componentId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("componentId must be a bounded managed identifier");
        }
        request = Objects.requireNonNull(request, "request");
        application = Objects.requireNonNull(application, "application");
        resolvedSecrets = List.copyOf(Objects.requireNonNull(resolvedSecrets, "resolvedSecrets"));
        resourceBindings = Objects.requireNonNull(resourceBindings, "resourceBindings");
        request = request.withFiles(resourceBindings.fileBindings());
        resourceBindings = new ManagedComponentResourceBindings(request.fileBindings(),resourceBindings.databaseBindings());
        if (!request.databaseBindings().equals(resourceBindings.databaseBindings())) throw new IllegalArgumentException("database resource bindings differ from reviewed request");
        if (!application.id().equals(request.facts().applicationId()) || !application.server().equals(request.server())) {
            throw new IllegalArgumentException("component request and managed identity must match");
        }
        if (!resolvedSecrets.stream().map(ResolvedSecretRevision::reference).toList().equals(request.secretReferences())) {
            throw new IllegalArgumentException("resolved component secrets must exactly match reviewed references");
        }
    }

    /**
     * Compatibility constructor for tests and callers with explicitly empty reviewed resources. / 为显式空审阅资源的测试及调用方提供兼容构造。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param resolvedSecrets resolved secrets / 已解析秘密集合
     */
    public ReviewedComponentDeployment(String componentId, ReviewedDeploymentRequest request,
                                       ManagedApplication application, List<ResolvedSecretRevision> resolvedSecrets) {
        this(componentId, request, application, resolvedSecrets,
                new ManagedComponentResourceBindings(request.fileBindings(), request.databaseBindings()));
    }
}
