package gold.debug.windowstolinux.shared.model.project;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Typed runtime definition for exactly one typed deployment single-component project type.
 *
 * <p>恰好一个部署单组件项目类型的类型化运行定义。
 */
public sealed interface DeploymentRuntimeSpecification permits DeploymentRuntimeSpecification.SpringBoot,
        DeploymentRuntimeSpecification.JavaJar, DeploymentRuntimeSpecification.JavaSource,
        DeploymentRuntimeSpecification.NodeService,
        DeploymentRuntimeSpecification.PythonService, DeploymentRuntimeSpecification.StaticSite,
        DeploymentRuntimeSpecification.Container, DeploymentRuntimeSpecification.GoService,
        DeploymentRuntimeSpecification.RustService, DeploymentRuntimeSpecification.DotNetService,
        DeploymentRuntimeSpecification.KotlinService, DeploymentRuntimeSpecification.PhpService,
        DeploymentRuntimeSpecification.RubyService, DeploymentRuntimeSpecification.CmakeService {
    /** Returns the matching project type. / 返回匹配的项目类型。 */
    DeploymentProjectType projectType();

    /** Returns the required layered health check. / 返回所需的分层健康检查。 */
    HealthCheck healthCheck();

    /** Spring Boot systemd runtime independent of its reviewed build tool. / 与经审阅构建工具无关的 Spring Boot systemd 运行时。 */
    record SpringBoot(HealthCheck healthCheck) implements DeploymentRuntimeSpecification {
        /** Creates a {@code SpringBoot} specification. / 创建 {@code SpringBoot} 规范。 */
        public SpringBoot { healthCheck = Objects.requireNonNull(healthCheck, "healthCheck"); }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.SPRING_BOOT; }
    }

    /** Java JAR systemd runtime with structured JVM and application arguments. / 具有结构化 JVM 和应用参数的 Java JAR systemd 运行时。 */
    record JavaJar(String jarRelativePath, String mainClass, String javaVersion, List<String> jvmArguments,
                   List<String> applicationArguments, HealthCheck healthCheck) implements DeploymentRuntimeSpecification {
        /** Creates a {@code JavaJar} specification. / 创建 {@code JavaJar} 规范。 */
        public JavaJar {
            jarRelativePath = relativePath(jarRelativePath, "jarRelativePath");
            mainClass = javaName(mainClass, "mainClass");
            javaVersion = requireJavaVersion(javaVersion);
            jvmArguments = safeArguments(jvmArguments, "jvmArguments");
            applicationArguments = safeArguments(applicationArguments, "applicationArguments");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_JAR; }
    }

    /** Dependency-free Java source compiled into one executable JAR on the target. / 在目标机编译为单一可执行 JAR 的无依赖 Java 源码。 */
    record JavaSource(String sourceRoot, String mainClass, String javaVersion, List<String> jvmArguments,
                      List<String> applicationArguments, HealthCheck healthCheck) implements DeploymentRuntimeSpecification {
        /** Creates a reviewed Java source specification. / 创建经审阅的 Java 源码规范。 */
        public JavaSource {
            sourceRoot = relativePath(sourceRoot, "sourceRoot");
            mainClass = javaName(mainClass, "mainClass");
            javaVersion = requireJavaVersion(javaVersion);
            if (!"21".equals(javaVersion)) {
                throw new IllegalArgumentException("Java source compilation requires the fixed Java 21 baseline");
            }
            jvmArguments = safeArguments(jvmArguments, "jvmArguments");
            applicationArguments = safeArguments(applicationArguments, "applicationArguments");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_SOURCE; }
    }

    /** Lockfile-backed Node service runtime. / 由锁文件支持的 Node 服务运行时。 */
    record NodeService(int nodeMajorVersion, HealthCheck healthCheck) implements DeploymentRuntimeSpecification {
        /** Creates a {@code NodeService} specification. / 创建 {@code NodeService} 规范。 */
        public NodeService {
            if (nodeMajorVersion < 18 || nodeMajorVersion > 24) {
                throw new IllegalArgumentException("nodeMajorVersion must be a supported explicit major version");
            }
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.NODE_SERVICE; }
    }

    /** Project-virtual-environment Python runtime. / 项目虚拟环境 Python 运行时。 */
    record PythonService(String pythonVersion, String entrypoint, HealthCheck healthCheck) implements DeploymentRuntimeSpecification {
        /** Creates a {@code PythonService} specification. / 创建 {@code PythonService} 规范。 */
        public PythonService {
            pythonVersion = requirePythonVersion(pythonVersion);
            entrypoint = pythonEntrypoint(entrypoint);
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PYTHON_SERVICE; }
    }

    /** Static site served only from a bounded generated output directory. / 仅从有界生成输出目录提供的静态站点。 */
    record StaticSite(String outputDirectory, OptionalInt nodeMajorVersion,
                      HealthCheck.Http healthCheck) implements DeploymentRuntimeSpecification {
        /** Creates a {@code StaticSite} specification. / 创建 {@code StaticSite} 规范。 */
        public StaticSite {
            outputDirectory = relativePath(outputDirectory, "outputDirectory");
            if (outputDirectory.equals(".")) {
                throw new IllegalArgumentException("outputDirectory must not expose the source root");
            }
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
            nodeMajorVersion = Objects.requireNonNull(nodeMajorVersion, "nodeMajorVersion");
            if (nodeMajorVersion.isPresent()
                    && (nodeMajorVersion.getAsInt() < 18 || nodeMajorVersion.getAsInt() > 24)) {
                throw new IllegalArgumentException("nodeMajorVersion must be a supported explicit major version");
            }
        }
        /** Creates a pure static-site specification without a Node build. / 创建不含 Node 构建的纯静态站点规范。 */
        public StaticSite(String outputDirectory, HealthCheck.Http healthCheck) {
            this(outputDirectory, OptionalInt.empty(), healthCheck);
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.STATIC_SITE; }
    }

    /** One image and one container without privileged or host-namespace escape options. / 不含特权或宿主命名空间逃逸选项的单镜像单容器。 */
    record Container(ContainerEngineType engine, Map<Integer, Integer> publishedPorts, List<ManagedVolume> volumes,
                     HealthCheck healthCheck) implements DeploymentRuntimeSpecification {
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
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOCKERFILE_CONTAINER; }
    }

    /** Go service compiled from one locked module. / 从一个锁定模块编译的 Go 服务。 */
    record GoService(String version, String artifactName, String entrypoint, HealthCheck healthCheck)
            implements DeploymentRuntimeSpecification {
        /** Creates an instance of this type. / 创建此类型的实例。 */
        public GoService {
            version = validateServiceVersion(version, "1\\.(?:22|23|24)"); artifactName = safeName(artifactName, "artifactName");
            entrypoint = relativePath(entrypoint, "entrypoint"); requireExact(entrypoint, "main.go", "Go entrypoint");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.GO_SERVICE; }
    }

    /** Rust service compiled from one locked Cargo package. / 从一个锁定 Cargo 包编译的 Rust 服务。 */
    record RustService(String version, String artifactName, String entrypoint, HealthCheck healthCheck)
            implements DeploymentRuntimeSpecification {
        /** Creates an instance of this type. / 创建此类型的实例。 */
        public RustService {
            version = validateServiceVersion(version, "1\\.(?:7[5-9]|8[0-9]|9[0-9])(?:\\.[0-9]+)?"); artifactName = safeName(artifactName, "artifactName");
            entrypoint = relativePath(entrypoint, "entrypoint"); requireExact(entrypoint, "src/main.rs", "Rust entrypoint");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUST_SERVICE; }
    }

    /** .NET service published from one locked project. / 从一个锁定项目发布的 .NET 服务。 */
    record DotNetService(String version, String artifactName, String entrypoint, HealthCheck healthCheck)
            implements DeploymentRuntimeSpecification {
        /** Creates an instance of this type. / 创建此类型的实例。 */
        public DotNetService {
            version = validateServiceVersion(version, "(?:8|9)\\.0(?:\\.[0-9]+)?"); artifactName = safeName(artifactName, "artifactName");
            entrypoint = relativePath(entrypoint, "entrypoint"); requireExact(entrypoint, artifactName + ".dll", ".NET entrypoint");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOTNET_SERVICE; }
    }

    /** Kotlin/JVM service with an exact compiler or plugin version and fixed Java 21 target. / 具有精确编译器或插件版本并固定到 Java 21 的 Kotlin/JVM 服务。 */
    record KotlinService(String version, String artifactName, String entrypoint, HealthCheck healthCheck)
            implements DeploymentRuntimeSpecification {
        /** Creates an instance of this type. / 创建此类型的实例。 */
        public KotlinService {
            version = validateServiceVersion(version, "(?:1\\.9|2\\.[0-9]+)\\.[0-9]+"); artifactName = safeName(artifactName, "artifactName");
            entrypoint = javaName(entrypoint, "entrypoint"); healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.KOTLIN_SERVICE; }
    }

    /** Composer-locked PHP HTTP service. / Composer 锁定的 PHP HTTP 服务。 */
    record PhpService(String version, String artifactName, String entrypoint, int servicePort, HealthCheck healthCheck)
            implements DeploymentRuntimeSpecification {
        /** Creates an instance of this type. / 创建此类型的实例。 */
        public PhpService {
            version = validateServiceVersion(version, "8\\.(?:2|3|4)"); artifactName = safeName(artifactName, "artifactName");
            entrypoint = relativePath(entrypoint, "entrypoint"); requireExact(artifactName, "public", "PHP document root");
            requireExact(entrypoint, "public/index.php", "PHP router"); requirePort(servicePort);
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PHP_SERVICE; }
    }

    /** Bundler-backed or dependency-free Ruby service. / Bundler 支持或无依赖 Ruby 服务。 */
    record RubyService(String version, String artifactName, String entrypoint, int servicePort, HealthCheck healthCheck)
            implements DeploymentRuntimeSpecification {
        /** Creates an instance of this type. / 创建此类型的实例。 */
        public RubyService {
            version = validateServiceVersion(version, "3\\.(?:2|3|4)(?:\\.[0-9]+)?"); artifactName = safeName(artifactName, "artifactName");
            entrypoint = relativePath(entrypoint, "entrypoint");
            boolean bundler = artifactName.equals("bundle") && entrypoint.equals("config.ru");
            boolean cli = artifactName.equals("source") && entrypoint.endsWith(".rb");
            if (!bundler && !cli) {
                throw new IllegalArgumentException("Ruby runtime must be one reviewed Bundler or CLI shape");
            }
            requirePort(servicePort);
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUBY_SERVICE; }
    }

    /** One fixed CMake preset and one C or C++ service executable. / 一个固定 CMake preset 与单一 C 或 C++ 服务可执行文件。 */
    record CmakeService(String preset, String target, String artifactName, HealthCheck healthCheck)
            implements DeploymentRuntimeSpecification {
        /** Creates a reviewed CMake service specification. / 创建经审阅的 CMake 服务规范。 */
        public CmakeService {
            preset = safeName(preset, "preset");
            target = safeName(target, "target");
            artifactName = safeName(artifactName, "artifactName");
            requireExact(artifactName, target, "CMake artifact");
            healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        }
        /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.CMAKE_SERVICE; }
    }

    /** Supported single-container engine model. / 受支持的单容器引擎模型。 */
    enum ContainerEngineType { /** Docker daemon. / Docker 守护进程。 */ DOCKER, /** Podman Quadlet. / Podman Quadlet 单元定义。 */ PODMAN }

    /** Platform-managed volume that never targets a host root or arbitrary host path. / 绝不指向宿主根目录或任意宿主路径的平台受管卷。 */
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

    private static String safeName(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a bounded safe name");
        }
        return value;
    }

    private static String validateServiceVersion(String value, String pattern) {
        value = Objects.requireNonNull(value, "version").trim();
        if (!value.matches(pattern)) {
            throw new IllegalArgumentException("version is not allowed for the selected service runtime");
        }
        return value;
    }

    private static void requirePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("servicePort must be in the TCP/UDP port range");
        }
    }

    private static void requireExact(String value, String expected, String name) {
        if (!value.equals(expected)) {
            throw new IllegalArgumentException(name + " must use the fixed reviewed value");
        }
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
        if (!value.matches("[A-Za-z_][A-Za-z0-9_.]{0,127}")) {
            throw new IllegalArgumentException("entrypoint must be a Python module reference");
        }
        return value;
    }

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
