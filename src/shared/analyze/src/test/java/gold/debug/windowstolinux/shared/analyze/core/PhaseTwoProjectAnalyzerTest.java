package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.model.analysis.PhaseTwoAdmission;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoBuildTool;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseTwoProjectAnalyzerTest {
    @TempDir
    Path temporaryDirectory;

    private final PhaseTwoProjectAnalyzer analyzer = new PhaseTwoProjectAnalyzer();

    @Test
    void acceptsACompleteGradleSpringBootProjectWithoutRunningItsWrapper() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("gradle-service"));
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { id(\"org.springframework.boot\") version \"3.5.0\" }");
        Files.writeString(project.resolve("gradlew"), "this script must not run");
        Files.createDirectories(project.resolve("gradle/wrapper"));
        Files.writeString(project.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https://example.test/gradle.zip");

        var assessment = analyzer.analyze(project, PhaseTwoProjectType.GRADLE_SPRING_BOOT);

        assertEquals(PhaseTwoAdmission.READY_FOR_PLANNING, assessment.admission());
        assertEquals(PhaseTwoBuildTool.GRADLE_WRAPPER, assessment.facts().orElseThrow().buildTool());
    }

    @Test
    void requiresLockfileAndFixedScriptsForNodeInsteadOfExecutingPackageJson() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("node-service"));
        Files.writeString(project.resolve("package.json"), """
                {"name":"demo-node","scripts":{"build":"unsafe build content","start":"unsafe start content"}}
                """);

        var assessment = analyzer.analyze(project, PhaseTwoProjectType.NODE_SERVICE);

        assertEquals(PhaseTwoAdmission.REQUIRES_INPUT, assessment.admission());
        assertTrue(assessment.facts().orElseThrow().missingInformation().stream()
                .anyMatch(message -> message.key().equals("analysis.phase2.missing.nodeLockfile")));
    }

    @Test
    void rejectsComposeAsAPhaseThreeMultiComponentProject() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("container"));
        Files.writeString(project.resolve("Dockerfile"), "FROM alpine:3.20");
        Files.writeString(project.resolve("compose.yml"), "services: {}");

        var assessment = analyzer.analyze(project, PhaseTwoProjectType.DOCKERFILE_CONTAINER);

        assertEquals(PhaseTwoAdmission.REJECTED, assessment.admission());
        assertTrue(assessment.rejections().stream().anyMatch(reason -> reason.code().equals("MULTI_CONTAINER_COMPOSE_DETECTED")));
    }
}
