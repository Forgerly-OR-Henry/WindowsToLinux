package gold.debug.windowstolinux.shared.deploy.support.runtime;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.EcosystemToolType;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
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
            case DeploymentRuntimeSpecification.JavaSource ignored ->
                    capabilities.javaMajorVersions().contains(21) && hasMajor(capabilities, EcosystemToolType.JAVAC, "21")
                            && hasTool(capabilities, EcosystemToolType.JAR) ? null
                            : "Java source requires Java 21, javac 21, and the JDK jar tool";
            case DeploymentRuntimeSpecification.NodeService node -> {
                EcosystemToolType packageManager = switch (facts.buildTool()) {
                    case NPM -> EcosystemToolType.NPM;
                    case PNPM -> EcosystemToolType.PNPM;
                    case YARN -> EcosystemToolType.YARN;
                    default -> throw new IllegalArgumentException("Node service facts require one package manager");
                };
                yield capabilities.nodeMajorVersions().contains(node.nodeMajorVersion())
                        && compatibleNodePackageManager(capabilities, packageManager)
                        ? null : "the selected Node.js major and package manager must both be available";
            }
            case DeploymentRuntimeSpecification.PythonService python -> {
                EcosystemToolType dependencyTool = switch (facts.buildTool()) {
                    case PIP_LOCKED -> EcosystemToolType.PIP;
                    case PIPENV_LOCKED -> EcosystemToolType.PIPENV;
                    case POETRY_LOCKED -> EcosystemToolType.POETRY;
                    case UV_LOCKED -> EcosystemToolType.UV;
                    default -> throw new IllegalArgumentException("Python service facts require one dependency architecture");
                };
                yield capabilities.pythonVersions().contains(python.pythonVersion())
                        && compatiblePythonTool(capabilities, dependencyTool)
                        ? null : "the selected Python interpreter and dependency tool must both be available";
            }
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
            case DeploymentRuntimeSpecification.KotlinService service -> {
                boolean java21 = capabilities.javaMajorVersions().contains(21);
                boolean compiler = facts.buildTool() != DeploymentBuildToolType.KOTLINC
                        || hasVersion(capabilities, EcosystemToolType.KOTLINC, service.version());
                yield java21 && compiler ? null
                        : "the selected Kotlin architecture requires its exact compiler and Java 21";
            }
            case DeploymentRuntimeSpecification.PhpService service ->
                    serviceVersion(capabilities, service.projectType(), service.version()) == null
                            && (facts.buildTool() == DeploymentBuildToolType.PHP_CLI
                            || hasTool(capabilities, EcosystemToolType.COMPOSER)) ? null
                            : "the selected PHP runtime and dependency tool are not available";
            case DeploymentRuntimeSpecification.RubyService service ->
                    serviceVersion(capabilities, service.projectType(), service.version()) == null
                            && (facts.buildTool() == DeploymentBuildToolType.RUBY_CLI
                            || hasTool(capabilities, EcosystemToolType.BUNDLER)) ? null
                            : "the selected Ruby runtime and dependency tool are not available";
            case DeploymentRuntimeSpecification.CmakeService ignored ->
                    !facts.languageFacts().sourceLanguages().isEmpty()
                            && anyVersion(capabilities, EcosystemToolType.CMAKE,
                                    segments -> segments[0] > 3 || segments[0] == 3 && segments[1] >= 25)
                            && hasTool(capabilities, EcosystemToolType.NINJA)
                            && (!facts.languageFacts().sourceLanguages().contains(SourceLanguageType.C)
                            || hasTool(capabilities, EcosystemToolType.C_COMPILER))
                            && (!facts.languageFacts().sourceLanguages().contains(SourceLanguageType.CPP)
                            || hasTool(capabilities, EcosystemToolType.CPP_COMPILER)) ? null
                            : "CMake, Ninja, and every compiler required by the reviewed source language set must be available";
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

    private static boolean hasTool(LinuxCapabilityFacts capabilities, EcosystemToolType tool) {
        return !capabilities.ecosystemToolVersions().getOrDefault(tool, java.util.Set.of()).isEmpty();
    }

    private static boolean hasVersion(LinuxCapabilityFacts capabilities, EcosystemToolType tool, String version) {
        return capabilities.ecosystemToolVersions().getOrDefault(tool, java.util.Set.of()).contains(version);
    }

    private static boolean hasMajor(LinuxCapabilityFacts capabilities, EcosystemToolType tool, String major) {
        return capabilities.ecosystemToolVersions().getOrDefault(tool, java.util.Set.of()).stream()
                .anyMatch(version -> version.equals(major) || version.startsWith(major + "."));
    }

    private static boolean compatibleNodePackageManager(
            LinuxCapabilityFacts capabilities,
            EcosystemToolType tool
    ) {
        return switch (tool) {
            case NPM -> hasTool(capabilities, tool);
            case PNPM -> anyVersion(capabilities, tool, segments -> segments[0] >= 9 && segments[0] <= 11);
            case YARN -> anyVersion(capabilities, tool, segments -> segments[0] == 4);
            default -> false;
        };
    }

    private static boolean compatiblePythonTool(
            LinuxCapabilityFacts capabilities,
            EcosystemToolType tool
    ) {
        return switch (tool) {
            case PIP, PIPENV -> hasTool(capabilities, tool);
            case POETRY -> anyVersion(capabilities, tool, segments -> segments[0] == 2);
            case UV -> anyVersion(capabilities, tool,
                    segments -> segments[0] > 0 || segments[1] >= 4);
            default -> false;
        };
    }

    private static boolean anyVersion(
            LinuxCapabilityFacts capabilities,
            EcosystemToolType tool,
            java.util.function.Predicate<int[]> accepted
    ) {
        return capabilities.ecosystemToolVersions().getOrDefault(tool, java.util.Set.of()).stream()
                .map(RuntimeCapabilityEvaluator::numericVersion)
                .filter(Objects::nonNull)
                .anyMatch(accepted);
    }

    private static int[] numericVersion(String version) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+)(?:[.](\\d+))?").matcher(version);
        if (!matcher.find()) return null;
        return new int[]{Integer.parseInt(matcher.group(1)), matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2))};
    }
}
