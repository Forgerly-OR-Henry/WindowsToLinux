package gold.debug.windowstolinux.shared.model.project;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Typed runtime definition for exactly one typed deployment single-component project type.
 *
 *  <p>恰好一个部署单组件项目类型的类型化运行定义。
 */
public sealed interface DeploymentRuntimeSpecification permits DeploymentRuntimeSpecification.SpringBoot,
        DeploymentRuntimeSpecification.JavaJar, DeploymentRuntimeSpecification.JavaSource,
        DeploymentRuntimeSpecification.NodeService,
        DeploymentRuntimeSpecification.PythonService, DeploymentRuntimeSpecification.StaticSite,
        DeploymentRuntimeSpecification.Container, DeploymentRuntimeSpecification.GoService,
        DeploymentRuntimeSpecification.RustService, DeploymentRuntimeSpecification.DotNetService,
        DeploymentRuntimeSpecification.KotlinService, DeploymentRuntimeSpecification.PhpService,
        DeploymentRuntimeSpecification.RubyService, DeploymentRuntimeSpecification.CmakeService {
    /**
     * Returns the matching project type. / 返回匹配的项目类型。
     *
     * @return the matching project type / 匹配的项目类型
     */
    DeploymentProjectType projectType();

    /**
     * Returns the required layered health check. / 返回所需的分层健康检查。
     *
     * @return the required layered health check / 所需的分层健康检查
     */
    HealthCheck healthCheck();

    /**
     * Returns the declared runtime identity policy. / 返回声明的运行身份策略。
     *
     * @return the declared runtime identity policy / 声明的运行身份策略
     */
    RuntimeIdentityMode identityPolicy();

    /**
     * Returns execution and delivery facts independent of language. / 返回独立于语言的执行与交付信息。
     *
     * @return execution and delivery facts independent of language / 独立于语言的执行与交付信息
     */
    ApplicationWorkload workload();

    /**
     * Copies a runtime with one reviewed application declaration. / 使用经审阅应用声明复制运行时。
     *
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    default DeploymentRuntimeSpecification withWorkload(ApplicationWorkload workload) {
        Objects.requireNonNull(workload);
        return switch (this) {
            case SpringBoot value -> new SpringBoot(value.javaVersion(), value.healthCheck(), value.identityPolicy(), workload);
            case JavaJar value -> new JavaJar(value.jarRelativePath(), value.mainClass(), value.javaVersion(), value.jvmArguments(), value.applicationArguments(), value.healthCheck(), value.identityPolicy(), workload);
            case JavaSource value -> new JavaSource(value.sourceRoot(), value.mainClass(), value.javaVersion(), value.jvmArguments(), value.applicationArguments(), value.healthCheck(), value.identityPolicy(), workload);
            case NodeService value -> new NodeService(value.nodeMajorVersion(), value.healthCheck(), value.identityPolicy(), workload);
            case PythonService value -> new PythonService(value.pythonVersion(), value.entrypoint(), value.healthCheck(), value.identityPolicy(), workload);
            case StaticSite value -> new StaticSite(value.outputDirectory(), value.nodeMajorVersion(), value.healthCheck(), value.identityPolicy(), workload);
            case Container value -> new Container(value.engine(), value.publishedPorts(), value.volumes(), value.healthCheck(), value.identityPolicy(), workload);
            case GoService value -> new GoService(value.version(), value.artifactName(), value.entrypoint(), value.healthCheck(), value.identityPolicy(), workload);
            case RustService value -> new RustService(value.version(), value.artifactName(), value.entrypoint(), value.healthCheck(), value.identityPolicy(), workload);
            case DotNetService value -> new DotNetService(value.version(), value.artifactName(), value.entrypoint(), value.healthCheck(), value.identityPolicy(), workload);
            case KotlinService value -> new KotlinService(value.version(), value.artifactName(), value.entrypoint(), value.jvmTarget(), value.healthCheck(), value.identityPolicy(), workload);
            case PhpService value -> new PhpService(value.version(), value.artifactName(), value.entrypoint(), value.servicePort(), value.healthCheck(), value.identityPolicy(), workload);
            case RubyService value -> new RubyService(value.version(), value.artifactName(), value.entrypoint(), value.servicePort(), value.healthCheck(), value.identityPolicy(), workload);
            case CmakeService value -> new CmakeService(value.preset(), value.target(), value.artifactName(), value.healthCheck(), value.identityPolicy(), workload);
        };
    }

    /**
     * Copies runtime facts with an explicit current or historical identity policy. / 使用明确的当前或历史身份策略复制运行事实。
     *
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     * @return constructed or resolved deployment runtime specification / 构造或解析得到的部署运行时规格
     */
    default DeploymentRuntimeSpecification withIdentityPolicy(RuntimeIdentityMode policy) {
        return switch (this) {
            case SpringBoot value -> new SpringBoot(value.javaVersion(), value.healthCheck(), policy, workload());
            case JavaJar value -> new JavaJar(value.jarRelativePath(), value.mainClass(), value.javaVersion(), value.jvmArguments(), value.applicationArguments(), value.healthCheck(), policy, workload());
            case JavaSource value -> new JavaSource(value.sourceRoot(), value.mainClass(), value.javaVersion(), value.jvmArguments(), value.applicationArguments(), value.healthCheck(), policy, workload());
            case NodeService value -> new NodeService(value.nodeMajorVersion(), value.healthCheck(), policy, workload());
            case PythonService value -> new PythonService(value.pythonVersion(), value.entrypoint(), value.healthCheck(), policy, workload());
            case StaticSite value -> new StaticSite(value.outputDirectory(), value.nodeMajorVersion(), value.healthCheck(), policy, workload());
            case Container value -> new Container(value.engine(), value.publishedPorts(), value.volumes(), value.healthCheck(), policy, workload());
            case GoService value -> new GoService(value.version(), value.artifactName(), value.entrypoint(), value.healthCheck(), policy, workload());
            case RustService value -> new RustService(value.version(), value.artifactName(), value.entrypoint(), value.healthCheck(), policy, workload());
            case DotNetService value -> new DotNetService(value.version(), value.artifactName(), value.entrypoint(), value.healthCheck(), policy, workload());
            case KotlinService value -> new KotlinService(value.version(), value.artifactName(), value.entrypoint(), value.jvmTarget(), value.healthCheck(), policy, workload());
            case PhpService value -> new PhpService(value.version(), value.artifactName(), value.entrypoint(), value.servicePort(), value.healthCheck(), policy, workload());
            case RubyService value -> new RubyService(value.version(), value.artifactName(), value.entrypoint(), value.servicePort(), value.healthCheck(), policy, workload());
            case CmakeService value -> new CmakeService(value.preset(), value.target(), value.artifactName(), value.healthCheck(), policy, workload());
        };
    }

    /**
     * Requires reviewed process identity and privilege policy and rejects inputs outside the declared constraints.
     * <p>要求已审阅进程身份及权限策略并拒绝超出已声明约束的输入。
     *
     * @param actual actual / 实际
     * @param supported supported / 受支持
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static void requireIdentityPolicy(RuntimeIdentityMode actual, RuntimeIdentityMode supported) {
        Objects.requireNonNull(actual, "identityPolicy");
        if (actual != supported && actual != RuntimeIdentityMode.LEGACY_UNSPECIFIED) {
            throw new IllegalArgumentException("runtime identity policy does not match its execution backend");
        }
    }

    /**
     * Spring Boot systemd runtime independent of its reviewed build tool. / 与经审阅构建工具无关的 Spring Boot systemd 运行时。
     *
     * @param javaVersion java version / Java版本
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record SpringBoot(String javaVersion, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param javaVersion java version / Java版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public SpringBoot(String javaVersion, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(javaVersion, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param javaVersion java version / Java版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public SpringBoot(String javaVersion, HealthCheck healthCheck) {
            this(javaVersion, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates a {@code SpringBoot} specification. / 创建 {@code SpringBoot} 规范。
         *
         * @param javaVersion java version / Java版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public SpringBoot {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC); javaVersion = requireJavaVersion(javaVersion); healthCheck = Objects.requireNonNull(healthCheck, "healthCheck"); }
        /**
         * Initializes spring boot through its shared constructor contract.
         * <p>通过共享构造契约初始化Spring启动。
         *
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public SpringBoot(HealthCheck healthCheck) { this("21", healthCheck); }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.SPRING_BOOT; }
    }

    /**
     * Java JAR systemd runtime with structured JVM and application arguments. / 具有结构化 JVM 和应用参数的 Java JAR systemd 运行时。
     *
     * @param jarRelativePath jar relative path / jar相对路径
     * @param mainClass main class / 主类
     * @param javaVersion java version / Java版本
     * @param jvmArguments jvm arguments / jvm参数
     * @param applicationArguments application arguments / 应用参数
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record JavaJar(String jarRelativePath, String mainClass, String javaVersion, List<String> jvmArguments,
                   List<String> applicationArguments, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param jarRelativePath jar relative path / jar相对路径
         * @param mainClass main class / 主类
         * @param javaVersion java version / Java版本
         * @param jvmArguments jvm arguments / jvm参数
         * @param applicationArguments application arguments / 应用参数
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public JavaJar(String jarRelativePath, String mainClass, String javaVersion, List<String> jvmArguments,
                   List<String> applicationArguments, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(jarRelativePath, mainClass, javaVersion, jvmArguments, applicationArguments, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param jarRelativePath jar relative path / jar相对路径
         * @param mainClass main class / 主类
         * @param javaVersion java version / Java版本
         * @param jvmArguments jvm arguments / jvm参数
         * @param applicationArguments application arguments / 应用参数
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public JavaJar(String jarRelativePath, String mainClass, String javaVersion, List<String> jvmArguments,
                   List<String> applicationArguments, HealthCheck healthCheck) {
            this(jarRelativePath, mainClass, javaVersion, jvmArguments, applicationArguments, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates a {@code JavaJar} specification. / 创建 {@code JavaJar} 规范。
         *
         * @param jarRelativePath jar relative path / jar相对路径
         * @param mainClass main class / 主类
         * @param javaVersion java version / Java版本
         * @param jvmArguments jvm arguments / jvm参数
         * @param applicationArguments application arguments / 应用参数
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public JavaJar {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            jarRelativePath = relativePath(jarRelativePath, "jarRelativePath");
            mainClass = javaName(mainClass, "mainClass");
            javaVersion = requireJavaVersion(javaVersion);
            jvmArguments = safeArguments(jvmArguments, "jvmArguments");
            applicationArguments = safeArguments(applicationArguments, "applicationArguments");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_JAR; }
    }

    /**
     * Dependency-free Java source compiled into one executable JAR on the target. / 在目标机编译为单一可执行 JAR 的无依赖 Java 源码。
     *
     * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
     * @param mainClass main class / 主类
     * @param javaVersion java version / Java版本
     * @param jvmArguments jvm arguments / jvm参数
     * @param applicationArguments application arguments / 应用参数
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record JavaSource(String sourceRoot, String mainClass, String javaVersion, List<String> jvmArguments,
                      List<String> applicationArguments, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
         * @param mainClass main class / 主类
         * @param javaVersion java version / Java版本
         * @param jvmArguments jvm arguments / jvm参数
         * @param applicationArguments application arguments / 应用参数
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public JavaSource(String sourceRoot, String mainClass, String javaVersion, List<String> jvmArguments,
                      List<String> applicationArguments, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(sourceRoot, mainClass, javaVersion, jvmArguments, applicationArguments, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
         * @param mainClass main class / 主类
         * @param javaVersion java version / Java版本
         * @param jvmArguments jvm arguments / jvm参数
         * @param applicationArguments application arguments / 应用参数
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public JavaSource(String sourceRoot, String mainClass, String javaVersion, List<String> jvmArguments,
                      List<String> applicationArguments, HealthCheck healthCheck) {
            this(sourceRoot, mainClass, javaVersion, jvmArguments, applicationArguments, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates a reviewed Java source specification. / 创建经审阅的 Java 源码规范。
         *
         * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
         * @param mainClass main class / 主类
         * @param javaVersion java version / Java版本
         * @param jvmArguments jvm arguments / jvm参数
         * @param applicationArguments application arguments / 应用参数
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public JavaSource {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            sourceRoot = relativePath(sourceRoot, "sourceRoot");
            mainClass = javaName(mainClass, "mainClass");
            javaVersion = requireJavaVersion(javaVersion);
            jvmArguments = safeArguments(jvmArguments, "jvmArguments");
            applicationArguments = safeArguments(applicationArguments, "applicationArguments");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_SOURCE; }
    }

    /**
     * Lockfile-backed Node service runtime. / 由锁文件支持的 Node 服务运行时。
     *
     * @param nodeMajorVersion node major version / 节点主版本版本
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record NodeService(int nodeMajorVersion, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param nodeMajorVersion node major version / 节点主版本版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public NodeService(int nodeMajorVersion, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(nodeMajorVersion, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param nodeMajorVersion node major version / 节点主版本版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public NodeService(int nodeMajorVersion, HealthCheck healthCheck) {
            this(nodeMajorVersion, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates a {@code NodeService} specification. / 创建 {@code NodeService} 规范。
         *
         * @param nodeMajorVersion node major version / 节点主版本版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public NodeService {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            if (nodeMajorVersion < 0) {
                throw new IllegalArgumentException("nodeMajorVersion must be a supported explicit major version");
            }
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.NODE_SERVICE; }
    }

    /**
     * Project-virtual-environment Python runtime. / 项目虚拟环境 Python 运行时。
     *
     * @param pythonVersion python version / python版本
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record PythonService(String pythonVersion, String entrypoint, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param pythonVersion python version / python版本
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public PythonService(String pythonVersion, String entrypoint, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(pythonVersion, entrypoint, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param pythonVersion python version / python版本
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public PythonService(String pythonVersion, String entrypoint, HealthCheck healthCheck) {
            this(pythonVersion, entrypoint, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates a {@code PythonService} specification. / 创建 {@code PythonService} 规范。
         *
         * @param pythonVersion python version / python版本
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public PythonService {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            pythonVersion = requirePythonVersion(pythonVersion);
            entrypoint = pythonEntrypoint(entrypoint);
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PYTHON_SERVICE; }
    }

    /**
     * Static site served only from a bounded generated output directory. / 仅从有界生成输出目录提供的静态站点。
     *
     * @param outputDirectory output directory / 输出目录
     * @param nodeMajorVersion node major version / 节点主版本版本
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record StaticSite(String outputDirectory, OptionalInt nodeMajorVersion,
                      HealthCheck.Http healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param outputDirectory output directory / 输出目录
         * @param nodeMajorVersion node major version / 节点主版本版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public StaticSite(String outputDirectory, OptionalInt nodeMajorVersion,
                      HealthCheck.Http healthCheck, RuntimeIdentityMode identityPolicy) {
            this(outputDirectory, nodeMajorVersion, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param outputDirectory output directory / 输出目录
         * @param nodeMajorVersion node major version / 节点主版本版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public StaticSite(String outputDirectory, OptionalInt nodeMajorVersion,
                      HealthCheck.Http healthCheck) {
            this(outputDirectory, nodeMajorVersion, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates a {@code StaticSite} specification. / 创建 {@code StaticSite} 规范。
         *
         * @param outputDirectory output directory / 输出目录
         * @param nodeMajorVersion node major version / 节点主版本版本
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public StaticSite {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            outputDirectory = relativePath(outputDirectory, "outputDirectory");
            if (outputDirectory.equals(".")) {
                throw new IllegalArgumentException("outputDirectory must not expose the source root");
            }
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
            nodeMajorVersion = Objects.requireNonNull(nodeMajorVersion, "nodeMajorVersion");
            if (nodeMajorVersion.isPresent()
                    && nodeMajorVersion.getAsInt() < 0) {
                throw new IllegalArgumentException("nodeMajorVersion must be a supported explicit major version");
            }
        }
        /**
         * Creates a pure static-site specification without a Node build. / 创建不含 Node 构建的纯静态站点规范。
         *
         * @param outputDirectory output directory / 输出目录
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public StaticSite(String outputDirectory, HealthCheck.Http healthCheck) {
            this(outputDirectory, OptionalInt.empty(), healthCheck);
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.STATIC_SITE; }
    }

    /**
     * One image and one container without privileged or host-namespace escape options. / 不含特权或宿主命名空间逃逸选项的单镜像单容器。
     *
     * @param engine engine / 引擎
     * @param publishedPorts published ports / 已发布端口集合
     * @param volumes volumes / 卷集合
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record Container(ContainerEngineType engine, Map<Integer, Integer> publishedPorts, List<ManagedVolume> volumes,
                     HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param engine engine / 引擎
         * @param publishedPorts published ports / 已发布端口集合
         * @param volumes volumes / 卷集合
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public Container(ContainerEngineType engine, Map<Integer, Integer> publishedPorts, List<ManagedVolume> volumes,
                     HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(engine, publishedPorts, volumes, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param engine engine / 引擎
         * @param publishedPorts published ports / 已发布端口集合
         * @param volumes volumes / 卷集合
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public Container(ContainerEngineType engine, Map<Integer, Integer> publishedPorts, List<ManagedVolume> volumes,
                     HealthCheck healthCheck) {
            this(engine, publishedPorts, volumes, healthCheck, RuntimeIdentityMode.CONTAINER_NON_ROOT);
        }
        /**
         * Creates a {@code Container} specification. / 创建 {@code Container} 规范。
         *
         * @param engine engine / 引擎
         * @param publishedPorts published ports / 已发布端口集合
         * @param volumes volumes / 卷集合
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Container {
            Objects.requireNonNull(workload, "workload");
            if (!workload.workers().isEmpty()) throw new IllegalArgumentException("container workers must be managed by the image entrypoint");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.CONTAINER_NON_ROOT);
            engine = Objects.requireNonNull(engine, "engine");
            publishedPorts = Map.copyOf(Objects.requireNonNull(publishedPorts, "publishedPorts"));
            publishedPorts.forEach((hostPort, containerPort) -> {
                if (hostPort == null || containerPort == null || hostPort < 1 || hostPort > 65535
                        || containerPort < 1 || containerPort > 65535) {
                    throw new IllegalArgumentException("container ports must be in the TCP/UDP port range");
                }
            });
            volumes = List.copyOf(Objects.requireNonNull(volumes, "volumes"));
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOCKERFILE_CONTAINER; }
    }

    /**
     * Go service compiled from one locked module. / 从一个锁定模块编译的 Go 服务。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record GoService(String version, String artifactName, String entrypoint, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public GoService(String version, String artifactName, String entrypoint, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(version, artifactName, entrypoint, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public GoService(String version, String artifactName, String entrypoint, HealthCheck healthCheck) {
            this(version, artifactName, entrypoint, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates an instance of this type. / 创建此类型的实例。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public GoService {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            version = requireVersionToken(version); artifactName = safeName(artifactName, "artifactName");
            entrypoint = relativePath(entrypoint, "entrypoint"); requireExact(entrypoint, "main.go", "Go entrypoint");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.GO_SERVICE; }
    }

    /**
     * Rust service compiled from one locked Cargo package. / 从一个锁定 Cargo 包编译的 Rust 服务。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record RustService(String version, String artifactName, String entrypoint, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public RustService(String version, String artifactName, String entrypoint, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(version, artifactName, entrypoint, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public RustService(String version, String artifactName, String entrypoint, HealthCheck healthCheck) {
            this(version, artifactName, entrypoint, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates an instance of this type. / 创建此类型的实例。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public RustService {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            version = requireVersionToken(version); artifactName = safeName(artifactName, "artifactName");
            entrypoint = relativePath(entrypoint, "entrypoint"); requireExact(entrypoint, "src/main.rs", "Rust entrypoint");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUST_SERVICE; }
    }

    /**
     * .NET service published from one locked project. / 从一个锁定项目发布的 .NET 服务。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record DotNetService(String version, String artifactName, String entrypoint, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public DotNetService(String version, String artifactName, String entrypoint, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(version, artifactName, entrypoint, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public DotNetService(String version, String artifactName, String entrypoint, HealthCheck healthCheck) {
            this(version, artifactName, entrypoint, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates an instance of this type. / 创建此类型的实例。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public DotNetService {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            version = requireVersionToken(version); artifactName = safeName(artifactName, "artifactName");
            entrypoint = relativePath(entrypoint, "entrypoint"); requireExact(entrypoint, artifactName + ".dll", ".NET entrypoint");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOTNET_SERVICE; }
    }

    /**
     * Kotlin/JVM service with an exact compiler or plugin version and an explicit JVM target. / 具有精确编译器或插件版本及显式 JVM 目标的 Kotlin/JVM 服务。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param jvmTarget jvm target / jvm目标
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record KotlinService(String version, String artifactName, String entrypoint, String jvmTarget, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param jvmTarget jvm target / jvm目标
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public KotlinService(String version, String artifactName, String entrypoint, String jvmTarget, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(version, artifactName, entrypoint, jvmTarget, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param jvmTarget jvm target / jvm目标
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public KotlinService(String version, String artifactName, String entrypoint, String jvmTarget, HealthCheck healthCheck) {
            this(version, artifactName, entrypoint, jvmTarget, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates an instance of this type. / 创建此类型的实例。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param jvmTarget jvm target / jvm目标
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public KotlinService {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            jvmTarget = requireJavaVersion(jvmTarget);
            version = requireVersionToken(version); artifactName = safeName(artifactName, "artifactName");
            entrypoint = javaName(entrypoint, "entrypoint"); healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public KotlinService(String version, String artifactName, String entrypoint, HealthCheck healthCheck) {
            this(version, artifactName, entrypoint, "21", healthCheck);
        }
        /**
         * Returns the supported project category handled by this strategy.
         * <p>返回当前策略处理的受支持项目类别。
         *
         * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.KOTLIN_SERVICE; }
    }

    /**
     * Composer-locked PHP HTTP service. / Composer 锁定的 PHP HTTP 服务。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param servicePort service port / 服务端口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record PhpService(String version, String artifactName, String entrypoint, int servicePort, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param servicePort service port / 服务端口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public PhpService(String version, String artifactName, String entrypoint, int servicePort, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(version, artifactName, entrypoint, servicePort, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param servicePort service port / 服务端口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public PhpService(String version, String artifactName, String entrypoint, int servicePort, HealthCheck healthCheck) {
            this(version, artifactName, entrypoint, servicePort, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates an instance of this type. / 创建此类型的实例。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param servicePort service port / 服务端口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public PhpService {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            version = requireVersionToken(version); artifactName = safeName(artifactName, "artifactName");
            entrypoint = relativePath(entrypoint, "entrypoint");
            if (servicePort == 0) {
                requireExact(artifactName, "source", "PHP CLI artifact");
                if (!entrypoint.endsWith(".php")) throw new IllegalArgumentException("PHP CLI entrypoint must be a PHP file");
            } else {
                requireExact(artifactName, "public", "PHP document root");
                if (!entrypoint.endsWith(".php")) throw new IllegalArgumentException("PHP router must be a PHP file");
                requirePort(servicePort);
            }
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PHP_SERVICE; }
    }

    /**
     * Bundler-backed or dependency-free Ruby service. / Bundler 支持或无依赖 Ruby 服务。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param servicePort service port / 服务端口
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record RubyService(String version, String artifactName, String entrypoint, int servicePort, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param servicePort service port / 服务端口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public RubyService(String version, String artifactName, String entrypoint, int servicePort, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(version, artifactName, entrypoint, servicePort, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param servicePort service port / 服务端口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public RubyService(String version, String artifactName, String entrypoint, int servicePort, HealthCheck healthCheck) {
            this(version, artifactName, entrypoint, servicePort, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates an instance of this type. / 创建此类型的实例。
         *
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
         * @param servicePort service port / 服务端口
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public RubyService {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            version = requireVersionToken(version); artifactName = safeName(artifactName, "artifactName");
            entrypoint = relativePath(entrypoint, "entrypoint");
            boolean bundler = artifactName.equals("bundle") && (entrypoint.equals("config.ru")
                    || servicePort == 0 && entrypoint.endsWith(".rb"));
            boolean cli = artifactName.equals("source") && entrypoint.endsWith(".rb");
            if (!bundler && !cli) {
                throw new IllegalArgumentException("Ruby runtime must be one reviewed Bundler or CLI shape");
            }
            if (servicePort != 0) requirePort(servicePort);
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUBY_SERVICE; }
    }

    /**
     * One fixed CMake preset and one C or C++ service executable. / 一个固定 CMake preset 与单一 C 或 C++ 服务可执行文件。
     *
     * @param preset preset / 预设
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     */
    record CmakeService(String preset, String target, String artifactName, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy, ApplicationWorkload workload) implements DeploymentRuntimeSpecification {
        /**
         * Constructs an unreviewed execution declaration for programmatic callers. / 程序化调用者需另行审阅执行声明。
         *
         * @param preset preset / 预设
         * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         */
        public CmakeService(String preset, String target, String artifactName, HealthCheck healthCheck, RuntimeIdentityMode identityPolicy) {
            this(preset, target, artifactName, healthCheck, identityPolicy, ApplicationWorkload.unspecified());
        }
        /**
         * Creates a runtime with the current explicit isolation policy. / 使用当前明确隔离策略创建运行时。
         *
         * @param preset preset / 预设
         * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         */
        public CmakeService(String preset, String target, String artifactName, HealthCheck healthCheck) {
            this(preset, target, artifactName, healthCheck, RuntimeIdentityMode.SYSTEMD_STATIC);
        }
        /**
         * Creates a reviewed CMake service specification. / 创建经审阅的 CMake 服务规范。
         *
         * @param preset preset / 预设
         * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
         * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
         * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
         * @param identityPolicy reviewed process identity and privilege policy / 已审阅进程身份及权限策略
         * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public CmakeService {
            Objects.requireNonNull(workload, "workload");
            requireIdentityPolicy(identityPolicy, RuntimeIdentityMode.SYSTEMD_STATIC);
            preset = safeName(preset, "preset");
            target = safeName(target, "target");
            artifactName = safeName(artifactName, "artifactName");
            requireExact(artifactName, target, "CMake artifact");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /**
         * Returns the supported deployment project type. / 返回支持的部署项目类型。
         *
         * @return the supported deployment project type / 支持的部署项目类型
         */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.CMAKE_SERVICE; }
    }

    /**
     * Supported single-container engine model. / 受支持的单容器引擎模型。
     */
    enum ContainerEngineType { /**
     * Docker daemon. / Docker 守护进程。
     */ DOCKER, /**
     * Podman Quadlet. / Podman Quadlet 单元定义。
     */ PODMAN }

    /**
     * Platform-managed volume that never targets a host root or arbitrary host path. / 绝不指向宿主根目录或任意宿主路径的平台受管卷。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param containerPath container path / 容器路径
     * @param readOnly read only / 读取仅
     */
    record ManagedVolume(String name, String containerPath, boolean readOnly) {
        /**
         * Returns binding id.
         * <p>返回绑定标识。
         *
         * @return binding id / 绑定标识
         */
        public String bindingId() { return name.substring("windowstolinux-".length()); }
        /**
         * Creates a {@code ManagedVolume} specification. / 创建 {@code ManagedVolume} 规范。
         *
         * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
         * @param containerPath container path / 容器路径
         * @param readOnly read only / 读取仅
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public ManagedVolume {
            name = Objects.requireNonNull(name, "name").trim();
            if (!name.matches("windowstolinux-[a-z0-9][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("managed volume names must stay in the WindowsToLinux namespace");
            }
            containerPath = Objects.requireNonNull(containerPath, "containerPath").trim();
            if (!containerPath.matches("/[A-Za-z0-9._/-]{1,255}") || containerPath.equals("/")
                    || containerPath.contains("..") || containerPath.contains("//")) {
                throw new IllegalArgumentException("container volume paths must be bounded absolute non-root paths");
            }
        }
    }

    /**
     * Validates a relative path before it is joined to the controlled root.
     * <p>在与受控根目录拼接前验证相对路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return relative path text / 相对路径文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String relativePath(String value, String name) {
        value = Objects.requireNonNull(value, name).trim().replace('\\', '/');
        if (value.isBlank() || value.startsWith("/") || value.contains("..") || value.contains("//")
                || !value.matches("[A-Za-z0-9._/-]{1,255}")) {
            throw new IllegalArgumentException(name + " must be a bounded safe relative path");
        }
        return value;
    }

    /**
     * Checks java name syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查Java名称语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return java name text / Java名称文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String javaName(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}")) {
            throw new IllegalArgumentException(name + " must be a Java binary class name");
        }
        return value;
    }

    /**
     * Validates and produces safe name for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的安全名称。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return safe name text / 安全名称文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String safeName(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a bounded safe name");
        }
        return value;
    }

    /**
     * Validates and returns version token and rejects inputs outside the declared constraints.
     * <p>校验并返回版本令牌并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return require version token text / 要求版本令牌文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireVersionToken(String value) {
        value = Objects.requireNonNull(value, "version").trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,95}")) {
            throw new IllegalArgumentException("version is not allowed for the selected service runtime");
        }
        return value;
    }

    /**
     * Requires network port number in the reviewed endpoint and rejects inputs outside the declared constraints.
     * <p>要求已审阅端点中的网络端口号并拒绝超出已声明约束的输入。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void requirePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("servicePort must be in the TCP/UDP port range");
        }
    }

    /**
     * Requires exact and rejects inputs outside the declared constraints.
     * <p>要求精确并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void requireExact(String value, String expected, String name) {
        if (!value.equals(expected)) {
            throw new IllegalArgumentException(name + " must use the fixed reviewed value");
        }
    }

    /**
     * Validates and returns java version and rejects inputs outside the declared constraints.
     * <p>校验并返回Java版本并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return require java version text / 要求Java版本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireJavaVersion(String value) {
        value = Objects.requireNonNull(value, "javaVersion").trim();
        var parsed = gold.debug.windowstolinux.shared.model.toolchain.ToolchainVersion.parse(
                gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.JAVA, value);
        if (parsed.isEmpty()) throw new IllegalArgumentException("javaVersion must be an explicit version");
        return parsed.orElseThrow().branch();
    }

    /**
     * Validates and returns python version and rejects inputs outside the declared constraints.
     * <p>校验并返回python版本并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return require python version text / 要求Python版本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requirePythonVersion(String value) {
        value = Objects.requireNonNull(value, "pythonVersion").trim();
        if (!value.matches("[0-9]{1,9}\\.[0-9]{1,9}(?:\\.[0-9]{1,9})?(?:[-+][A-Za-z0-9._-]+)?")) {
            throw new IllegalArgumentException("pythonVersion must be one supported explicit minor version");
        }
        return value;
    }

    /**
     * Checks python entrypoint syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查python入口语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return python entrypoint text / python入口文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String pythonEntrypoint(String value) {
        value = Objects.requireNonNull(value, "entrypoint").trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_.]{0,127}")) {
            throw new IllegalArgumentException("entrypoint must be a Python module reference");
        }
        return value;
    }

    /**
     * Validates and produces safe arguments for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的安全参数。
     *
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static List<String> safeArguments(List<String> arguments, String name) {
        arguments = List.copyOf(Objects.requireNonNull(arguments, name));
        if (arguments.size() > 32) {
            throw new IllegalArgumentException(name + " exceeds the bounded argument count");
        }
        for (String argument : arguments) {
            if (argument == null || !argument.matches("[A-Za-z0-9@%_+=:,./-]{1,512}")) {
                throw new IllegalArgumentException(name + " contains an unsafe argument");
            }
        }
        return arguments;
    }
}
