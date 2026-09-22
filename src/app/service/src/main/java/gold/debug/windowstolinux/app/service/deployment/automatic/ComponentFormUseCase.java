package gold.debug.windowstolinux.app.service.deployment.automatic;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

import gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput;
import gold.debug.windowstolinux.app.service.contract.definition.ComponentHealthMode;
import gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput;
import gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.shared.standard.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.shared.standard.deploy.input.DeploymentRuntimeParser;

/**
 * Parses component form state inside the service boundary. / 在服务边界内解析组件表单状态。
 */
public final class ComponentFormUseCase {
    /**
     * Source content consumed by this operation.
     * <p>当前操作消费的源内容。
     */
    private final ComponentFormInput input;

    /**
     * Binds immutable form input for review. / 绑定待审阅的不可变表单输入。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ComponentFormUseCase(ComponentFormInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    /**
     * Returns the deterministic static-analysis request. / 返回确定性静态分析请求。
     *
     * @return the deterministic static-analysis request / 确定性静态分析请求
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public ComponentAnalysisRequest analysisRequest() {
        HealthCheck health = healthCheck();
        var runtime = runtime(health);
        var configuration = DeploymentConfigurationParser.parse(input.configurationEntries());
        var secrets = gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser
                .secrets(input.secretReferences());
        var databases = DeploymentRuntimeParser.databaseBindings(input.databaseMode(), input.databaseDetails());
        databases.orElseThrow().stream().map(binding -> binding.connection()).filter(
                gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection.Server.class::isInstance)
                .map(gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection.Server.class::cast)
                .map(connection -> connection.passwordReference()).filter(reference -> !secrets.contains(reference))
                .findFirst().ifPresent(reference -> {
                    throw new IllegalArgumentException(
                            "database password reference must be included in component secrets");
                });
        return new ComponentAnalysisRequest(input.componentId(), input.relativeSourceRoot(), input.projectType(),
                Optional.of(runtime), tokens(input.artifactPaths()), ports(input.declaredPorts()),
                configuration.stream().map(value -> value.key()).toList(),
                secrets.stream().map(value -> value.identifier()).distinct().toList(), List.of(),
                identifiers(input.dependencies()), input.required(), ComponentIsolationSpecification.managed());
    }

    /**
     * Returns deployment-time reviewed inputs bound to the analyzed managed identity. / 返回绑定已分析受管身份的部署期审阅输入。
     *
     * @param managedApplicationId managed application id / 受管应用标识
     * @param containerRiskAccepted container risk accepted / 容器风险已接受
     * @param experimentalRiskAccepted experimental risk accepted / 实验性风险已接受
     * @return deployment-time reviewed inputs bound to the analyzed managed identity / 绑定已分析受管身份的部署期审阅输入
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public MultiComponentReviewInput reviewInput(String managedApplicationId, boolean containerRiskAccepted,
            boolean experimentalRiskAccepted) {
        if (input.rootBuild())
            throw new IllegalArgumentException(
                    "Root builds are no longer supported; review the draft with restricted execution");
        var entries = DeploymentConfigurationParser.parse(input.configurationEntries());
        var secrets = gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser
                .secrets(input.secretReferences());
        var databases = DeploymentRuntimeParser.databaseBindings(input.databaseMode(), input.databaseDetails());
        return new MultiComponentReviewInput(input.componentId(),
                ConfigurationSnapshot.create(managedApplicationId, Instant.now().toEpochMilli(), "runtime-v1",
                        Instant.now(), entries),
                secrets, databases, userAccess(), BuildLimitConfiguration.defaultNonRoot(), containerRiskAccepted,
                experimentalRiskAccepted);
    }

    /**
     * Returns the typed health contract. / 返回类型化健康契约。
     *
     * @return the typed health contract / 类型化健康契约
     */
    public HealthCheck healthCheck() {
        return new gold.debug.windowstolinux.shared.standard.deploy.input.AutomaticRuntimeResolver().health(values());
    }

