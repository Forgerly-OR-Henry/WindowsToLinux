package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.sshd.build.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.renderer.JavaJarBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.renderer.SpringBootBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.renderer.NodeBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.renderer.PythonBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.renderer.ServiceBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.renderer.ContainerBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.renderer.StaticSiteBuildRenderer;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentBuildRendererTest {
    private static final String SHA = "a".repeat(64);
    private static final HealthCheck.Tcp TCP = new HealthCheck.Tcp(8080, 10, 1);
    private static final HealthCheck.Http HTTP = new HealthCheck.Http(URI.create("http://127.0.0.1:8080/"), 200, 10);
    @TempDir Path temporaryDirectory;

    @Test
    void rendersAllTwelveTypesThroughTheirFixedEntrypoints() {
        assertTrue(render(new SpringBootBuildRenderer(), DeploymentBuildTool.GRADLE_WRAPPER,
                new DeploymentRuntimeSpecification.SpringBoot(TCP)).contains("./gradlew --no-daemon -x test bootJar"));
        assertTrue(render(new JavaJarBuildRenderer(), DeploymentBuildTool.JAVA,
                new DeploymentRuntimeSpecification.JavaJar("server.jar", "demo.Main", "21", List.of(), List.of(), TCP))
                .contains("test -f \"$artifact\""));
        String node = render(new NodeBuildRenderer(), DeploymentBuildTool.PNPM,
                new DeploymentRuntimeSpecification.NodeService(22, TCP));
        assertTrue(node.contains("uniq -d"));
        assertTrue(node.contains("pnpm install --frozen-lockfile --ignore-scripts"));
        assertTrue(node.contains("node --version | grep -Eq '^v22\\.'"));
        assertFalse(node.contains("run npm run build"));
        String python = render(new PythonBuildRenderer(), DeploymentBuildTool.PYTHON_VENV,
                new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", TCP));
        assertTrue(python.contains("'python3.12' -m venv --copies ./.venv"));
        assertTrue(python.contains("pip install --disable-pip-version-check --require-hashes"));
        assertTrue(render(new StaticSiteBuildRenderer(), DeploymentBuildTool.STATIC_SITE_BUILD,
                new DeploymentRuntimeSpecification.StaticSite("public", HTTP)).contains("test -d './public'"));
        assertTrue(render(new ContainerBuildRenderer(), DeploymentBuildTool.CONTAINER_BUILD,
                new DeploymentRuntimeSpecification.Container(DeploymentRuntimeSpecification.ContainerEngine.PODMAN,
                        Map.of(8080, 8080), List.of(), TCP)).contains("build --pull=true"));
        assertTrue(service(DeploymentProjectType.GO_SERVICE, DeploymentBuildTool.GO_MODULE, "1.24", "w2l-app",
                "main.go", OptionalInt.empty()).contains("go build -mod=readonly"));
        assertTrue(service(DeploymentProjectType.RUST_SERVICE, DeploymentBuildTool.CARGO_LOCKED, "1.89.0", "demo",
                "src/main.rs", OptionalInt.empty()).contains("cargo build --locked --release"));
        String dotnet = service(DeploymentProjectType.DOTNET_SERVICE, DeploymentBuildTool.DOTNET_LOCKED, "8.0.408", "Demo",
                "Demo.dll", OptionalInt.empty());
        assertTrue(dotnet.contains("dotnet restore --locked-mode"));
        assertTrue(dotnet.contains("DOTNET_GCHeapHardLimit=0x40000000"));
        String kotlin = service(DeploymentProjectType.KOTLIN_SERVICE, DeploymentBuildTool.GRADLE_KOTLIN_WRAPPER, "21", "demo",
                "demo.MainKt", OptionalInt.empty());
        assertTrue(kotlin.contains("retry_run 3 ./gradlew --no-daemon --version"));
        assertTrue(kotlin.contains("https://services.gradle.org/distributions/*|https://downloads.gradle.org/distributions/*"));
        assertTrue(kotlin.contains("--retry-all-errors"));
        assertTrue(kotlin.contains("--continue-at -"));
        assertTrue(kotlin.contains("/var/lib/windowstolinux/cache/gradle-distributions"));
        assertTrue(kotlin.contains("flock 9"));
        assertTrue(kotlin.contains("gradle_distribution.partial"));
        assertTrue(kotlin.contains("sha256sum --check --status"));
        assertTrue(kotlin.contains("distributionUrl=file\\:%s"));
        assertTrue(kotlin.contains("--no-daemon installDist"));
        assertTrue(kotlin.contains("-Xmx768m"));
        assertFalse(kotlin.contains("--offline"));
        assertTrue(service(DeploymentProjectType.PHP_SERVICE, DeploymentBuildTool.COMPOSER_LOCKED, "8.3", "public",
                "public/index.php", OptionalInt.of(8080)).contains("--no-plugins --no-scripts"));
        String ruby = service(DeploymentProjectType.RUBY_SERVICE, DeploymentBuildTool.BUNDLER_LOCKED, "3.3.5", "bundle",
                "config.ru", OptionalInt.of(8080));
        assertTrue(ruby.contains("bundle install --jobs 1 --retry 0"));
        assertTrue(ruby.contains("require \"rack\"; require \"webrick\""));
    }

    @Test
    void rendersAllThreeSpringBootBuildToolsAndOneSupportedExecutableJarCheck() {
        var runtime = new DeploymentRuntimeSpecification.SpringBoot(TCP);
        String gradle = render(new SpringBootBuildRenderer(), DeploymentBuildTool.GRADLE_WRAPPER, runtime);
        String wrapper = render(new SpringBootBuildRenderer(), DeploymentBuildTool.MAVEN_WRAPPER, runtime);
        String maven = render(new SpringBootBuildRenderer(), DeploymentBuildTool.MAVEN, runtime);

        assertTrue(gradle.contains("./gradlew --no-daemon -x test bootJar"));
        assertTrue(wrapper.contains("./mvnw -B -DskipTests package"));
        assertTrue(maven.contains("run mvn -B -DskipTests package"));
        for (String script : List.of(gradle, wrapper, maven)) {
            assertTrue(script.contains("test \"${#artifacts[@]}\" -eq 1"));
            assertTrue(script.contains("! -name '*-plain.jar'"));
            assertTrue(script.contains("loader\\.(launch\\.)?JarLauncher"));
            assertTrue(script.contains("tr -d '\\r'"));
            assertFalse(script.contains("PropertiesLauncher"));
            assertTrue(script.contains("source.tar.gz"));
            assertTrue(script.contains("tar --extract --gzip"));
        }
    }

    @Test
    void requiresAnExplicitNodeMajorOnlyForBuiltStaticSites() {
        String built = render(new StaticSiteBuildRenderer(), DeploymentBuildTool.NPM,
                new DeploymentRuntimeSpecification.StaticSite("dist", OptionalInt.of(20), HTTP));
        assertTrue(built.contains("node --version | grep -Eq '^v20\\.'"));
        assertThrows(IllegalArgumentException.class, () -> render(new StaticSiteBuildRenderer(), DeploymentBuildTool.NPM,
                new DeploymentRuntimeSpecification.StaticSite("dist", HTTP)));
        assertThrows(IllegalArgumentException.class, () -> render(new StaticSiteBuildRenderer(),
                DeploymentBuildTool.STATIC_SITE_BUILD,
                new DeploymentRuntimeSpecification.StaticSite("public", OptionalInt.of(20), HTTP)));
    }

    @Test
    void rejectsRuntimeAndBuildToolMismatchesBeforeRendering() {
        assertThrows(IllegalArgumentException.class, () -> render(new NodeBuildRenderer(), DeploymentBuildTool.NPM,
                new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", TCP)));
        assertThrows(IllegalArgumentException.class, () -> render(new NodeBuildRenderer(), DeploymentBuildTool.PYTHON_VENV,
                new DeploymentRuntimeSpecification.NodeService(20, TCP)));
        assertThrows(IllegalArgumentException.class, () -> render(new JavaJarBuildRenderer(), DeploymentBuildTool.NPM,
                new DeploymentRuntimeSpecification.JavaJar("server.jar", "demo.Main", "21", List.of(), List.of(), TCP)));
    }

    @Test
    void rejectsOrQuotesCommandInjectionInputs() {
        assertThrows(IllegalArgumentException.class, () -> new DeploymentRuntimeSpecification.JavaJar(
                "server.jar;touch-pwned", "demo.Main", "21", List.of(), List.of(), TCP));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentRuntimeSpecification.PythonService(
                "3.12;touch-pwned", "demo.main", TCP));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentRuntimeSpecification.StaticSite("dist;touch-pwned", HTTP));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentRuntimeSpecification.PhpService(
                "8.3", "public", "public/index.php;touch-pwned", 8080, TCP));
        String quoted = SafeBuildScriptEnvelope.shellQuote("value'; touch /tmp/pwned; printf '");
        assertFalse(quoted.contains("value'; touch"));
        assertTrue(quoted.startsWith("'value'\"'\"'"));
    }

    @Test
    void registryRejectsDuplicateOrMissingRenderers() {
        List<DeploymentBuildRenderer> complete = renderers();
        assertThrows(IllegalArgumentException.class, () -> new DeploymentBuildRendererRegistry(
                java.util.stream.Stream.concat(complete.stream(), java.util.stream.Stream.of(new NodeBuildRenderer())).toList()));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentBuildRendererRegistry(
                complete.subList(0, complete.size() - 1)));
    }

    private String render(DeploymentBuildRenderer renderer, DeploymentBuildTool tool,
                          DeploymentRuntimeSpecification runtime) {
        return renderer.render(facts(renderer.projectType(), tool), runtime, new RemoteWorkspace("demo", SHA),
                BuildLimits.defaultNonRoot());
    }

    private DeploymentProjectFacts facts(DeploymentProjectType type, DeploymentBuildTool tool) {
        return new DeploymentProjectFacts(temporaryDirectory, "demo", type, tool,
                List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture",
                        LocalizedMessage.of("test.detected"), EvidenceConfidence.HIGH)), List.of(), List.of());
    }

    private String service(DeploymentProjectType type, DeploymentBuildTool tool, String version,
                            String artifact, String entrypoint, OptionalInt port) {
        return render(new ServiceBuildRenderer(type), tool, runtime(type, version, artifact, entrypoint, port));
    }

    private DeploymentRuntimeSpecification runtime(DeploymentProjectType type, String version, String artifact,
                                                    String entrypoint, OptionalInt port) {
        return switch (type) {
            case GO_SERVICE -> new DeploymentRuntimeSpecification.GoService(version, artifact, entrypoint, TCP);
            case RUST_SERVICE -> new DeploymentRuntimeSpecification.RustService(version, artifact, entrypoint, TCP);
            case DOTNET_SERVICE -> new DeploymentRuntimeSpecification.DotNetService(version, artifact, entrypoint, TCP);
            case KOTLIN_SERVICE -> new DeploymentRuntimeSpecification.KotlinService(version, artifact, entrypoint, TCP);
            case PHP_SERVICE -> new DeploymentRuntimeSpecification.PhpService(version, artifact, entrypoint,
                    port.orElseThrow(), TCP);
            case RUBY_SERVICE -> new DeploymentRuntimeSpecification.RubyService(version, artifact, entrypoint,
                    port.orElseThrow(), TCP);
            default -> throw new IllegalArgumentException("unsupported service test type");
        };
    }

    private static List<DeploymentBuildRenderer> renderers() {
        return List.of(new SpringBootBuildRenderer(), new JavaJarBuildRenderer(), new NodeBuildRenderer(),
                new PythonBuildRenderer(), new StaticSiteBuildRenderer(), new ContainerBuildRenderer(),
                new ServiceBuildRenderer(DeploymentProjectType.GO_SERVICE),
                new ServiceBuildRenderer(DeploymentProjectType.RUST_SERVICE),
                new ServiceBuildRenderer(DeploymentProjectType.DOTNET_SERVICE),
                new ServiceBuildRenderer(DeploymentProjectType.KOTLIN_SERVICE),
                new ServiceBuildRenderer(DeploymentProjectType.PHP_SERVICE),
                new ServiceBuildRenderer(DeploymentProjectType.RUBY_SERVICE));
    }
}
