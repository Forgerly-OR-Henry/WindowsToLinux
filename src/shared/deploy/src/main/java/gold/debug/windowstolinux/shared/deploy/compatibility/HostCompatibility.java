package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates only collected host facts; it does not connect, install packages, or claim runtime acceptance.
 *
 * <p>仅评估已采集的主机事实；不连接、不安装软件包，也不声明运行环境验收。
 */
public final class HostCompatibility {
    private HostCompatibility() {
    }

    /**
     * Evaluates the selected runtime against live host facts.
     *
     * <p>根据实时主机事实评估所选运行时。
     *
     * @param capabilities collected host facts / 已采集的主机事实
     * @param runtime selected typed runtime / 所选类型化运行时
     * @return conservative compatibility outcome / 保守兼容性结果
     */
    public static Result evaluate(LinuxCapabilities capabilities, DeploymentProjectFacts facts,
                                  DeploymentRuntimeSpecification runtime) {
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        facts = Objects.requireNonNull(facts, "facts");
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (facts.projectType() != runtime.projectType()) {
            throw new IllegalArgumentException("project facts and runtime must use the same type");
        }
        List<String> evidence = new ArrayList<>();
        if (!capabilities.x86_64()) {
            return result(HostSupport.UNSUPPORTED, "architecture must be x86_64", evidence);
        }
        if (!capabilities.systemdAvailable()) {
            return result(HostSupport.UNSUPPORTED, "systemd is required for managed lifecycle", evidence);
        }
        HostSupport base = baseSupport(capabilities, evidence);
        if (base != HostSupport.READY_FOR_RUNTIME_VALIDATION) {
            return new Result(base, List.copyOf(evidence));
        }
        String missingRuntime = missingRuntime(capabilities, facts, runtime);
        if (missingRuntime != null) {
            return result(HostSupport.UNSUPPORTED, missingRuntime, evidence);
        }
        evidence.add("runtime matrix matches collected host facts only");
        return new Result(HostSupport.READY_FOR_RUNTIME_VALIDATION, List.copyOf(evidence));
    }

    private static String missingRuntime(LinuxCapabilities capabilities, DeploymentProjectFacts facts,
                                         DeploymentRuntimeSpecification runtime) {
        return switch (runtime) {
            case DeploymentRuntimeSpecification.SpringBoot ignored -> {
                if (!capabilities.javaMajorVersions().contains(21)) {
                    yield "Java 21 is required for the Spring Boot build and runtime";
                }
                yield facts.buildTool() == DeploymentBuildTool.MAVEN && !capabilities.mavenAvailable()
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
                    ? capabilities.npmAvailable() && capabilities.nodeMajorVersions().contains(site.nodeMajorVersion().getAsInt())
                    ? null : "the selected Node.js major and npm are required for the static build"
                    : capabilities.python3Available() ? null : "Python 3 is required for the managed static server";
            case DeploymentRuntimeSpecification.Container container -> switch (container.engine()) {
                case DOCKER -> capabilities.dockerAvailable() && capabilities.dockerOperational() ? null
                        : "the Docker client and daemon must be usable by the authenticated account";
                case PODMAN -> capabilities.podmanAvailable() && capabilities.podmanOperational()
                        && capabilities.podmanQuadletAvailable() ? null
                        : "Podman and Quadlet must be usable by the authenticated account";
            };
        };
    }

    private static HostSupport baseSupport(LinuxCapabilities capabilities, List<String> evidence) {
        return switch (capabilities.distro()) {
            case UBUNTU -> {
                if (!"apt".equals(capabilities.packageManager())
                        || !("22.04".equals(capabilities.version()) || "24.04".equals(capabilities.version()))) {
                    yield result(HostSupport.UNSUPPORTED, "Ubuntu must be 22.04 or 24.04 with apt", evidence).support();
                }
                yield HostSupport.READY_FOR_RUNTIME_VALIDATION;
            }
            case CENTOS_STREAM -> {
                if (!"dnf".equals(capabilities.packageManager())
                        || !("9".equals(capabilities.version()) || "10".equals(capabilities.version()))) {
                    yield result(HostSupport.UNSUPPORTED, "CentOS Stream must be 9 or 10 with dnf", evidence).support();
                }
                if ("10".equals(capabilities.version())) {
                    if (!capabilities.x86_64V3Available()) {
                        evidence.add("CentOS Stream 10 requires a runtime-linker-confirmed cumulative x86-64-v3 level");
                        yield HostSupport.REQUIRES_CPU_REVIEW;
                    }
                    evidence.add("CentOS Stream 10 host runtime linker reports the cumulative x86-64-v3 level as supported");
                }
                yield HostSupport.READY_FOR_RUNTIME_VALIDATION;
            }
            case LEGACY_CENTOS -> {
                evidence.add("discontinued CentOS requires explicit maintenance and repository-risk acknowledgement");
                yield HostSupport.LEGACY_RISK_CONFIRMATION_REQUIRED;
            }
            case OTHER -> result(HostSupport.UNSUPPORTED, "distribution is outside the typed deployment matrix", evidence).support();
        };
    }

    private static Result result(HostSupport support, String detail, List<String> evidence) {
        evidence.add(detail);
        return new Result(support, List.copyOf(evidence));
    }

    /**
     * A structured decision with no live-state assertion.
     *
     * <p>不包含实时状态断言的结构化决策。
     *
     * @param support conservative matrix status / 保守矩阵状态
     * @param evidence decision evidence / 决策证据
     */
    public record Result(HostSupport support, List<String> evidence) {
        /** Creates a {@code Result} instance. / 创建 {@code Result} 实例。 */
        public Result {
            support = Objects.requireNonNull(support, "support");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        }
    }
}
