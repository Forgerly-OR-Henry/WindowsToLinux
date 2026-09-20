package gold.debug.windowstolinux.shared.model.project.component;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Complete static record for one component inside a mixed project.
 *
 * <p>混合项目中一个组件的完整静态记录。
 *
 * @param componentId stable component identifier / 稳定组件标识符
 * @param sourceRoot normalized component source root / 规范化组件源码根
 * @param facts deterministic language, framework, build, and support facts / 确定性语言、框架、构建与支持事实
 * @param runtime reviewed typed runtime when deployable / 可部署时经审阅的类型化运行时
 * @param artifactPaths application-root-relative artifact paths / 相对应用根的产物路径
 * @param ports bound host ports / 绑定的宿主机端口
 * @param configurationKeys declared non-secret configuration keys / 声明的非秘密配置键
 * @param secretIdentifiers opaque secret identifiers / 透明秘密标识符
 * @param dataPaths persistent-data contracts / 持久化数据契约
 * @param dependencies component identifiers that must start first / 必须先启动的组件标识符
 * @param required whether the whole application requires this component / 整体应用是否需要此组件
 * @param isolation requested execution capabilities / 请求的执行能力
 */
public record DeploymentComponent(
        String componentId,
        Path sourceRoot,
        DeploymentProjectFacts facts,
        Optional<DeploymentRuntimeSpecification> runtime,
        List<String> artifactPaths,
        Set<Integer> ports,
        List<String> configurationKeys,
        List<String> secretIdentifiers,
        List<ComponentDataPath> dataPaths,
        Set<String> dependencies,
        boolean required,
        ComponentIsolationSpecification isolation
) {
    /** Validates a complete bounded component record. / 验证完整有界组件记录。 */
    public DeploymentComponent {
        componentId = identifier(componentId, "componentId");
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        facts = Objects.requireNonNull(facts, "facts");
        if (!sourceRoot.equals(facts.sourceRoot())) {
            throw new IllegalArgumentException("component source root must match analyzed project facts");
        }
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (runtime.isPresent() != facts.support().level().deployable()) {
            throw new IllegalArgumentException("runtime presence must match the component support level");
        }
        if (runtime.isPresent() && runtime.orElseThrow().projectType() != facts.projectType()) {
            throw new IllegalArgumentException("component runtime must match analyzed project facts");
        }
        artifactPaths = sortedDistinct(artifactPaths, DeploymentComponent::relativePath, "artifactPaths");
        if (runtime.isPresent() && artifactPaths.isEmpty()) {
            throw new IllegalArgumentException("deployable components require at least one bounded artifact path");
        }
        TreeSet<Integer> normalizedPorts = new TreeSet<>(Objects.requireNonNull(ports, "ports"));
        normalizedPorts.forEach(DeploymentComponent::requirePort);
        ports = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(normalizedPorts));
        configurationKeys = sortedDistinct(configurationKeys, DeploymentComponent::configurationKey, "configurationKeys");
        secretIdentifiers = sortedDistinct(secretIdentifiers,
                value -> identifier(value, "secret identifier"), "secretIdentifiers");
        dataPaths = List.copyOf(Objects.requireNonNull(dataPaths, "dataPaths"));
        if (dataPaths.stream().map(ComponentDataPath::path).distinct().count() != dataPaths.size()) {
            throw new IllegalArgumentException("component data paths must be unique");
        }
        dependencies = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(new TreeSet<>(
                Objects.requireNonNull(dependencies, "dependencies").stream()
                        .map(value -> identifier(value, "dependency")).toList())));
        isolation = Objects.requireNonNull(isolation, "isolation");
        if (runtime.isPresent() && runtime.orElseThrow().healthCheck().portNumber().isPresent()
                && !ports.contains(runtime.orElseThrow().healthCheck().portNumber().orElseThrow())) {
            throw new IllegalArgumentException("component ports must include the health-check port");
        }
    }

    /** Returns the declared health policy when the component is deployable. / 在组件可部署时返回声明的健康策略。 */
    public Optional<HealthCheck> healthCheck() {
        return runtime.map(DeploymentRuntimeSpecification::healthCheck);
    }


    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a bounded managed identifier");
        }
        return value;
    }

    private static String relativePath(String value) {
        value = Objects.requireNonNull(value, "artifact path").trim().replace('\\', '/');
        if (value.equals(".") || !value.matches("[A-Za-z0-9._/-]{1,255}") || value.startsWith("/")
                || value.contains("..") || value.contains("//")) {
            throw new IllegalArgumentException("artifact paths must be bounded and relative to the application root");
        }
        return value;
    }

    private static String configurationKey(String value) {
        value = Objects.requireNonNull(value, "configuration key").trim();
        if (!value.matches("[A-Z][A-Z0-9_]{0,63}") || value.matches(".*_(PASSWORD|SECRET|TOKEN|KEY)$")) {
            throw new IllegalArgumentException("configuration keys must be non-secret environment identifiers");
        }
        return value;
    }

    private static void requirePort(int value) {
        if (value < 1 || value > 65535) throw new IllegalArgumentException("component ports must be valid ports");
    }

    private static List<String> sortedDistinct(List<String> values,
                                               java.util.function.Function<String, String> normalizer,
                                               String name) {
        Objects.requireNonNull(values, name);
        List<String> normalized = values.stream().map(normalizer).sorted().toList();
        if (normalized.stream().distinct().count() != normalized.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
        return normalized;
    }
}
