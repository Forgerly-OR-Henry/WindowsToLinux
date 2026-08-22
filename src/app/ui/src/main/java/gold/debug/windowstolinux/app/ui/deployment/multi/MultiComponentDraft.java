package gold.debug.windowstolinux.app.ui.deployment.multi;

import gold.debug.windowstolinux.app.ui.deployment.DeploymentConfigurationParser;
import gold.debug.windowstolinux.app.ui.deployment.DeploymentRuntimeParser;

import gold.debug.windowstolinux.app.service.deployment.multi.MultiComponentReviewInput;
import gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
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

/**
 * Secret-value-free typed form state for one explicitly reviewed component.
 *
 * <p>一个显式审阅组件不含秘密值的类型化表单状态。
 */
record MultiComponentDraft(
        String componentId,
        String relativeSourceRoot,
        DeploymentProjectType projectType,
        String runtimePrimary,
        String runtimeSecondary,
        String runtimeVersion,
        String runtimeArguments,
        String runtimeAdditional,
        MultiComponentHealthMode healthMode,
        String healthEndpoint,
        String expectedStatus,
        String timeoutSeconds,
        String stabilitySeconds,
        String userAccessUrl,
        String artifactPaths,
        String declaredPorts,
        String dependencies,
        String configurationEntries,
        DeploymentRuntimeParser.DatabaseReviewMode databaseMode,
        String databaseDetails,
        String secretReferences,
        boolean required,
        boolean rootBuild
) {
    /** Preserves only bounded text and typed selections. / 仅保留有界文本与类型化选择。 */
    MultiComponentDraft {
        componentId = text(componentId);
        relativeSourceRoot = text(relativeSourceRoot);
        projectType = Objects.requireNonNull(projectType, "projectType");
        runtimePrimary = text(runtimePrimary);
        runtimeSecondary = text(runtimeSecondary);
        runtimeVersion = text(runtimeVersion);
        runtimeArguments = text(runtimeArguments);
        runtimeAdditional = text(runtimeAdditional);
        healthMode = Objects.requireNonNull(healthMode, "healthMode");
        healthEndpoint = text(healthEndpoint);
        expectedStatus = text(expectedStatus);
        timeoutSeconds = text(timeoutSeconds);
        stabilitySeconds = text(stabilitySeconds);
        userAccessUrl = text(userAccessUrl);
        artifactPaths = text(artifactPaths);
        declaredPorts = text(declaredPorts);
        dependencies = text(dependencies);
        configurationEntries = text(configurationEntries);
        databaseMode = Objects.requireNonNull(databaseMode, "databaseMode");
        databaseDetails = text(databaseDetails);
        secretReferences = text(secretReferences);
    }

    /** Returns the deterministic static-analysis request. / 返回确定性静态分析请求。 */
    ComponentAnalysisRequest analysisRequest() {
        HealthCheck health = healthCheck();
        var runtime = runtime(health);
        var configuration = DeploymentConfigurationParser.parse(configurationEntries);
        var secrets = DeploymentRuntimeParser.secrets(secretReferences);
        var databases = DeploymentRuntimeParser.databaseBindings(databaseMode, databaseDetails);
        databases.orElseThrow().stream()
                .map(binding -> binding.connection())
                .filter(gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection.Server.class::isInstance)
                .map(gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection.Server.class::cast)
                .map(connection -> connection.passwordReference())
                .filter(reference -> !secrets.contains(reference))
                .findFirst().ifPresent(reference -> {
                    throw new IllegalArgumentException("database password reference must be included in component secrets");
                });
        return new ComponentAnalysisRequest(componentId, relativeSourceRoot, projectType, Optional.of(runtime),
                tokens(artifactPaths), ports(declaredPorts), configuration.stream().map(value -> value.key()).toList(),
                secrets.stream().map(value -> value.identifier()).distinct().toList(), List.of(),
                identifiers(dependencies), required, ComponentIsolationSpecification.managed());
    }

    /** Returns deployment-time reviewed inputs bound to the analyzed managed identity. / 返回绑定已分析受管身份的部署期审阅输入。 */
    MultiComponentReviewInput reviewInput(String managedApplicationId, boolean containerRiskAccepted,
                                          boolean experimentalRiskAccepted) {
        var entries = DeploymentConfigurationParser.parse(configurationEntries);
        var secrets = DeploymentRuntimeParser.secrets(secretReferences);
        var databases = DeploymentRuntimeParser.databaseBindings(databaseMode, databaseDetails);
        return new MultiComponentReviewInput(componentId,
                ConfigurationSnapshot.create(managedApplicationId, Instant.now().toEpochMilli(), "runtime-v1",
                        Instant.now(), entries), secrets, databases, userAccess(),
                rootBuild ? new BuildLimitConfiguration(1800, 1024, 4096, 4L * 1024 * 1024,
                        4L * 1024 * 1024 * 1024, true) : BuildLimitConfiguration.defaultNonRoot(),
                containerRiskAccepted, experimentalRiskAccepted);
    }

    /** Returns the typed health contract. / 返回类型化健康契约。 */
    HealthCheck healthCheck() {
        int timeout = Integer.parseInt(timeoutSeconds.trim());
        return switch (healthMode) {
            case HTTP -> new HealthCheck.Http(URI.create(healthEndpoint.trim()),
                    Integer.parseInt(expectedStatus.trim()), timeout);
            case TCP -> new HealthCheck.Tcp(Integer.parseInt(healthEndpoint.trim()), timeout,
                    Integer.parseInt(stabilitySeconds.trim()));
        };
    }

    private DeploymentRuntimeSpecification runtime(HealthCheck health) {
        return switch (projectType) {
            case SPRING_BOOT -> new DeploymentRuntimeSpecification.SpringBoot(health);
            case JAVA_JAR -> new DeploymentRuntimeSpecification.JavaJar(runtimePrimary, runtimeSecondary,
                    runtimeVersion, DeploymentRuntimeParser.arguments(runtimeArguments),
                    DeploymentRuntimeParser.arguments(runtimeAdditional), health);
            case JAVA_SOURCE -> new DeploymentRuntimeSpecification.JavaSource(runtimePrimary, runtimeSecondary,
                    runtimeVersion, DeploymentRuntimeParser.arguments(runtimeArguments),
                    DeploymentRuntimeParser.arguments(runtimeAdditional), health);
            case NODE_SERVICE -> new DeploymentRuntimeSpecification.NodeService(
                    Integer.parseInt(runtimeVersion.trim()), health);
            case PYTHON_SERVICE -> new DeploymentRuntimeSpecification.PythonService(
                    runtimeVersion, runtimeSecondary, health);
            case STATIC_SITE -> new DeploymentRuntimeSpecification.StaticSite(runtimePrimary,
                    runtimeVersion.isBlank() ? OptionalInt.empty()
                            : OptionalInt.of(Integer.parseInt(runtimeVersion.trim())), requireHttp(health));
            case DOCKERFILE_CONTAINER -> new DeploymentRuntimeSpecification.Container(
                    DeploymentRuntimeSpecification.ContainerEngineType.valueOf(runtimePrimary.toUpperCase(Locale.ROOT)),
                    DeploymentRuntimeParser.ports(runtimeSecondary),
                    DeploymentRuntimeParser.volumes(runtimeAdditional), health);
            case GO_SERVICE, RUST_SERVICE, DOTNET_SERVICE, KOTLIN_SERVICE, PHP_SERVICE, RUBY_SERVICE ->
                    DeploymentRuntimeParser.service(projectType, runtimeVersion, runtimePrimary, runtimeSecondary, health);
            case CMAKE_SERVICE -> new DeploymentRuntimeSpecification.CmakeService(runtimeVersion, runtimeSecondary,
                    runtimePrimary, health);
            case RECOGNITION_PREVIEW -> throw new IllegalArgumentException(
                    "recognition-preview components cannot enter deployment review");
        };
    }

    private Optional<UserAccessUrl> userAccess() {
        if (healthMode == MultiComponentHealthMode.HTTP) {
            if (userAccessUrl.isBlank()) throw new IllegalArgumentException("HTTP components require a user access URL");
            return Optional.of(new UserAccessUrl(URI.create(userAccessUrl)));
        }
        if (!userAccessUrl.isBlank()) throw new IllegalArgumentException("TCP components cannot declare a user access URL");
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

    private static String text(String value) {
        value = Objects.requireNonNull(value, "form value").trim();
        if (value.length() > 4096) throw new IllegalArgumentException("component form value is too long");
        return value;
    }
}
