package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.CargoBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.CmakeBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.DotNetSdkBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.GoBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java.GradleBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java.JavaJarBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java.JdkBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java.MavenBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.kotlin.KotlinCompilerBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.kotlin.KotlinGradleBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.node.NpmBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.node.PnpmBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.node.YarnBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.php.ComposerBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.php.PhpCliBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python.PipBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python.PipenvBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python.PoetryBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python.UvBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.ruby.BundlerBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.ruby.RubyCliBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.extension.registry.DeploymentBuildRendererRegistry;
import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.workload.ContainerBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.workload.StaticSiteBuildRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentBuildRendererTest {
    private static final String SHA = "a".repeat(64);
    private static final HealthCheck.Tcp TCP = new HealthCheck.Tcp(8080, 10, 1);
    private static final HealthCheck.Http HTTP = new HealthCheck.Http(URI.create("http://127.0.0.1:8080/"), 200, 10);
    @TempDir Path temporaryDirectory;

    @Test
    void rendersEveryNamedArchitectureThroughItsFixedEntrypoint() {
        var spring = new DeploymentRuntimeSpecification.SpringBoot(TCP);
        assertTrue(render(new GradleBuildRenderer(), DeploymentBuildToolType.GRADLE_WRAPPER, spring)
                .contains("./gradlew --no-daemon -x test bootJar"));
        assertTrue(render(new MavenBuildRenderer(), DeploymentBuildToolType.MAVEN_WRAPPER, spring)
                .contains("./mvnw -B \"${maven_toolchain_arguments[@]}\" -DskipTests package"));
        assertTrue(render(new MavenBuildRenderer(), DeploymentBuildToolType.MAVEN, spring)
                .contains("maven_package mvn -B \"${maven_toolchain_arguments[@]}\" -DskipTests package"));
        assertTrue(render(new JavaJarBuildRenderer(), DeploymentBuildToolType.JAVA,
                new DeploymentRuntimeSpecification.JavaJar("server.jar", "demo.Main", "21", List.of(), List.of(), TCP))
                .contains("test -f \"$artifact\""));
        String jdk = render(new JdkBuildRenderer(), DeploymentBuildToolType.JDK,
                new DeploymentRuntimeSpecification.JavaSource("src", "demo.Main", "21", List.of(), List.of(), TCP));
        assertTrue(jdk.contains("javac \"${javac_arguments[@]}\" -proc:none"));
        assertTrue(jdk.contains("jar cfm ./.w2l/java/app.jar ./.w2l/java/MANIFEST.MF"));

        var node = new DeploymentRuntimeSpecification.NodeService(22, TCP);
        assertTrue(render(new NpmBuildRenderer(), DeploymentBuildToolType.NPM, node).contains("npm ci --ignore-scripts"));
        assertTrue(render(new PnpmBuildRenderer(), DeploymentBuildToolType.PNPM, node)
                .contains("pnpm install --frozen-lockfile --ignore-scripts"));
        assertTrue(render(new YarnBuildRenderer(), DeploymentBuildToolType.YARN, node)
                .contains("export YARN_ENABLE_SCRIPTS=false\nrun yarn install --immutable"));

        var python = new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", TCP);
        assertTrue(render(new PipBuildRenderer(), DeploymentBuildToolType.PIP_LOCKED, python)
                .contains("pip install --disable-pip-version-check --require-hashes"));
        assertTrue(render(new PipenvBuildRenderer(), DeploymentBuildToolType.PIPENV_LOCKED, python)
                .contains("run pipenv verify\nexport PIPENV_IGNORE_VIRTUALENVS=0\nrun pipenv sync"));
        String poetry = render(new PoetryBuildRenderer(), DeploymentBuildToolType.POETRY_LOCKED, python);
        assertTrue(poetry.contains("run poetry check --lock"));
        assertTrue(poetry.contains("poetry sync --only main --no-root --no-interaction"));
        assertFalse(poetry.contains("poetry install"));
        assertTrue(render(new UvBuildRenderer(), DeploymentBuildToolType.UV_LOCKED, python)
                .contains("uv sync --active --locked --no-dev"));

        assertTrue(render(new StaticSiteBuildRenderer(), DeploymentBuildToolType.STATIC_SITE_BUILD,
                new DeploymentRuntimeSpecification.StaticSite("public", HTTP)).contains("test -d './public'"));
        assertTrue(render(new ContainerBuildRenderer(), DeploymentBuildToolType.CONTAINER_BUILD,
                new DeploymentRuntimeSpecification.Container(DeploymentRuntimeSpecification.ContainerEngineType.PODMAN,
                        Map.of(8080, 8080), List.of(), TCP)).contains("build --pull=true"));
        assertTrue(service(new GoBuildRenderer(), DeploymentBuildToolType.GO_MODULE, "1.24", "w2l-app", "main.go",
                OptionalInt.empty()).contains("go build -mod=readonly"));
        assertTrue(service(new CargoBuildRenderer(), DeploymentBuildToolType.CARGO_LOCKED, "1.89.0", "demo",
                "src/main.rs", OptionalInt.empty()).contains("cargo build --locked --release"));
        String dotnet = service(new DotNetSdkBuildRenderer(), DeploymentBuildToolType.DOTNET_LOCKED, "8.0.408", "Demo",
                "Demo.dll", OptionalInt.empty());
        assertTrue(dotnet.contains("\"${dotnet_command[@]}\" restore --locked-mode"));
        assertTrue(dotnet.contains("publish --no-restore --configuration Release \"-p:PublishDir=$PWD/.w2l/dotnet/\""));

        String kotlinGradle = service(new KotlinGradleBuildRenderer(), DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER,
                "2.0.21", "demo", "demo.MainKt", OptionalInt.empty());
        assertTrue(kotlinGradle.contains("--no-daemon installDist"));
        assertTrue(kotlinGradle.contains("sha256sum --check --status"));
        assertTrue(kotlinGradle.contains("gradle_cache_root=\"$mutable/home/gradle-distributions\""));
        assertFalse(kotlinGradle.contains("/var/lib/windowstolinux/cache"));
        assertTrue(kotlinGradle.contains("wrapper_properties=\"$wrapper_root/gradle/wrapper/gradle-wrapper.properties\""));
        assertTrue(kotlinGradle.contains("--project-dir \"$source\""));
        assertTrue(kotlinGradle.contains("distribution_urls[0]//\\\\:/:"));
        assertFalse(kotlinGradle.contains("wrapper_properties=./gradle/"));
        assertTrue(service(new KotlinCompilerBuildRenderer(), DeploymentBuildToolType.KOTLINC,
                "2.0.21", "demo", "demo.MainKt", OptionalInt.empty())
                .contains("run \"$kotlin_compiler\" -jvm-target '21' -include-runtime"));
        assertTrue(service(new ComposerBuildRenderer(), DeploymentBuildToolType.COMPOSER_LOCKED, "8.3", "public",
                "public/index.php", OptionalInt.of(8080)).contains("--no-plugins --no-scripts"));
        assertTrue(service(new PhpCliBuildRenderer(), DeploymentBuildToolType.PHP_CLI, "8.3", "public",
                "public/index.php", OptionalInt.of(8080)).contains("php -n -l"));
        assertTrue(service(new BundlerBuildRenderer(), DeploymentBuildToolType.BUNDLER_LOCKED, "3.3.5", "bundle",
                "config.ru", OptionalInt.of(8080)).contains("bundle install --jobs 1 --retry 0"));
        assertTrue(service(new RubyCliBuildRenderer(), DeploymentBuildToolType.RUBY_CLI, "3.3.5", "source",
                "server.rb", OptionalInt.of(8080)).contains("ruby -c"));
        String cmake = render(new CmakeBuildRenderer(), DeploymentBuildToolType.CMAKE,
                new DeploymentRuntimeSpecification.CmakeService("w2l-release", "demo", "demo", TCP));
        assertTrue(cmake.contains("cmake --preset \"$preset\""));
        assertTrue(cmake.contains("ldd \"./.w2l/bin/$target\""));
    }

    @Test
    void kotlinWrapperAcceptsWindowsLineEndingsWithoutModifyingSource() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(Path.of(bash)), "Bash required");
        String build = service(new KotlinGradleBuildRenderer(), DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER,
                "2.0.21", "demo", "demo.MainKt", OptionalInt.empty());
        String body = build.substring(build.indexOf("wrapper_root="), build.indexOf("run \"$wrapper_root/gradlew\" --project-dir \"$source\" --no-daemon installDist"));
        // Git Bash lacks flock/POSIX install permissions; this test exercises wrapper text and checksum handling. / 此测试验证 wrapper 文本和校验和，不验证 Git Bash 缺失的 POSIX 权限与锁。
        String platformSetup = System.getProperty("os.name", "").startsWith("Windows")
                ? "install() { mkdir -p -- \"${@: -1}\"; }\nflock() { :; }\n" : "";
        for (String newline : List.of("\n", "\r\n")) {
            Path root = java.nio.file.Files.createDirectory(temporaryDirectory.resolve(newline.length() == 1 ? "lf" : "crlf"));
            Path wrapper = java.nio.file.Files.createDirectories(root.resolve("gradle/wrapper"));
            java.nio.file.Files.writeString(wrapper.resolve("gradle-wrapper.jar"), "fixture");
            String script = "#!/bin/sh" + newline + "printf WRAPPER_OK" + newline;
            java.nio.file.Files.writeString(root.resolve("gradlew"), script);
            byte[] archive = "fixture distribution".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(archive));
            Path cache = java.nio.file.Files.createDirectories(root.resolve("mutable/home/gradle-distributions"));
            java.nio.file.Files.write(cache.resolve(digest + ".zip"), archive);
            String properties = "distributionUrl=https\\://downloads.gradle.org/distributions/gradle-8.10.2-bin.zip" + newline
                    + "distributionSha256Sum=" + digest + newline;
            java.nio.file.Files.writeString(wrapper.resolve("gradle-wrapper.properties"), properties);
            Path log = root.resolve("output.txt");
            Process process = new ProcessBuilder(bash, "-s").directory(root.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(("set -euo pipefail\nexport PATH=/usr/bin:/bin:$PATH\nsource=$PWD\nmutable=$PWD/mutable\n"
                            + platformSetup + "retry_run() { shift; \"$@\"; }\n" + body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
                org.junit.jupiter.api.Assertions.assertEquals(0, process.exitValue(), java.nio.file.Files.readString(log));
                assertTrue(java.nio.file.Files.readString(log).contains("WRAPPER_OK"));
                org.junit.jupiter.api.Assertions.assertEquals(script, java.nio.file.Files.readString(root.resolve("gradlew")));
                org.junit.jupiter.api.Assertions.assertEquals(properties, java.nio.file.Files.readString(wrapper.resolve("gradle-wrapper.properties")));
            } finally {
                if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); }
            }
        }
    }

    @Test
    void rendersBuiltStaticSitesOnlyWithAnExplicitNodeMajor() {
        for (DeploymentBuildToolType tool : List.of(DeploymentBuildToolType.NPM, DeploymentBuildToolType.PNPM,
                DeploymentBuildToolType.YARN)) {
            String script = render(new StaticSiteBuildRenderer(), tool,
                    new DeploymentRuntimeSpecification.StaticSite("dist", OptionalInt.of(20), HTTP));
            assertTrue(script.contains("node --version | grep -Eq '^v20\\.'"));
        }
        assertThrows(IllegalArgumentException.class, () -> render(new StaticSiteBuildRenderer(), DeploymentBuildToolType.NPM,
                new DeploymentRuntimeSpecification.StaticSite("dist", HTTP)));
    }

    @Test
    void rejectsArchitectureMismatchesAndCommandInjection() {
        assertThrows(IllegalArgumentException.class, () -> render(new NpmBuildRenderer(), DeploymentBuildToolType.NPM,
                new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", TCP)));
        assertThrows(IllegalArgumentException.class, () -> render(new NpmBuildRenderer(), DeploymentBuildToolType.PNPM,
                new DeploymentRuntimeSpecification.NodeService(20, TCP)));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentRuntimeSpecification.JavaSource(
                "src;touch-pwned", "demo.Main", "21", List.of(), List.of(), TCP));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentRuntimeSpecification.CmakeService(
                "w2l-release", "demo;touch-pwned", "demo", TCP));
        String quoted = SafeBuildScriptEnvelope.shellQuote("value'; touch /tmp/pwned; printf '");
        assertFalse(quoted.contains("value'; touch"));
        assertTrue(quoted.startsWith("'value'\"'\"'"));
    }

    @Test
    void rendersCatalogBoundariesWithExplicitSelectionsAndUnchangedProjectTargets() {
        var catalog = gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.defaults();
        for (var ecosystem : gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.values()) {
            var branches = catalog.branches(ecosystem);
            for (var branch : List.of(branches.getFirst(), branches.get(branches.size() / 2), branches.getLast())) {
                String version = branch.version();
                DeploymentRuntimeSpecification runtime = switch (ecosystem) {
                    case JAVA -> new DeploymentRuntimeSpecification.JavaSource("src", "demo.Main", version, List.of(), List.of(), TCP);
                    case NODE -> new DeploymentRuntimeSpecification.NodeService(Integer.parseInt(version), TCP);
                    case PYTHON -> new DeploymentRuntimeSpecification.PythonService(version, "demo.main", TCP);
                    case GO -> new DeploymentRuntimeSpecification.GoService(version, "demo", "main.go", TCP);
                    case RUST -> new DeploymentRuntimeSpecification.RustService(version, "demo", "src/main.rs", TCP);
                    case DOTNET -> new DeploymentRuntimeSpecification.DotNetService(version + ".0.100", "Demo", "Demo.dll", TCP);
                    case KOTLIN -> new DeploymentRuntimeSpecification.KotlinService(version, "demo", "demo.MainKt", "8", TCP);
                    case PHP -> new DeploymentRuntimeSpecification.PhpService(version, "public", "public/index.php", 8080, TCP);
                    case RUBY -> new DeploymentRuntimeSpecification.RubyService(version, "source", "server.rb", 8080, TCP);
                    case C, CPP -> new DeploymentRuntimeSpecification.CmakeService("w2l-release", "demo", "demo", TCP);
                };
                DeploymentBuildRenderer renderer = switch (ecosystem) {
                    case JAVA -> new JdkBuildRenderer(); case NODE -> new NpmBuildRenderer();
                    case PYTHON -> new PipBuildRenderer(); case GO -> new GoBuildRenderer();
                    case RUST -> new CargoBuildRenderer(); case DOTNET -> new DotNetSdkBuildRenderer();
                    case KOTLIN -> new KotlinCompilerBuildRenderer(); case PHP -> new PhpCliBuildRenderer();
                    case RUBY -> new RubyCliBuildRenderer(); case C, CPP -> new CmakeBuildRenderer();
                };
                var tool = renderer.buildTools().iterator().next();
                String build = render(renderer, tool, runtime);
                assertTrue(build.contains("BUILD_TOOL=" + tool.name()), ecosystem + version);
                if (ecosystem == gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.C
                        || ecosystem == gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.CPP) {
                    assertTrue(build.contains("cmake --preset"));
                    assertFalse(build.contains("-DCMAKE_C_STANDARD="));
                    continue;
                }
                var requirement = gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement.declared(ecosystem,
                        version, "boundary-fixture", gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement.PurposeType.BUILD);
                var exact = gold.debug.windowstolinux.shared.model.toolchain.ToolchainVersion.parse(ecosystem,
                        version + (ecosystem.branchSegments() == 1 ? ".0.1" : ".1")).orElseThrow();
                var set = new gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet(catalog.revision(), List.of(
                        new gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet.Selection(requirement, exact,
                                "/usr/local/lib/windowstolinux/toolchains/versions/fixture-" + SHA,
                                gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet.OriginType.MANAGED,
                                "https://example.test/fixture", SHA)));
                String environment = gold.debug.windowstolinux.shared.linux.sshd.toolchain.ToolchainBuildEnvironment.render(set);
                assertTrue(environment.contains("WTL_" + ecosystem.name() + "_VERSION='" + exact.text() + "'"));
                assertTrue(environment.contains("export PATH='/usr/local/lib/windowstolinux/toolchains/versions/fixture-"));
                if (ecosystem == gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.JAVA)
                    assertTrue(build.contains("target_java='" + version + "'"));
                assertFalse(build.contains("sed -i"), "version preparation must not edit project declarations");
            }
        }
    }

    @Test
    void jdkBuildRejectsSystemFallbackWhenBoundCompilerIsInaccessible() throws Exception {
        String bash = System.getProperty("managed.test.bash", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(bash.isBlank(), "requires an explicitly selected bash");
        var ecosystem = gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.JAVA;
        var requirement = gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement.declared(ecosystem,
                "21", "fixture", gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement.PurposeType.BUILD);
        var version = gold.debug.windowstolinux.shared.model.toolchain.ToolchainVersion.parse(ecosystem, "21.0.12.1+1").orElseThrow();
        var selection = new gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet.Selection(requirement,
                version, "/wtl-selected-jdk", gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet.OriginType.MANAGED,
                "https://example.test/fixture", SHA);
        String guard = gold.debug.windowstolinux.shared.linux.sshd.toolchain.ToolchainBuildEnvironment.render(
                new gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet("fixture", List.of(selection)));
        for (boolean available : new boolean[]{false, true}) {
            String setup = "export WTL_JAVA_HOME=/wtl-selected-jdk\n"
                    + "command() { printf '%s\\n' /" + (available ? "wtl-selected-jdk" : "system-jdk") + "/bin/$2; }\n"
                    + "[() { if test \"$1\" = '!' && test \"$2\" = -x; then return 1; fi; builtin [ \"$@\"; }\n";
            Process process = new ProcessBuilder(bash, "--noprofile", "--norc").redirectErrorStream(true).start();
            try (var input = process.getOutputStream()) {
                input.write((setup + guard + "printf 'BOUND_JDK_ACCEPTED\\n'\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertEquals(available ? 0 : 65, process.waitFor(), output);
            org.junit.jupiter.api.Assertions.assertEquals(available, output.contains("BOUND_JDK_ACCEPTED"), output);
            org.junit.jupiter.api.Assertions.assertEquals(!available, output.contains("TOOLCHAIN_FAILURE=integrity"), output);
        }
    }

    @Test
    void registryRequiresExactlyOneRendererForEveryArchitecture() {
        List<DeploymentBuildRenderer> complete = renderers();
        new DeploymentBuildRendererRegistry(complete);
        assertThrows(IllegalArgumentException.class, () -> new DeploymentBuildRendererRegistry(
                java.util.stream.Stream.concat(complete.stream(), java.util.stream.Stream.of(new NpmBuildRenderer())).toList()));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentBuildRendererRegistry(
                complete.subList(0, complete.size() - 1)));
    }

    @Test
    void containerExtractionScopesItsMaskAndUsesTheExistingHardVolumeLimit() throws Exception {
        String bash = System.getProperty("managed.test.bash", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(bash.isBlank(), "requires an explicitly selected bash");
        for (boolean container : new boolean[]{false, true}) {
            var workspace = new RemoteWorkspace("demo", SHA);
            String script = SafeBuildScriptEnvelope.wrap(facts(container ? DeploymentProjectType.DOCKERFILE_CONTAINER
                            : DeploymentProjectType.NODE_SERVICE, container ? DeploymentBuildToolType.CONTAINER_BUILD
                            : DeploymentBuildToolType.NPM), workspace, BuildLimitConfiguration.defaultNonRoot(),
                    "printf 'PROJECT_MASK=%s\\n' \"$(umask)\"");
            script = script.replace(workspace.candidateRoot(), temporaryDirectory.resolve(container ? "container" : "ordinary")
                    .toString().replace('\\', '/'));
            String setup = """
                    umask 077
                    test() { if [ "$1" = -f ]; then return 0; fi; builtin test "$@"; }
                    sha256sum() { printf '%s\\n' '%s'; }
                    tar() {
                      case "$1" in -tzf) echo entry ;; -tvzf) echo '-file' ;;
                        --extract) printf 'EXTRACT_MASK=%%s\\n' "$(umask)" ;; esac
                    }
                    du() { echo 'UNREADABLE_ENGINE_FILE' >&2; return 7; }
                    """.formatted("%s", SHA);
            Process process = new ProcessBuilder(bash, "--noprofile", "--norc").redirectErrorStream(true).start();
            try (var input = process.getOutputStream()) {
                input.write((setup + script).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertEquals(container ? 0 : 7, process.waitFor(), output);
            assertTrue(output.contains("EXTRACT_MASK=" + (container ? "0022" : "0077")), output);
            assertTrue(output.contains("PROJECT_MASK=0077"), output);
            org.junit.jupiter.api.Assertions.assertEquals(!container, output.contains("UNREADABLE_ENGINE_FILE"), output);
        }
    }

    private String render(DeploymentBuildRenderer renderer, DeploymentBuildToolType tool,
                          DeploymentRuntimeSpecification runtime) {
        return renderer.render(facts(renderer.projectType(), tool), runtime, new RemoteWorkspace("demo", SHA),
                BuildLimitConfiguration.defaultNonRoot());
    }

    private DeploymentProjectFacts facts(DeploymentProjectType type, DeploymentBuildToolType tool) {
        if (type == DeploymentProjectType.CMAKE_SERVICE) {
            return new DeploymentProjectFacts(temporaryDirectory, "demo", type, tool,
                    new ProjectLanguageFacts(Set.of(), Set.of(SourceLanguageType.C, SourceLanguageType.CPP),
                            Map.of(), List.of()),
                    List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture",
                            LocalizedMessage.of("test.detected"), EvidenceConfidenceLevel.HIGH)), List.of(), List.of());
        }
        return new DeploymentProjectFacts(temporaryDirectory, "demo", type, tool,
                List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture",
                        LocalizedMessage.of("test.detected"), EvidenceConfidenceLevel.HIGH)), List.of(), List.of());
    }

    private String service(DeploymentBuildRenderer renderer, DeploymentBuildToolType tool, String version,
                           String artifact, String entrypoint, OptionalInt port) {
        DeploymentRuntimeSpecification runtime = switch (renderer.projectType()) {
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
        return render(renderer, tool, runtime);
    }

    private static List<DeploymentBuildRenderer> renderers() {
        return List.of(new GradleBuildRenderer(), new MavenBuildRenderer(), new JavaJarBuildRenderer(),
                new JdkBuildRenderer(), new NpmBuildRenderer(), new PnpmBuildRenderer(), new YarnBuildRenderer(),
                new PipBuildRenderer(), new PipenvBuildRenderer(), new PoetryBuildRenderer(), new UvBuildRenderer(),
                new StaticSiteBuildRenderer(), new ContainerBuildRenderer(), new GoBuildRenderer(),
                new CargoBuildRenderer(), new DotNetSdkBuildRenderer(), new KotlinGradleBuildRenderer(),
                new KotlinCompilerBuildRenderer(), new ComposerBuildRenderer(), new PhpCliBuildRenderer(),
                new BundlerBuildRenderer(), new RubyCliBuildRenderer(), new CmakeBuildRenderer());
    }
}
