package gold.debug.windowstolinux.mod.deployment.service.deploy.build.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.message.DeploymentLocalizedMessage;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.CargoBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.CmakeBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.DotNetSdkBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.GoBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java.GradleBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java.JavaJarBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java.JdkBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java.MavenBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.kotlin.KotlinCompilerBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.kotlin.KotlinGradleBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.node.NpmBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.node.PnpmBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.node.YarnBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.php.ComposerBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.php.PhpCliBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.python.PipBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.python.PipenvBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.python.PoetryBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.python.UvBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.ruby.BundlerBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.ruby.RubyCliBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.registry.DeploymentBuildWriterRegistry;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.workload.ContainerBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.workload.StaticSiteBuildWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentBuildWriterTest {
    private static final String SHA = "a".repeat(64);

    private static final DeploymentHealthCheck.Tcp TCP = new DeploymentHealthCheck.Tcp(8080, 10, 1);

    private static final DeploymentHealthCheck.Http HTTP = new DeploymentHealthCheck.Http(
        URI.create("http://127.0.0.1:8080/"), 200, 10);

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersEveryNamedArchitectureThroughItsFixedEntrypoint() {
        var spring = new DeploymentRuntimeDefinition.SpringBoot(TCP);
        assertTrue(render(new GradleBuildWriter(), DeploymentBuildToolType.GRADLE_WRAPPER, spring)
            .contains("./gradlew --no-daemon -x test bootJar"));
        assertTrue(render(new MavenBuildWriter(), DeploymentBuildToolType.MAVEN_WRAPPER, spring)
            .contains("./mvnw -B \"${maven_toolchain_arguments[@]}\" -DskipTests package"));
        assertTrue(render(new MavenBuildWriter(), DeploymentBuildToolType.MAVEN, spring)
            .contains("maven_package mvn -B \"${maven_toolchain_arguments[@]}\" -DskipTests package"));
        assertTrue(render(new JavaJarBuildWriter(), DeploymentBuildToolType.JAVA,
            new DeploymentRuntimeDefinition.JavaJar("server.jar", "demo.Main", "21", List.of(), List.of(), TCP))
            .contains("test -f \"$artifact\""));
        String jdk = render(new JdkBuildWriter(), DeploymentBuildToolType.JDK,
            new DeploymentRuntimeDefinition.JavaSource("src", "demo.Main", "21", List.of(), List.of(), TCP));
        assertTrue(jdk.contains("javac \"${javac_arguments[@]}\" -proc:none"));
        assertTrue(jdk.contains("jar cfm ./.w2l/java/app.jar ./.w2l/java/MANIFEST.MF"));

        var node = new DeploymentRuntimeDefinition.NodeService(22, TCP);
        assertTrue(
            render(new NpmBuildWriter(), DeploymentBuildToolType.NPM, node).contains("npm ci --ignore-scripts"));
        assertTrue(render(new PnpmBuildWriter(), DeploymentBuildToolType.PNPM, node)
            .contains("pnpm install --frozen-lockfile --ignore-scripts"));
        assertTrue(render(new YarnBuildWriter(), DeploymentBuildToolType.YARN, node)
            .contains("export YARN_ENABLE_SCRIPTS=false\nrun yarn install --immutable"));

        var python = new DeploymentRuntimeDefinition.PythonService("3.12", "demo.main", TCP);
        assertTrue(render(new PipBuildWriter(), DeploymentBuildToolType.PIP_LOCKED, python)
            .contains("pip install --disable-pip-version-check --require-hashes"));
        assertTrue(render(new PipenvBuildWriter(), DeploymentBuildToolType.PIPENV_LOCKED, python)
            .contains("run pipenv verify\nexport PIPENV_IGNORE_VIRTUALENVS=0\nrun pipenv sync"));
        String poetry = render(new PoetryBuildWriter(), DeploymentBuildToolType.POETRY_LOCKED, python);
        assertTrue(poetry.contains("run poetry check --lock"));
        assertTrue(poetry.contains("poetry sync --only main --no-root --no-interaction"));
        assertFalse(poetry.contains("poetry install"));
        assertTrue(render(new UvBuildWriter(), DeploymentBuildToolType.UV_LOCKED, python)
            .contains("uv sync --active --locked --no-dev"));

        assertTrue(render(new StaticSiteBuildWriter(), DeploymentBuildToolType.STATIC_SITE_BUILD,
            new DeploymentRuntimeDefinition.StaticSite("public", HTTP)).contains("test -d './public'"));
        assertTrue(render(new ContainerBuildWriter(), DeploymentBuildToolType.CONTAINER_BUILD,
            new DeploymentRuntimeDefinition.Container(DeploymentRuntimeDefinition.ContainerEngineType.PODMAN,
                Map.of(8080, 8080), List.of(), TCP))
            .contains("build --pull=true"));
        assertTrue(service(new GoBuildWriter(), DeploymentBuildToolType.GO_MODULE, "1.24", "w2l-app", "main.go",
            OptionalInt.empty()).contains("go build -mod=readonly"));
        assertTrue(service(new CargoBuildWriter(), DeploymentBuildToolType.CARGO_LOCKED, "1.89.0", "demo",
            "src/main.rs", OptionalInt.empty()).contains("cargo build --locked --release"));
        String dotnet = service(new DotNetSdkBuildWriter(), DeploymentBuildToolType.DOTNET_LOCKED, "8.0.408", "Demo",
            "Demo.dll", OptionalInt.empty());
        assertTrue(dotnet.contains("\"${dotnet_command[@]}\" restore --locked-mode"));
        assertTrue(dotnet.contains("publish --no-restore --configuration Release \"-p:PublishDir=$PWD/.w2l/dotnet/\""));

        String kotlinGradle = service(new KotlinGradleBuildWriter(), DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER,
            "2.0.21", "demo", "demo.MainKt", OptionalInt.empty());
        assertTrue(kotlinGradle.contains("--no-daemon installDist"));
        assertTrue(kotlinGradle.contains("sha256sum --check --status"));
        assertTrue(kotlinGradle.contains("gradle_cache_root=\"$mutable/home/gradle-distributions\""));
        assertFalse(kotlinGradle.contains("/var/lib/windowstolinux/cache"));
        assertTrue(
            kotlinGradle.contains("wrapper_properties=\"$wrapper_root/gradle/wrapper/gradle-wrapper.properties\""));
        assertTrue(kotlinGradle.contains("--project-dir \"$source\""));
        assertTrue(kotlinGradle.contains("distribution_urls[0]//\\\\:/:"));
        assertFalse(kotlinGradle.contains("wrapper_properties=./gradle/"));
        assertTrue(service(new KotlinCompilerBuildWriter(), DeploymentBuildToolType.KOTLINC, "2.0.21", "demo",
            "demo.MainKt", OptionalInt.empty())
            .contains("run \"$kotlin_compiler\" -jvm-target '21' -include-runtime"));
        assertTrue(service(new ComposerBuildWriter(), DeploymentBuildToolType.COMPOSER_LOCKED, "8.3", "public",
            "public/index.php", OptionalInt.of(8080)).contains("--no-plugins --no-scripts"));
        assertTrue(service(new PhpCliBuildWriter(), DeploymentBuildToolType.PHP_CLI, "8.3", "public",
            "public/index.php", OptionalInt.of(8080)).contains("php -n -l"));
        assertTrue(service(new BundlerBuildWriter(), DeploymentBuildToolType.BUNDLER_LOCKED, "3.3.5", "bundle",
            "config.ru", OptionalInt.of(8080)).contains("bundle install --jobs 1 --retry 0"));
        assertTrue(service(new RubyCliBuildWriter(), DeploymentBuildToolType.RUBY_CLI, "3.3.5", "source", "server.rb",
            OptionalInt.of(8080)).contains("ruby -c"));
        String cmake = render(new CmakeBuildWriter(), DeploymentBuildToolType.CMAKE,
            new DeploymentRuntimeDefinition.CmakeService("w2l-release", "demo", "demo", TCP));
        assertTrue(cmake.contains("cmake -S . -B .w2l/cmake-build"));
        assertTrue(cmake.contains("ldd \"./.w2l/bin/$target\""));
    }

    @Test
    void kotlinWrapperAcceptsWindowsLineEndingsWithoutModifyingSource() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(Path.of(bash)), "Bash required");
        String build = service(new KotlinGradleBuildWriter(), DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER, "2.0.21",
            "demo", "demo.MainKt", OptionalInt.empty());
        String body = build.substring(build.indexOf("wrapper_root="),
            build.indexOf("run \"$wrapper_root/gradlew\" --project-dir \"$source\" --no-daemon installDist"));
        // Git Bash lacks flock/POSIX install permissions; this test exercises wrapper text and checksum handling. / 此测试验证 wrapper 文本和校验和，不验证 Git Bash 缺失的 POSIX 权限与锁。
        String platformSetup = System.getProperty("os.name", "").startsWith("Windows")
            ? "install() { mkdir -p -- \"${@: -1}\"; }\nflock() { :; }\n"
            : "";
        for (String newline : List.of("\n", "\r\n")) {
            Path root = java.nio.file.Files
                .createDirectory(temporaryDirectory.resolve(newline.length() == 1 ? "lf" : "crlf"));
            Path wrapper = java.nio.file.Files.createDirectories(root.resolve("gradle/wrapper"));
            java.nio.file.Files.writeString(wrapper.resolve("gradle-wrapper.jar"), "fixture");
            String script = "#!/bin/sh" + newline + "printf WRAPPER_OK" + newline;
            java.nio.file.Files.writeString(root.resolve("gradlew"), script);
            byte[] archive = "fixture distribution".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String digest = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(archive));
            Path cache = java.nio.file.Files.createDirectories(root.resolve("mutable/home/gradle-distributions"));
            java.nio.file.Files.write(cache.resolve(digest + ".zip"), archive);
            String properties = "distributionUrl=https\\://downloads.gradle.org/distributions/gradle-8.10.2-bin.zip"
                + newline + "distributionSha256Sum=" + digest + newline;
            java.nio.file.Files.writeString(wrapper.resolve("gradle-wrapper.properties"), properties);
            Path log = root.resolve("output.txt");
            Process process = new ProcessBuilder(bash, "-s").directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(
                        ("set -euo pipefail\nexport PATH=/usr/bin:/bin:$PATH\nsource=$PWD\nmutable=$PWD/mutable\n"
                            + platformSetup + "retry_run() { shift; \"$@\"; }\n" + body)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
                org.junit.jupiter.api.Assertions.assertEquals(0, process.exitValue(),
                    java.nio.file.Files.readString(log));
                assertTrue(java.nio.file.Files.readString(log).contains("WRAPPER_OK"));
                org.junit.jupiter.api.Assertions.assertEquals(script,
                    java.nio.file.Files.readString(root.resolve("gradlew")));
                org.junit.jupiter.api.Assertions.assertEquals(properties,
                    java.nio.file.Files.readString(wrapper.resolve("gradle-wrapper.properties")));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void composerUsesSelectedPhpEvenWhenItsShebangPointsToAnotherInterpreter() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(Path.of(bash)), "Bash required");
        String build = render(new ComposerBuildWriter(), DeploymentBuildToolType.COMPOSER_LOCKED,
            new DeploymentRuntimeDefinition.PhpService("8.3", "public", "public/index.php", 8080, TCP));
        String body = build.substring(build.indexOf("require_build_tool php"), build.indexOf("printf 'ARTIFACT="));
        String fixtureDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest("fixed-composer-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        body = body.replace("7a2d379d5b8ffdaa028580ef26494c36d2feef4b178d3dd1473a4dbc5e17c8d6", fixtureDigest);
        String setup = """
            set -euo pipefail
            export PATH=/usr/bin:/bin:$PATH
            mkdir -p tools public
            touch composer.json composer.lock public/index.php
            cat > tools/composer <<'COMPOSER'
            #!/unselected/system/php
            COMPOSER
            cat > tools/php <<'PHP'
            #!/bin/bash
            set -euo pipefail
            if [ "$1" = -r ]; then printf '8.3'; exit; fi
            [ "$1" = "$PWD/mutable/home/.wtl-tools/composer-2.10.3.phar" ] && [ "$2" = install ]
            printf selected-php > selected-interpreter
            mkdir -p vendor
            touch vendor/autoload.php
            PHP
            chmod +x tools/php tools/composer
            export PATH="$PWD/tools:$PATH"
            mutable="$PWD/mutable"
            require_build_tool() { command -v "$1" >/dev/null; }
            curl() {
              [ "$*" = "--disable --fail --silent --show-error --proto =https --max-time 120 --retry 2 --retry-max-time 180 --max-filesize 16777216 --output $mutable/home/.wtl-tools/composer-2.10.3.phar https://getcomposer.org/download/2.10.3/composer.phar" ] || return 91
              printf 'fixed-composer-fixture' > "$mutable/home/.wtl-tools/composer-2.10.3.phar"
              if [ "$scenario" = altered ]; then printf altered >> "$mutable/home/.wtl-tools/composer-2.10.3.phar"; fi
            }
            run() { "$@"; }
            """;
        for (String scenario : List.of("valid", "altered")) {
            Path directory = java.nio.file.Files.createDirectory(temporaryDirectory.resolve(scenario));
            Path log = directory.resolve("composer-output.txt");
            Process process = new ProcessBuilder(bash, "-s").directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(("scenario=" + scenario + "\n" + setup + body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
                org.junit.jupiter.api.Assertions.assertEquals(scenario.equals("valid") ? 0 : 1, process.exitValue(), java.nio.file.Files.readString(log));
                org.junit.jupiter.api.Assertions.assertEquals(scenario.equals("valid"), java.nio.file.Files.exists(directory.resolve("selected-interpreter")));
                if (scenario.equals("valid"))
                    org.junit.jupiter.api.Assertions.assertEquals("selected-php", java.nio.file.Files.readString(directory.resolve("selected-interpreter")));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void rendersBuiltStaticSitesOnlyWithAnExplicitNodeMajor() {
        for (DeploymentBuildToolType tool : List.of(DeploymentBuildToolType.NPM, DeploymentBuildToolType.PNPM,
            DeploymentBuildToolType.YARN)) {
            String script = render(new StaticSiteBuildWriter(), tool,
                new DeploymentRuntimeDefinition.StaticSite("dist", OptionalInt.of(20), HTTP));
            assertTrue(script.contains("node --version | grep -Eq '^v20\\.'"));
        }
        assertThrows(IllegalArgumentException.class, () -> render(new StaticSiteBuildWriter(),
            DeploymentBuildToolType.NPM, new DeploymentRuntimeDefinition.StaticSite("dist", HTTP)));
    }

    @Test
    void rejectsArchitectureMismatchesAndCommandInjection() {
        assertThrows(IllegalArgumentException.class, () -> render(new NpmBuildWriter(), DeploymentBuildToolType.NPM,
            new DeploymentRuntimeDefinition.PythonService("3.12", "demo.main", TCP)));
        assertThrows(IllegalArgumentException.class, () -> render(new NpmBuildWriter(), DeploymentBuildToolType.PNPM,
            new DeploymentRuntimeDefinition.NodeService(20, TCP)));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentRuntimeDefinition.JavaSource("src;touch-pwned",
            "demo.Main", "21", List.of(), List.of(), TCP));
        assertThrows(IllegalArgumentException.class,
            () -> new DeploymentRuntimeDefinition.CmakeService("w2l-release", "demo;touch-pwned", "demo", TCP));
        String quoted = SafeBuildScriptEnvelope.shellQuote("value'; touch /tmp/pwned; printf '");
        assertFalse(quoted.contains("value'; touch"));
        assertTrue(quoted.startsWith("'value'\"'\"'"));
    }

    @Test
    void rendersCatalogBoundariesWithExplicitSelectionsAndUnchangedProjectTargets() {
        var catalog = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog
            .defaults();
        for (var ecosystem : gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType
            .values()) {
            var branches = catalog.branches(ecosystem);
            for (var branch : List.of(branches.getFirst(), branches.get(branches.size() / 2), branches.getLast())) {
                String version = branch.version();
                DeploymentRuntimeDefinition runtime = switch (ecosystem) {
                    case JAVA -> new DeploymentRuntimeDefinition.JavaSource("src", "demo.Main", version, List.of(),
                        List.of(), TCP);
                    case NODE -> new DeploymentRuntimeDefinition.NodeService(Integer.parseInt(version), TCP);
                    case PYTHON -> new DeploymentRuntimeDefinition.PythonService(version, "demo.main", TCP);
                    case GO -> new DeploymentRuntimeDefinition.GoService(version, "demo", "main.go", TCP);
                    case RUST -> new DeploymentRuntimeDefinition.RustService(version, "demo", "src/main.rs", TCP);
                    case DOTNET ->
                        new DeploymentRuntimeDefinition.DotNetService(version + ".0.100", "Demo", "Demo.dll", TCP);
                    case KOTLIN ->
                        new DeploymentRuntimeDefinition.KotlinService(version, "demo", "demo.MainKt", "8", TCP);
                    case PHP ->
                        new DeploymentRuntimeDefinition.PhpService(version, "public", "public/index.php", 8080, TCP);
                    case RUBY -> new DeploymentRuntimeDefinition.RubyService(version, "source", "server.rb", 8080, TCP);
                    case C, CPP -> new DeploymentRuntimeDefinition.CmakeService("w2l-release", "demo", "demo", TCP);
                };
                DeploymentBuildWriter renderer = switch (ecosystem) {
                    case JAVA -> new JdkBuildWriter();
                    case NODE -> new NpmBuildWriter();
                    case PYTHON -> new PipBuildWriter();
                    case GO -> new GoBuildWriter();
                    case RUST -> new CargoBuildWriter();
                    case DOTNET -> new DotNetSdkBuildWriter();
                    case KOTLIN -> new KotlinCompilerBuildWriter();
                    case PHP -> new PhpCliBuildWriter();
                    case RUBY -> new RubyCliBuildWriter();
                    case C, CPP -> new CmakeBuildWriter();
                };
                var tool = renderer.buildTools().iterator().next();
                String build = render(renderer, tool, runtime);
                assertTrue(build.contains("BUILD_TOOL=" + tool.name()), ecosystem + version);
                if (ecosystem == gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.C
                    || ecosystem == gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.CPP) {
                    assertTrue(build.contains("cmake -S . -B .w2l/cmake-build"));
                    assertFalse(build.contains("-DCMAKE_C_STANDARD="));
                    continue;
                }
                var requirement = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement
                    .declared(ecosystem, version, "boundary-fixture",
                        gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement.PurposeType.BUILD);
                var exact = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion
                    .parse(ecosystem, version + (ecosystem.branchSegments() == 1 ? ".0.1" : ".1")).orElseThrow();
                var set = new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains(
                    catalog.revision(),
                    List.of(new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.Selection(
                        requirement, exact, "/usr/local/lib/windowstolinux/toolchains/versions/fixture-" + SHA,
                        gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.OriginType.MANAGED,
                        "https://example.test/fixture", SHA)));
                String environment = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.ToolchainBuildEnvironment
                    .render(set);
                assertTrue(environment.contains("WTL_" + ecosystem.name() + "_VERSION='" + exact.text() + "'"));
                assertTrue(environment
                    .contains("export PATH='/usr/local/lib/windowstolinux/toolchains/versions/fixture-"));
                if (ecosystem == gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.JAVA)
                    assertTrue(build.contains("target_java='" + version + "'"));
                assertFalse(build.contains("sed -i"), "version preparation must not edit project declarations");
            }
        }
    }

    @Test
    void jdkBuildRejectsSystemFallbackWhenBoundCompilerIsInaccessible() throws Exception {
        String bash = System.getProperty("managed.test.bash", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(bash.isBlank(), "requires an explicitly selected bash");
        var ecosystem = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.JAVA;
        var requirement = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement
            .declared(ecosystem, "21", "fixture",
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement.PurposeType.BUILD);
        var version = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion
            .parse(ecosystem, "21.0.12.1+1").orElseThrow();
        var selection = new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.Selection(
            requirement, version, "/wtl-selected-jdk",
            gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.OriginType.MANAGED,
            "https://example.test/fixture", SHA);
        String guard = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.ToolchainBuildEnvironment
            .render(new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains(
                "fixture", List.of(selection)));
        for (boolean available : new boolean[]{false, true}) {
            String setup = "export WTL_JAVA_HOME=/wtl-selected-jdk\n" + "command() { printf '%s\\n' /"
                + (available ? "wtl-selected-jdk" : "system-jdk") + "/bin/$2; }\n"
                + "[() { if test \"$1\" = '!' && test \"$2\" = -x; then return 1; fi; builtin [ \"$@\"; }\n";
            Process process = new ProcessBuilder(bash, "--noprofile", "--norc").redirectErrorStream(true).start();
            try (var input = process.getOutputStream()) {
                input.write((setup + guard + "printf 'BOUND_JDK_ACCEPTED\\n'\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertEquals(available ? 0 : 65, process.waitFor(), output);
            org.junit.jupiter.api.Assertions.assertEquals(available, output.contains("BOUND_JDK_ACCEPTED"), output);
            org.junit.jupiter.api.Assertions.assertEquals(!available, output.contains("TOOLCHAIN_FAILURE=integrity"),
                output);
        }
    }

    @Test
    void registryRequiresExactlyOneRendererForEveryArchitecture() {
        List<DeploymentBuildWriter> complete = renderers();
        new DeploymentBuildWriterRegistry(complete);
        assertThrows(IllegalArgumentException.class, () -> new DeploymentBuildWriterRegistry(java.util.stream.Stream
            .concat(complete.stream(), java.util.stream.Stream.of(new NpmBuildWriter())).toList()));
        assertThrows(IllegalArgumentException.class,
            () -> new DeploymentBuildWriterRegistry(complete.subList(0, complete.size() - 1)));
    }

    @Test
    void containerExtractionScopesItsMaskAndUsesTheExistingHardVolumeLimit() throws Exception {
        String bash = System.getProperty("managed.test.bash", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(bash.isBlank(), "requires an explicitly selected bash");
        for (boolean container : new boolean[]{false, true}) {
            var workspace = new DeploymentRemoteWorkspace("demo", SHA);
            String script = SafeBuildScriptEnvelope.wrap(
                facts(container ? DeploymentProjectKind.DOCKERFILE_CONTAINER : DeploymentProjectKind.NODE_SERVICE,
                    container ? DeploymentBuildToolType.CONTAINER_BUILD : DeploymentBuildToolType.NPM),
                workspace, gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(), "printf 'PROJECT_MASK=%s\\n' \"$(umask)\"");
            script = script.replace(workspace.candidateRoot(),
                temporaryDirectory.resolve(container ? "container" : "ordinary").toString().replace('\\', '/'));
            String setup = """
                export PATH=/usr/bin:/bin:$PATH
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
            String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertEquals(container ? 0 : 7, process.waitFor(), output);
            assertTrue(output.contains("EXTRACT_MASK=" + (container ? "0022" : "0077")), output);
            assertTrue(output.contains("PROJECT_MASK=0077"), output);
            org.junit.jupiter.api.Assertions.assertEquals(!container, output.contains("UNREADABLE_ENGINE_FILE"),
                output);
        }
    }

    private String render(DeploymentBuildWriter renderer, DeploymentBuildToolType tool,
                          DeploymentRuntimeDefinition runtime) {
        return renderer.render(facts(renderer.projectType(), tool), runtime, new DeploymentRemoteWorkspace("demo", SHA),
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits());
    }

    private DeploymentProjectFacts facts(DeploymentProjectKind type, DeploymentBuildToolType tool) {
        if (type == DeploymentProjectKind.CMAKE_SERVICE) {
            return new DeploymentProjectFacts(temporaryDirectory, "demo", type, tool,
                new ProjectLanguageFacts(Set.of(), Set.of(SourceLanguageType.C, SourceLanguageType.CPP), Map.of(),
                    List.of()),
                List.of(new AnalysisEvidence(DeploymentLocalizedMessage.of("test.evidence"), "fixture",
                    DeploymentLocalizedMessage.of("test.detected"), EvidenceConfidenceLevel.HIGH)),
                List.of(), List.of());
        }
        return new DeploymentProjectFacts(temporaryDirectory, "demo", type, tool,
            List.of(new AnalysisEvidence(DeploymentLocalizedMessage.of("test.evidence"), "fixture",
                DeploymentLocalizedMessage.of("test.detected"), EvidenceConfidenceLevel.HIGH)),
            List.of(), List.of());
    }

    private String service(DeploymentBuildWriter renderer, DeploymentBuildToolType tool, String version,
                           String artifact, String entrypoint, OptionalInt port) {
        DeploymentRuntimeDefinition runtime = switch (renderer.projectType()) {
            case GO_SERVICE -> new DeploymentRuntimeDefinition.GoService(version, artifact, entrypoint, TCP);
            case RUST_SERVICE -> new DeploymentRuntimeDefinition.RustService(version, artifact, entrypoint, TCP);
            case DOTNET_SERVICE -> new DeploymentRuntimeDefinition.DotNetService(version, artifact, entrypoint, TCP);
            case KOTLIN_SERVICE -> new DeploymentRuntimeDefinition.KotlinService(version, artifact, entrypoint, TCP);
            case PHP_SERVICE ->
                new DeploymentRuntimeDefinition.PhpService(version, artifact, entrypoint, port.orElseThrow(), TCP);
            case RUBY_SERVICE ->
                new DeploymentRuntimeDefinition.RubyService(version, artifact, entrypoint, port.orElseThrow(), TCP);
            default -> throw new IllegalArgumentException("unsupported service test type");
        };
        return render(renderer, tool, runtime);
    }

    private static List<DeploymentBuildWriter> renderers() {
        return List.of(new GradleBuildWriter(), new MavenBuildWriter(), new JavaJarBuildWriter(),
            new JdkBuildWriter(), new NpmBuildWriter(), new PnpmBuildWriter(), new YarnBuildWriter(),
            new PipBuildWriter(), new PipenvBuildWriter(), new PoetryBuildWriter(), new UvBuildWriter(),
            new StaticSiteBuildWriter(), new ContainerBuildWriter(), new GoBuildWriter(),
            new CargoBuildWriter(), new DotNetSdkBuildWriter(), new KotlinGradleBuildWriter(),
            new KotlinCompilerBuildWriter(), new ComposerBuildWriter(), new PhpCliBuildWriter(),
            new BundlerBuildWriter(), new RubyCliBuildWriter(), new CmakeBuildWriter());
    }
}
