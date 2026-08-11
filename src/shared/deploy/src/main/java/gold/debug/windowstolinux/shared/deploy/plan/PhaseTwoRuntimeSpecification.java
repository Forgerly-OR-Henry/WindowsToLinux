package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A typed runtime definition for exactly one Phase Two single-component project type.
 *
 * <p>恰好一个二期单组件项目类型的类型化运行定义。
 */
public sealed interface PhaseTwoRuntimeSpecification permits PhaseTwoRuntimeSpecification.GradleSpringBoot,
        PhaseTwoRuntimeSpecification.JavaJar, PhaseTwoRuntimeSpecification.NodeService,
        PhaseTwoRuntimeSpecification.PythonService, PhaseTwoRuntimeSpecification.StaticSite,
        PhaseTwoRuntimeSpecification.Container {
    /**
     * Returns the project type this specification can run.
     *
     * <p>返回此规范能够运行的项目类型。
     *
     * @return the matching project type / 匹配的项目类型
     */
    PhaseTwoProjectType projectType();

    /**
     * Returns the required layered health check.
     *
     * <p>返回所需的分层健康检查。
     *
     * @return the health check / 健康检查
     */
    HealthCheck healthCheck();

    /** Gradle Spring Boot systemd runtime. / Gradle Spring Boot systemd 运行时。 */
    record GradleSpringBoot(HealthCheck healthCheck) implements PhaseTwoRuntimeSpecification {
        /** Creates a {@code GradleSpringBoot} specification. / 创建 {@code GradleSpringBoot} 规范。 */
        public GradleSpringBoot { healthCheck = Objects.requireNonNull(healthCheck, "healthCheck"); }
        @Override public PhaseTwoProjectType projectType() { return PhaseTwoProjectType.GRADLE_SPRING_BOOT; }
    }

    /** A Java JAR systemd runtime with structured JVM and application arguments. / 具有结构化 JVM 和应用参数的 Java JAR systemd 运行时。 */
    record JavaJar(String jarRelativePath, String mainClass, String javaVersion, List<String> jvmArguments,
                   List<String> applicationArguments, HealthCheck healthCheck) implements PhaseTwoRuntimeSpecification {
        /** Creates a {@code JavaJar} specification. / 创建 {@code JavaJar} 规范。 */
        public JavaJar {
            jarRelativePath = relativePath(jarRelativePath, "jarRelativePath");
            mainClass = javaName(mainClass, "mainClass");
            javaVersion = requireJavaVersion(javaVersion);
            jvmArguments = safeArguments(jvmArguments, "jvmArguments");
            applicationArguments = safeArguments(applicationArguments, "applicationArguments");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        @Override public PhaseTwoProjectType projectType() { return PhaseTwoProjectType.JAVA_JAR; }
    }

    /** A lockfile-backed Node service runtime. / 由锁文件支持的 Node 服务运行时。 */
    record NodeService(int nodeMajorVersion, HealthCheck healthCheck) implements PhaseTwoRuntimeSpecification {
        /** Creates a {@code NodeService} specification. / 创建 {@code NodeService} 规范。 */
        public NodeService {
            if (nodeMajorVersion < 18 || nodeMajorVersion > 24) {
                throw new IllegalArgumentException("nodeMajorVersion must be a supported explicit major version");
            }
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        @Override public PhaseTwoProjectType projectType() { return PhaseTwoProjectType.NODE_SERVICE; }
    }

    /** A project-virtual-environment Python runtime. / 项目虚拟环境 Python 运行时。 */
    record PythonService(String pythonVersion, String entrypoint, HealthCheck healthCheck) implements PhaseTwoRuntimeSpecification {
        /** Creates a {@code PythonService} specification. / 创建 {@code PythonService} 规范。 */
        public PythonService {
            pythonVersion = requirePythonVersion(pythonVersion);
            entrypoint = pythonEntrypoint(entrypoint);
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        @Override public PhaseTwoProjectType projectType() { return PhaseTwoProjectType.PYTHON_SERVICE; }
    }

    /** A static site served only from a bounded generated output directory. / 仅从有界生成输出目录提供的静态站点。 */
    record StaticSite(String outputDirectory, HealthCheck.Http healthCheck) implements PhaseTwoRuntimeSpecification {
        /** Creates a {@code StaticSite} specification. / 创建 {@code StaticSite} 规范。 */
        public StaticSite {
            outputDirectory = relativePath(outputDirectory, "outputDirectory");
            if (outputDirectory.equals(".")) {
                throw new IllegalArgumentException("outputDirectory must not expose the source root");
            }
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        @Override public PhaseTwoProjectType projectType() { return PhaseTwoProjectType.STATIC_SITE; }
    }

    /** One image and one container without privileged or host-namespace escape options. / 不含特权或宿主命名空间逃逸选项的单镜像单容器。 */
    record Container(ContainerEngine engine, Map<Integer, Integer> publishedPorts, List<ManagedVolume> volumes,
                     HealthCheck healthCheck) implements PhaseTwoRuntimeSpecification {
        /** Creates a {@code Container} specification. / 创建 {@code Container} 规范。 */
        public Container {
            engine = Objects.requireNonNull(engine, "engine");
            publishedPorts = Map.copyOf(Objects.requireNonNull(publishedPorts, "publishedPorts"));
            if (publishedPorts.isEmpty()) {
                throw new IllegalArgumentException("a managed container must declare at least one explicit published port");
            }
            publishedPorts.forEach((hostPort, containerPort) -> {
                if (hostPort == null || containerPort == null || hostPort < 1 || hostPort > 65535
                        || containerPort < 1 || containerPort > 65535) {
                    throw new IllegalArgumentException("container ports must be in the TCP/UDP port range");
                }
            });
            volumes = List.copyOf(Objects.requireNonNull(volumes, "volumes"));
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        @Override public PhaseTwoProjectType projectType() { return PhaseTwoProjectType.DOCKERFILE_CONTAINER; }
    }

    /** The supported single-container engine model. / 受支持的单容器引擎模型。 */
    enum ContainerEngine { /** Docker daemon. / Docker 守护进程。 */ DOCKER, /** Podman Quadlet. / Podman Quadlet。 */ PODMAN }

    /** A platform-managed volume that can never target a host root or arbitrary host path. / 平台受管卷，绝不能指向宿主根目录或任意宿主路径。 */
    record ManagedVolume(String name, String containerPath, boolean readOnly) {
        /** Creates a {@code ManagedVolume} specification. / 创建 {@code ManagedVolume} 规范。 */
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

    private static String relativePath(String value, String name) {
        value = Objects.requireNonNull(value, name).trim().replace('\\', '/');
        if (value.isBlank() || value.startsWith("/") || value.contains("..") || value.contains("//")
                || !value.matches("[A-Za-z0-9._/-]{1,255}")) {
            throw new IllegalArgumentException(name + " must be a bounded safe relative path");
        }
        return value;
    }

    private static String javaName(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}")) {
            throw new IllegalArgumentException(name + " must be a Java binary class name");
        }
        return value;
    }

    private static String requireJavaVersion(String value) {
        value = Objects.requireNonNull(value, "javaVersion").trim();
        if (!value.matches("(?:17|21|22)")) {
            throw new IllegalArgumentException("javaVersion must be one supported major version");
        }
        return value;
    }

    private static String requirePythonVersion(String value) {
        value = Objects.requireNonNull(value, "pythonVersion").trim();
        if (!value.matches("3\\.(?:10|11|12|13)")) {
            throw new IllegalArgumentException("pythonVersion must be one supported explicit minor version");
        }
        return value;
    }

    private static String pythonEntrypoint(String value) {
        value = Objects.requireNonNull(value, "entrypoint").trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_.]{0,127}(?::[A-Za-z_][A-Za-z0-9_]{0,127})?")) {
            throw new IllegalArgumentException("entrypoint must be a Python module or module:callable reference");
        }
        return value;
    }

    private static List<String> safeArguments(List<String> arguments, String name) {
        arguments = List.copyOf(Objects.requireNonNull(arguments, name));
        if (arguments.size() > 32) {
            throw new IllegalArgumentException(name + " exceeds the bounded argument count");
        }
        for (String argument : arguments) {
            if (argument == null || argument.isBlank() || argument.length() > 512 || argument.matches(".*[\\r\\n\\0].*")) {
                throw new IllegalArgumentException(name + " contains an unsafe argument");
            }
        }
        return arguments;
    }
}
