package gold.debug.windowstolinux.shared.model.project;

/**
 * The single-component project types considered by the typed deployment planner.
 *
 * <p>部署计划器考虑的单组件项目类型。
 */
public enum DeploymentProjectType {
    /** A Spring Boot executable JAR built through one reviewed build tool. / 通过一个经审阅构建工具构建的 Spring Boot 可执行 JAR。 */
    SPRING_BOOT,
    /** A Java JAR with an explicit main class. / 具有显式主类的 Java JAR。 */
    JAVA_JAR,
    /** A lockfile-backed Node.js service. / 由锁文件支持的 Node.js 服务。 */
    NODE_SERVICE,
    /** A lockfile-backed Python service. / 由锁文件支持的 Python 服务。 */
    PYTHON_SERVICE,
    /** A bounded static-site build and controlled static runtime. / 有界静态站点构建和受控静态运行时。 */
    STATIC_SITE,
    /** One Dockerfile image and one managed container. / 一个 Dockerfile 镜像和一个受管容器。 */
    DOCKERFILE_CONTAINER(true),
    /** Static language and metadata recognition with no deployment path. / 不含部署路径的静态语言与元数据识别。 */
    RECOGNITION_PREVIEW(false);

    private final boolean deployable;

    DeploymentProjectType() {
        this(true);
    }

    DeploymentProjectType(boolean deployable) {
        this.deployable = deployable;
    }

    /** Returns whether the type requires deployment adapters and renderers. / 返回此类型是否需要部署适配器与渲染器。 */
    public boolean deployable() {
        return deployable;
    }

    /** Returns all plan-capable types. / 返回全部可计划类型。 */
    public static java.util.Set<DeploymentProjectType> deployableTypes() {
        return java.util.Arrays.stream(values()).filter(DeploymentProjectType::deployable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
