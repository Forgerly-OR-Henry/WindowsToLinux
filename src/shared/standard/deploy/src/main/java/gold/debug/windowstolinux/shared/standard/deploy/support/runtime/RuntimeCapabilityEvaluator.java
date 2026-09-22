package gold.debug.windowstolinux.shared.standard.deploy.support.runtime;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.capability.EcosystemToolType;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.standard.deploy.toolchain.ProjectToolchainRequirements;

/**
 * Matches one typed runtime against already collected host tool capabilities. / 将一个类型化运行时与已采集的主机工具能力进行匹配。
 */
public final class RuntimeCapabilityEvaluator {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private RuntimeCapabilityEvaluator() {
    }

    /**
     * Checks the requested runtime and any resolved toolchain bindings against observed server capabilities.
     * <p>根据已观测服务器能力检查请求运行规格及已解析工具链绑定。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param tools tools / 工具集合
     * @return constructed or resolved runtime capability decision / 构造或解析得到的运行时能力决定
     */
    public static RuntimeCapabilityDecision evaluate(LinuxCapabilityFacts capabilities, DeploymentProjectFacts facts,
            DeploymentRuntimeSpecification runtime,
            gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet tools) {
        if (tools.selections().isEmpty())
            return evaluate(capabilities, facts, runtime);
        if (!runtime.workload().companions().isEmpty() && !companionBuildTools(capabilities))
            return RuntimeCapabilityDecision
                    .unsupportedRuntime("Companion builds require CMake 3.25 or later and Ninja");
        var catalog = gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.defaults();
        for (var requirement : gold.debug.windowstolinux.shared.standard.deploy.toolchain.ProjectToolchainRequirements
                .from(facts, runtime)) {
            var candidates = catalog.candidates(requirement);
            if (candidates.isEmpty())
                return RuntimeCapabilityDecision
                        .unsupportedRuntime("No supported toolchain candidate: " + requirement.declaration());
            if (candidates.stream().allMatch(candidate -> candidate
                    .installation() == gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.InstallationType.SYSTEM_COMPILER)) {
                EcosystemToolType compiler = requirement
                        .ecosystem() == gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.C
                                ? EcosystemToolType.C_COMPILER
                                : EcosystemToolType.CPP_COMPILER;
                if (!hasTool(capabilities, compiler))
                    return RuntimeCapabilityDecision
                            .unsupportedRuntime("The declared companion compiler is unavailable: " + compiler);
                continue;
            }
            var selected = tools.selections().stream().filter(s -> s.requirement().equals(requirement)).findFirst();
            if (selected.isEmpty() || !catalog.permits(selected.orElseThrow().version()) || candidates.stream()
                    .noneMatch(b -> b.version().equals(selected.orElseThrow().version().branch())))
                return RuntimeCapabilityDecision
                        .unsupportedRuntime("Prepared toolchain does not match the reviewed requirement");
        }
        return RuntimeCapabilityDecision.supportedRuntime();
    }

    /**
     * Checks the requested runtime and any resolved toolchain bindings against observed server capabilities.
     * <p>根据已观测服务器能力检查请求运行规格及已解析工具链绑定。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved runtime capability decision / 构造或解析得到的运行时能力决定
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static RuntimeCapabilityDecision evaluate(LinuxCapabilityFacts capabilities, DeploymentProjectFacts facts,
            DeploymentRuntimeSpecification runtime) {
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        facts = Objects.requireNonNull(facts, "facts");
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (facts.projectType() != runtime.projectType()) {
            throw new IllegalArgumentException("project facts and runtime must use the same type");
        }
        if (!runtime.workload().companions().isEmpty() && !companionBuildTools(capabilities))
            return RuntimeCapabilityDecision
                    .unsupportedRuntime("Companion builds require CMake 3.25 or later and Ninja");
        for (var requirement : gold.debug.windowstolinux.shared.standard.deploy.toolchain.ProjectToolchainRequirements
                .from(facts, runtime)) {
            if (gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.defaults()
                    .candidates(requirement).isEmpty())
                return RuntimeCapabilityDecision
                        .unsupportedRuntime("No supported toolchain candidate: " + requirement.declaration());
        }
        String detail = missingRuntime(capabilities, facts, runtime);
        return detail == null
                ? RuntimeCapabilityDecision.supportedRuntime()
                : RuntimeCapabilityDecision.unsupportedRuntime(detail);
    }

    /**
     * Tests the companion build tools predicate against the supplied evidence.
     * <p>根据所提供证据检查配套单元构建工具集合条件。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @return true when companion build tools predicate against the supplied evidence, false otherwise / 根据所提供证据检查配套单元构建工具集合条件时为 true，否则为 false
     */
    private static boolean companionBuildTools(LinuxCapabilityFacts capabilities) {
        return anyVersion(capabilities, EcosystemToolType.CMAKE,
                version -> version[0] > 3 || version[0] == 3 && version[1] >= 25)
                && hasTool(capabilities, EcosystemToolType.NINJA);
    }

