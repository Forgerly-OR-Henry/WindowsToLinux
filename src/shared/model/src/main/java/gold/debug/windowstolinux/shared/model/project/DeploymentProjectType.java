package gold.debug.windowstolinux.shared.model.project;

/**
 * The single-component project types considered by the typed deployment planner.
 *
 *  <p>部署计划器考虑的单组件项目类型。
 */
public enum DeploymentProjectType {
    /**
     * A Spring Boot executable JAR built through one reviewed build tool. / 通过一个经审阅构建工具构建的 Spring Boot 可执行 JAR。
     */
    SPRING_BOOT,
    /**
     * A Java JAR with an explicit main class. / 具有显式主类的 Java JAR。
     */
    JAVA_JAR,
    /**
     * Dependency-free Java source compiled by the target JDK. / 由目标机 JDK 编译的无依赖 Java 源码。
     */
    JAVA_SOURCE,
    /**
     * A lockfile-backed Node.js service. / 由锁文件支持的 Node.js 服务。
     */
    NODE_SERVICE,
    /**
     * A lockfile-backed Python service. / 由锁文件支持的 Python 服务。
     */
    PYTHON_SERVICE,
    /**
     * A bounded static-site build and controlled static runtime. / 有界静态站点构建和受控静态运行时。
     */
    STATIC_SITE,
    /**
     * One Dockerfile image and one managed container. / 一个 Dockerfile 镜像和一个受管容器。
     */
    DOCKERFILE_CONTAINER(true),
    /**
     * A locked Go module compiled to one managed service binary. / 编译为一个受管服务二进制文件的锁定 Go 模块。
     */
    GO_SERVICE,
    /**
     * A locked Rust Cargo package compiled to one managed service binary. / 编译为一个受管服务二进制文件的锁定 Rust Cargo 包。
     */
    RUST_SERVICE,
    /**
     * A locked .NET service published to one managed output. / 发布为一个受管输出的锁定 .NET 服务。
     */
    DOTNET_SERVICE,
    /**
     * A Gradle Wrapper Kotlin/JVM application distribution. / Gradle Wrapper Kotlin/JVM 应用分发。
     */
    KOTLIN_SERVICE,
    /**
     * A Composer-locked PHP service with a bounded document root and router. / 具有有界文档根与路由器的 Composer 锁定 PHP 服务。
     */
    PHP_SERVICE,
    /**
     * A Bundler-locked Rack service with a bounded config file. / 具有有界配置文件的 Bundler 锁定 Rack 服务。
     */
    RUBY_SERVICE,
    /**
     * One reviewed CMake preset and one C or C++ service executable. / 一个经审阅的 CMake preset 与单一 C 或 C++ 服务可执行文件。
     */
    CMAKE_SERVICE,
    /**
     * Static language and metadata recognition with no deployment path. / 不含部署路径的静态语言与元数据识别。
     */
    RECOGNITION_PREVIEW(false),
    /** Language-independent managed artifact; excluded from static adapter discovery. / 与语言无关的受管制品，不参与静态适配器发现。 */
    MANAGED_PROCESS(false);

    /**
     * Deployable.
     * <p>可部署。
     */
    private final boolean deployable;

    /**
     * Initializes deployment project type through its shared constructor contract.
     * <p>通过共享构造契约初始化部署项目类型。
     */
    DeploymentProjectType() {
        this(true);
    }

    /**
     * Binds the supplied dependencies and state for deployment project type.
     * <p>为部署项目类型绑定传入的依赖及状态。
     *
     * @param deployable deployable / 可部署
     */
    DeploymentProjectType(boolean deployable) {
        this.deployable = deployable;
    }

    /**
     * Returns whether the type requires deployment adapters and renderers. / 返回此类型是否需要部署适配器与渲染器。
     *
     * @return true when returns whether the type requires deployment adapters and renderers, false otherwise / 返回此类型是否需要部署适配器与渲染器时为 true，否则为 false
     */
    public boolean deployable() {
        return deployable;
    }

    /**
     * Returns all plan-capable types. / 返回全部可计划类型。
     *
     * @return all plan-capable types / 全部可计划类型
     */
    public static java.util.Set<DeploymentProjectType> deployableTypes() {
        return java.util.Arrays.stream(values()).filter(DeploymentProjectType::deployable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
