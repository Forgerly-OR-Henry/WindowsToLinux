package gold.debug.windowstolinux.shared.backup.manifest;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Closed portable representation of every reviewed deployment runtime. / 每种经审阅部署运行时的封闭可移植表示。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BackupComponentRuntime.SpringBoot.class, name = "spring-boot"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.JavaJar.class, name = "java-jar"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.JavaSource.class, name = "java-source"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.NodeService.class, name = "node-service"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.PythonService.class, name = "python-service"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.StaticSite.class, name = "static-site"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.Container.class, name = "container"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.GoService.class, name = "go-service"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.RustService.class, name = "rust-service"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.DotNetService.class, name = "dotnet-service"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.KotlinService.class, name = "kotlin-service"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.PhpService.class, name = "php-service"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.RubyService.class, name = "ruby-service"),
        @JsonSubTypes.Type(value = BackupComponentRuntime.CmakeService.class, name = "cmake-service")
})
public sealed interface BackupComponentRuntime permits BackupComponentRuntime.SpringBoot,
        BackupComponentRuntime.JavaJar, BackupComponentRuntime.JavaSource, BackupComponentRuntime.NodeService,
        BackupComponentRuntime.PythonService, BackupComponentRuntime.StaticSite, BackupComponentRuntime.Container,
        BackupComponentRuntime.GoService, BackupComponentRuntime.RustService, BackupComponentRuntime.DotNetService,
        BackupComponentRuntime.KotlinService, BackupComponentRuntime.PhpService,
        BackupComponentRuntime.RubyService, BackupComponentRuntime.CmakeService {
    /**
     * Returns the exact reviewed project type. / 返回精确的经审阅项目类型。
     *
     * @return the exact reviewed project type / 精确的经审阅项目类型
     */
    DeploymentProjectType projectType();

    /**
     * Returns the portable component health check. / 返回可移植组件健康检查。
     *
     * @return the portable component health check / 可移植组件健康检查
     */
    BackupHealthCheck healthCheck();

    /**
     * Recreates the canonical runtime without interpreting arbitrary text. / 在不解释任意文本的情况下重建规范运行时。
     *
     * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
     */
    DeploymentRuntimeSpecification toSpecification();

    /**
     * Spring Boot runtime. / Spring Boot 运行时。
     *
     * @param javaVersion java version / Java版本
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record SpringBoot(String javaVersion, BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /**
         * Initializes spring boot through its shared constructor contract.
         * <p>通过共享构造契约初始化Spring启动。
         *
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public SpringBoot(BackupHealthCheck healthCheck) { this("21", healthCheck); }
        /**
         * Validates this runtime. / 校验此运行时。
         *
         * @param javaVersion java version / Java版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public SpringBoot {
            javaVersion = javaVersion == null ? "21" : javaVersion;
            healthCheck = required(healthCheck);
            new DeploymentRuntimeSpecification.SpringBoot(javaVersion, healthCheck.toHealthCheck());
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.SPRING_BOOT; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.SpringBoot(javaVersion, healthCheck.toHealthCheck());
        }
    }

    /**
     * Java JAR runtime. / Java JAR 运行时。
     *
     * @param jarRelativePath jar relative path / jar相对路径
     * @param mainClass main class / 主类
     * @param javaVersion java version / Java版本
     * @param jvmArguments jvm arguments / jvm参数
     * @param applicationArguments application arguments / 应用参数
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record JavaJar(String jarRelativePath, String mainClass, String javaVersion, List<String> jvmArguments,
                   List<String> applicationArguments, BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /**
         * Validates and freezes this runtime. / 校验并冻结此运行时。
         *
         * @param jarRelativePath jar relative path / jar相对路径
         * @param mainClass main class / 主类
         * @param javaVersion java version / Java版本
         * @param jvmArguments jvm arguments / jvm参数
         * @param applicationArguments application arguments / 应用参数
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public JavaJar {
            jvmArguments = List.copyOf(Objects.requireNonNull(jvmArguments, "jvmArguments"));
            applicationArguments = List.copyOf(Objects.requireNonNull(applicationArguments, "applicationArguments"));
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.JavaJar checked = new DeploymentRuntimeSpecification.JavaJar(
                    jarRelativePath, mainClass, javaVersion, jvmArguments, applicationArguments,
                    healthCheck.toHealthCheck());
            jarRelativePath = checked.jarRelativePath(); mainClass = checked.mainClass(); javaVersion = checked.javaVersion();
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_JAR; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.JavaJar(jarRelativePath, mainClass, javaVersion,
                    jvmArguments, applicationArguments, healthCheck.toHealthCheck());
        }
    }

    /**
     * Dependency-free Java source runtime. / 无依赖 Java 源码运行时。
     *
     * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
     * @param mainClass main class / 主类
     * @param javaVersion java version / Java版本
     * @param jvmArguments jvm arguments / jvm参数
     * @param applicationArguments application arguments / 应用参数
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record JavaSource(String sourceRoot, String mainClass, String javaVersion, List<String> jvmArguments,
                      List<String> applicationArguments, BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /**
         * Validates and freezes this runtime. / 校验并冻结此运行时。
         *
         * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
         * @param mainClass main class / 主类
         * @param javaVersion java version / Java版本
         * @param jvmArguments jvm arguments / jvm参数
         * @param applicationArguments application arguments / 应用参数
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public JavaSource {
            jvmArguments = List.copyOf(Objects.requireNonNull(jvmArguments, "jvmArguments"));
            applicationArguments = List.copyOf(Objects.requireNonNull(applicationArguments, "applicationArguments"));
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.JavaSource checked = new DeploymentRuntimeSpecification.JavaSource(
                    sourceRoot, mainClass, javaVersion, jvmArguments, applicationArguments,
                    healthCheck.toHealthCheck());
            sourceRoot = checked.sourceRoot(); mainClass = checked.mainClass(); javaVersion = checked.javaVersion();
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_SOURCE; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.JavaSource(sourceRoot, mainClass, javaVersion,
                    jvmArguments, applicationArguments, healthCheck.toHealthCheck());
        }
    }

    /**
     * Node service runtime. / Node 服务运行时。
     *
     * @param nodeMajorVersion node major version / 节点主版本版本
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record NodeService(int nodeMajorVersion, BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /**
         * Validates this runtime. / 校验此运行时。
         *
         * @param nodeMajorVersion node major version / 节点主版本版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public NodeService {
            healthCheck = required(healthCheck);
            new DeploymentRuntimeSpecification.NodeService(nodeMajorVersion, healthCheck.toHealthCheck());
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.NODE_SERVICE; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.NodeService(nodeMajorVersion, healthCheck.toHealthCheck());
        }
    }

    /**
     * Python service runtime. / Python 服务运行时。
     *
     * @param pythonVersion python version / python版本
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record PythonService(String pythonVersion, String entrypoint, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /**
         * Validates and normalizes this runtime. / 校验并规范化此运行时。
         *
         * @param pythonVersion python version / python版本
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public PythonService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.PythonService checked =
                    new DeploymentRuntimeSpecification.PythonService(pythonVersion, entrypoint, healthCheck.toHealthCheck());
            pythonVersion = checked.pythonVersion(); entrypoint = checked.entrypoint();
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PYTHON_SERVICE; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.PythonService(pythonVersion, entrypoint, healthCheck.toHealthCheck());
        }
    }

    /**
     * Static-site runtime with zero meaning no Node build. / 以零表示不含 Node 构建的静态站点运行时。
     *
     * @param outputDirectory output directory / 输出目录
     * @param nodeMajorVersion node major version / 节点主版本版本
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record StaticSite(String outputDirectory, int nodeMajorVersion, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /**
         * Validates and normalizes this runtime. / 校验并规范化此运行时。
         *
         * @param outputDirectory output directory / 输出目录
         * @param nodeMajorVersion node major version / 节点主版本版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public StaticSite {
            healthCheck = required(healthCheck);
            if (healthCheck.type() != BackupHealthCheckType.HTTP) {
                throw new IllegalArgumentException("static-site health must use HTTP");
            }
            if (nodeMajorVersion < 0) throw new IllegalArgumentException("nodeMajorVersion must not be negative");
            DeploymentRuntimeSpecification.StaticSite checked = new DeploymentRuntimeSpecification.StaticSite(
                    outputDirectory, nodeMajorVersion == 0 ? OptionalInt.empty() : OptionalInt.of(nodeMajorVersion),
                    (gold.debug.windowstolinux.shared.model.health.HealthCheck.Http) healthCheck.toHealthCheck());
            outputDirectory = checked.outputDirectory();
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.STATIC_SITE; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            if (nodeMajorVersion < 0) throw new IllegalArgumentException("nodeMajorVersion must not be negative");
            return new DeploymentRuntimeSpecification.StaticSite(outputDirectory,
                    nodeMajorVersion == 0 ? OptionalInt.empty() : OptionalInt.of(nodeMajorVersion),
                    (gold.debug.windowstolinux.shared.model.health.HealthCheck.Http) healthCheck.toHealthCheck());
        }
    }

    /**
     * Single managed container runtime. / 单个受管容器运行时。
     *
     * @param engine engine / 引擎
     * @param publishedPorts published ports / 已发布端口集合
     * @param volumes volumes / 卷集合
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record Container(DeploymentRuntimeSpecification.ContainerEngineType engine,
                     Map<Integer, Integer> publishedPorts, List<BackupManagedVolume> volumes,
                     BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /**
         * Validates and freezes this runtime. / 校验并冻结此运行时。
         *
         * @param engine engine / 引擎
         * @param publishedPorts published ports / 已发布端口集合
         * @param volumes volumes / 卷集合
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Container {
            engine = Objects.requireNonNull(engine, "engine");
            publishedPorts = Map.copyOf(Objects.requireNonNull(publishedPorts, "publishedPorts"));
            volumes = List.copyOf(Objects.requireNonNull(volumes, "volumes"));
            healthCheck = required(healthCheck);
            new DeploymentRuntimeSpecification.Container(engine, publishedPorts,
                    volumes.stream().map(BackupManagedVolume::toManagedVolume).toList(), healthCheck.toHealthCheck());
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOCKERFILE_CONTAINER; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.Container(engine, publishedPorts,
                    volumes.stream().map(BackupManagedVolume::toManagedVolume).toList(), healthCheck.toHealthCheck());
        }
    }

    /**
     * Go service runtime. / Go 服务运行时。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record GoService(String version, String artifactName, String entrypoint, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /**
         * Validates and normalizes this runtime. / 校验并规范化此运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public GoService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.GoService checked = new DeploymentRuntimeSpecification.GoService(
                    version, artifactName, entrypoint, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.GO_SERVICE; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.GoService(version, artifactName, entrypoint, healthCheck.toHealthCheck());
        }
    }

    /**
     * Rust service runtime. / Rust 服务运行时。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record RustService(String version, String artifactName, String entrypoint, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /**
         * Validates and normalizes this runtime. / 校验并规范化此运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public RustService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.RustService checked = new DeploymentRuntimeSpecification.RustService(
                    version, artifactName, entrypoint, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUST_SERVICE; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.RustService(version, artifactName, entrypoint, healthCheck.toHealthCheck());
        }
    }

    /**
     * .NET service runtime. / .NET 服务运行时。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record DotNetService(String version, String artifactName, String entrypoint, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /**
         * Validates and normalizes this runtime. / 校验并规范化此运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public DotNetService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.DotNetService checked = new DeploymentRuntimeSpecification.DotNetService(
                    version, artifactName, entrypoint, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOTNET_SERVICE; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.DotNetService(version, artifactName, entrypoint,
                    healthCheck.toHealthCheck());
        }
    }

    /**
     * Kotlin service runtime. / Kotlin 服务运行时。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param jvmTarget jvm target / jvm目标
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record KotlinService(String version, String artifactName, String entrypoint, String jvmTarget, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /**
         * Initializes kotlin service through its shared constructor contract.
         * <p>通过共享构造契约初始化Kotlin服务。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public KotlinService(String version, String artifactName, String entrypoint, BackupHealthCheck healthCheck) {
            this(version, artifactName, entrypoint, "21", healthCheck);
        }
        /**
         * Validates and normalizes this runtime. / 校验并规范化此运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param jvmTarget jvm target / jvm目标
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public KotlinService {
            jvmTarget = jvmTarget == null ? "21" : jvmTarget;
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.KotlinService checked = new DeploymentRuntimeSpecification.KotlinService(
                    version, artifactName, entrypoint, jvmTarget, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.KOTLIN_SERVICE; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.KotlinService(version, artifactName, entrypoint, jvmTarget,
                    healthCheck.toHealthCheck());
        }
    }

    /**
     * PHP service runtime. / PHP 服务运行时。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param servicePort service port / 服务端口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record PhpService(String version, String artifactName, String entrypoint, int servicePort,
                      BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /**
         * Validates and normalizes this runtime. / 校验并规范化此运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param servicePort service port / 服务端口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public PhpService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.PhpService checked = new DeploymentRuntimeSpecification.PhpService(
                    version, artifactName, entrypoint, servicePort, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PHP_SERVICE; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.PhpService(version, artifactName, entrypoint, servicePort,
                    healthCheck.toHealthCheck());
        }
    }

    /**
     * Ruby service runtime. / Ruby 服务运行时。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param servicePort service port / 服务端口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record RubyService(String version, String artifactName, String entrypoint, int servicePort,
                       BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /**
         * Validates and normalizes this runtime. / 校验并规范化此运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param servicePort service port / 服务端口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public RubyService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.RubyService checked = new DeploymentRuntimeSpecification.RubyService(
                    version, artifactName, entrypoint, servicePort, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUBY_SERVICE; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.RubyService(version, artifactName, entrypoint, servicePort,
                    healthCheck.toHealthCheck());
        }
    }

    /**
     * CMake service runtime. / CMake 服务运行时。
     *
     * @param preset preset / 预设
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     */
    record CmakeService(String preset, String target, String artifactName, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /**
         * Validates and normalizes this runtime. / 校验并规范化此运行时。
         *
         * @param preset preset / 预设
         * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public CmakeService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.CmakeService checked = new DeploymentRuntimeSpecification.CmakeService(
                    preset, target, artifactName, healthCheck.toHealthCheck());
            preset = checked.preset(); target = checked.target(); artifactName = checked.artifactName();
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.CMAKE_SERVICE; }
        /**
         * Converts the current contract to specification.
         * <p>将当前契约转换为规格。
         *
         * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
         */
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.CmakeService(preset, target, artifactName,
                    healthCheck.toHealthCheck());
        }
    }

    /**
     * Copies one canonical runtime into the portable schema. / 将一个规范运行时复制到可移植 schema。
     *
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved backup component runtime / 构造或解析得到的备份组件运行时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static BackupComponentRuntime from(DeploymentRuntimeSpecification runtime) {
        return switch (Objects.requireNonNull(runtime, "runtime")) {
            case DeploymentRuntimeSpecification.SpringBoot value -> new SpringBoot(value.javaVersion(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.JavaJar value -> new JavaJar(value.jarRelativePath(), value.mainClass(),
                    value.javaVersion(), value.jvmArguments(), value.applicationArguments(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.JavaSource value -> new JavaSource(value.sourceRoot(), value.mainClass(),
                    value.javaVersion(), value.jvmArguments(), value.applicationArguments(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.NodeService value -> new NodeService(value.nodeMajorVersion(),
                    BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.PythonService value -> new PythonService(value.pythonVersion(),
                    value.entrypoint(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.StaticSite value -> new StaticSite(value.outputDirectory(),
                    value.nodeMajorVersion().orElse(0), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.Container value -> new Container(value.engine(), value.publishedPorts(),
                    value.volumes().stream().map(BackupManagedVolume::from).toList(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.GoService value -> new GoService(value.version(), value.artifactName(),
                    value.entrypoint(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.RustService value -> new RustService(value.version(), value.artifactName(),
                    value.entrypoint(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.DotNetService value -> new DotNetService(value.version(), value.artifactName(),
                    value.entrypoint(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.KotlinService value -> new KotlinService(value.version(), value.artifactName(),
                    value.entrypoint(), value.jvmTarget(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.PhpService value -> new PhpService(value.version(), value.artifactName(),
                    value.entrypoint(), value.servicePort(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.RubyService value -> new RubyService(value.version(), value.artifactName(),
                    value.entrypoint(), value.servicePort(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.CmakeService value -> new CmakeService(value.preset(), value.target(),
                    value.artifactName(), BackupHealthCheck.from(value.healthCheck()));
        };
    }

    /**
     * Requires the named input to be present and valid before continuing.
     * <p>继续前要求具名输入存在且有效。
     *
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return constructed or resolved backup health check / 构造或解析得到的备份健康检查
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static BackupHealthCheck required(BackupHealthCheck healthCheck) {
        return Objects.requireNonNull(healthCheck, "healthCheck");
    }
}
