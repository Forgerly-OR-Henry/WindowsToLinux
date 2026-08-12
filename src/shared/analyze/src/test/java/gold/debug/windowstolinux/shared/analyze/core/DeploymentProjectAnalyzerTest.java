package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmission;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentProjectAnalyzerTest {
    @TempDir
    Path temporaryDirectory;

    private final DeploymentProjectAnalyzer analyzer = new DeploymentProjectAnalyzer();

    @Test
    void acceptsACompleteGradleSpringBootProjectWithoutRunningItsWrapper() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("gradle-service"));
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { id(\"org.springframework.boot\") version \"3.5.0\" }");
        Files.writeString(project.resolve("gradlew"), "this script must not run");
        Files.createDirectories(project.resolve("gradle/wrapper"));
        Files.writeString(project.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https://example.test/gradle.zip");

        var assessment = analyzer.analyze(project, DeploymentProjectType.GRADLE_SPRING_BOOT);

        assertEquals(DeploymentAdmission.READY_FOR_PLANNING, assessment.admission());
        assertEquals(DeploymentBuildTool.GRADLE_WRAPPER, assessment.facts().orElseThrow().buildTool());
    }

    @Test
    void requiresLockfileAndFixedScriptsForNodeInsteadOfExecutingPackageJson() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("node-service"));
        Files.writeString(project.resolve("package.json"), """
                {"name":"demo-node","scripts":{"build":"unsafe build content","start":"unsafe start content"}}
                """);

        var assessment = analyzer.analyze(project, DeploymentProjectType.NODE_SERVICE);

        assertEquals(DeploymentAdmission.REQUIRES_INPUT, assessment.admission());
        assertTrue(assessment.facts().orElseThrow().missingInformation().stream()
                .anyMatch(message -> message.key().equals("analysis.deployment.missing.nodeLockfile")));
    }

    @Test
    void rejectsComposeAsAMultiComponentProject() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("container"));
        Files.writeString(project.resolve("Dockerfile"), "FROM alpine:3.20");
        Files.writeString(project.resolve("compose.yml"), "services: {}");

        var assessment = analyzer.analyze(project, DeploymentProjectType.DOCKERFILE_CONTAINER);

        assertEquals(DeploymentAdmission.REJECTED, assessment.admission());
        assertTrue(assessment.rejections().stream().anyMatch(reason -> reason.code().equals("MULTI_CONTAINER_COMPOSE_DETECTED")));
    }

    @Test
    void acceptsEverySelectedTypeWhenItsSourceFactsAreComplete() throws Exception {
        Path javaJar = Files.createDirectories(temporaryDirectory.resolve("java-jar"));
        Files.writeString(javaJar.resolve("application.jar"), "opaque binary content");
        Path node = Files.createDirectories(temporaryDirectory.resolve("node"));
        Files.writeString(node.resolve("package.json"), """
                {"name":"demo-node","scripts":{"build":"build","start":"start"}}
                """);
        Files.writeString(node.resolve("package-lock.json"), "{}");
        Path python = Files.createDirectories(temporaryDirectory.resolve("python"));
        Files.writeString(python.resolve("pyproject.toml"), "[project]\nname = \"demo-python\"\n");
        Files.writeString(python.resolve("requirements.lock"), "example==1.0 --hash=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n");
        Path staticSite = Files.createDirectories(temporaryDirectory.resolve("static"));
        Files.writeString(staticSite.resolve("index.html"), "<!doctype html>");
        Path container = Files.createDirectories(temporaryDirectory.resolve("container-ready"));
        Files.writeString(container.resolve("Dockerfile"), "FROM alpine:3.20");

        assertEquals(DeploymentAdmission.READY_FOR_PLANNING,
                analyzer.analyze(javaJar, DeploymentProjectType.JAVA_JAR).admission());
        assertEquals(DeploymentAdmission.READY_FOR_PLANNING,
                analyzer.analyze(node, DeploymentProjectType.NODE_SERVICE).admission());
        assertEquals(DeploymentAdmission.READY_FOR_PLANNING,
                analyzer.analyze(python, DeploymentProjectType.PYTHON_SERVICE).admission());
        assertEquals(DeploymentAdmission.READY_FOR_PLANNING,
                analyzer.analyze(staticSite, DeploymentProjectType.STATIC_SITE).admission());
        assertEquals(DeploymentAdmission.READY_FOR_PLANNING,
                analyzer.analyze(container, DeploymentProjectType.DOCKERFILE_CONTAINER).admission());
    }
}