    /**
     * Projects the edited component form into the deterministic deployment input map.
     * <p>将编辑后的组件表单投影为确定性部署输入映射。
     *
     * @return constructed or resolved map / 构造或解析得到的映射
     */
    private java.util.Map<String, String> values() {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("version", input.runtimeVersion());
        values.put("primary", input.runtimePrimary());
        values.put("secondary", input.runtimeSecondary());
        values.put("jvmTarget", input.kotlinJvmTarget());
        values.put("jvmArguments", input.runtimeArguments());
        values.put("arguments", input.runtimeAdditional());
        values.put("healthMode", input.healthMode().name());
        values.put("timeout", input.timeoutSeconds());
        values.put("stability", input.stabilitySeconds());
        values.put("expectedStatus", input.expectedStatus());
        values.put("applicationDeclaration", input.applicationDeclaration());
        values.put("accessUrl", input.userAccessUrl());
        if (input.healthMode() == ComponentHealthMode.HTTP) {
            values.put("healthEndpoint", input.healthEndpoint());
            var endpoint = URI.create(input.healthEndpoint());
            values.put("port", Integer.toString(
                    endpoint.getPort() > 0 ? endpoint.getPort() : "https".equals(endpoint.getScheme()) ? 443 : 80));
        } else if (input.healthMode() == ComponentHealthMode.TCP || input.healthMode() == ComponentHealthMode.UDP)
            values.put("port", input.healthEndpoint());
        if (input.projectType() == DeploymentProjectType.PYTHON_SERVICE)
            values.put("primary", input.runtimeVersion());
        if (input.projectType() == DeploymentProjectType.DOCKERFILE_CONTAINER) {
            values.put("containerEngine", input.runtimePrimary().toUpperCase(Locale.ROOT));
            values.put("ports", input.runtimeSecondary());
            values.put("volumes", input.runtimeAdditional());
        }
        return gold.debug.windowstolinux.shared.standard.deploy.input.ApplicationDeclaration.completed(values);
    }

    /**
     * Rejects incomplete component inputs and resolves their deployment runtime with the selected health check.
     * <p>拒绝未补全的组件输入，并结合所选健康检查解析部署运行规格。
     *
     * @param health health / 健康
     * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private DeploymentRuntimeSpecification runtime(HealthCheck health) {
        var resolver = new gold.debug.windowstolinux.shared.standard.deploy.input.AutomaticRuntimeResolver();
        var values = values();
        if (!resolver.missing(input.componentId(), input.projectType(), values).isEmpty())
            throw new IllegalArgumentException(
                    "Complete the application service exposure and runtime declaration before review");
        return resolver.runtime(input.projectType(), values);
    }

    /**
     * Returns user access.
     * <p>返回用户访问。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private Optional<UserAccessUrl> userAccess() {
        var workload = gold.debug.windowstolinux.shared.standard.deploy.input.ApplicationDeclaration.resolve(values());
        if (workload
                .category() != gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.CategoryType.WEBSITE)
            return Optional.empty();
        String declared = workload.endpoints().stream().map(endpoint -> endpoint.accessUrl())
                .filter(url -> !url.isBlank()).findFirst().orElse(input.userAccessUrl());
        return declared.isBlank() ? Optional.empty() : Optional.of(new UserAccessUrl(URI.create(declared)));
    }

    /**
     * Validates and returns HTTP protocol and rejects inputs outside the declared constraints.
     * <p>校验并返回HTTP 协议并拒绝超出已声明约束的输入。
     *
     * @param health health / 健康
     * @return constructed or resolved http / 构造或解析得到的HTTP
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static HealthCheck.Http requireHttp(HealthCheck health) {
        if (health instanceof HealthCheck.Http http)
            return http;
        throw new IllegalArgumentException("static-site components require HTTP health");
    }

    /**
     * Splits comma- or semicolon-separated values, trims them and returns sorted distinct nonblank tokens.
     * <p>拆分逗号或分号分隔的值，去除首尾空白，并返回排序去重的非空项。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    private static List<String> tokens(String value) {
        return value.isBlank()
                ? List.of()
                : Arrays.stream(value.split("[,;]")).map(String::trim).filter(item -> !item.isBlank()).distinct()
                        .sorted().toList();
    }

    /**
     * Converts normalized form tokens into an immutable identifier set.
     * <p>将规范化表单项转换为不可变标识集合。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved set / 构造或解析得到的集合
     */
    private static Set<String> identifiers(String value) {
        return Set.copyOf(tokens(value));
    }

    /**
     * Parses normalized form tokens as integer ports and removes duplicates.
     * <p>将规范化表单项解析为整数端口并去重。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return normalized form tokens as integer ports and removes duplicates / 将规范化表单项解析为整数端口并去重
     */
    private static Set<Integer> ports(String value) {
        TreeSet<Integer> ports = new TreeSet<>();
        tokens(value).forEach(item -> ports.add(Integer.parseInt(item)));
        return Set.copyOf(ports);
    }

}
