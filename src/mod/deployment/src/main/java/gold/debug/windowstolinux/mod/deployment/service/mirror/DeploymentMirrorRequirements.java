package gold.debug.windowstolinux.mod.deployment.service.mirror;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.ProjectPartFinder;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.ApplicationBundleChecker;

/** Pure download-purpose classification; discovery never executes project content. */
public final class DeploymentMirrorRequirements {
    private static final Set<DownloadPoint> ALL = Collections.unmodifiableSet(EnumSet.allOf(DownloadPoint.class));
    private static final Set<DownloadPoint> SYSTEM = Collections.unmodifiableSet(EnumSet.of(
        DownloadPoint.APT_DEBIAN, DownloadPoint.APT_UBUNTU, DownloadPoint.DNF_FEDORA,
        DownloadPoint.DNF_ROCKY, DownloadPoint.DNF_ALMA, DownloadPoint.DNF_CENTOS_STREAM, DownloadPoint.DNF_ORACLE));

    private DeploymentMirrorRequirements() { }

    /** Empty/unknown kinds deliberately return every point; callers must retain that uncertainty. */
    public static Set<DownloadPoint> forKinds(Collection<DeploymentProjectKind> kinds) {
        Objects.requireNonNull(kinds);
        if (kinds.isEmpty() || kinds.stream().anyMatch(kind -> kind == null || !kind.deployable())) return ALL;
        var result = EnumSet.copyOf(SYSTEM);
        for (var kind : kinds) add(result, kind, null);
        return Collections.unmodifiableSet(result);
    }

    /** Supplements source purposes with optional tools explicitly selected in the reviewed runtime. */
    public static Set<DownloadPoint> additionalForRuntime(DeploymentRuntimeDefinition runtime) {
        Objects.requireNonNull(runtime);
        if (runtime instanceof DeploymentRuntimeDefinition.StaticSite site && site.nodeMajorVersion().isPresent())
            return Set.of(DownloadPoint.NODE_DISTRIBUTION, DownloadPoint.NODE_METADATA, DownloadPoint.NODE_PACKAGE);
        return Set.of();
    }

    /** Returns every point for unclassified content; invalid/unreadable source evidence remains an error. */
    public static Set<DownloadPoint> forSource(Path source) throws IOException {
        Path root = Objects.requireNonNull(source).toAbsolutePath().normalize();
        var parts = new ProjectPartFinder().discover(root);
        var declaration = ApplicationBundleChecker.declaration(root);
        Path declaredRoot = ApplicationBundleChecker.type(declaration).isPresent()
            ? ApplicationBundleChecker.mainRoot(root, declaration) : null;
        var result = EnumSet.copyOf(SYSTEM);
        for (var part : parts) {
            if (part.types().isEmpty() || part.types().stream().anyMatch(kind -> !kind.deployable())
                || part.types().containsAll(DeploymentProjectKind.deployableTypes())) return ALL;
            Path component = declaredRoot == null ? root.resolve(part.relativeRoot()).normalize() : declaredRoot;
            for (var kind : part.types()) add(result, kind, component);
        }
        return Collections.unmodifiableSet(result);
    }

