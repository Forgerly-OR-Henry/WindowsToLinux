package gold.debug.windowstolinux.app.main.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsibilityPackageBoundaryTest {
    private static final Set<String> LEGACY_FILES = Set.of(
            "StaticProjectAnalyzer.java", "DeploymentProjectAnalyzer.java", "DeploymentRuntimeInference.java",
            "DesktopPages.java", "DesktopDatabase.java", "DesktopRepository.java", "GitSnapshotService.java",
            "DeploymentBuildSupport.java", "SystemdRuntimeExecutor.java", "DeploymentSystemdUnitRenderer.java",
            "ManagedPrivilegeHelper.java", "ManagedSpringBootAnalysisCoordinator.java",
            "GradleSpringBootDeploymentInspector.java", "SpringBootProjectInspector.java",
            "ProjectAssessment.java", "SupportDecision.java", "SourceProjectFacts.java", "SourcePreparation.java",
            "DeploymentRequest.java", "ManagedDeploymentService.java", "DeploymentUseCase.java",
            "RemoteBuildResult.java", "LinuxBuildOperations.java", "LinuxReleaseOperations.java",
            "MavenBuildExecutor.java", "MavenBuildSupport.java", "ManagedReleaseProtocolExecutor.java");
    private static final Set<String> ANALYSIS_CORE = Set.of(
            "DeploymentAnalysisCoordinator.java", "DeploymentTypeInspector.java", "DeploymentTypeInspection.java");
    private static final Set<String> DESKTOP_SHELL = Set.of(
            "DesktopFrame.java", "DesktopPageCoordinator.java", "DesktopViewState.java", "PageMessages.java",
            "PageNavigator.java");
    private static final Set<String> DEPLOYMENT_PAGE = Set.of(
            "DeploymentAnalysisPresenter.java", "DeploymentConfigurationParser.java", "DeploymentPage.java",
            "DeploymentPageState.java", "DeploymentRuntimeParser.java", "ReviewContext.java");
    private static final Set<String> REPOSITORIES = Set.of(
            "AiProfileRepository.java", "ApplicationSecretRepository.java", "ConfigurationSnapshotRepository.java",
            "DesktopPreferenceRepository.java", "EncryptedSecretRepository.java", "ManagedApplicationRepository.java",
            "RepositoryTransactions.java", "ServerProfileRepository.java");
    private static final Set<String> HELPER_FRAGMENTS = Set.of(
            "00-common.sh", "10-typed-release.sh", "15-deployment-input.sh", "20-candidate-workspace.sh", "30-ordinary-release.sh",
            "35-advanced-runtime.sh", "40-typed-runtime.sh", "50-container-release.sh", "60-lifecycle.sh",
            "70-command-dispatch.sh");
    private static final Pattern PERIOD_NAME = Pattern.compile("(?i)(?:phase|stage)[-_]?[0-9]+|(?:一期|二期|三期|四期|五期)");

    @Test
    void legacyResponsibilityMonolithsCannotReturn() throws Exception {
        Path root = projectRoot();
        try (Stream<Path> files = Files.walk(root.resolve("src"))) {
            List<Path> legacy = files.filter(Files::isRegularFile)
                    .filter(path -> LEGACY_FILES.contains(path.getFileName().toString())).toList();
            assertTrue(legacy.isEmpty(), () -> "legacy responsibility files returned: " + legacy);
        }
        assertFalse(Files.exists(root.resolve(
                "src/shared/linux-sshd/src/main/resources/gold/debug/windowstolinux/shared/linux/sshd/protocol/managed-helper")));
    }

    @Test
    void coreShellRepositoriesAndHelperResourcesStayFocused() throws Exception {
        Path root = projectRoot();
        Path core = root.resolve("src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/core");
        Path shell = root.resolve("src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/shell");
        Path deploymentPage = root.resolve("src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/deployment");
        Path repositories = root.resolve("src/app/db/src/main/java/gold/debug/windowstolinux/app/db/repository");
        Path fragments = root.resolve(
                "src/shared/linux-sshd/src/main/resources/gold/debug/windowstolinux/shared/linux/sshd/protocol/managed-helper-fragments");

        assertEquals(ANALYSIS_CORE, fileNames(core));
        assertEquals(DESKTOP_SHELL, fileNames(shell));
        assertEquals(DEPLOYMENT_PAGE, fileNames(deploymentPage));
        assertEquals(REPOSITORIES, fileNames(repositories));
        assertEquals(HELPER_FRAGMENTS, fileNames(fragments));
        assertMaximumLines(core, 400);
        assertMaximumLines(shell, 400);
        assertMaximumLines(deploymentPage, 480);
        assertMaximumLines(repositories, 320);
        assertMaximumLines(fragments, 300);
    }

    @Test
    void productionNamesUseStableResponsibilitiesAndMatchFileDocumentation() throws Exception {
        Path root = projectRoot();
        try (Stream<Path> files = Files.walk(root.resolve("src"))) {
            List<String> periodNames = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().contains("src" + java.io.File.separator + "main"))
                    .map(root::relativize).map(Path::toString).filter(name -> PERIOD_NAME.matcher(name).find()).toList();
            assertTrue(periodNames.isEmpty(), () -> "production paths contain period names: " + periodNames);
        }

        String structure = Files.readString(root.resolve("docs/File.md"));
        for (String required : List.of("DeploymentAnalysisCoordinator", "SpringBootDeploymentInspector",
                "DesktopPageCoordinator", "DesktopPersistence", "GitSnapshotPreparer", "DeploymentBuildRenderer",
                "SpringBootBuildRenderer", "SystemdHealthChecker", "SystemdOwnershipObserver",
                "SystemdLifecycleExecutor", "ManagedHelperBundle")) {
            assertTrue(structure.contains(required), () -> "File.md is missing the current responsibility: " + required);
        }
    }

    private static Set<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static void assertMaximumLines(Path directory, long maximum) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                try (Stream<String> lines = Files.lines(file)) {
                    long count = lines.count();
                    assertTrue(count <= maximum, () -> file + " carries " + count + " lines across declared responsibilities");
                }
            }
        }
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !(Files.isRegularFile(current.resolve("pom.xml"))
                && Files.isRegularFile(current.resolve("docs/File.md")))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root was not found");
        return current;
    }
}