    /**
     * Returns the reason a requested runtime is unavailable, or null when the observed capabilities satisfy it.
     * <p>返回请求运行环境不可用的原因；观测能力满足要求时返回 null。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return missing runtime reason, or null when the capability is available / 缺失运行环境原因；能力可用时为 null
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String missingRuntime(LinuxCapabilityFacts capabilities, DeploymentProjectFacts facts,
            DeploymentRuntimeSpecification runtime) {
        return switch (runtime) {
            case DeploymentRuntimeSpecification.ManagedProcess ignored ->
                throw new IllegalArgumentException("generic runtime requires artifact evidence");
            case DeploymentRuntimeSpecification.SpringBoot java -> {
                if (!capabilities.javaMajorVersions().contains(Integer.parseInt(java.javaVersion()))) {
                    yield "The declared Java version requires preparation for the Spring Boot build and runtime";
                }
                yield facts.buildTool() == DeploymentBuildToolType.MAVEN && !capabilities.mavenAvailable()
                        ? "Maven is required for the reviewed system Maven build"
                        : null;
            }
            case DeploymentRuntimeSpecification.JavaJar javaJar ->
                capabilities.javaMajorVersions().contains(Integer.parseInt(javaJar.javaVersion()))
                        ? null
                        : "the selected Java major is not available";
            case DeploymentRuntimeSpecification.JavaSource java ->
                capabilities.javaMajorVersions().contains(Integer.parseInt(java.javaVersion()))
                        && hasMajor(capabilities, EcosystemToolType.JAVAC, java.javaVersion())
                        && hasTool(capabilities, EcosystemToolType.JAR)
                                ? null
                                : "Java source requires its declared JDK, javac, and jar tools";
            case DeploymentRuntimeSpecification.NodeService node -> {
                EcosystemToolType packageManager = switch (facts.buildTool()) {
                    case NPM -> EcosystemToolType.NPM;
                    case PNPM -> EcosystemToolType.PNPM;
                    case YARN -> EcosystemToolType.YARN;
                    default -> throw new IllegalArgumentException("Node service facts require one package manager");
                };
                yield capabilities.nodeMajorVersions().contains(node.nodeMajorVersion())
                        && compatibleNodePackageManager(capabilities, packageManager)
                                ? null
                                : "the selected Node.js major and package manager must both be available";
            }
            case DeploymentRuntimeSpecification.PythonService python -> {
                EcosystemToolType dependencyTool = switch (facts.buildTool()) {
                    case PYTHON_STDLIB -> null;
                    case PIP_LOCKED -> EcosystemToolType.PIP;
                    case PIPENV_LOCKED -> EcosystemToolType.PIPENV;
                    case POETRY_LOCKED -> EcosystemToolType.POETRY;
                    case UV_LOCKED -> EcosystemToolType.UV;
                    default ->
                        throw new IllegalArgumentException("Python service facts require one dependency architecture");
                };
                yield capabilities.pythonVersions().contains(python.pythonVersion())
                        && (dependencyTool == null || compatiblePythonTool(capabilities, dependencyTool))
                                ? null
                                : "the selected Python interpreter and dependency tool must both be available";
            }
            case DeploymentRuntimeSpecification.StaticSite site -> site.nodeMajorVersion().isPresent()
                    ? capabilities.npmAvailable()
                            && capabilities.nodeMajorVersions().contains(site.nodeMajorVersion().getAsInt())
                                    ? null
                                    : "the selected Node.js major and npm are required for the static build"
                    : capabilities.python3Available() ? null : "Python 3 is required for the managed static server";
            case DeploymentRuntimeSpecification.Container container -> switch (container.engine()) {
                case DOCKER -> capabilities.dockerAvailable() && capabilities.dockerOperational()
                        ? null
                        : "the Docker client and daemon must be usable by the authenticated account";
                case PODMAN -> capabilities.podmanAvailable() && capabilities.podmanOperational()
                        && capabilities.podmanQuadletAvailable()
                                ? null
                                : "Podman and Quadlet must be usable by the authenticated account";
            };
            case DeploymentRuntimeSpecification.GoService service ->
                serviceVersion(capabilities, service.projectType(), service.version());
            case DeploymentRuntimeSpecification.RustService service ->
                serviceVersion(capabilities, service.projectType(), service.version());
            case DeploymentRuntimeSpecification.DotNetService service ->
                serviceVersion(capabilities, service.projectType(), service.version());
            case DeploymentRuntimeSpecification.KotlinService service -> {
                boolean java = capabilities.javaMajorVersions().contains(Integer.parseInt(service.jvmTarget()));
                boolean compiler = facts.buildTool() != DeploymentBuildToolType.KOTLINC
                        || hasVersion(capabilities, EcosystemToolType.KOTLINC, service.version());
                yield java && compiler
                        ? null
                        : "the selected Kotlin architecture requires its declared compiler and JVM toolchain";
            }
            case DeploymentRuntimeSpecification.PhpService service ->
                serviceVersion(capabilities, service.projectType(), service.version()) == null
                        && (facts.buildTool() == DeploymentBuildToolType.PHP_CLI
                                || hasTool(capabilities, EcosystemToolType.COMPOSER))
                                        ? null
                                        : "the selected PHP runtime and dependency tool are not available";
            case DeploymentRuntimeSpecification.RubyService service ->
                serviceVersion(capabilities, service.projectType(), service.version()) == null
                        && (facts.buildTool() == DeploymentBuildToolType.RUBY_CLI
                                || hasTool(capabilities, EcosystemToolType.BUNDLER))
                                        ? null
                                        : "the selected Ruby runtime and dependency tool are not available";
            case DeploymentRuntimeSpecification.CmakeService ignored -> !facts.languageFacts().sourceLanguages()
                    .isEmpty()
                    && anyVersion(capabilities, EcosystemToolType.CMAKE,
                            segments -> segments[0] > 3 || segments[0] == 3 && segments[1] >= 25)
                    && hasTool(capabilities, EcosystemToolType.NINJA)
                    && (!facts.languageFacts().sourceLanguages().contains(SourceLanguageType.C)
                            || hasTool(capabilities, EcosystemToolType.C_COMPILER))
                    && (!facts.languageFacts().sourceLanguages().contains(SourceLanguageType.CPP)
                            || hasTool(capabilities, EcosystemToolType.CPP_COMPILER))
                                    ? null
                                    : "CMake, Ninja, and every compiler required by the reviewed source language set must be available";
        };
    }

    /**
     * Returns null when the exact service runtime version is installed, otherwise returns an unavailable-runtime reason.
     * <p>精确服务运行版本已安装时返回 null，否则返回运行环境不可用原因。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @return missing version reason, or null when the exact version is available / 缺失版本原因；精确版本可用时为 null
     */
    private static String serviceVersion(LinuxCapabilityFacts capabilities, DeploymentProjectType projectType,
            String version) {
        return capabilities.serviceRuntimeVersions().getOrDefault(projectType, java.util.Set.of()).contains(version)
                ? null
                : "the selected ecosystem service runtime version is not available";
    }

