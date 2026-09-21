package gold.debug.windowstolinux.shared.model.project;

import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provides evidence-backed support claims for exact project-type and architecture pairs. / 为精确项目类型与架构组合提供证据支撑的支持声明。
 */
public final class DeploymentSupportCatalog {
    /**
     * Ubuntu 24.04 validation target.
     * <p>Ubuntu 24.04 验证目标。
     */
    private static final ValidatedDeploymentTarget UBUNTU_2404 = new ValidatedDeploymentTarget(
            LinuxDistroType.UBUNTU, "24.04", "x86_64", LocalDate.of(2026, 8, 12));
    /**
     * PHASE THREE EVIDENCE.
     * <p>PHASETHREE证据。
     */
    private static final String PHASE_THREE_EVIDENCE = "phase3-product-entrypoint-2026-08-12";

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DeploymentSupportCatalog() {
    }

    /**
     * Returns every exact architecture that requires an inspector, renderer, capability gate, and deployment path. / 返回需要检查器、Renderer、能力门禁与部署路径的全部精确架构。
     *
     * @return every exact architecture that requires an inspector, renderer, capability gate, and deployment path / 需要检查器、Renderer、能力门禁与部署路径的全部精确架构
     */
    public static Set<DeploymentArchitectureType> deployableArchitectures() {
        return Set.of(
                architecture(DeploymentProjectType.SPRING_BOOT, DeploymentBuildToolType.GRADLE_WRAPPER),
                architecture(DeploymentProjectType.SPRING_BOOT, DeploymentBuildToolType.MAVEN_WRAPPER),
                architecture(DeploymentProjectType.SPRING_BOOT, DeploymentBuildToolType.MAVEN),
                architecture(DeploymentProjectType.JAVA_JAR, DeploymentBuildToolType.JAVA),
                architecture(DeploymentProjectType.JAVA_SOURCE, DeploymentBuildToolType.JDK),
                architecture(DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM),
                architecture(DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.PNPM),
                architecture(DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.YARN),
                architecture(DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildToolType.PIP_LOCKED),
                architecture(DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildToolType.PYTHON_STDLIB),
                architecture(DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildToolType.PIPENV_LOCKED),
                architecture(DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildToolType.POETRY_LOCKED),
                architecture(DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildToolType.UV_LOCKED),
                architecture(DeploymentProjectType.STATIC_SITE, DeploymentBuildToolType.STATIC_SITE_BUILD),
                architecture(DeploymentProjectType.STATIC_SITE, DeploymentBuildToolType.NPM),
                architecture(DeploymentProjectType.STATIC_SITE, DeploymentBuildToolType.PNPM),
                architecture(DeploymentProjectType.STATIC_SITE, DeploymentBuildToolType.YARN),
                architecture(DeploymentProjectType.DOCKERFILE_CONTAINER, DeploymentBuildToolType.CONTAINER_BUILD),
                architecture(DeploymentProjectType.GO_SERVICE, DeploymentBuildToolType.GO_MODULE),
                architecture(DeploymentProjectType.RUST_SERVICE, DeploymentBuildToolType.CARGO_LOCKED),
                architecture(DeploymentProjectType.DOTNET_SERVICE, DeploymentBuildToolType.DOTNET_LOCKED),
                architecture(DeploymentProjectType.KOTLIN_SERVICE, DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER),
                architecture(DeploymentProjectType.KOTLIN_SERVICE, DeploymentBuildToolType.KOTLINC),
                architecture(DeploymentProjectType.PHP_SERVICE, DeploymentBuildToolType.COMPOSER_LOCKED),
                architecture(DeploymentProjectType.PHP_SERVICE, DeploymentBuildToolType.PHP_CLI),
                architecture(DeploymentProjectType.RUBY_SERVICE, DeploymentBuildToolType.BUNDLER_LOCKED),
                architecture(DeploymentProjectType.RUBY_SERVICE, DeploymentBuildToolType.RUBY_CLI),
                architecture(DeploymentProjectType.CMAKE_SERVICE, DeploymentBuildToolType.CMAKE));
    }

