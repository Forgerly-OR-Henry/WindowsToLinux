package gold.debug.windowstolinux.shared.analyze.service;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LanguageServiceInspectionTest {
    @TempDir Path temporaryDirectory;

    @Test
    void admitsEachCompleteLockedEcosystemServiceProjectShape() throws Exception {
        List<Project> projects = List.of(go(), rust(), dotnet(), kotlin(), php(), ruby());

        for (Project project : projects) {
            var assessment = new DeploymentAnalysisCoordinator().analyze(project.root(), project.type());
            assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING, assessment.admission(), project.type().name());
            assertEquals(DeploymentSupportLevel.EXPERIMENTAL_ADAPTER,
                    assessment.facts().orElseThrow().support().level(), project.type().name());
            assertEquals(project.buildTool(), assessment.facts().orElseThrow().buildTool(), project.type().name());
            var runtime = assessment.runtimeSuggestion().orElseThrow();
            var expected = new java.util.EnumMap<DeploymentRuntimeAssessment.RuntimeInputType, String>(DeploymentRuntimeAssessment.RuntimeInputType.class);
            expected.putAll(project.runtimeValues());
            if (project.type() == DeploymentProjectType.KOTLIN_SERVICE)
                expected.put(DeploymentRuntimeAssessment.RuntimeInputType.KOTLIN_JVM_TARGET, "21");
            assertEquals(expected, runtime.values(), project.type().name());
            assertEquals(project.requiresServicePort(), runtime.requiredUserInput().stream()
                    .anyMatch(message -> message.key().equals("analysis.service.servicePort")), project.type().name());
        }
    }

    @Test
    void stopsBeforePlanningWhenTheLanguageLockIsMissing() throws Exception {
        Project project = go();
        Files.delete(project.root().resolve("go.sum"));

        var assessment = new DeploymentAnalysisCoordinator().analyze(project.root(), project.type());

        assertEquals(DeploymentAdmissionStatus.REQUIRES_INPUT, assessment.admission());
        assertFalse(assessment.facts().orElseThrow().missingInformation().isEmpty());
    }

    @Test
    void rejectsConsoleDotnetAndUnlockedKotlinShapesBeforePlanning() throws Exception {
        Project dotnet = dotnet();
        write(dotnet.root(), "Demo.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"></Project>\n");
        Project kotlin = kotlin();
        write(kotlin.root(), "build.gradle.kts", "plugins { application }\napplication { mainClass.set(\"demo.MainKt\") }\n");

        assertEquals(DeploymentAdmissionStatus.REQUIRES_INPUT,
                new DeploymentAnalysisCoordinator().analyze(dotnet.root(), dotnet.type()).admission());
        assertEquals(DeploymentAdmissionStatus.REQUIRES_INPUT,
                new DeploymentAnalysisCoordinator().analyze(kotlin.root(), kotlin.type()).admission());
    }

    private Project go() throws Exception {
        Path root = root("go");
        write(root, "go.mod", "module example.test/demo\n\ngo 1.24\n");
        write(root, "go.sum", "example.test/dependency v1.0.0 h1:fixture\n");
        write(root, "main.go", "package main\nfunc main() {}\n");
        return project(root, DeploymentProjectType.GO_SERVICE, DeploymentBuildToolType.GO_MODULE,
                "1.24", "w2l-app", "main.go", false);
    }

    private Project rust() throws Exception {
        Path root = root("rust");
        write(root, "Cargo.toml", "[package]\nname = \"demo\"\nversion = \"0.1.0\"\n");
        write(root, "Cargo.lock", "version = 4\n");
        write(root, "rust-toolchain.toml", "[toolchain]\nchannel = \"1.89.0\"\n");
        write(root, "src/main.rs", "fn main() {}\n");
        return project(root, DeploymentProjectType.RUST_SERVICE, DeploymentBuildToolType.CARGO_LOCKED,
                "1.89.0", "demo", "src/main.rs", false);
    }

    private Project dotnet() throws Exception {
        Path root = root("dotnet");
        write(root, "global.json", "{\"sdk\":{\"version\":\"8.0.408\"}}\n");
        write(root, "packages.lock.json", "{\"version\":1,\"dependencies\":{}}\n");
        write(root, "Demo.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"></Project>\n");
        write(root, "Program.cs", "var app = WebApplication.CreateBuilder(args).Build();\n");
        return project(root, DeploymentProjectType.DOTNET_SERVICE, DeploymentBuildToolType.DOTNET_LOCKED,
                "8.0.408", "Demo", "Demo.dll", false);
    }

    private Project kotlin() throws Exception {
        Path root = root("kotlin");
        write(root, "build.gradle.kts", """
                plugins { kotlin("jvm") version "2.0.21"; application }
                java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
                dependencyLocking { lockAllConfigurations() }
                application { mainClass.set("demo.MainKt") }
                """);
        write(root, "settings.gradle.kts", "rootProject.name = \"demo\"\n");
        write(root, "gradlew", "#!/bin/sh\n");
        write(root, "gradle.lockfile", "empty=fixture\n");
        write(root, "gradle/wrapper/gradle-wrapper.jar", "fixture");
        write(root, "gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https://example.test/gradle.zip\n");
        write(root, "src/main/kotlin/demo/Main.kt", "package demo\nfun main() {}\n");
        return project(root, DeploymentProjectType.KOTLIN_SERVICE, DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER,
                "2.0.21", "kotlin", "demo.MainKt", false);
    }

    private Project php() throws Exception {
        Path root = root("php");
        write(root, "composer.json", "{\"config\":{\"platform\":{\"php\":\"8.3\"}}}\n");
        write(root, "composer.lock", "{\"packages\":[]}\n");
        write(root, "public/index.php", "<?php echo 'ok';\n");
        return project(root, DeploymentProjectType.PHP_SERVICE, DeploymentBuildToolType.COMPOSER_LOCKED,
                "8.3", "public", "public/index.php", true);
    }

    private Project ruby() throws Exception {
        Path root = root("ruby");
        write(root, ".ruby-version", "3.3.5\n");
        write(root, "Gemfile", "source \"https://rubygems.org\"\ngem \"rack\"\n");
        write(root, "Gemfile.lock", "GEM\n");
        write(root, "config.ru", "run ->(_env) { [200, {}, ['ok']] }\n");
        return project(root, DeploymentProjectType.RUBY_SERVICE, DeploymentBuildToolType.BUNDLER_LOCKED,
                "3.3.5", "bundle", "config.ru", true);
    }

    private Path root(String name) throws Exception {
        return Files.createDirectory(temporaryDirectory.resolve(name));
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Project project(Path root, DeploymentProjectType type, DeploymentBuildToolType buildTool,
                                   String version, String artifact, String entrypoint, boolean requiresServicePort) {
        return new Project(root, type, buildTool, Map.of(
                DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_VERSION, version,
                DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_ARTIFACT, artifact,
                DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_ENTRYPOINT, entrypoint), requiresServicePort);
    }

    private record Project(Path root, DeploymentProjectType type, DeploymentBuildToolType buildTool,
                           Map<DeploymentRuntimeAssessment.RuntimeInputType, String> runtimeValues,
                           boolean requiresServicePort) {
    }
}
