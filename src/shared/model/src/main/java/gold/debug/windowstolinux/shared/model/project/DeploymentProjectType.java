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
    DOCKERFILE_CONTAINER
}