    /**
     * Returns the exact checked-in claim for one selected project type and architecture. / 返回所选项目类型与架构的精确已检入声明。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @return the exact checked-in claim for one selected project type and architecture / 所选项目类型与架构的精确已检入声明
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static DeploymentSupportProfile forArchitecture(
            DeploymentProjectType type,
            DeploymentBuildToolType buildTool
    ) {
        type = Objects.requireNonNull(type, "type");
        buildTool = Objects.requireNonNull(buildTool, "buildTool");
        return switch (type) {
            case SPRING_BOOT -> switch (buildTool) {
                case GRADLE_WRAPPER -> experimental(SourceLanguageType.JAVA, "gradle", "Spring Boot");
                case MAVEN, MAVEN_WRAPPER -> experimental(SourceLanguageType.JAVA, "maven", "Spring Boot");
                default -> invalid(type, buildTool);
            };
            case JAVA_JAR -> buildTool == DeploymentBuildToolType.JAVA
                    ? formal(SourceLanguageType.JAVA, "jar", "Java executable JAR") : invalid(type, buildTool);
            case JAVA_SOURCE -> buildTool == DeploymentBuildToolType.JDK
                    ? experimental(SourceLanguageType.JAVA, "jdk", "Dependency-free Java source") : invalid(type, buildTool);
            case NODE_SERVICE -> switch (buildTool) {
                case NPM -> formal(SourceLanguageType.JAVASCRIPT, "npm", "Node.js service");
                case PNPM -> experimental(SourceLanguageType.JAVASCRIPT, "pnpm", "Node.js service");
                case YARN -> experimental(SourceLanguageType.JAVASCRIPT, "yarn", "Node.js service");
                default -> invalid(type, buildTool);
            };
            case PYTHON_SERVICE -> switch (buildTool) {
                case PYTHON_STDLIB -> experimental(SourceLanguageType.PYTHON, "stdlib", "Standard-library Python application");
                case PIP_LOCKED -> formal(SourceLanguageType.PYTHON, "pip", "Python service");
                case PIPENV_LOCKED -> experimental(SourceLanguageType.PYTHON, "pipenv", "Python service");
                case POETRY_LOCKED -> experimental(SourceLanguageType.PYTHON, "poetry", "Python service");
                case UV_LOCKED -> experimental(SourceLanguageType.PYTHON, "uv", "Python service");
                default -> invalid(type, buildTool);
            };
            case STATIC_SITE -> switch (buildTool) {
                case STATIC_SITE_BUILD, NPM, PNPM, YARN -> formal(SourceLanguageType.HTML, "static", "Managed static site");
                default -> invalid(type, buildTool);
            };
            case DOCKERFILE_CONTAINER -> buildTool == DeploymentBuildToolType.CONTAINER_BUILD
                    ? formal(SourceLanguageType.CONTAINERFILE, "dockerfile", "Single Dockerfile container")
                    : invalid(type, buildTool);
            case GO_SERVICE -> buildTool == DeploymentBuildToolType.GO_MODULE
                    ? experimental(SourceLanguageType.GO, "gomodule", "Go service") : invalid(type, buildTool);
            case RUST_SERVICE -> buildTool == DeploymentBuildToolType.CARGO_LOCKED
                    ? experimental(SourceLanguageType.RUST, "cargo", "Rust service") : invalid(type, buildTool);
            case DOTNET_SERVICE -> buildTool == DeploymentBuildToolType.DOTNET_LOCKED
                    ? experimental(SourceLanguageType.CSHARP, "dotnetsdk", ".NET service") : invalid(type, buildTool);
            case KOTLIN_SERVICE -> switch (buildTool) {
                case GRADLE_KOTLIN_WRAPPER -> experimental(SourceLanguageType.KOTLIN, "gradle", "Kotlin/JVM service");
                case KOTLINC -> experimental(SourceLanguageType.KOTLIN, "kotlinc", "Dependency-free Kotlin/JVM service");
                default -> invalid(type, buildTool);
            };
            case PHP_SERVICE -> switch (buildTool) {
                case COMPOSER_LOCKED -> experimental(SourceLanguageType.PHP, "composer", "PHP service");
                case PHP_CLI -> experimental(SourceLanguageType.PHP, "phpcli", "Dependency-free PHP service");
                default -> invalid(type, buildTool);
            };
            case RUBY_SERVICE -> switch (buildTool) {
                case BUNDLER_LOCKED -> experimental(SourceLanguageType.RUBY, "bundler", "Ruby service");
                case RUBY_CLI -> experimental(SourceLanguageType.RUBY, "rubycli", "Dependency-free Ruby service");
                default -> invalid(type, buildTool);
            };
            case CMAKE_SERVICE -> buildTool == DeploymentBuildToolType.CMAKE
                    ? experimental(SourceLanguageType.C, "cmake", "C/C++ service executable") : invalid(type, buildTool);
            case RECOGNITION_PREVIEW -> buildTool == DeploymentBuildToolType.NONE_PREVIEW
                    ? preview(SourceLanguageType.UNKNOWN) : invalid(type, buildTool);
        };
    }

    /**
     * Returns a mutation-free recognition-only profile. / 返回禁止修改目标机的仅识别配置。
     *
     * @param language selected language identity / 选定语言身份
     * @return a mutation-free recognition-only profile / 禁止修改目标机的仅识别配置
     */
    public static DeploymentSupportProfile preview(SourceLanguageType language) {
        return new DeploymentSupportProfile(DeploymentSupportLevel.RECOGNITION_PREVIEW, language, "none",
                "Unclassified project", List.of(), List.of(),
                List.of(LocalizedMessage.of("support.limitation.previewOnly")));
    }

