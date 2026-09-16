package gold.debug.windowstolinux.app.service.deployment.automatic;


import gold.debug.windowstolinux.app.service.config.DeploymentConfigurationParser;
import gold.debug.windowstolinux.app.service.deployment.automatic.DeploymentRuntimeParser;

import gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput;
import gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;

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

/** Parses component form state inside the service boundary. / 在服务边界内解析组件表单状态。 */
public final class ComponentFormUseCase {
    private final ComponentFormInput input;

    /** Binds immutable form input for review. / 绑定待审阅的不可变表单输入。 */
    public ComponentFormUseCase(ComponentFormInput input) { this.input = Objects.requireNonNull(input, "input"); }

    /** Returns the deterministic static-analysis request. / 返回确定性静态分析请求。 */
    public ComponentAnalysisRequest analysisRequest() {
        HealthCheck health = healthCheck();
        var runtime = runtime(health);
        var configuration = DeploymentConfigurationParser.parse(input.configurationEntries());
        var secrets = gold.debug.windowstolinux.app.service.config.DeploymentConfigurationParser.secrets(input.secretReferences());
        var databases = DeploymentRuntimeParser.databaseBindings(input.databaseMode(), input.databaseDetails());
        databases.orElseThrow().stream()
                .map(binding -> binding.connection())
                .filter(gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection.Server.class::isInstance)
                .map(gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection.Server.class::cast)
                .map(connection -> connection.passwordReference())
                .filter(reference -> !secrets.contains(reference))
                .findFirst().ifPresent(reference -> {
                    throw new IllegalArgumentException("database password reference must be included in component secrets");
                });
        return new ComponentAnalysisRequest(input.componentId(), input.relativeSourceRoot(), input.projectType(), Optional.of(runtime),
                tokens(input.artifactPaths()), ports(input.declaredPorts()), configuration.stream().map(value -> value.key()).toList(),
                secrets.stream().map(value -> value.identifier()).distinct().toList(), List.of(),
                identifiers(input.dependencies()), input.required(), ComponentIsolationSpecification.managed());
    }

    /** Returns deployment-time reviewed inputs bound to the analyzed managed identity. / 返回绑定已分析受管身份的部署期审阅输入。 */
    public MultiComponentReviewInput reviewInput(String managedApplicationId, boolean containerRiskAccepted,
                                          boolean experimentalRiskAccepted) {
        if (input.rootBuild()) throw new IllegalArgumentException("Root builds are no longer supported; review the draft with restricted execution");
        var entries = DeploymentConfigurationParser.parse(input.configurationEntries());
        var secrets = gold.debug.windowstolinux.app.service.config.DeploymentConfigurationParser.secrets(input.secretReferences());
        var databases = DeploymentRuntimeParser.databaseBindings(input.databaseMode(), input.databaseDetails());
        return new MultiComponentReviewInput(input.componentId(),
                ConfigurationSnapshot.create(managedApplicationId, Instant.now().toEpochMilli(), "runtime-v1",
                        Instant.now(), entries), secrets, databases, userAccess(),
                BuildLimitConfiguration.defaultNonRoot(),
                containerRiskAccepted, experimentalRiskAccepted);
    }

    /** Returns the typed health contract. / 返回类型化健康契约。 */
    public HealthCheck healthCheck() {
        int timeout = Integer.parseInt(input.timeoutSeconds().trim());
        return switch (input.healthMode()) {
            case HTTP -> new HealthCheck.Http(URI.create(input.healthEndpoint().trim()),
                    Integer.parseInt(input.expectedStatus().trim()), timeout);
            case TCP -> new HealthCheck.Tcp(Integer.parseInt(input.healthEndpoint().trim()), timeout,
                    Integer.parseInt(input.stabilitySeconds().trim()));
        };
    }