    private static void add(EnumSet<DownloadPoint> points, DeploymentProjectKind kind, Path root) {
        switch (kind) {
            case NODE_SERVICE -> node(points);
            case STATIC_SITE -> {
                Collections.addAll(points, DownloadPoint.PYTHON_DISTRIBUTION, DownloadPoint.PYTHON_METADATA,
                    DownloadPoint.GNU_MAKE_SOURCE);
                if (root == null || regular(root, "package.json")) node(points);
            }
            case PYTHON_SERVICE -> Collections.addAll(points, DownloadPoint.PYTHON_DISTRIBUTION,
                DownloadPoint.PYTHON_METADATA, DownloadPoint.PYTHON_PIP_BOOTSTRAP, DownloadPoint.PYTHON_PACKAGE,
                DownloadPoint.GNU_MAKE_SOURCE);
            case JAVA_SOURCE -> java(points);
            case JAVA_JAR, SPRING_BOOT -> {
                java(points);
                boolean maven = root == null || regular(root, "pom.xml") || regular(root, ".mvn/wrapper/maven-wrapper.properties");
                boolean gradle = root == null || regular(root, "build.gradle") || regular(root, "build.gradle.kts")
                    || regular(root, "gradle/wrapper/gradle-wrapper.properties");
                if (root != null && !maven && !gradle && kind == DeploymentProjectKind.SPRING_BOOT) {
                    maven = true; gradle = true;
                }
                if (maven) maven(points);
                if (gradle) gradle(points);
            }
            case KOTLIN_SERVICE -> {
                java(points);
                // The existing preparation contract installs kotlinc even for the Gradle architecture.
                Collections.addAll(points, DownloadPoint.KOTLIN_DISTRIBUTION, DownloadPoint.KOTLIN_METADATA);
                boolean gradle = root == null || regular(root, "build.gradle") || regular(root, "build.gradle.kts")
                    || regular(root, "gradle/wrapper/gradle-wrapper.properties");
                if (!gradle && !regular(root, "windowstolinux-kotlin.properties")) gradle = true;
                if (gradle) gradle(points);
            }
            case GO_SERVICE -> Collections.addAll(points, DownloadPoint.GO_DISTRIBUTION,
                DownloadPoint.GO_METADATA, DownloadPoint.GO_MODULE, DownloadPoint.GO_CHECKSUM);
            case RUST_SERVICE -> Collections.addAll(points, DownloadPoint.RUST_TOOLCHAIN,
                DownloadPoint.RUST_METADATA, DownloadPoint.RUST_CRATE, DownloadPoint.RUST_INDEX);
            case DOTNET_SERVICE -> Collections.addAll(points, DownloadPoint.DOTNET_SDK, DownloadPoint.DOTNET_METADATA, DownloadPoint.NUGET);
            case PHP_SERVICE -> Collections.addAll(points, DownloadPoint.PHP_DISTRIBUTION,
                DownloadPoint.PHP_METADATA, DownloadPoint.COMPOSER_INSTALLER, DownloadPoint.COMPOSER_REPOSITORY);
            case RUBY_SERVICE -> Collections.addAll(points, DownloadPoint.RUBY_DISTRIBUTION,
                DownloadPoint.RUBY_METADATA, DownloadPoint.RUBYGEMS, DownloadPoint.GNU_MAKE_SOURCE);
            case DOCKERFILE_CONTAINER -> points.add(DownloadPoint.OCI_REGISTRY);
            case CMAKE_SERVICE -> { /* The existing adapter obtains its tools through the system package manager. */ }
            case RECOGNITION_PREVIEW, MANAGED_PROCESS -> points.addAll(ALL);
        }
    }

    private static boolean regular(Path root, String marker) {
        return Files.isRegularFile(root.resolve(marker), LinkOption.NOFOLLOW_LINKS);
    }
    private static void java(Set<DownloadPoint> points) {
        Collections.addAll(points, DownloadPoint.JAVA_JDK, DownloadPoint.JAVA_METADATA);
    }
    private static void maven(Set<DownloadPoint> points) {
        Collections.addAll(points, DownloadPoint.MAVEN_DISTRIBUTION, DownloadPoint.MAVEN_REPOSITORY);
    }
    private static void gradle(Set<DownloadPoint> points) {
        Collections.addAll(points, DownloadPoint.GRADLE_DISTRIBUTION, DownloadPoint.GRADLE_PLUGIN, DownloadPoint.MAVEN_REPOSITORY);
    }
    private static void node(Set<DownloadPoint> points) {
        Collections.addAll(points, DownloadPoint.NODE_DISTRIBUTION, DownloadPoint.NODE_METADATA, DownloadPoint.NODE_PACKAGE);
    }
}
