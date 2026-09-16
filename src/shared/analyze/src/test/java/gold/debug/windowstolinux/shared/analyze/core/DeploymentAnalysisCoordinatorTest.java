package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportLevel;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentAnalysisCoordinatorTest {
    @TempDir
    Path temporaryDirectory;

    private final DeploymentAnalysisCoordinator analyzer = new DeploymentAnalysisCoordinator();

    @Test
    void acceptsACompleteGradleSpringBootProjectWithoutRunningItsWrapper() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("gradle-service"));
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { id(\"org.springframework.boot\") version \"3.5.0\" }");
        Files.writeString(project.resolve("gradlew"), "this script must not run");
        Files.createDirectories(project.resolve("gradle/wrapper"));
        Files.writeString(project.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https://example.test/gradle.zip");
        writeGradleWrapperJar(project.resolve("gradle/wrapper/gradle-wrapper.jar"));

        var assessment = analyzer.analyze(project, DeploymentProjectType.SPRING_BOOT);

        assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING, assessment.admission());
        assertEquals(DeploymentBuildToolType.GRADLE_WRAPPER, assessment.facts().orElseThrow().buildTool());
    }

    @Test
    void requiresTheGradleWrapperJarWithoutExecutingTheWrapper() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("incomplete-gradle-wrapper"));
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'org.springframework.boot' version '3.5.0' }");
        Files.writeString(project.resolve("gradlew"), "this script must not run");
        Files.createDirectories(project.resolve("gradle/wrapper"));
        Files.writeString(project.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https://example.test/gradle.zip");

        var assessment = analyzer.analyze(project, DeploymentProjectType.SPRING_BOOT);

        assertEquals(DeploymentAdmissionStatus.REQUIRES_INPUT, assessment.admission());
        assertTrue(assessment.facts().orElseThrow().missingInformation().stream()
                .anyMatch(message -> message.key().equals("analysis.deployment.missing.gradleWrapper")));
    }

    @Test
    void rejectsAnInvalidGradleWrapperJarWithoutLoadingIt() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("invalid-gradle-wrapper"));
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'org.springframework.boot' version '3.5.0' }");
        Files.writeString(project.resolve("gradlew"), "this script must not run");
        Files.createDirectories(project.resolve("gradle/wrapper"));
        Files.writeString(project.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https://example.test/gradle.zip");
        Files.write(project.resolve("gradle/wrapper/gradle-wrapper.jar"), new byte[]{0});

        var assessment = analyzer.analyze(project, DeploymentProjectType.SPRING_BOOT);

        assertEquals(DeploymentAdmissionStatus.REQUIRES_INPUT, assessment.admission());
        assertTrue(assessment.facts().orElseThrow().missingInformation().stream()
                .anyMatch(message -> message.key().equals("analysis.deployment.missing.gradleWrapper")));
    }

    @Test
    void scansGroovyGradleScriptsForAutomaticDatabaseMigrationTools() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("gradle-migration"));
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'org.springframework.boot' version '3.5.0' }
                dependencies { implementation 'org.flywaydb:flyway-core:11.0.0' }
                """);

        var assessment = analyzer.analyze(project, DeploymentProjectType.SPRING_BOOT);

        assertEquals(DeploymentAdmissionStatus.REJECTED, assessment.admission());
        assertTrue(assessment.rejections().stream()
                .anyMatch(reason -> reason.code().equals("DATABASE_MIGRATION_DETECTED")));
    }

    @Test
    void requiresLockfileAndFixedScriptsForNodeInsteadOfExecutingPackageJson() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("node-service"));
        Files.writeString(project.resolve("package.json"), """
                {"name":"demo-node","scripts":{"build":"unsafe build content","start":"unsafe start content"}}
                """);

        var assessment = analyzer.analyze(project, DeploymentProjectType.NODE_SERVICE);

        assertEquals(DeploymentAdmissionStatus.REQUIRES_INPUT, assessment.admission());
        assertTrue(assessment.facts().orElseThrow().missingInformation().stream()
                .anyMatch(message -> message.key().equals("analysis.deployment.missing.nodeLockfile")));
    }

    @Test
    void rejectsComposeAsAMultiComponentProject() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("container"));
        Files.writeString(project.resolve("Dockerfile"), "FROM alpine:3.20");
        Files.writeString(project.resolve("compose.yml"), "services: {}");

        var assessment = analyzer.analyze(project, DeploymentProjectType.DOCKERFILE_CONTAINER);

        assertEquals(DeploymentAdmissionStatus.REJECTED, assessment.admission());
        assertTrue(assessment.rejections().stream().anyMatch(reason -> reason.code().equals("MULTI_CONTAINER_COMPOSE_DETECTED")));
    }

    @Test
    void rejectsDatabaseMutationArtifactsForEveryDeploymentType() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("node-database-change"));
        Files.writeString(project.resolve("package.json"), """
                {"name":"demo-node","scripts":{"build":"build","start":"start"},"dependencies":{"prisma":"1.0"}}
                """);
        Files.writeString(project.resolve("package-lock.json"), npmLock("demo-node"));
        Files.createDirectories(project.resolve("database/migrations"));
        Files.writeString(project.resolve("database/migrations/001.sql"), "create table demo (id bigint)");

        var assessment = analyzer.analyze(project, DeploymentProjectType.NODE_SERVICE);

        assertEquals(DeploymentAdmissionStatus.REJECTED, assessment.admission());
        assertTrue(assessment.rejections().stream().anyMatch(reason -> reason.code().equals("DATABASE_MIGRATION_DETECTED")));
        assertTrue(assessment.rejections().stream().anyMatch(reason -> reason.code().equals("AUTOMATIC_SCHEMA_MUTATION_DETECTED")));
    }

    @Test
    void acceptsEverySelectedTypeWhenItsSourceFactsAreComplete() throws Exception {
        Path javaJar = Files.createDirectories(temporaryDirectory.resolve("java-jar"));
        Files.writeString(javaJar.resolve("application.jar"), "opaque binary content");
        Path node = Files.createDirectories(temporaryDirectory.resolve("node"));
        Files.writeString(node.resolve("package.json"), """
                {"name":"demo-node","scripts":{"build":"build","start":"start"}}
                """);
        Files.writeString(node.resolve("package-lock.json"), npmLock("demo-node"));
        Path python = Files.createDirectories(temporaryDirectory.resolve("python"));
        Files.writeString(python.resolve("pyproject.toml"), "[project]\nname = \"demo-python\"\n");
        Files.writeString(python.resolve("requirements.lock"), "example==1.0 --hash=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n");
        Path staticSite = Files.createDirectories(temporaryDirectory.resolve("static"));
        Files.writeString(staticSite.resolve("index.html"), "<!doctype html>");
        Path container = Files.createDirectories(temporaryDirectory.resolve("container-ready"));
        Files.writeString(container.resolve("Dockerfile"), "FROM alpine@sha256:" + "a".repeat(64));

        assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING,
                analyzer.analyze(javaJar, DeploymentProjectType.JAVA_JAR).admission());
        assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING,
                analyzer.analyze(node, DeploymentProjectType.NODE_SERVICE).admission());
        assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING,
                analyzer.analyze(python, DeploymentProjectType.PYTHON_SERVICE).admission());
        assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING,
                analyzer.analyze(staticSite, DeploymentProjectType.STATIC_SITE).admission());
        assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING,
                analyzer.analyze(container, DeploymentProjectType.DOCKERFILE_CONTAINER).admission());
    }

    @Test
    void infersOnlyExactRuntimeMetadataAndLeavesRangesForHumanReview() throws Exception {
        Path node = Files.createDirectories(temporaryDirectory.resolve("node-exact"));
        Files.writeString(node.resolve("package.json"), """
                {"name":"demo-node","engines":{"node":"22"},"scripts":{"build":"unsafe","start":"unsafe"}}
                """);
        Files.writeString(node.resolve("package-lock.json"), npmLock("node-exact"));
        var exact = analyzer.analyze(node, DeploymentProjectType.NODE_SERVICE).runtimeSuggestion().orElseThrow();
        assertEquals("22", exact.value(DeploymentRuntimeAssessment.RuntimeInputType.NODE_MAJOR_VERSION).orElseThrow());

        Path ranged = Files.createDirectories(temporaryDirectory.resolve("node-range"));
        Files.writeString(ranged.resolve("package.json"), """
                {"name":"range-node","engines":{"node":">=20"},"scripts":{"build":"unsafe","start":"unsafe"}}
                """);
        Files.writeString(ranged.resolve("package-lock.json"), npmLock("range-node"));
        var unresolved = analyzer.analyze(ranged, DeploymentProjectType.NODE_SERVICE).runtimeSuggestion().orElseThrow();
        assertTrue(unresolved.value(DeploymentRuntimeAssessment.RuntimeInputType.NODE_MAJOR_VERSION).isEmpty());
        assertTrue(unresolved.requiredUserInput().stream().anyMatch(message ->
                message.key().equals("analysis.deployment.runtime.nodeVersion")));
    }

    @Test
    void infersJavaPythonStaticAndContainerValuesFromSafeMetadataOnly() throws Exception {
        Path java = Files.createDirectories(temporaryDirectory.resolve("jar-inference"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "example.Main");
        manifest.getMainAttributes().putValue("Build-Jdk-Spec", "21");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(java.resolve("service.jar")), manifest)) {
            // A manifest-only JAR is sufficient because inference never invokes or loads it. / 仅含清单的 JAR 已足够，因为推导绝不调用或加载它。
        }
        var javaSuggestion = analyzer.analyze(java, DeploymentProjectType.JAVA_JAR).runtimeSuggestion().orElseThrow();
        assertEquals("service.jar", javaSuggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_JAR_PATH).orElseThrow());
        assertEquals("example.Main", javaSuggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_MAIN_CLASS).orElseThrow());
        assertEquals("21", javaSuggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_VERSION).orElseThrow());

        Path python = Files.createDirectories(temporaryDirectory.resolve("python-inference/src/demo"));
        Files.writeString(python.getParent().getParent().resolve("pyproject.toml"), """
                [project]
                name = "demo"
                requires-python = "==3.12.*"
                """);
        Files.writeString(python.getParent().getParent().resolve("requirements.lock"), "demo==1 --hash=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Files.writeString(python.resolve("__main__.py"), "raise SystemExit(0)");
        var pythonSuggestion = analyzer.analyze(python.getParent().getParent(), DeploymentProjectType.PYTHON_SERVICE)
                .runtimeSuggestion().orElseThrow();
        assertEquals("3.12", pythonSuggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.PYTHON_VERSION).orElseThrow());
        assertEquals("demo", pythonSuggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.PYTHON_ENTRYPOINT).orElseThrow());

        Path staticSite = Files.createDirectories(temporaryDirectory.resolve("static-inference"));
        Files.writeString(staticSite.resolve("package.json"), """
                {"name":"site","scripts":{"build":"vite build"}}
                """);
        Files.writeString(staticSite.resolve("package-lock.json"), "{}");
        Files.writeString(staticSite.resolve("vite.config.js"), "export default { build: { outDir: 'public-site' } }");
        var staticSuggestion = analyzer.analyze(staticSite, DeploymentProjectType.STATIC_SITE).runtimeSuggestion().orElseThrow();
        assertEquals("public-site", staticSuggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.STATIC_OUTPUT_DIRECTORY).orElseThrow());

        Path container = Files.createDirectories(temporaryDirectory.resolve("container-inference"));
        Files.writeString(container.resolve("Dockerfile"), "FROM alpine@sha256:" + "a".repeat(64)
                + "\nEXPOSE 8080 8443/tcp\nVOLUME /var/lib/demo");
        var containerSuggestion = analyzer.analyze(container, DeploymentProjectType.DOCKERFILE_CONTAINER).runtimeSuggestion().orElseThrow();
        assertEquals(Map.of(8080, 8080, 8443, 8443), containerSuggestion.suggestedContainerPorts());
        assertEquals("/var/lib/demo", containerSuggestion.suggestedManagedVolumes().getFirst().containerPath());
    }

    @Test
    void recognitionPreviewReportsEveryCandidateLanguageWithoutCreatingADeploymentPath() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("recognition-preview"));
        for (String file : new String[]{"main.go", "main.rs", "Program.cs", "Main.kt", "index.php", "app.rb",
                "native.c", "native.cpp", "Main.scala", "core.clj", "app.ex", "main.dart", "init.lua",
                "worker.pl", "main.swift", "install.sh"}) {
            Files.writeString(project.resolve(file), "static marker only");
        }

        var assessment = analyzer.analyze(project, DeploymentProjectType.RECOGNITION_PREVIEW);

        assertEquals(DeploymentAdmissionStatus.RECOGNITION_PREVIEW, assessment.admission());
        var facts = assessment.facts().orElseThrow();
        assertEquals(DeploymentBuildToolType.NONE_PREVIEW, facts.buildTool());
        assertEquals(DeploymentSupportLevel.RECOGNITION_PREVIEW, facts.support().level());
        assertTrue(!facts.readyForPlanning());
        assertTrue(facts.languageFacts().sourceLanguages().containsAll(java.util.Set.of(
                SourceLanguageType.GO, SourceLanguageType.RUST, SourceLanguageType.CSHARP, SourceLanguageType.KOTLIN,
                SourceLanguageType.PHP, SourceLanguageType.RUBY, SourceLanguageType.C, SourceLanguageType.CPP,
                SourceLanguageType.SCALA, SourceLanguageType.CLOJURE, SourceLanguageType.ELIXIR, SourceLanguageType.DART,
                SourceLanguageType.LUA, SourceLanguageType.PERL, SourceLanguageType.SWIFT, SourceLanguageType.SHELL)));
        assertTrue(assessment.runtimeSuggestion().isEmpty());
    }

    @Test
    void recognitionPreviewReportsUnrecognizedWithoutInventingSupport() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("unrecognized-preview"));
        Files.writeString(project.resolve("opaque.bin"), "opaque");

        var assessment = analyzer.analyze(project, DeploymentProjectType.RECOGNITION_PREVIEW);

        assertEquals(DeploymentAdmissionStatus.RECOGNITION_PREVIEW, assessment.admission());
        assertEquals(DeploymentSupportLevel.UNRECOGNIZED, assessment.facts().orElseThrow().support().level());
    }

    private static String npmLock(String name) {
        return "{\"name\":\"" + name + "\",\"lockfileVersion\":3,\"requires\":true,"
                + "\"packages\":{\"\":{\"name\":\"" + name + "\"}}}";
    }

    private static void writeGradleWrapperJar(Path path) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("org/gradle/wrapper/GradleWrapperMain.class"));
            output.write(0);
            output.closeEntry();
        }
    }
}