    private DeploymentRuntimeSpecification runtime(HealthCheck health) {
        return switch (input.projectType()) {
            case SPRING_BOOT -> new DeploymentRuntimeSpecification.SpringBoot(input.runtimeVersion(), health);
            case JAVA_JAR -> new DeploymentRuntimeSpecification.JavaJar(input.runtimePrimary(), input.runtimeSecondary(),
                    input.runtimeVersion(), DeploymentRuntimeParser.arguments(input.runtimeArguments()),
                    DeploymentRuntimeParser.arguments(input.runtimeAdditional()), health);
            case JAVA_SOURCE -> new DeploymentRuntimeSpecification.JavaSource(input.runtimePrimary(), input.runtimeSecondary(),
                    input.runtimeVersion(), DeploymentRuntimeParser.arguments(input.runtimeArguments()),
                    DeploymentRuntimeParser.arguments(input.runtimeAdditional()), health);
            case NODE_SERVICE -> new DeploymentRuntimeSpecification.NodeService(
                    Integer.parseInt(input.runtimeVersion().trim()), health);
            case PYTHON_SERVICE -> new DeploymentRuntimeSpecification.PythonService(
                    input.runtimeVersion(), input.runtimeSecondary(), health);
            case STATIC_SITE -> new DeploymentRuntimeSpecification.StaticSite(input.runtimePrimary(),
                    input.runtimeVersion().isBlank() ? OptionalInt.empty()
                            : OptionalInt.of(Integer.parseInt(input.runtimeVersion().trim())), requireHttp(health));
            case DOCKERFILE_CONTAINER -> new DeploymentRuntimeSpecification.Container(
                    DeploymentRuntimeSpecification.ContainerEngineType.valueOf(input.runtimePrimary().toUpperCase(Locale.ROOT)),
                    DeploymentRuntimeParser.ports(input.runtimeSecondary()),
                    DeploymentRuntimeParser.volumes(input.runtimeAdditional()), health);
            case KOTLIN_SERVICE -> new DeploymentRuntimeSpecification.KotlinService(input.runtimeVersion(), input.runtimePrimary(), input.runtimeSecondary(), input.kotlinJvmTarget(), health);
            case GO_SERVICE, RUST_SERVICE, DOTNET_SERVICE, PHP_SERVICE, RUBY_SERVICE ->
                    DeploymentRuntimeParser.service(input.projectType(), input.runtimeVersion(), input.runtimePrimary(), input.runtimeSecondary(), health);
            case CMAKE_SERVICE -> new DeploymentRuntimeSpecification.CmakeService(input.runtimeVersion(), input.runtimeSecondary(),
                    input.runtimePrimary(), health);
            case RECOGNITION_PREVIEW -> throw new IllegalArgumentException(
                    "recognition-preview components cannot enter deployment review");
        };
    }

    private Optional<UserAccessUrl> userAccess() {
        if (input.healthMode() == ComponentHealthMode.HTTP) {
            if (input.userAccessUrl().isBlank()) throw new IllegalArgumentException("HTTP components require a user access URL");
            return Optional.of(new UserAccessUrl(URI.create(input.userAccessUrl())));
        }
        if (!input.userAccessUrl().isBlank()) throw new IllegalArgumentException("TCP components cannot declare a user access URL");
        return Optional.empty();
    }

    private static HealthCheck.Http requireHttp(HealthCheck health) {
        if (health instanceof HealthCheck.Http http) return http;
        throw new IllegalArgumentException("static-site components require HTTP health");
    }

    private static List<String> tokens(String value) {
        return value.isBlank() ? List.of() : Arrays.stream(value.split("[,;]"))
                .map(String::trim).filter(item -> !item.isBlank()).distinct().sorted().toList();
    }

    private static Set<String> identifiers(String value) {
        return Set.copyOf(tokens(value));
    }

    private static Set<Integer> ports(String value) {
        TreeSet<Integer> ports = new TreeSet<>();
        tokens(value).forEach(item -> ports.add(Integer.parseInt(item)));
        return Set.copyOf(ports);
    }

}