    /**
     * Returns an honest result when no bounded marker identified a language. / 在没有有界标记识别语言时返回真实结果。
     *
     * @return an honest result when no bounded marker identified a language / 在没有有界标记识别语言时返回真实结果
     */
    public static DeploymentSupportProfile unrecognized() {
        return new DeploymentSupportProfile(DeploymentSupportLevel.UNRECOGNIZED, SourceLanguageType.UNKNOWN, "none",
                "Unrecognized project", List.of(), List.of(),
                List.of(LocalizedMessage.of("support.limitation.unrecognized")));
    }

    /**
     * Builds deployment support profile from the supplied experimental inputs.
     * <p>根据所提供实验性输入构建部署支持配置资料。
     *
     * @param language selected language identity / 选定语言身份
     * @param architecture observed machine architecture / 观测到的机器架构
     * @param framework selected framework or workload identity / 选定框架或工作负载身份
     * @return deployment support profile from the supplied experimental inputs / 根据所提供实验性输入构建部署支持配置资料
     */
    private static DeploymentSupportProfile experimental(
            SourceLanguageType language,
            String architecture,
            String framework
    ) {
        return new DeploymentSupportProfile(DeploymentSupportLevel.EXPERIMENTAL_ADAPTER, language, architecture,
                framework, List.of(), List.of(), List.of(LocalizedMessage.of("support.limitation.runtimePending")));
    }

    /**
     * Builds deployment support profile from the supplied formal inputs.
     * <p>根据所提供正式输入构建部署支持配置资料。
     *
     * @param language selected language identity / 选定语言身份
     * @param architecture observed machine architecture / 观测到的机器架构
     * @param framework selected framework or workload identity / 选定框架或工作负载身份
     * @return deployment support profile from the supplied formal inputs / 根据所提供正式输入构建部署支持配置资料
     */
    private static DeploymentSupportProfile formal(
            SourceLanguageType language,
            String architecture,
            String framework
    ) {
        return new DeploymentSupportProfile(DeploymentSupportLevel.FORMALLY_SUPPORTED, language, architecture,
                framework, List.of(UBUNTU_2404), List.of(PHASE_THREE_EVIDENCE),
                List.of(LocalizedMessage.of("support.limitation.listedMatrixOnly")));
    }

    /**
     * Creates the owning module's failure for rejected input or evidence.
     * <p>为被拒绝输入或证据创建所属模块的失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @return the owning module's failure for rejected input or evidence / 为被拒绝输入或证据创建所属模块的失败
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static DeploymentSupportProfile invalid(DeploymentProjectType type, DeploymentBuildToolType buildTool) {
        throw new IllegalArgumentException("build tool " + buildTool + " is not valid for project type " + type);
    }

    /**
     * Builds deployment architecture type from the supplied architecture inputs.
     * <p>根据所提供架构输入构建部署架构类型。
     *
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @return deployment architecture type from the supplied architecture inputs / 根据所提供架构输入构建部署架构类型
     */
    private static DeploymentArchitectureType architecture(
            DeploymentProjectType projectType,
            DeploymentBuildToolType buildTool
    ) {
        return new DeploymentArchitectureType(projectType, buildTool);
    }
}
