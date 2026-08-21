package gold.debug.windowstolinux.shared.backup.manifest;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/** Closed portable representation of every reviewed deployment runtime. / 每种经审阅部署运行时的封闭可移植表示。 */
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
    /** Returns the exact reviewed project type. / 返回精确的经审阅项目类型。 */
    DeploymentProjectType projectType();

    /** Returns the portable component health check. / 返回可移植组件健康检查。 */
    BackupHealthCheck healthCheck();

    /** Recreates the canonical runtime without interpreting arbitrary text. / 在不解释任意文本的情况下重建规范运行时。 */
    DeploymentRuntimeSpecification toSpecification();

    /** Spring Boot runtime. / Spring Boot 运行时。 */
    record SpringBoot(BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /** Validates this runtime. / 校验此运行时。 */
        public SpringBoot {
            healthCheck = required(healthCheck);
            new DeploymentRuntimeSpecification.SpringBoot(healthCheck.toHealthCheck());
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.SPRING_BOOT; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.SpringBoot(healthCheck.toHealthCheck());
        }
    }

    /** Java JAR runtime. / Java JAR 运行时。 */
    record JavaJar(String jarRelativePath, String mainClass, String javaVersion, List<String> jvmArguments,
                   List<String> applicationArguments, BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /** Validates and freezes this runtime. / 校验并冻结此运行时。 */
        public JavaJar {
            jvmArguments = List.copyOf(Objects.requireNonNull(jvmArguments, "jvmArguments"));
            applicationArguments = List.copyOf(Objects.requireNonNull(applicationArguments, "applicationArguments"));
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.JavaJar checked = new DeploymentRuntimeSpecification.JavaJar(
                    jarRelativePath, mainClass, javaVersion, jvmArguments, applicationArguments,
                    healthCheck.toHealthCheck());
            jarRelativePath = checked.jarRelativePath(); mainClass = checked.mainClass(); javaVersion = checked.javaVersion();
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_JAR; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.JavaJar(jarRelativePath, mainClass, javaVersion,
                    jvmArguments, applicationArguments, healthCheck.toHealthCheck());
        }
    }

    /** Dependency-free Java source runtime. / 无依赖 Java 源码运行时。 */
    record JavaSource(String sourceRoot, String mainClass, String javaVersion, List<String> jvmArguments,
                      List<String> applicationArguments, BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /** Validates and freezes this runtime. / 校验并冻结此运行时。 */
        public JavaSource {
            jvmArguments = List.copyOf(Objects.requireNonNull(jvmArguments, "jvmArguments"));
            applicationArguments = List.copyOf(Objects.requireNonNull(applicationArguments, "applicationArguments"));
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.JavaSource checked = new DeploymentRuntimeSpecification.JavaSource(
                    sourceRoot, mainClass, javaVersion, jvmArguments, applicationArguments,
                    healthCheck.toHealthCheck());
            sourceRoot = checked.sourceRoot(); mainClass = checked.mainClass(); javaVersion = checked.javaVersion();
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_SOURCE; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.JavaSource(sourceRoot, mainClass, javaVersion,
                    jvmArguments, applicationArguments, healthCheck.toHealthCheck());
        }
    }

    /** Node service runtime. / Node 服务运行时。 */
    record NodeService(int nodeMajorVersion, BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /** Validates this runtime. / 校验此运行时。 */
        public NodeService {
            healthCheck = required(healthCheck);
            new DeploymentRuntimeSpecification.NodeService(nodeMajorVersion, healthCheck.toHealthCheck());
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.NODE_SERVICE; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.NodeService(nodeMajorVersion, healthCheck.toHealthCheck());
        }
    }

    /** Python service runtime. / Python 服务运行时。 */
    record PythonService(String pythonVersion, String entrypoint, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /** Validates and normalizes this runtime. / 校验并规范化此运行时。 */
        public PythonService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.PythonService checked =
                    new DeploymentRuntimeSpecification.PythonService(pythonVersion, entrypoint, healthCheck.toHealthCheck());
            pythonVersion = checked.pythonVersion(); entrypoint = checked.entrypoint();
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PYTHON_SERVICE; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.PythonService(pythonVersion, entrypoint, healthCheck.toHealthCheck());
        }
    }

    /** Static-site runtime with zero meaning no Node build. / 以零表示不含 Node 构建的静态站点运行时。 */
    record StaticSite(String outputDirectory, int nodeMajorVersion, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /** Validates and normalizes this runtime. / 校验并规范化此运行时。 */
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
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.STATIC_SITE; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            if (nodeMajorVersion < 0) throw new IllegalArgumentException("nodeMajorVersion must not be negative");
            return new DeploymentRuntimeSpecification.StaticSite(outputDirectory,
                    nodeMajorVersion == 0 ? OptionalInt.empty() : OptionalInt.of(nodeMajorVersion),
                    (gold.debug.windowstolinux.shared.model.health.HealthCheck.Http) healthCheck.toHealthCheck());
        }
    }

    /** Single managed container runtime. / 单个受管容器运行时。 */
    record Container(DeploymentRuntimeSpecification.ContainerEngineType engine,
                     Map<Integer, Integer> publishedPorts, List<BackupManagedVolume> volumes,
                     BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /** Validates and freezes this runtime. / 校验并冻结此运行时。 */
        public Container {
            engine = Objects.requireNonNull(engine, "engine");
            publishedPorts = Map.copyOf(Objects.requireNonNull(publishedPorts, "publishedPorts"));
            volumes = List.copyOf(Objects.requireNonNull(volumes, "volumes"));
            healthCheck = required(healthCheck);
            new DeploymentRuntimeSpecification.Container(engine, publishedPorts,
                    volumes.stream().map(BackupManagedVolume::toManagedVolume).toList(), healthCheck.toHealthCheck());
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOCKERFILE_CONTAINER; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.Container(engine, publishedPorts,
                    volumes.stream().map(BackupManagedVolume::toManagedVolume).toList(), healthCheck.toHealthCheck());
        }
    }

    /** Go service runtime. / Go 服务运行时。 */
    record GoService(String version, String artifactName, String entrypoint, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /** Validates and normalizes this runtime. / 校验并规范化此运行时。 */
        public GoService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.GoService checked = new DeploymentRuntimeSpecification.GoService(
                    version, artifactName, entrypoint, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.GO_SERVICE; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.GoService(version, artifactName, entrypoint, healthCheck.toHealthCheck());
        }
    }

    /** Rust service runtime. / Rust 服务运行时。 */
    record RustService(String version, String artifactName, String entrypoint, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /** Validates and normalizes this runtime. / 校验并规范化此运行时。 */
        public RustService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.RustService checked = new DeploymentRuntimeSpecification.RustService(
                    version, artifactName, entrypoint, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUST_SERVICE; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.RustService(version, artifactName, entrypoint, healthCheck.toHealthCheck());
        }
    }

    /** .NET service runtime. / .NET 服务运行时。 */
    record DotNetService(String version, String artifactName, String entrypoint, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /** Validates and normalizes this runtime. / 校验并规范化此运行时。 */
        public DotNetService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.DotNetService checked = new DeploymentRuntimeSpecification.DotNetService(
                    version, artifactName, entrypoint, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOTNET_SERVICE; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.DotNetService(version, artifactName, entrypoint,
                    healthCheck.toHealthCheck());
        }
    }

    /** Kotlin service runtime. / Kotlin 服务运行时。 */
    record KotlinService(String version, String artifactName, String entrypoint, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /** Validates and normalizes this runtime. / 校验并规范化此运行时。 */
        public KotlinService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.KotlinService checked = new DeploymentRuntimeSpecification.KotlinService(
                    version, artifactName, entrypoint, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.KOTLIN_SERVICE; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.KotlinService(version, artifactName, entrypoint,
                    healthCheck.toHealthCheck());
        }
    }

    /** PHP service runtime. / PHP 服务运行时。 */
    record PhpService(String version, String artifactName, String entrypoint, int servicePort,
                      BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /** Validates and normalizes this runtime. / 校验并规范化此运行时。 */
        public PhpService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.PhpService checked = new DeploymentRuntimeSpecification.PhpService(
                    version, artifactName, entrypoint, servicePort, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PHP_SERVICE; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.PhpService(version, artifactName, entrypoint, servicePort,
                    healthCheck.toHealthCheck());
        }
    }

    /** Ruby service runtime. / Ruby 服务运行时。 */
    record RubyService(String version, String artifactName, String entrypoint, int servicePort,
                       BackupHealthCheck healthCheck) implements BackupComponentRuntime {
        /** Validates and normalizes this runtime. / 校验并规范化此运行时。 */
        public RubyService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.RubyService checked = new DeploymentRuntimeSpecification.RubyService(
                    version, artifactName, entrypoint, servicePort, healthCheck.toHealthCheck());
            version = checked.version(); artifactName = checked.artifactName(); entrypoint = checked.entrypoint();
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUBY_SERVICE; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.RubyService(version, artifactName, entrypoint, servicePort,
                    healthCheck.toHealthCheck());
        }
    }

    /** CMake service runtime. / CMake 服务运行时。 */
    record CmakeService(String preset, String target, String artifactName, BackupHealthCheck healthCheck)
            implements BackupComponentRuntime {
        /** Validates and normalizes this runtime. / 校验并规范化此运行时。 */
        public CmakeService {
            healthCheck = required(healthCheck);
            DeploymentRuntimeSpecification.CmakeService checked = new DeploymentRuntimeSpecification.CmakeService(
                    preset, target, artifactName, healthCheck.toHealthCheck());
            preset = checked.preset(); target = checked.target(); artifactName = checked.artifactName();
        }
        @Override public DeploymentProjectType projectType() { return DeploymentProjectType.CMAKE_SERVICE; }
        @Override public DeploymentRuntimeSpecification toSpecification() {
            return new DeploymentRuntimeSpecification.CmakeService(preset, target, artifactName,
                    healthCheck.toHealthCheck());
        }
    }

    /** Copies one canonical runtime into the portable schema. / 将一个规范运行时复制到可移植 schema。 */
    static BackupComponentRuntime from(DeploymentRuntimeSpecification runtime) {
        return switch (Objects.requireNonNull(runtime, "runtime")) {
            case DeploymentRuntimeSpecification.SpringBoot value -> new SpringBoot(BackupHealthCheck.from(value.healthCheck()));
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
                    value.entrypoint(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.PhpService value -> new PhpService(value.version(), value.artifactName(),
                    value.entrypoint(), value.servicePort(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.RubyService value -> new RubyService(value.version(), value.artifactName(),
                    value.entrypoint(), value.servicePort(), BackupHealthCheck.from(value.healthCheck()));
            case DeploymentRuntimeSpecification.CmakeService value -> new CmakeService(value.preset(), value.target(),
                    value.artifactName(), BackupHealthCheck.from(value.healthCheck()));
        };
    }

    private static BackupHealthCheck required(BackupHealthCheck healthCheck) {
        return Objects.requireNonNull(healthCheck, "healthCheck");
    }
}