    /**
     * Reports whether the tool condition holds for this contract.
     * <p>判断当前契约是否满足工具条件。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param tool tool / 工具
     * @return true when tool condition holds for this contract, false otherwise / 当前契约是否满足工具条件时为 true，否则为 false
     */
    private static boolean hasTool(LinuxCapabilityFacts capabilities, EcosystemToolType tool) {
        return !capabilities.ecosystemToolVersions().getOrDefault(tool, java.util.Set.of()).isEmpty();
    }

    /**
     * Reports whether the version of the relevant protocol, configuration or runtime condition holds for this contract.
     * <p>判断当前契约是否满足相应协议、配置或运行时的版本条件。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param tool tool / 工具
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @return true when version of the relevant protocol, configuration or runtime condition holds for this contract, false otherwise / 当前契约是否满足相应协议、配置或运行时的版本条件时为 true，否则为 false
     */
    private static boolean hasVersion(LinuxCapabilityFacts capabilities, EcosystemToolType tool, String version) {
        return capabilities.ecosystemToolVersions().getOrDefault(tool, java.util.Set.of()).contains(version);
    }

    /**
     * Reports whether the major condition holds for this contract.
     * <p>判断当前契约是否满足主版本条件。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param tool tool / 工具
     * @param major major / 主版本
     * @return true when major condition holds for this contract, false otherwise / 当前契约是否满足主版本条件时为 true，否则为 false
     */
    private static boolean hasMajor(LinuxCapabilityFacts capabilities, EcosystemToolType tool, String major) {
        return capabilities.ecosystemToolVersions().getOrDefault(tool, java.util.Set.of()).stream()
                .anyMatch(version -> version.equals(major) || version.startsWith(major + "."));
    }

