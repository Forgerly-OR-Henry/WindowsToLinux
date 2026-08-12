package gold.debug.windowstolinux.shared.analyze.component;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationRequirements;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * User-reviewed component boundaries supplied to deterministic mixed-project analysis.
 *
 * <p>提供给确定性混合项目分析的用户审阅组件边界。
 */
public record ComponentAnalysisRequest(
        String componentId,
        String relativeSourceRoot,
        DeploymentProjectType projectType,
        Optional<DeploymentRuntimeSpecification> runtime,
        List<String> artifactPaths,
        Set<Integer> ports,
        List<String> configurationKeys,
        List<String> secretIdentifiers,
        List<ComponentDataPath> dataPaths,
        Set<String> dependencies,
        boolean required,
        ComponentIsolationRequirements isolation
) {
    /** Validates a bounded explicit component selection without accessing source. / 在不访问源码的情况下验证有界显式组件选择。 */
    public ComponentAnalysisRequest {
        componentId = identifier(componentId, "componentId");
        relativeSourceRoot = relativeRoot(relativeSourceRoot);
        projectType = Objects.requireNonNull(projectType, "projectType");
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (projectType.deployable() != runtime.isPresent()) {
            throw new IllegalArgumentException("deployable component selections require a matching typed runtime");
        }
        if (runtime.isPresent() && runtime.orElseThrow().projectType() != projectType) {
            throw new IllegalArgumentException("selected component runtime must match its project type");
        }
        artifactPaths = List.copyOf(Objects.requireNonNull(artifactPaths, "artifactPaths"));
        ports = Set.copyOf(Objects.requireNonNull(ports, "ports"));
        configurationKeys = List.copyOf(Objects.requireNonNull(configurationKeys, "configurationKeys"));
        secretIdentifiers = List.copyOf(Objects.requireNonNull(secretIdentifiers, "secretIdentifiers"));
        dataPaths = List.copyOf(Objects.requireNonNull(dataPaths, "dataPaths"));
        dependencies = Set.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        isolation = Objects.requireNonNull(isolation, "isolation");
    }

    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a bounded managed identifier");
        }
        return value;
    }

    private static String relativeRoot(String value) {
        value = Objects.requireNonNull(value, "relativeSourceRoot").trim().replace('\\', '/');
        if (!(value.equals(".") || value.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*"))
                || value.startsWith("/") || value.contains("..") || value.contains("//")) {
            throw new IllegalArgumentException("relativeSourceRoot must stay inside the selected application root");
        }
        return value;
    }
}
