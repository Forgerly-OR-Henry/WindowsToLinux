package gold.debug.windowstolinux.shared.deploy.support.runtime;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Objects;

/** Matches one typed runtime against already collected host tool capabilities. / 将一个类型化运行时与已采集的主机工具能力进行匹配。 */
public final class RuntimeCapabilityEvaluator {
    private RuntimeCapabilityEvaluator() {
    }

    /** Evaluates runtime tools and versions without connecting to or mutating the host. / 在不连接或修改主机的情况下评估运行时工具与版本。 */
    public static RuntimeCapabilityDecision evaluate(
            LinuxCapabilityFacts capabilities,
            DeploymentProjectFacts facts,
            DeploymentRuntimeSpecification runtime
    ) {
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        facts = Objects.requireNonNull(facts, "facts");
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (facts.projectType() != runtime.projectType()) {
            throw new IllegalArgumentException("project facts and runtime must use the same type");
        }
        String detail = missingRuntime(capabilities, facts, runtime);
        return detail == null ? RuntimeCapabilityDecision.supportedRuntime()
                : RuntimeCapabilityDecision.unsupportedRuntime(detail);
    }

    private static String missingRuntime(LinuxCapabilityFacts capabilities, DeploymentProjectFacts facts,
                                         DeploymentRuntimeSpecification runtime) {
        return switch (runtime) {
            case DeploymentRuntimeSpecification.SpringBoot ignored -> {
                if (!capabilities.javaMajorVersions().contains(21)) {
                    yield "Java 21 is required for the Spring Boot build and runtime";
                }
                yield facts.buildTool() == DeploymentBuildToolType.MAVEN && !capabilities.mavenAvailable()
                        ? "Maven is required for the reviewed system Maven build" : null;
            }
            case DeploymentRuntimeSpecification.JavaJar javaJar ->
                    capabilities.javaMajorVersions().contains(Integer.parseInt(javaJar.javaVersion())) ? null
                            : "the selected Java major is not available";
            case DeploymentRuntimeSpecification.NodeService node ->
                    capabilities.npmAvailable() && capabilities.nodeMajorVersions().contains(node.nodeMajorVersion()) ? null
                            : "the selected Node.js major and npm must both be available";
            case DeploymentRuntimeSpecification.PythonService python ->
                    capabilities.pythonVersions().contains(python.pythonVersion()) ? null
                            : "the selected Python interpreter must provide venv support";
            case DeploymentRuntimeSpecification.StaticSite site -> site.nodeMajorVersion().isPresent()
                    ? capabilities.npmAvailable()
                    && capabilities.nodeMajorVersions().contains(site.nodeMajorVersion().getAsInt())
                    ? null : "the selected Node.js major and npm are required for the static build"
                    : capabilities.python3Available() ? null : "Python 3 is required for the managed static server";
            case DeploymentRuntimeSpecification.Container container -> switch (container.engine()) {
                case DOCKER -> capabilities.dockerAvailable() && capabilities.dockerOperational() ? null
                        : "the Docker client and daemon must be usable by the authenticated account";
                case PODMAN -> capabilities.podmanAvailable() && capabilities.podmanOperational()
                        && capabilities.podmanQuadletAvailable() ? null
                        : "Podman and Quadlet must be usable by the authenticated account";
            };
            case DeploymentRuntimeSpecification.GoService service ->
                    serviceVersion(capabilities, service.projectType(), service.version());
            case DeploymentRuntimeSpecification.RustService service ->
                    serviceVersion(capabilities, service.projectType(), service.version());
            case DeploymentRuntimeSpecification.DotNetService service ->
                    serviceVersion(capabilities, service.projectType(), service.version());
            case DeploymentRuntimeSpecification.KotlinService service ->
                    serviceVersion(capabilities, service.projectType(), service.version());
            case DeploymentRuntimeSpecification.PhpService service ->
                    serviceVersion(capabilities, service.projectType(), service.version());
            case DeploymentRuntimeSpecification.RubyService service ->
                    serviceVersion(capabilities, service.projectType(), service.version());
        };
    }

    private static String serviceVersion(
            LinuxCapabilityFacts capabilities,
            DeploymentProjectType projectType,
            String version
    ) {
        return capabilities.serviceRuntimeVersions().getOrDefault(projectType, java.util.Set.of()).contains(version)
                ? null : "the selected ecosystem service runtime version is not available";
    }
}