    /**
     * Tests the compatible node package manager predicate against the supplied evidence.
     * <p>根据所提供证据检查兼容节点软件包管理器条件。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param tool tool / 工具
     * @return true when compatible node package manager predicate against the supplied evidence, false otherwise / 根据所提供证据检查兼容节点软件包管理器条件时为 true，否则为 false
     */
    private static boolean compatibleNodePackageManager(LinuxCapabilityFacts capabilities, EcosystemToolType tool) {
        return switch (tool) {
            case NPM -> hasTool(capabilities, tool);
            case PNPM -> anyVersion(capabilities, tool, segments -> segments[0] >= 8);
            case YARN -> anyVersion(capabilities, tool, segments -> segments[0] >= 2);
            default -> false;
        };
    }

    /**
     * Tests the compatible python tool predicate against the supplied evidence.
     * <p>根据所提供证据检查兼容Python工具条件。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param tool tool / 工具
     * @return true when compatible python tool predicate against the supplied evidence, false otherwise / 根据所提供证据检查兼容Python工具条件时为 true，否则为 false
     */
    private static boolean compatiblePythonTool(LinuxCapabilityFacts capabilities, EcosystemToolType tool) {
        return switch (tool) {
            case PIP, PIPENV -> hasTool(capabilities, tool);
            case POETRY -> anyVersion(capabilities, tool, segments -> segments[0] >= 2);
            case UV -> anyVersion(capabilities, tool, segments -> segments[0] > 0 || segments[1] >= 4);
            default -> false;
        };
    }

    /**
     * Tests the any version predicate against the supplied evidence.
     * <p>根据所提供证据检查任意版本条件。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param tool tool / 工具
     * @param accepted accepted / 已接受
     * @return true when any version predicate against the supplied evidence, false otherwise / 根据所提供证据检查任意版本条件时为 true，否则为 false
     */
    private static boolean anyVersion(LinuxCapabilityFacts capabilities, EcosystemToolType tool,
            java.util.function.Predicate<int[]> accepted) {
        return capabilities.ecosystemToolVersions().getOrDefault(tool, java.util.Set.of()).stream()
                .map(RuntimeCapabilityEvaluator::numericVersion).filter(Objects::nonNull).anyMatch(accepted);
    }

    /**
     * Parses major and optional minor numeric version components, returning null for unsupported syntax.
     * <p>解析数字主版本及可选次版本，对不支持的语法返回 null。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @return major/minor pair, or null when the text is not numeric version evidence / 主次版本对；文本不是数字版本证据时为 null
     */
    private static int[] numericVersion(String version) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+)(?:[.](\\d+))?").matcher(version);
        if (!matcher.find())
            return null;
        return new int[]{Integer.parseInt(matcher.group(1)),
                matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2))};
    }
}
