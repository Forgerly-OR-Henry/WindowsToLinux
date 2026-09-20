package gold.debug.windowstolinux.app.service.deployment.automatic;


import gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser;
import gold.debug.windowstolinux.shared.deploy.input.DeploymentRuntimeParser;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

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
        var secrets = gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser.secrets(input.secretReferences());
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
        var secrets = gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser.secrets(input.secretReferences());
        var databases = DeploymentRuntimeParser.databaseBindings(input.databaseMode(), input.databaseDetails());
        return new MultiComponentReviewInput(input.componentId(),
                ConfigurationSnapshot.create(managedApplicationId, Instant.now().toEpochMilli(), "runtime-v1",
                        Instant.now(), entries), secrets, databases, userAccess(),
                BuildLimitConfiguration.defaultNonRoot(),
                containerRiskAccepted, experimentalRiskAccepted);
    }

    /** Returns the typed health contract. / 返回类型化健康契约。 */
    public HealthCheck healthCheck() {
        return new gold.debug.windowstolinux.shared.deploy.input.AutomaticRuntimeResolver().health(values());
    }

    private java.util.Map<String,String> values() {
        var values = new java.util.LinkedHashMap<String,String>();
        values.put("version",input.runtimeVersion()); values.put("primary",input.runtimePrimary());
        values.put("secondary",input.runtimeSecondary()); values.put("jvmTarget",input.kotlinJvmTarget());
        values.put("jvmArguments",input.runtimeArguments()); values.put("arguments",input.runtimeAdditional());
        values.put("healthMode",input.healthMode().name()); values.put("timeout",input.timeoutSeconds());
        values.put("stability",input.stabilitySeconds()); values.put("expectedStatus",input.expectedStatus());
        values.put("applicationDeclaration",input.applicationDeclaration()); values.put("accessUrl",input.userAccessUrl());
        if(input.healthMode()==ComponentHealthMode.HTTP) {
            values.put("healthEndpoint",input.healthEndpoint());
            var endpoint=URI.create(input.healthEndpoint());
            values.put("port",Integer.toString(endpoint.getPort()>0?endpoint.getPort():"https".equals(endpoint.getScheme())?443:80));
        } else if(input.healthMode()==ComponentHealthMode.TCP || input.healthMode()==ComponentHealthMode.UDP) values.put("port",input.healthEndpoint());
        if(input.projectType()==DeploymentProjectType.PYTHON_SERVICE) values.put("primary",input.runtimeVersion());
        if(input.projectType()==DeploymentProjectType.DOCKERFILE_CONTAINER) {
            values.put("containerEngine",input.runtimePrimary().toUpperCase(Locale.ROOT));
            values.put("ports",input.runtimeSecondary()); values.put("volumes",input.runtimeAdditional());
        }
        return gold.debug.windowstolinux.shared.deploy.input.ApplicationDeclaration.completed(values);
    }
    private DeploymentRuntimeSpecification runtime(HealthCheck health) {
        var resolver=new gold.debug.windowstolinux.shared.deploy.input.AutomaticRuntimeResolver();
        var values=values();
        if(!resolver.missing(input.componentId(),input.projectType(),values).isEmpty())
            throw new IllegalArgumentException("Complete the application service exposure and runtime declaration before review");
        return resolver.runtime(input.projectType(),values);
    }
    private Optional<UserAccessUrl> userAccess() {
        var workload=gold.debug.windowstolinux.shared.deploy.input.ApplicationDeclaration.resolve(values());
        if(workload.category()!=gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.CategoryType.WEBSITE)
            return Optional.empty();
        String declared=workload.endpoints().stream().map(endpoint -> endpoint.accessUrl()).filter(url -> !url.isBlank()).findFirst().orElse(input.userAccessUrl());
        return declared.isBlank()?Optional.empty():Optional.of(new UserAccessUrl(URI.create(declared)));
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
