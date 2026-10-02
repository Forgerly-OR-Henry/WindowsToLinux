package gold.debug.windowstolinux.app.main.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;

class PackageStructureArchitectureTest {
    // Fixed post-formatter caps, not growth allowances. / 格式展开后的固定上限，不提供后续增长余量。
    private static final Map<String, Long> FORMATTED_LINE_CAPS = Map.of("DesktopFrame.java", 445L,
        "00-protocol-foundation.sh", 306L, "65-database-backup.sh", 362L, "66-database-activation.sh", 303L,
        "17-managed-content.sh", 372L, "50-container-release.sh", 316L, "54-restore-candidate.sh", 410L,
        "56-restore-commit.sh", 317L);

    private static final Set<String> LEGACY_FILES = Set.of("StaticProjectAnalyzer.java",
        "DeploymentProjectAnalyzer.java", "DeploymentRuntimeInference.java", "DesktopPages.java",
        "DesktopDatabase.java", "DesktopRepository.java", "GitSnapshotService.java", "DeploymentBuildSupport.java",
        "SystemdRuntimeExecutor.java", "DeploymentSystemdUnitRenderer.java", "ManagedPrivilegeHelper.java",
        "ManagedSpringBootAnalysisCoordinator.java", "GradleSpringBootDeploymentInspector.java",
        "SpringBootProjectInspector.java", "ProjectAssessment.java", "SupportDecision.java",
        "SourceProjectFacts.java", "SourcePreparation.java", "DeploymentRequest.java",
        "ManagedDeploymentService.java", "DeploymentUseCase.java", "RemoteBuildResult.java",
        "LinuxBuildOperations.java", "LinuxReleaseOperations.java", "MavenBuildExecutor.java",
        "MavenBuildSupport.java", "ManagedReleaseProtocolExecutor.java", "ServiceDeploymentInspector.java",
        "DebianFamilySetupCatalog.java", "EnterpriseLinuxSetupCatalog.java");

    private static final Set<String> ANALYSIS_CORE = Set.of("DeploymentAnalysisCoordinator.java",
        "ProjectLanguageInspector.java");

    private static final Set<String> DESKTOP_SHELL = Set.of("DesktopDisplayChangeHandler.java", "DesktopFrame.java",
        "DesktopPageCoordinator.java", "DesktopViewState.java", "PageNavigationController.java",
        "DesktopLockController.java", "DesktopHeader.java");

    private static final Set<String> DEPLOYMENT_SHARED = Set.of("ReviewContext.java");

    private static final Set<String> DEPLOYMENT_AUTOMATIC = Set.of("DeploymentForm.java", "DeploymentPage.java",
        "DeploymentPageState.java", "DeploymentInputDialog.java", "DeploymentSourceCard.java",
        "DeploymentModeSelector.java", "DeploymentTaskControls.java");

    private static final Set<String> DEPLOYMENT_MULTI = Set.of("MultiComponentDraftController.java",
        "MultiComponentFormState.java", "MultiComponentPage.java", "MultiComponentPageState.java",
        "MultiComponentResultPresenter.java");

    private static final Set<String> REPOSITORIES = Set.of("AiProfileRepository.java", "AiPurposeRepository.java",
        "AgentTaskRepository.java", "BrowserRecoveryRepository.java", "ApplicationSecretRepository.java",
        "ConfigurationSnapshotRepository.java", "DesktopPreferenceRepository.java", "SecurityRepository.java",
        "ManagedApplicationRepository.java", "ManagedApplicationGraphRepository.java",
        "RepositoryTransactionExecutor.java", "ServerProfileRepository.java", "ExternalApplicationRepository.java");

    private static final Set<String> HELPER_FRAGMENTS = Set.of("27-agent-workspace.sh", "source-operations.py",
        "21-workspace-volume.sh", "22-restricted-build.sh", "23-container-builder.sh", "24-build-entry.sh",
        "25-workspace-recovery.sh", "26-build-output.sh", "12-container-image-input.sh", "61-service-identity.sh",
        "64-database-client.sh", "00-protocol-foundation.sh", "10-typed-release.sh", "15-deployment-input.sh",
        "16-application-input.sh", "17-managed-content.sh", "18-container-storage.sh", "20-candidate-workspace.sh",
        "41-application-runtime.sh", "42-application-job.sh", "43-application-container.sh",
        "44-application-health.sh", "45-application-container-contract.sh", "57-application-restore.sh",
        "35-ecosystem-dispatch.sh", "40-typed-runtime.sh", "50-container-release.sh", "52-container-recovery.sh",
        "55-podman-quadlet.sh", "60-lifecycle.sh", "54-restore-candidate.sh", "56-restore-commit.sh",
        "65-database-backup.sh", "66-database-activation.sh", "67-managed-backup.sh", "70-command-dispatch.sh",
        "10-release-metadata.py", "20-installation-boundaries.py", "30-managed-installation.py",
        "40-binding-protocol.py", "10-native-instances.sh", "20-native-targets.sh");

    private static final Pattern PERIOD_NAME = Pattern.compile("(?i)(?:phase|stage)[-_]?[0-9]+|(?:一期|二期|三期|四期|五期)");

    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
        "(?m)^(?:public\\s+)?(?:(?:final|abstract|sealed|non-sealed)\\s+)?(?:class|record|interface|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    private static final Pattern UNNORMALIZED_ACRONYM = Pattern.compile("[A-Z]{2,}(?![a-z])");

    private static final Set<String> FORBIDDEN_TYPE_SUFFIXES = Set.of("UseCases", "Locks", "Components", "Stores",
        "Transactions", "Profiles", "Checks", "Operations", "Settings", "Fixtures", "Impl");

    private static final Set<String> BUILD_ARCHITECTURE_PACKAGE_NAMES = Set.of("bundler", "cargo", "cmake", "composer",
        "dotnetsdk", "gradle", "gomodule", "jar", "jdk", "kotlinc", "maven", "npm", "phpcli", "pip", "pipenv",
        "pnpm", "poetry", "rubycli", "uv", "yarn");

    private static final Set<String> FORBIDDEN_PACKAGE_SEGMENTS = Set.of("util", "common", "misc", "impl");

    private static final Set<String> ENUM_SUFFIXES = Set.of("Action", "Decision", "Disposition", "Event", "Kind",
        "Level", "Mode", "Profile", "Scope", "Source", "State", "Status", "Type");

    private static final Set<String> STABLE_PUBLIC_ENUM_NAMES = Set.of("gold.debug.windowstolinux.appapi.ai.AiPurpose",
        "gold.debug.windowstolinux.appapi.ai.AiRole",
        "gold.debug.windowstolinux.appapi.linux.TransferConflictChoice",
        "gold.debug.windowstolinux.appapi.linux.TransferStallChoice",
        "gold.debug.windowstolinux.appapi.linux.TransferProgress.Stage",
        "gold.debug.windowstolinux.appapi.mod.StopReason",
        "gold.debug.windowstolinux.appapi.server.ReferenceResolution",
        "gold.debug.windowstolinux.appapi.task.LogStorage", "gold.debug.windowstolinux.appapi.task.Outcome",
        "gold.debug.windowstolinux.appapi.task.ProblemCode");

    private static final Set<String> STABLE_PUBLIC_RESTRICTED_NAMES = Set
        .of("gold.debug.windowstolinux.appapi.server.ServerInfo");

    private static final Set<String> OBSOLETE_ENUM_NAMES = Set.of("Access", "AiCollaborationRole", "AiResponseLanguage",
        "ContainerEngine", "DeploymentBuildTool", "DeploymentStep", "EvidenceConfidence", "HostSupport",
        "LanguageEcosystem", "LanguageFact", "LinuxDistro", "LinuxSecurityModule", "RuntimeInput",
        "SetupExpectation", "Severity", "SourceLanguage");

    private static final Set<String> RESTRICTED_TYPE_TOKENS = Set.of("Base", "Bean", "Checker", "Common", "Concrete",
        "Controller", "Data", "Generic", "Handler", "Helper", "Impl", "Implementation", "Info", "Legacy", "Manager",
        "Misc", "New", "Object", "Old", "Processor", "Support", "Temp", "Temporary", "Util", "Utils");

    private static final Set<String> ALLOWED_RESTRICTED_TYPE_NAMES = Set.of("AlmaLinuxSupportPolicy",
        "CentosStreamSupportPolicy", "ComponentDataPath", "DebianSupportPolicy", "ToolchainSupportCatalog",
        "ToolchainSupportCatalogTest", "DeploymentSupportCatalog", "DeploymentSupportLevel",
        "DeploymentSupportProfile", "DesktopDisplayChangeHandler", "DesktopWindowController",
        "DistributionSupportEvaluator", "DistributionSupportEvaluatorTest", "DistributionSupportPolicy",
        "DistributionSupportRules", "HostSupportDecision", "HostSupportEvaluator", "HostSupportEvaluatorTest",
        "HostSupportStatus", "ManagedHelperBundle", "ManagedHelperBundleTest", "ManagedHelperProtocol",
        "DeploymentManagedHelper", "DeploymentManagedHelperTest", "DeploymentManagedHelperRuntimeTest",
        "DeploymentManagedHelperBundle", "DeploymentManagedHelperBundleTest", "DeploymentHelperInstallerScript",
        "ManagedHelperProtocolVersion", "MultiComponentDraftController", "OracleLinuxSupportPolicy",
        "PageNavigationController", "RockyLinuxSupportPolicy", "UbuntuSupportPolicy", "DesktopLockController",
        "WebAiController", "WebApplicationController", "WebBackupController", "WebErrorController",
        "WebHealthController", "WebPreferenceController", "WebSecretController", "WebServerController",
        "WebSourceController", "WebTaskController");

    private static final Set<String> FORBIDDEN_RESOURCE_TOKENS = Set.of("bean", "checker", "common", "concrete",
        "generic", "impl", "implementation", "legacy", "misc", "new", "object", "old", "processor", "temp",
        "temporary", "util", "utils");

    private static final Set<String> VERSIONED_SCHEMA_RESOURCES = Set.of(
        "src/app/db/src/main/resources/gold/debug/windowstolinux/app/db/schema/platform-v1.sql",
        "src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql",
        "src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/db/schema-v2.sql",
        "src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/db/schema-v3.sql",
        "src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/db/validation-v1.sql",
        "src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/db/validation-v2.sql",
        "src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/db/validation-v3.sql");

    private static final Set<String> DISTRO_CLASSIFICATION_PACKAGES = Set.of("apt", "contract", "dnf", "extension",
        "generation");

    private static final Set<String> ECOSYSTEM_CLASSIFICATION_PACKAGES = Set.of("c", "java", "node", "python", "go",
        "rust", "dotnet", "kotlin", "php", "ruby");

    private static final Set<String> DELETED_WRAPPERS = Set.of("GoServiceDeploymentInspector",
        "RustServiceDeploymentInspector", "DotNetServiceDeploymentInspector", "RustBuildRenderer",
        "DotNetBuildRenderer", "KotlinBuildRenderer", "PhpBuildRenderer", "RubyBuildRenderer", "UbuntuSetup",
        "DebianSetup", "CentosStreamSetup", "RockyLinuxSetup", "AlmaLinuxSetup", "OracleLinuxSetup",
        "ServiceBuildRenderer", "SpringBootBuildRenderer", "NodeBuildRenderer", "PythonBuildRenderer",
        "EcosystemBuildScript");

    private static final List<String> OLD_PACKAGE_PREFIXES = List.of("gold.debug.windowstolinux.shared.model.db",
        "gold.debug.windowstolinux.shared.standard.analyze.db", "gold.debug.windowstolinux.shared.linux.db",
        "gold.debug.windowstolinux.shared.linux.sshd.db", "gold.debug.windowstolinux.shared.linux.sshd.ecosystem",
        "gold.debug.windowstolinux.shared.ai.prompt",
        "gold.debug.windowstolinux.shared.standard.analyze.policy",
        "gold.debug.windowstolinux.shared.standard.analyze.registry",
        "gold.debug.windowstolinux.shared.standard.analyze.spi",
        "gold.debug.windowstolinux.shared.config.definition", "gold.debug.windowstolinux.shared.deploy.adapter",
        "gold.debug.windowstolinux.shared.deploy.environment", "gold.debug.windowstolinux.shared.deploy.lifecycle",
        "gold.debug.windowstolinux.shared.deploy.registry", "gold.debug.windowstolinux.shared.deploy.result",
        "gold.debug.windowstolinux.shared.deploy.spi", "gold.debug.windowstolinux.shared.deploy.transaction",
        "gold.debug.windowstolinux.shared.source.validation",
        "gold.debug.windowstolinux.shared.linux.sshd.build.registry",
        "gold.debug.windowstolinux.shared.linux.sshd.build.script",
        "gold.debug.windowstolinux.shared.linux.sshd.build.spi",
        "gold.debug.windowstolinux.shared.linux.sshd.distro.profile",
        "gold.debug.windowstolinux.shared.linux.sshd.distro.registry",
        "gold.debug.windowstolinux.shared.linux.sshd.distro.script",
        "gold.debug.windowstolinux.shared.linux.sshd.protocol",
        "gold.debug.windowstolinux.shared.linux.sshd.transfer", "gold.debug.windowstolinux.app.secret.api",
        "gold.debug.windowstolinux.app.secret.store", "gold.debug.windowstolinux.app.secret.windows",
        "gold.debug.windowstolinux.shared.git.reference", "gold.debug.windowstolinux.shared.git.remote",
        "gold.debug.windowstolinux.shared.standard.analyze.source.metadata",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.jvm",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.ProjectLanguageInspector",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.ServiceDeploymentInspector",
        "gold.debug.windowstolinux.shared.standard.analyze.workload.container",
        "gold.debug.windowstolinux.shared.standard.analyze.workload.staticweb",
        "gold.debug.windowstolinux.shared.deploy.adapter.service",
        "gold.debug.windowstolinux.shared.deploy.adapter.workload",
        "gold.debug.windowstolinux.shared.deploy.support.HostSupportStatus;",
        "gold.debug.windowstolinux.shared.deploy.support.DistributionSupportPolicy;",
        "gold.debug.windowstolinux.shared.deploy.support.DistributionSupportRules;",
        "gold.debug.windowstolinux.shared.deploy.support.DistributionSupportRegistry;",
        "gold.debug.windowstolinux.shared.deploy.support.UbuntuSupportPolicy;",
        "gold.debug.windowstolinux.shared.deploy.support.DebianSupportPolicy;",
        "gold.debug.windowstolinux.shared.deploy.support.CentosStreamSupportPolicy;",
        "gold.debug.windowstolinux.shared.deploy.support.RockyLinuxSupportPolicy;",
        "gold.debug.windowstolinux.shared.deploy.support.AlmaLinuxSupportPolicy;",
        "gold.debug.windowstolinux.shared.deploy.support.OracleLinuxSupportPolicy;",
        "gold.debug.windowstolinux.shared.linux.sshd.workload",
        "gold.debug.windowstolinux.shared.linux.sshd.build.config",
        "gold.debug.windowstolinux.shared.linux.sshd.build.shell",
        "gold.debug.windowstolinux.shared.linux.sshd.build.renderer",
        "gold.debug.windowstolinux.shared.linux.sshd.capability.probe",
        "gold.debug.windowstolinux.shared.linux.sshd.distro.script.EcosystemCapabilityScriptRenderer",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.java.JavaJarDeploymentInspector",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.dotnet.DotNetSdkDeploymentInspector",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.dotnet.DotNetSdkFacts",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.go.GoModuleDeploymentInspector",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.go.GoModuleFacts",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.kotlin.KotlinGradleDeploymentInspector",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.kotlin.KotlinGradleFacts",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.php.PhpComposerDeploymentInspector",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.php.PhpComposerFacts",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.ruby.RubyBundlerDeploymentInspector",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.ruby.RubyBundlerFacts",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.rust.RustCargoDeploymentInspector",
        "gold.debug.windowstolinux.shared.standard.analyze.ecosystem.rust.RustCargoFacts",
        "gold.debug.windowstolinux.shared.linux.sshd.distro.setup",
        "gold.debug.windowstolinux.shared.linux.sshd.protocol.workspace",
        "gold.debug.windowstolinux.shared.linux.sshd.runtime.container",
        "gold.debug.windowstolinux.shared.linux.sshd.runtime.dispatch",
        "gold.debug.windowstolinux.app.service.port", "gold.debug.windowstolinux.app.service.locking",
        "gold.debug.windowstolinux.app.ui.settings", "gold.debug.windowstolinux.shared.ai.client.RoleChatTransport",
        "gold.debug.windowstolinux.shared.ai.client.HttpRoleChatTransport",
        "gold.debug.windowstolinux.shared.ai.client.RoleChatResult",
        "gold.debug.windowstolinux.shared.ai.parser.AiStructuralAssessment",
        "gold.debug.windowstolinux.shared.linux.sshd.distro.execution");

    private static final Set<String> MIGRATED_PACKAGE_PREFIXES = Set.of("gold.debug.windowstolinux.shared.model.db",
        "gold.debug.windowstolinux.shared.standard.analyze.db", "gold.debug.windowstolinux.shared.linux.db",
        "gold.debug.windowstolinux.shared.linux.sshd.db", "gold.debug.windowstolinux.shared.linux.sshd.ecosystem",
        "gold.debug.windowstolinux.shared.ai.prompt",
        "gold.debug.windowstolinux.shared.standard.analyze.policy",
        "gold.debug.windowstolinux.shared.standard.analyze.registry",
        "gold.debug.windowstolinux.shared.standard.analyze.spi",
        "gold.debug.windowstolinux.shared.config.definition", "gold.debug.windowstolinux.shared.deploy.adapter",
        "gold.debug.windowstolinux.shared.deploy.environment", "gold.debug.windowstolinux.shared.deploy.lifecycle",
        "gold.debug.windowstolinux.shared.deploy.registry", "gold.debug.windowstolinux.shared.deploy.result",
        "gold.debug.windowstolinux.shared.deploy.spi", "gold.debug.windowstolinux.shared.deploy.transaction",
        "gold.debug.windowstolinux.shared.source.validation",
        "gold.debug.windowstolinux.shared.linux.sshd.build.registry",
        "gold.debug.windowstolinux.shared.linux.sshd.build.script",
        "gold.debug.windowstolinux.shared.linux.sshd.build.spi",
        "gold.debug.windowstolinux.shared.linux.sshd.distro.profile",
        "gold.debug.windowstolinux.shared.linux.sshd.distro.registry",
        "gold.debug.windowstolinux.shared.linux.sshd.distro.script",
        "gold.debug.windowstolinux.shared.linux.sshd.protocol",
        "gold.debug.windowstolinux.shared.linux.sshd.transfer");

    private static final Map<String, String> RESPONSIBILITY_PACKAGES = responsibilityPackages();

    private static final Map<String, String> PREVIOUS_RESPONSIBILITY_PACKAGES = previousResponsibilityPackages();

    private static final Set<String> RESPONSIBILITY_ROOTS = Set.of("gold.debug.windowstolinux.shared.ai.collaboration",
        "gold.debug.windowstolinux.shared.deploy.contract.result", "gold.debug.windowstolinux.shared.model.project",
        "gold.debug.windowstolinux.shared.model.language", "gold.debug.windowstolinux.shared.model.server",
        "gold.debug.windowstolinux.app.service.deployment", "gold.debug.windowstolinux.app.ui.deployment");

    private static final List<PackageDependencyRule> FORBIDDEN_RESPONSIBILITY_DEPENDENCIES = List.of(
        new PackageDependencyRule("gold.debug.windowstolinux.shared.ai.collaboration.advice",
            Set.of("gold.debug.windowstolinux.shared.ai.collaboration",
                "gold.debug.windowstolinux.shared.ai.collaboration.invocation",
                "gold.debug.windowstolinux.shared.ai.collaboration.role")),
        new PackageDependencyRule("gold.debug.windowstolinux.shared.ai.collaboration.role",
            Set.of("gold.debug.windowstolinux.shared.ai.collaboration",
                "gold.debug.windowstolinux.shared.ai.collaboration.advice",
                "gold.debug.windowstolinux.shared.ai.collaboration.invocation")),
        new PackageDependencyRule("gold.debug.windowstolinux.shared.ai.collaboration.invocation",
            Set.of("gold.debug.windowstolinux.shared.ai.collaboration")),
        new PackageDependencyRule("gold.debug.windowstolinux.app.ui.deployment",
            Set.of("gold.debug.windowstolinux.app.ui.deployment.automatic",
                "gold.debug.windowstolinux.app.ui.deployment.multi")),
        new PackageDependencyRule("gold.debug.windowstolinux.app.ui.deployment.automatic",
            Set.of("gold.debug.windowstolinux.app.ui.deployment.multi")),
        new PackageDependencyRule("gold.debug.windowstolinux.app.ui.deployment.multi",
            Set.of("gold.debug.windowstolinux.app.ui.deployment.automatic")),
        new PackageDependencyRule("gold.debug.windowstolinux.app.service.deployment.single",
            Set.of("gold.debug.windowstolinux.app.service.deployment",
                "gold.debug.windowstolinux.app.service.deployment.multi")),
        new PackageDependencyRule("gold.debug.windowstolinux.app.service.deployment.multi",
            Set.of("gold.debug.windowstolinux.app.service.deployment",
                "gold.debug.windowstolinux.app.service.deployment.single")),
        new PackageDependencyRule("gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility",
            Set.of("gold.debug.windowstolinux.shared.deploy.contract.result.deployment",
                "gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle")),
        new PackageDependencyRule("gold.debug.windowstolinux.shared.deploy.contract.result.deployment",
            Set.of("gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility",
                "gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle")),
        new PackageDependencyRule("gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle",
            Set.of("gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility",
                "gold.debug.windowstolinux.shared.deploy.contract.result.deployment")),
        new PackageDependencyRule("gold.debug.windowstolinux.shared.model.language",
            Set.of("gold.debug.windowstolinux.shared.model.project",
                "gold.debug.windowstolinux.shared.model.project.component")),
        new PackageDependencyRule("gold.debug.windowstolinux.shared.model.server.security",
            Set.of("gold.debug.windowstolinux.shared.model.server")));

    private static volatile SourceModel cachedSourceModel;

    @Test
    void legacyResponsibilityMonolithsCannotReturn() throws Exception {
        Path root = projectRoot();
        try (Stream<Path> files = activeFiles()) {
            List<Path> legacy = files.filter(Files::isRegularFile)
                .filter(path -> LEGACY_FILES.contains(path.getFileName().toString())).toList();
            assertTrue(legacy.isEmpty(), () -> "legacy responsibility files returned: " + legacy);
        }
        assertFalse(Files.exists(root.resolve(
            "src/shared/linux-sshd/src/main/resources/gold/debug/windowstolinux/shared/linux/sshd/protocol/managed-helper")));
        assertFalse(Files.exists(root.resolve(
            "src/shared/linux-sshd/src/main/resources/gold/debug/windowstolinux/shared/linux/sshd/protocol/helper")));
    }

    @Test
    void coreShellRepositoriesAndHelperResourcesStayFocused() throws Exception {
        Path root = projectRoot();
        assertEquals(ANALYSIS_CORE, fileNames(root.resolve(
            "src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/core")));
        assertEquals(Set.of("DesktopLockController.java", "PlatformFrame.java", "PlatformNavigationShell.java"),
            fileNames(root.resolve("src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/window")));
        for (var entry : Map.ofEntries(
            Map.entry("ai/AiProfileRepository.java", "ai/StoredAiProviderProfile.java"),
            Map.entry("server/ServerProfileRepository.java", "server/StoredServerProfile.java"),
            Map.entry("security/SecurityRepository.java", "security/StoredSecurityProfile.java"),
            Map.entry("setting/DesktopPreferenceRepository.java", "sshrecover/BrowserRecoveryRepository.java"),
            Map.entry("connect/RepositoryTransactionExecutor.java", "schema/SchemaProvisioner.java")).entrySet()) {
            for (String relative : List.of(entry.getKey(), entry.getValue()))
                assertTrue(Files.isRegularFile(root.resolve(
                    "src/app/db/src/main/java/gold/debug/windowstolinux/app/db/" + relative)), relative);
        }
        var modules = MavenModuleGraph.read(root);
        assertEquals(
            Set.of("appapi", "mod/deployment", "app/ai", "app/db", "app/linux", "app/main", "app/mod", "app/secret",
                "app/service", "app/file", "app/ui", "app/windows"),
            modules.stream().filter(module -> !module.aggregate()).map(MavenModuleGraph.Module::path)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(modules.stream()
            .noneMatch(module -> module.path().startsWith("shared/") || module.path().equals("web")));
    }

    @Test
    void deploymentHelperResourcesMatchTheExactMigratedContract() throws Exception {
        Path root = projectRoot();
        Path resources = root
            .resolve("src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/linux");
        Set<String> expected = new HashSet<>(HELPER_FRAGMENTS);
        expected.addAll(Set.of("53-publication-observation.sh", "58-retained-activation.sh", "59-handoff-restore.sh",
            "59-handoff-database-restore.sh", "59-handoff-restore-cleanup.sh", "68-handoff-database.sh",
            "69-handoff-backup-status.sh", "55-publication-snapshot.sh", "21-native-operations.sh",
            "63-database-inspection.sh", "41-application-admission.sh", "56-restore-recovery.sh",
            "apparmor-namespace.sh", "systemd-manager-isolation.sh", "selinux-command-entry.sh",
            "centos-source-repositories.py"));
        assertEquals(58, expected.size());
        assertEquals(expected, fileNamesRecursively(resources));
        assertEquals(Set.of("source-operations.py"), fileNames(resources.resolve("workspace")));
        assertEquals(Set.of("10-release-metadata.py", "20-installation-boundaries.py", "30-managed-installation.py",
            "40-binding-protocol.py"), fileNames(resources.resolve("toolchain")));
        var actual = new HashSet<String>();
        try (var files = Files.walk(resources)) {
            files.filter(Files::isRegularFile)
                .forEach(file -> actual.add(resources.relativize(file).toString().replace('\\', '/')));
        }
        var packaged = new HashSet<String>();
        String bundle = Files.readString(root.resolve(
            "src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/linux/DeploymentManagedHelperBundle.java"));
        String declaredResources = bundle.substring(0, bundle.indexOf("private DeploymentManagedHelperBundle()"));
        var references = Pattern.compile("\"([a-z0-9/.-]+\\.(?:sh|py))\"").matcher(declaredResources);
        while (references.find())
            packaged.add(references.group(1));
        assertEquals(actual, packaged, "Every maintained helper resource must be explicitly assembled or inserted");
        assertMaximumLinesRecursively(resources, 300);
        Set<String> changed = Set.of("execution/protocol/helper/fragments/00-protocol-foundation.sh",
            "execution/protocol/helper/fragments/70-command-dispatch.sh",
            "execution/protocol/helper/fragments/backup/67-managed-backup.sh",
            "execution/protocol/helper/fragments/backup/69-handoff-backup-status.sh",
            "execution/protocol/helper/fragments/database/20-native-targets.sh",
            "execution/protocol/helper/fragments/database/21-native-operations.sh",
            "execution/protocol/helper/fragments/database/63-database-inspection.sh",
            "execution/protocol/helper/fragments/database/65-database-backup.sh",
            "execution/protocol/helper/fragments/database/68-handoff-database.sh",
            "execution/protocol/helper/fragments/release/52-container-recovery.sh",
            "execution/protocol/helper/fragments/release/53-publication-observation.sh",
            "execution/protocol/helper/fragments/release/55-publication-snapshot.sh",
            "execution/protocol/helper/fragments/release/58-retained-activation.sh",
            "execution/protocol/helper/fragments/restore/56-restore-commit.sh",
            "execution/protocol/helper/fragments/restore/56-restore-recovery.sh",
            "execution/protocol/helper/fragments/restore/59-handoff-database-restore.sh",
            "execution/protocol/helper/fragments/restore/59-handoff-restore-cleanup.sh",
            "execution/protocol/helper/fragments/restore/59-handoff-restore.sh",
            "execution/protocol/helper/fragments/runtime/40-typed-runtime.sh",
            "execution/protocol/helper/fragments/runtime/41-application-admission.sh",
            "execution/protocol/helper/fragments/runtime/41-application-runtime.sh");
        List<Path> unchanged;
        try (Stream<Path> files = Files.walk(resources)) {
            unchanged = files.filter(Files::isRegularFile)
                .filter(file -> !changed.contains(resources.relativize(file).toString().replace('\\', '/')))
                .toList();
        }
        assertEquals(37, unchanged.size());
        var baseline = new java.util.Properties();
        try (var input = getClass().getResourceAsStream("/architecture/publication-resource-baseline.properties")) {
            assertTrue(input != null, "Committed baseline resource proofs must be present");
            baseline.load(input);
        }
        Set<String> unchangedPaths = unchanged.stream()
            .map(file -> resources.relativize(file).toString().replace('\\', '/'))
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(unchangedPaths, baseline.stringPropertyNames(),
            "Every unchanged resource requires its committed byte identity");
        for (Path file : unchanged) {
            String relative = resources.relativize(file).toString().replace('\\', '/');
            String digest = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            assertEquals(baseline.getProperty(relative), digest, relative);
        }
    }

    @Test
    void productionNamesUseStableResponsibilitiesAndMatchFileDocumentation() throws Exception {
        Path root = projectRoot();
        try (Stream<Path> files = activeFiles()) {
            var periodNames = files.filter(path -> path.toString().contains("src" + java.io.File.separator + "main"))
                .filter(path -> !root.relativize(path).toString().replace('\\', '/').startsWith("src/mod/"))
                .map(root::relativize).map(Path::toString).filter(name -> PERIOD_NAME.matcher(name).find())
                .toList();
            assertTrue(periodNames.isEmpty(), () -> "production paths contain period names: " + periodNames);
        }
        String structure = Files.readString(root.resolve("docs/File.md"));
        assertTrue(structure.contains("12 个 Maven 叶子模块"));
        for (String required : List.of("db/connect", "linux/sftp", "service/core/model", "appapi",
            "mod/deployment"))
            assertTrue(structure.contains(required), () -> "File.md is missing the active responsibility: " + required);
        for (String required : List.of("app/main/src/main/java/gold/debug/windowstolinux/app/main/start",
            "app/db/src/main/java/gold/debug/windowstolinux/app/db/schema",
            "app/ai/src/main/java/gold/debug/windowstolinux/app/ai/http",
            "app/mod/src/main/java/gold/debug/windowstolinux/app/mod/run",
            "mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/core",
            "mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/linux/execution/protocol/helper/fragments/ecosystem"))
            assertTrue(Files.isDirectory(root.resolve("src/" + required)),
                () -> "current responsibility package is missing: " + required);
    }

    private static Map<String, String> responsibilityPackages() {
        Map<String, String> packages = new LinkedHashMap<>();
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration", "AiDecisionCoordinator",
            "AiCollaborationDecision", "CollaborationDisposition", "DeterministicDecision");
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration.advice", "AiAdviceDecision",
            "RoleAdviceAssessment");
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration.invocation", "AiInvocationEvidence",
            "AiInvocationStatus", "AiRoleInvocationResult", "AiProviderAttempt");
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration.role", "AiCollaborationRoleKind",
            "AiRoleBinding", "AiRoleContext", "ProjectAnalysisRoleContext", "DeploymentRiskRoleContext",
            "ErrorExplanationRoleContext");

        register(packages, "gold.debug.windowstolinux.app.ui.deployment", "ReviewContext");
        register(packages, "gold.debug.windowstolinux.app.ui.deployment.automatic", "DeploymentForm", "DeploymentPage",
            "DeploymentPageState");
        register(packages, "gold.debug.windowstolinux.app.ui.deployment.multi", "MultiComponentDraftController",
            "MultiComponentFormState", "MultiComponentPage", "MultiComponentPageState",
            "MultiComponentResultPresenter");

        register(packages, "gold.debug.windowstolinux.app.service.deployment.automatic", "AutomaticDatabaseUseCase",
            "AutomaticDeploymentUseCase", "AutomaticInputCompletion", "DeploymentFormUseCase",
            "ComponentFormUseCase", "AssistedDeploymentInteraction", "AssistedManagedToolCatalog",
            "AssistedDeploymentAdvisor", "AutomaticActionDescription", "AssistedDeploymentBoundary",
            "AutomaticDeploymentTaskService", "DeploymentCandidateToolService", "DeploymentDiagnosticEvidence");
        register(packages, "gold.debug.windowstolinux.shared.deploy.execution.environment", "DatabaseInstanceResolver",
            "NativeDatabasePreparationService");
        register(packages, "gold.debug.windowstolinux.shared.deploy.input", "AutomaticRuntimeResolver",
            "DeploymentRuntimeParser");
        register(packages, "gold.debug.windowstolinux.shared.config.input", "DeploymentConfigurationParser");
        register(packages, "gold.debug.windowstolinux.app.ui.deployment.automatic", "DeploymentInputDialog",
            "DeploymentSourceCard", "DeploymentModeSelector", "DeploymentTaskControls");
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration.role", "DeploymentInputRoleContext");
        register(packages, "gold.debug.windowstolinux.app.service.deployment", "DeploymentInspectionUseCase",
            "ManagedApplicationIdentityResolver", "ReviewedDeploymentUseCase", "MultiComponentDeploymentUseCase",
            "MultiComponentLifecycleUseCase", "AutonomousDeploymentUseCase");
        register(packages, "gold.debug.windowstolinux.app.service.deployment.single", "DeploymentHandoff",
            "DeploymentOutcome");
        register(packages, "gold.debug.windowstolinux.app.service.deployment.multi", "ManagedMultiComponentApplication",
            "ReviewedComponentApplication", "ReviewedMultiComponentApplication");

        register(packages, "gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility",
            "HostSupportStatus", "HostSupportDecision");
        register(packages, "gold.debug.windowstolinux.shared.deploy.contract.result.deployment",
            "ComponentDeploymentResult", "ComponentTransactionState", "DeploymentEvent", "DeploymentResult",
            "MultiComponentDeploymentResult");
        register(packages, "gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle",
            "ComponentLifecycleResult", "LifecycleActionResult", "MultiComponentLifecycleResult");

        register(packages, "gold.debug.windowstolinux.shared.model.language", "LanguageEcosystemType",
            "LanguageFactKind", "ProjectLanguageFacts", "SourceLanguageType");
        register(packages, "gold.debug.windowstolinux.shared.model.project", "DeploymentArchitectureType",
            "DeploymentBuildToolType", "DeploymentProjectFacts", "DeploymentProjectType",
            "DeploymentRuntimeSpecification", "DeploymentRuntimeAssessment", "DeploymentSupportCatalog",
            "RuntimeIdentityMode", "DeploymentSupportLevel", "DeploymentSupportProfile", "SourceRevision",
            "ValidatedDeploymentTarget");
        register(packages, "gold.debug.windowstolinux.shared.model.project.component", "ComponentDataPath",
            "ComponentIsolationSpecification", "DeploymentComponent");
        register(packages, "gold.debug.windowstolinux.shared.model.project.application", "ApplicationCommand",
            "ApplicationCompanion", "ApplicationEndpoint", "ApplicationInput", "ApplicationWorker",
            "ApplicationWorkload");
        register(packages, "gold.debug.windowstolinux.shared.model.server", "CpuMicroarchitectureLevel",
            "LinuxDistroType", "ManagedHelperProtocolVersion", "ServerIdentity");
        register(packages, "gold.debug.windowstolinux.shared.model.server.security", "LinuxSecurityModuleType",
            "LinuxSecurityState", "LinuxSecurityPosture", "LinuxFirewallKind", "LinuxFirewallState",
            "SelinuxPreparationPlan", "SelinuxPreparationState");
        return Map.copyOf(packages);
    }

    private static Map<String, String> previousResponsibilityPackages() {
        Map<String, String> packages = new LinkedHashMap<>();
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration", "AiAdviceDecision",
            "RoleAdviceAssessment", "AiInvocationEvidence", "AiInvocationStatus", "AiRoleInvocationResult",
            "AiProviderAttempt", "AiCollaborationRoleKind", "AiRoleBinding", "AiRoleContext",
            "ProjectAnalysisRoleContext", "DeploymentRiskRoleContext", "ErrorExplanationRoleContext");
        register(packages, "gold.debug.windowstolinux.app.ui.deployment", "DeploymentForm", "DeploymentPage",
            "DeploymentPageState", "MultiComponentDraftController", "MultiComponentFormState", "MultiComponentPage",
            "MultiComponentPageState", "MultiComponentResultPresenter");
        register(packages, "gold.debug.windowstolinux.app.service.deployment", "DeploymentHandoff", "DeploymentOutcome",
            "ManagedMultiComponentApplication", "MultiComponentReviewInput", "ReviewedComponentApplication",
            "ReviewedMultiComponentApplication");
        register(packages, "gold.debug.windowstolinux.shared.deploy.result", "HostSupportStatus", "HostSupportDecision",
            "ComponentDeploymentResult", "ComponentTransactionState", "DeploymentEvent", "DeploymentResult",
            "MultiComponentDeploymentResult", "ComponentLifecycleResult", "LifecycleActionResult",
            "MultiComponentLifecycleResult");
        register(packages, "gold.debug.windowstolinux.shared.model.project", "LanguageEcosystemType",
            "LanguageFactKind", "ProjectLanguageFacts", "SourceLanguageType");
        register(packages, "gold.debug.windowstolinux.shared.model.server", "LinuxSecurityModuleType",
            "LinuxSecurityState", "LinuxSecurityPosture", "LinuxFirewallKind", "LinuxFirewallState");
        return Map.copyOf(packages);
    }

    private static void register(Map<String, String> packages, String packageName, String... typeNames) {
        for (String typeName : typeNames) {
            String fileName = typeName + ".java";
            String previous = packages.put(fileName, packageName);
            if (previous != null)
                throw new IllegalStateException("duplicate responsibility type " + fileName);
        }
    }

    private static SourceModel sourceModel() throws IOException {
        SourceModel current = cachedSourceModel;
        if (current != null)
            return current;
        synchronized (PackageStructureArchitectureTest.class) {
            if (cachedSourceModel == null)
                cachedSourceModel = loadSourceModel();
            return cachedSourceModel;
        }
    }

    private static SourceModel loadSourceModel() throws IOException {
        List<SourceUnit> units = loadSourceUnits(
            path -> path.toString().replace('\\', '/').contains("/src/main/java/"));
        List<Path> sources = units.stream().map(SourceUnit::path).toList();

        Map<String, PackageStructureState> packages = new LinkedHashMap<>();
        for (SourceUnit unit : units) {
            Path javaRoot = sourceRoot(unit.path, "main");
            Path module = javaRoot.getParent().getParent().getParent();
            String modulePath = projectRoot().resolve("src").relativize(module).toString().replace('\\', '.')
                .replace('/', '.').replace('-', '.');
            String modulePackage = "gold.debug.windowstolinux." + modulePath;
            if (!Files.isRegularFile(module.resolve("pom.xml"))
                || !unit.packageName.startsWith(modulePackage + ".") && !unit.packageName.equals(modulePackage))
                throw new IllegalStateException("source package does not belong to its Maven module: " + unit.path);
            int depth = unit.packageName.split("\\.").length - modulePackage.split("\\.").length;
            int topLevelTypes = (int) unit.tree.getTypeDecls().stream().filter(ClassTree.class::isInstance).count();
            packages.computeIfAbsent(unit.packageName,
                ignored -> new PackageStructureState(unit.packageName, depth)).topLevelTypes += topLevelTypes;
        }
        Map<String, PackageStructureFacts> immutablePackages = new LinkedHashMap<>();
        packages.values().forEach(info -> immutablePackages.put(info.name,
            new PackageStructureFacts(info.name, info.depth, info.topLevelTypes)));
        return new SourceModel(List.copyOf(sources), List.copyOf(units), Map.copyOf(immutablePackages));
    }

    private static List<SourceUnit> loadSourceUnits(Predicate<Path> included) throws IOException {
        Path root = projectRoot();
        List<Path> sources;
        try (Stream<Path> files = activeFiles()) {
            sources = files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java"))
                .filter(included).sorted().toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
            throw new IllegalStateException("PackageStructureArchitectureTest requires a JDK compiler");
        List<SourceUnit> units = new ArrayList<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> javaFiles = fileManager.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null, List.of("-proc:none", "-Xlint:none"),
                null, javaFiles);
            for (CompilationUnitTree tree : task.parse()) {
                Path path = Path.of(tree.getSourceFile().toUri()).toAbsolutePath().normalize();
                String packageName = tree.getPackageName().toString();
                Set<String> references = new LinkedHashSet<>();
                tree.getImports().stream().map(ImportTree::getQualifiedIdentifier).map(Object::toString)
                    .forEach(references::add);
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMemberSelect(com.sun.source.tree.MemberSelectTree node, Void unused) {
                        if (node.toString()
                            .matches("gold\\.debug\\.windowstolinux\\..*\\.[A-Z][A-Za-z0-9_]*(?:\\..*)?"))
                            references.add(node.toString());
                        return super.visitMemberSelect(node, unused);
                    }
                }.scan(tree, null);
                List<String> imports = List.copyOf(references);
                units.add(new SourceUnit(path, packageName, imports, tree));
            }
        }
        return List.copyOf(units);
    }

    private static Stream<Path> activeFiles() throws IOException {
        List<Path> paths = new ArrayList<>();
        try {
            for (var module : MavenModuleGraph.read(projectRoot())) {
                if (module.aggregate())
                    continue;
                for (String kind : List.of("main", "test")) {
                    Path source = module.pom().getParent().resolve("src/" + kind);
                    if (Files.isDirectory(source))
                        try (Stream<Path> files = Files.walk(source)) {
                            paths.addAll(files.filter(Files::isRegularFile).toList());
                        }
                }
            }
        } catch (Exception failure) {
            throw new IOException("Cannot enumerate the active Maven source graph", failure);
        }
        return paths.stream().sorted();
    }

    private static Optional<String> resolveImportedPackage(String imported, Collection<String> packages) {
        return packages.stream().filter(candidate -> imported.equals(candidate) || imported.startsWith(candidate + "."))
            .max(Comparator.comparingInt(String::length));
    }

    private static Optional<List<String>> findCycle(Map<String, Set<String>> graph) {
        Map<String, Integer> states = new HashMap<>();
        List<String> stack = new ArrayList<>();
        for (String node : graph.keySet()) {
            Optional<List<String>> cycle = findCycle(node, graph, states, stack);
            if (cycle.isPresent())
                return cycle;
        }
        return Optional.empty();
    }

    private static Optional<List<String>> findCycle(String node, Map<String, Set<String>> graph,
                                                    Map<String, Integer> states, List<String> stack) {
        int state = states.getOrDefault(node, 0);
        if (state == 2)
            return Optional.empty();
        if (state == 1) {
            int start = stack.indexOf(node);
            List<String> cycle = new ArrayList<>(stack.subList(start, stack.size()));
            cycle.add(node);
            return Optional.of(List.copyOf(cycle));
        }
        states.put(node, 1);
        stack.add(node);
        for (String dependency : graph.getOrDefault(node, Set.of())) {
            Optional<List<String>> cycle = findCycle(dependency, graph, states, stack);
            if (cycle.isPresent())
                return cycle;
        }
        stack.removeLast();
        states.put(node, 2);
        return Optional.empty();
    }

    private static Path sourceRoot(Path source, String kind) {
        Path current = source.toAbsolutePath().normalize();
        while (current != null) {
            if (current.getFileName() != null && current.getFileName().toString().equals("java")
                && current.getParent() != null && current.getParent().getFileName().toString().equals(kind)
                && current.getParent().getParent() != null
                && current.getParent().getParent().getFileName().toString().equals("src")) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException("source root was not found for " + source);
    }

    private static Set<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    @Test
    void acronymChecksRespectPascalCaseWordBoundaries() {
        for (String name : List.of("CLanguageInspector", "CLanguageInspectorTest", "AiPage", "SshSession",
            "JavaJarManifestInspector")) {
            assertFalse(UNNORMALIZED_ACRONYM.matcher(name).find(), name);
        }
        for (String name : List.of("AILanguageInspector", "SSHSession", "JavaJARInspector", "UIPage", "CMAKEInspector",
            "ProjectHTTP")) {
            assertTrue(UNNORMALIZED_ACRONYM.matcher(name).find(), name);
        }
    }

    @Test
    void sourceAndTestNamesFollowTheUnifiedNamingStandard() throws Exception {
        Path root = projectRoot();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = activeFiles()) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java"))
                .toList()) {
                String content = Files.readString(source);
                String kind = source.toString().replace('\\', '/').contains("/src/test/java/") ? "test" : "main";
                Path expectedPath = sourceRoot(source, kind).resolve(
                    declaredPackage(content).replace('.', java.io.File.separatorChar))
                    .resolve(source.getFileName()).toAbsolutePath().normalize();
                if (!source.equals(expectedPath))
                    problems.add(source + " does not match its declared package path " + expectedPath);
                var declaration = TOP_LEVEL_TYPE.matcher(content);
                if (!declaration.find()) {
                    if (source.getFileName().toString().equals("package-info.java")
                        && content.matches("(?s).*\\bpackage\\s+[a-zA-Z0-9_.]+\\s*;\\s*"))
                        continue;
                    problems.add(source + " has no top-level type declaration");
                    continue;
                }
                String typeName = declaration.group(1);
                String fileName = source.getFileName().toString();
                if (!fileName.equals(typeName + ".java")) {
                    problems.add(source + " does not match top-level type " + typeName);
                }
                if (isModPackage(declaredPackage(content)))
                    continue;
                if (UNNORMALIZED_ACRONYM.matcher(typeName).find()) {
                    problems.add(typeName + " contains an unnormalized uppercase abbreviation");
                }
                FORBIDDEN_TYPE_SUFFIXES.stream().filter(typeName::endsWith)
                    .forEach(suffix -> problems.add(typeName + " uses forbidden type suffix " + suffix));
                if (source.toString()
                    .contains("src" + java.io.File.separator + "test" + java.io.File.separator + "java")) {
                    if (fileName.endsWith("IT.java")) {
                        problems.add(fileName + " uses the obsolete IT suffix");
                    }
                    boolean executableTest = hasExecutableTestAnnotation(content);
                    if (executableTest && !fileName.endsWith("Test.java")) {
                        problems.add(fileName + " is executable test code without a Test suffix");
                    }
                    boolean optionalAcceptance = Pattern.compile("(?m)^\\s*@EnabledIfSystemProperty\\b")
                        .matcher(content).find();
                    if (optionalAcceptance && !fileName.endsWith("AcceptanceTest.java")) {
                        problems.add(fileName + " is optional live acceptance without an AcceptanceTest suffix");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "unified naming violations: " + problems);
    }

    @Test
    void testConfigurationDoesNotCountAsAnExecutableTest() {
        assertFalse(hasExecutableTestAnnotation("@TestConfiguration class Example { }"));
        assertTrue(hasExecutableTestAnnotation("@Test void sample() { }"));
        assertTrue(hasExecutableTestAnnotation("@org.junit.jupiter.api.Test void sample() { }"));
        assertTrue(hasExecutableTestAnnotation("@ParameterizedTest void sample(int value) { }"));
    }

    private static boolean hasExecutableTestAnnotation(String content) {
        return Pattern.compile("@(?:[\\w$]+\\.)*(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\\b")
            .matcher(content).find();
    }

    @Test
    void allEnumAndRestrictedTypeNamesFollowTheStrictSemanticStandard() throws Exception {
        List<String> problems = new ArrayList<>();
        for (SourceUnit source : loadSourceUnits(ignored -> true)) {
            if (isModPackage(source.packageName))
                continue;
            for (Tree declaration : source.tree.getTypeDecls()) {
                new TreeScanner<Void, String>() {
                    @Override
                    public Void visitClass(ClassTree node, String owner) {
                        String simpleName = node.getSimpleName().toString();
                        if (simpleName.isBlank())
                            return super.visitClass(node, owner);
                        String qualifiedName = owner == null || owner.isBlank()
                            ? source.packageName + "." + simpleName
                            : owner + "." + simpleName;
                        Set<String> restricted = new LinkedHashSet<>(camelCaseTokens(simpleName));
                        restricted.retainAll(RESTRICTED_TYPE_TOKENS);
                        if (!restricted.isEmpty() && !ALLOWED_RESTRICTED_TYPE_NAMES.contains(simpleName)
                            && !STABLE_PUBLIC_RESTRICTED_NAMES.contains(qualifiedName)) {
                            problems.add(qualifiedName + " uses restricted terms " + restricted);
                        }
                        if (PERIOD_NAME.matcher(simpleName).find()
                            || Pattern.compile("(?:^|[A-Z])V[0-9]+(?:$|[A-Z])").matcher(simpleName).find()) {
                            problems.add(qualifiedName + " uses a phase or version name");
                        }
                        if (node.getKind() == Tree.Kind.ENUM) {
                            if (OBSOLETE_ENUM_NAMES.contains(simpleName)) {
                                problems.add(qualifiedName + " uses an obsolete enum name");
                            }
                            if (ENUM_SUFFIXES.stream().noneMatch(simpleName::endsWith)
                                && !STABLE_PUBLIC_ENUM_NAMES.contains(qualifiedName)) {
                                problems.add(qualifiedName + " has no approved semantic enum suffix");
                            }
                        }
                        return super.visitClass(node, qualifiedName);
                    }
                }.scan(declaration, "");
            }
        }
        assertTrue(problems.isEmpty(), () -> "strict type naming violations: " + problems);
    }

    @Test
    void explicitSdkNamesDoNotExemptOtherTypesOrUnreviewedSdkNames() {
        assertTrue(STABLE_PUBLIC_ENUM_NAMES.contains("gold.debug.windowstolinux.appapi.ai.AiPurpose"));
        assertTrue(STABLE_PUBLIC_RESTRICTED_NAMES.contains("gold.debug.windowstolinux.appapi.server.ServerInfo"));
        assertFalse(STABLE_PUBLIC_ENUM_NAMES.contains("gold.debug.windowstolinux.mod.deployment.ai.AiPurpose"));
        assertFalse(STABLE_PUBLIC_ENUM_NAMES.contains("gold.debug.windowstolinux.appapi.ai.GenericPurpose"));
        assertFalse(STABLE_PUBLIC_RESTRICTED_NAMES.contains("gold.debug.windowstolinux.appapi.server.OtherInfo"));
        assertFalse(STABLE_PUBLIC_RESTRICTED_NAMES.contains("gold.debug.windowstolinux.app.service.server.ServerInfo"));
    }

    @Test
    void topLevelTypeNamesAreUniqueAcrossProductionAndTests() throws Exception {
        Map<String, List<Path>> declarations = new LinkedHashMap<>();
        for (SourceUnit source : loadSourceUnits(ignored -> true)) {
            source.tree.getTypeDecls().stream().filter(ClassTree.class::isInstance).map(ClassTree.class::cast)
                .forEach(type -> declarations
                    .computeIfAbsent(isModPackage(source.packageName)
                        ? source.packageName + "." + type.getSimpleName() : type.getSimpleName().toString(),
                        ignored -> new ArrayList<>())
                    .add(source.path));
        }
        Map<String, List<Path>> duplicates = new LinkedHashMap<>();
        declarations.forEach((name, paths) -> {
            if (paths.size() > 1)
                duplicates.put(name, List.copyOf(paths));
        });
        assertTrue(duplicates.isEmpty(), () -> "duplicate top-level type names: " + duplicates);
    }

    @Test
    void maintainedResourceFileNamesUseStableTerms() throws Exception {
        Path root = projectRoot();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = activeFiles()) {
            for (Path resource : files.filter(Files::isRegularFile).filter(path -> {
                String value = path.toString();
                return value.contains("src" + java.io.File.separator + "main" + java.io.File.separator + "resources")
                    || value.contains(
                    "src" + java.io.File.separator + "test" + java.io.File.separator + "resources");
            }).toList()) {
                if (root.relativize(resource).toString().replace('\\', '/').startsWith("src/mod/"))
                    continue;
                String fileName = resource.getFileName().toString();
                String stem = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
                Set<String> tokens = new LinkedHashSet<>(
                    List.of(stem.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")));
                Set<String> forbidden = new LinkedHashSet<>(tokens);
                forbidden.retainAll(FORBIDDEN_RESOURCE_TOKENS);
                if (!forbidden.isEmpty())
                    problems.add(root.relativize(resource) + " uses forbidden terms " + forbidden);
                String relative = root.relativize(resource).toString().replace('\\', '/');
                if ((PERIOD_NAME.matcher(stem).find() || tokens.stream().anyMatch(token -> token.matches("v[0-9]+")))
                    && !VERSIONED_SCHEMA_RESOURCES.contains(relative)) {
                    problems.add(root.relativize(resource) + " uses a phase or version name");
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "resource naming violations: " + problems);
    }

    @Test
    void versionedSchemaNamesAreLimitedToExactRegisteredPersistentResources() {
        assertTrue(VERSIONED_SCHEMA_RESOURCES
            .contains("src/app/db/src/main/resources/gold/debug/windowstolinux/app/db/schema/platform-v1.sql"));
        assertTrue(VERSIONED_SCHEMA_RESOURCES.contains(
            "src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/db/validation-v3.sql"));
        assertFalse(VERSIONED_SCHEMA_RESOURCES
            .contains("src/app/ui/src/main/resources/gold/debug/windowstolinux/app/ui/schema-v1.sql"));
        assertFalse(VERSIONED_SCHEMA_RESOURCES.contains(
            "src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/db/schema-v4.sql"));
    }

    private static Set<String> camelCaseTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        var matcher = Pattern.compile("[A-Z](?:[a-z0-9]+|[A-Z]*(?=[A-Z]|$))").matcher(value);
        while (matcher.find())
            tokens.add(matcher.group());
        return Set.copyOf(tokens);
    }

    @Test
    void languageInspectorsStayAtEcosystemRootsAndDoNotDependOnBuildArchitectures() throws Exception {
        Map<String, String> inspectors = Map.of("c", "CLanguageInspector", "dotnet", "DotNetLanguageInspector", "go",
            "GoLanguageInspector", "java", "JavaLanguageInspector", "kotlin", "KotlinLanguageInspector", "node",
            "NodeLanguageInspector", "php", "PhpLanguageInspector", "python", "PythonLanguageInspector", "ruby",
            "RubyLanguageInspector", "rust", "RustLanguageInspector");
        List<String> problems = new ArrayList<>();
        for (String root : List.of("gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.")) {
            Set<String> found = new HashSet<>();
            for (SourceUnit unit : sourceModel().units) {
                String file = unit.path.getFileName().toString();
                if (!unit.packageName.startsWith(root) || !file.endsWith("LanguageInspector.java"))
                    continue;
                String ecosystem = unit.packageName.substring(root.length());
                if (!file.equals(inspectors.get(ecosystem) + ".java")) {
                    problems.add(unit.path + " must be the language inspector at its ecosystem root");
                }
                found.add(ecosystem);
                for (String dependency : unit.imports) {
                    if ((dependency.startsWith(root) && dependency.substring(root.length()).split("\\.").length > 2)
                        || dependency.startsWith("java.util.jar.")) {
                        problems.add(unit.path + " depends on build or archive parsing: " + dependency);
                    }
                }
            }
            assertEquals(ECOSYSTEM_CLASSIFICATION_PACKAGES, found, root + " requires every language inspector");
        }
        assertTrue(problems.isEmpty(), () -> "language/build boundary violations: " + problems);
    }

    @Test
    void packageTreeIsFlatBoundedAndFreeOfObsoletePaths() throws Exception {
        SourceModel model = sourceModel();
        List<String> problems = new ArrayList<>();
        model.packages.values().forEach(info -> verifyPackageLayout(info.name, info.depth, problems));
        Path projectRootPath = projectRoot();
        try (Stream<Path> directories = Files.walk(projectRootPath.resolve("src"))) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                String normalized = projectRootPath.relativize(directory).toString().replace(java.io.File.separatorChar,
                    '/');
                if (!normalized.contains("/src/main/java/") && !normalized.contains("/src/test/java/"))
                    continue;
                verifyMigratedPackagePath(normalized, problems);
            }
        }
        for (SourceUnit unit : model.units) {
            String fileName = unit.path.getFileName().toString();
            for (Tree type : unit.tree.getTypeDecls())
                if (type instanceof ClassTree declaration)
                    verifyModRootType(unit.packageName, declaration.getSimpleName().toString(), problems);
            Path physicalPath = sourceRoot(unit.path, "main")
                .resolve(unit.packageName.replace('.', java.io.File.separatorChar)).resolve(fileName)
                .toAbsolutePath().normalize();
            if (!physicalPath.equals(unit.path)) {
                problems.add(fileName + " is stored at " + unit.path + " rather than " + physicalPath);
            }
            boolean governed = RESPONSIBILITY_ROOTS.stream()
                .anyMatch(root -> unit.packageName.equals(root) || unit.packageName.startsWith(root + "."));
            if (!governed)
                continue;
            String expectedPackage = RESPONSIBILITY_PACKAGES.get(fileName);
            if (expectedPackage == null) {
                problems.add(unit.path + " has no reviewed responsibility package assignment");
                continue;
            }
            if (!expectedPackage.equals(unit.packageName)) {
                problems.add(fileName + " belongs to " + expectedPackage + " rather than " + unit.packageName);
            }
        }
        model.sources.forEach(source -> {
            String content;
            try {
                content = Files.readString(source);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            verifyOldPackageReferences(content, source.toString(), problems);
            PREVIOUS_RESPONSIBILITY_PACKAGES.forEach((fileName, oldPackage) -> {
                String oldType = oldPackage + "." + fileName.substring(0, fileName.length() - ".java".length());
                if (content.contains(oldType))
                    problems.add(source + " references old type " + oldType);
            });
            DELETED_WRAPPERS.stream()
                .filter(name -> content.contains(name + ".") || content.contains("new " + name + "("))
                .forEach(name -> problems.add(source + " references deleted wrapper " + name));
        });
        assertTrue(problems.isEmpty(), () -> "package structure violations: " + problems);
    }

    @Test
    void appFunctionalPackagesAllowRootsAndOnlyNecessaryExtraLayers() {
        List<String> problems = new ArrayList<>();
        for (String relative : List.of("file", "mod.install", "mod.dev", "mod.run", "mod.config", "db.connect",
            "db.schema", "db.ai", "db.server", "db.security", "db.setting", "db.sshrecover", "linux.sftp",
            "linux.ssh.connect", "linux.ssh.recover", "service.ai", "service.server", "service.file",
            "service.security", "service.task", "ui.control", "ui.window", "ui.task", "ui.mod", "ui.sshrecover")) {
            String packageName = "gold.debug.windowstolinux.app." + relative;
            verifyPackageLayout(packageName, relative.split("\\.").length - 1, problems);
        }
        assertTrue(problems.isEmpty(), () -> "valid APP functional packages rejected: " + problems);
    }

    @Test
    void modAuthorsChooseInternalWordsAndDepthWithinPlatformFirstLayer() {
        List<String> problems = new ArrayList<>();
        for (String prefix : List.of("gold.debug", "com.example.tools")) {
            String root = prefix + ".windowstolinux.mod.example";
            for (String relative : List.of("service.impl.internal.operations.old.detail", "file.transfer.remote",
                "db.persistence.repository", "ai.custom.Checks", "api.payload"))
                verifyPackageLayout(root + "." + relative, relative.split("\\.").length, problems);
            verifyModRootType(root, "Mod", problems);
        }
        assertTrue(problems.isEmpty(), () -> "author-owned Mod organization rejected: " + problems);
    }

    @Test
    void modInternalFreedomPreservesFirstLayerAndRootEntrypoint() {
        for (String relative : List.of("source", "lifecycle", "main", "mod", "util", "app")) {
            List<String> problems = new ArrayList<>();
            verifyPackageLayout("gold.debug.windowstolinux.mod.example." + relative + ".custom", 2, problems);
            assertTrue(problems.stream().anyMatch(problem -> problem.contains("Mod first layer")), relative);
        }
        List<String> rootProblems = new ArrayList<>();
        verifyModRootType("com.example.windowstolinux.mod.example", "PrivateWorkflow", rootProblems);
        assertFalse(rootProblems.isEmpty());
        List<String> childProblems = new ArrayList<>();
        verifyModRootType("com.example.windowstolinux.mod.example.service.custom", "PrivateWorkflow", childProblems);
        assertTrue(childProblems.isEmpty());
    }

    @Test
    void appStillRejectsMiscellaneousPackagesAndApiKeepsItsOwnGroups() {
        for (String segment : FORBIDDEN_PACKAGE_SEGMENTS) {
            List<String> problems = new ArrayList<>();
            verifyPackageLayout("gold.debug.windowstolinux.app.file." + segment, 1, problems);
            assertTrue(problems.stream().anyMatch(problem -> problem.contains("forbidden package segments")), segment);
        }
        List<String> problems = new ArrayList<>();
        verifyPackageLayout("gold.debug.windowstolinux.appapi.file.transfer", 1, problems);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("public API capability group")));
    }

    @Test
    void obsoleteBusinessPackagesRemainForbiddenInReferencesAndDirectories() {
        for (String packageName : List.of("gold.debug.windowstolinux.app.service.port",
            "gold.debug.windowstolinux.app.service.locking", "gold.debug.windowstolinux.app.ui.settings",
            "gold.debug.windowstolinux.shared.deploy.lifecycle")) {
            List<String> problems = new ArrayList<>();
            verifyOldPackageReferences("import " + packageName + ".Example;", "fixture", problems);
            assertFalse(problems.isEmpty(), packageName + " must remain forbidden");
        }
        for (String suffix : List.of("", "/nested")) {
            List<String> problems = new ArrayList<>();
            verifyMigratedPackagePath(
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/lifecycle" + suffix, problems);
            assertFalse(problems.isEmpty(), "migrated physical paths and their descendants must remain forbidden");
        }
    }

    private static final Set<String> MOD_FIRST_LAYER = Set.of("ai", "api", "db", "file", "linux", "secret",
        "service", "ui", "windows");

    private static final Set<String> API_CAPABILITY_GROUPS = Set.of("ai", "db", "file", "linux", "mod",
        "secret", "server", "task", "ui");

    private static boolean isModPackage(String packageName) {
        return packageName.contains(".windowstolinux.mod.");
    }

    private static String declaredPackage(String content) {
        var declaration = Pattern.compile("(?m)^package\\s+([A-Za-z0-9_$.]+)\\s*;").matcher(content);
        return declaration.find() ? declaration.group(1) : "";
    }

    private static void verifyModRootType(String packageName, String typeName, List<String> problems) {
        if (!isModPackage(packageName))
            return;
        String[] relative = packageName.substring(packageName.indexOf(".windowstolinux.mod.")
            + ".windowstolinux.mod.".length()).split("\\.");
        if (relative.length == 1 && !typeName.equals("Mod"))
            problems.add(packageName + "." + typeName + " is not the Mod root entrypoint");
    }

    private static void verifyPackageLayout(String packageName, int depth, List<String> problems) {
        if (isModPackage(packageName)) {
            String[] relative = packageName.substring(packageName.indexOf(".windowstolinux.mod.")
                + ".windowstolinux.mod.".length()).split("\\.");
            if (relative.length > 1 && !MOD_FIRST_LAYER.contains(relative[1]))
                problems.add(packageName + " uses an unsupported Mod first layer " + relative[1]);
            return;
        }
        String api = "gold.debug.windowstolinux.appapi.";
        if (packageName.startsWith(api) && !API_CAPABILITY_GROUPS.contains(packageName.substring(api.length())))
            problems.add(packageName + " is outside a public API capability group");
        if (packageName.startsWith("gold.debug.windowstolinux.app.")) {
            Set<String> forbidden = new HashSet<>(List.of(packageName.split("\\.")));
            forbidden.retainAll(FORBIDDEN_PACKAGE_SEGMENTS);
            if (!forbidden.isEmpty())
                problems.add(packageName + " uses forbidden package segments " + forbidden);
        }
    }

    private static void verifyOldPackageReferences(String content, String source, List<String> problems) {
        OLD_PACKAGE_PREFIXES.stream().filter(content::contains)
            .forEach(prefix -> problems.add(source + " references old package " + prefix));
    }

    private static void verifyMigratedPackagePath(String normalized, List<String> problems) {
        for (String prefix : MIGRATED_PACKAGE_PREFIXES) {
            String oldPath = "/" + prefix.replace('.', '/');
            if (normalized.endsWith(oldPath) || normalized.contains(oldPath + "/")) {
                problems.add(normalized + " retains migrated package path " + prefix);
            }
        }
    }

    @Test
    void publicApiPackagesFollowTheDocumentedDependencyDirection() throws Exception {
        var allowed = Map.of("task", Set.<String>of(), "server", Set.of("task"), "ui", Set.of("task"), "db",
            Set.of("task"), "ai", Set.of("task"), "file", Set.of("task", "server", "linux"), "secret", Set.of("task", "server"),
            "linux", Set.of("task", "server"), "mod",
            Set.of("task", "server", "ui", "db", "ai", "file", "secret", "linux"));
        String prefix = "gold.debug.windowstolinux.appapi.";
        var model = sourceModel();
        var violations = new ArrayList<String>();
        for (var unit : model.units) {
            if (!unit.packageName.startsWith(prefix))
                continue;
            String owner = unit.packageName.substring(prefix.length());
            assertTrue(allowed.containsKey(owner), unit.path.toString());
            for (var imported : unit.imports) {
                if (imported.startsWith("java.") || imported.startsWith("javax."))
                    continue;
                var target = resolveImportedPackage(imported, model.packages.keySet());
                if (target.isEmpty() || !target.get().equals(unit.packageName) && (!target.get().startsWith(prefix)
                    || !allowed.get(owner).contains(target.get().substring(prefix.length()))))
                    violations.add(unit.path + " -> " + imported);
            }
        }
        assertTrue(violations.isEmpty(), () -> "public API dependency direction violations: " + violations);
    }

    @Test
    void productionPackageDependenciesAreAcyclic() throws Exception {
        SourceModel model = sourceModel();
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        model.packages.keySet().forEach(name -> graph.put(name, new LinkedHashSet<>()));
        for (SourceUnit unit : model.units) {
            for (String imported : unit.imports) {
                resolveImportedPackage(imported, model.packages.keySet())
                    .filter(target -> !target.equals(unit.packageName))
                    .ifPresent(target -> graph.get(unit.packageName).add(target));
            }
        }
        Optional<List<String>> cycle = findCycle(graph);
        assertTrue(cycle.isEmpty(),
            () -> "production package dependency cycle: " + String.join(" -> ", cycle.orElseThrow()));
    }

    @Test
    void responsibilityPackagesDoNotReverseTheirDependencyDirection() throws Exception {
        SourceModel model = sourceModel();
        List<String> problems = new ArrayList<>();
        String deployment = "gold.debug.windowstolinux.mod.deployment.service.";
        for (SourceUnit unit : model.units) {
            for (String imported : unit.imports) {
                if ((unit.packageName.equals(deployment + "core") || unit.packageName.startsWith(deployment + "core."))
                    && (imported.startsWith(deployment + "standard.") || imported.startsWith(deployment + "agent.")
                    || imported.startsWith("gold.debug.windowstolinux.mod.deployment.ui.")))
                    problems.add(unit.path + " reverses core model dependency direction to " + imported);
                if ((unit.packageName.equals(deployment + "standard")
                    || unit.packageName.startsWith(deployment + "standard."))
                    && imported.startsWith(deployment + "agent."))
                    problems.add(unit.path + " makes standard analysis depend on agent policy: " + imported);
                if ((unit.packageName.equals(deployment + "agent")
                    || unit.packageName.startsWith(deployment + "agent."))
                    && imported.startsWith(deployment + "standard."))
                    problems.add(unit.path + " makes agent policy depend on standard analysis: " + imported);
                resolveImportedPackage(imported, model.packages.keySet())
                    .ifPresent(target -> FORBIDDEN_RESPONSIBILITY_DEPENDENCIES.stream()
                        .filter(rule -> rule.sourcePackage.equals(unit.packageName))
                        .filter(rule -> rule.forbiddenTargetPackages.contains(target)).forEach(rule -> problems
                            .add(unit.path + " reverses dependency direction to " + target)));
            }
        }
        assertTrue(problems.isEmpty(), () -> "responsibility dependency direction violations: " + problems);
    }

    @Test
    void linuxAndUiHonorTheNarrowExecutionAndServiceBoundaries() throws Exception {
        var allowedUiServiceTypes = Set.of("gold.debug.windowstolinux.app.service.ai.PlatformModelService",
            "gold.debug.windowstolinux.app.service.linux.TransferConfiguration",
            "gold.debug.windowstolinux.app.service.linux.TransferSettingsService",
            "gold.debug.windowstolinux.app.service.server.PlatformServerService",
            "gold.debug.windowstolinux.app.service.sshrecover.PlatformRecoveryService",
            "gold.debug.windowstolinux.app.service.log.PlatformLogService",
            "gold.debug.windowstolinux.app.service.mod.PlatformModDeletion",
            "gold.debug.windowstolinux.app.service.mod.PlatformModInstallation",
            "gold.debug.windowstolinux.app.service.mod.PlatformModIntents",
            "gold.debug.windowstolinux.app.service.mod.PlatformModReinstallation",
            "gold.debug.windowstolinux.app.service.task.PlatformAuthorizationService",
            "gold.debug.windowstolinux.app.service.task.PlatformInteractionService");
        Set<String> serviceContracts = serviceContractTypes(sourceModel());
        List<String> problems = new ArrayList<>();
        for (SourceUnit unit : sourceModel().units) {
            for (String reference : unit.imports) {
                if (unit.packageName.startsWith("gold.debug.windowstolinux.app.linux")
                    && (reference.startsWith("gold.debug.windowstolinux.app.db.")
                    || reference.startsWith("gold.debug.windowstolinux.app.service.")
                    || reference.startsWith("gold.debug.windowstolinux.app.ui.")))
                    problems.add(unit.path + " depends on persistence, service policy or UI: " + reference);
                if (unit.packageName.startsWith("gold.debug.windowstolinux.app.ui.")) {
                    if (reference.startsWith("gold.debug.windowstolinux.app.db.")
                        || reference.startsWith("gold.debug.windowstolinux.app.linux."))
                        problems.add(unit.path + " reaches a database implementation or remote port: " + reference);
                    if (reference.startsWith("gold.debug.windowstolinux.app.service.")
                        && serviceContracts.stream().noneMatch(type -> reference.equals(type)
                            || reference.startsWith(type + "."))
                        && allowedUiServiceTypes.stream()
                        .noneMatch(type -> (reference.equals(type) || reference.startsWith(type + "."))))
                        problems.add(unit.path + " calls service internals: " + reference);
                }
            }
        }
        var linux = MavenModuleGraph.read(projectRoot()).stream().filter(module -> module.path().equals("app/linux"))
            .findFirst().orElseThrow();
        assertEquals(Set.of("windowstolinux-appapi", "windowstolinux-app-ai", "windowstolinux-app-file",
            "windowstolinux-app-windows"),
            linux.artifacts());
        assertTrue(problems.isEmpty(), () -> "execution/service boundary violations: " + problems);
    }

    private static Set<String> serviceContractTypes(SourceModel model) {
        Set<String> result = new HashSet<>(Set.of(
            "gold.debug.windowstolinux.app.service.error.ApplicationServiceException"));
        for (SourceUnit source : model.units) {
            if (!source.packageName.startsWith("gold.debug.windowstolinux.app.service."))
                continue;
            for (Tree type : source.tree.getTypeDecls())
                if (type instanceof ClassTree declaration
                    && declaration.getModifiers().getFlags().contains(Modifier.PUBLIC)
                    && Set.of(Tree.Kind.INTERFACE, Tree.Kind.RECORD, Tree.Kind.ENUM).contains(declaration.getKind()))
                    result.add(source.packageName + "." + declaration.getSimpleName());
        }
        return Set.copyOf(result);
    }

    @Test
    void relocatedTestsAndRemovedDirectoriesMatchTheirResponsibilities() throws Exception {
        Path root = projectRoot();
        for (var entry : Map.of("DeploymentStaticInputTest", "service/standard/deploy/input",
            "DeploymentConfigurationSnapshotTest", "service/core/config").entrySet()) {
            Path test = root.resolve("src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/"
                + entry.getValue() + "/" + entry.getKey() + ".java");
            assertTrue(Files.isRegularFile(test), "Migrated validation test is absent: " + test);
            assertTrue(Files.readString(test).contains(
                "package gold.debug.windowstolinux.mod.deployment." + entry.getValue().replace('/', '.') + ";"));
        }
        for (var entry : Map
            .of("DeploymentRuntimeParserTest", "standard/deploy", "DeploymentConfigurationParserTest", "config")
            .entrySet()) {
            Path test = root
                .resolve("src/shared/" + entry.getValue() + "/src/test/java/gold/debug/windowstolinux/shared/"
                    + entry.getValue() + "/input/" + entry.getKey() + ".java");
            assertFalse(Files.exists(test), "Inactive shared validation cannot remain in the active root: " + test);
            assertTrue(Files.isRegularFile(root.resolve("history-src").resolve(root.relativize(test))),
                "Original validation source must remain in the frozen archive");
            assertFalse(
                Files.exists(root.resolve("src/app/ui/src/test/java/gold/debug/windowstolinux/app/ui/deployment/"
                    + entry.getKey() + ".java")));
        }
        for (String obsolete : List.of(
            "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/db",
            "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/ecosystem",
            "src/shared/linux-sshd/src/main/resources/gold/debug/windowstolinux/shared/linux/sshd/ecosystem",
            "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/execution/restore"))
            assertFalse(Files.exists(root.resolve(obsolete)), obsolete);
    }

    @Test
    void productionClassesStayWithinAstComplexityLimits() throws Exception {
        SourceModel model = sourceModel();
        List<String> problems = new ArrayList<>();
        for (SourceUnit source : model.units) {
            for (Tree declaration : source.tree.getTypeDecls()) {
                new TreeScanner<Void, String>() {
                    @Override
                    public Void visitClass(ClassTree node, String owner) {
                        String className = owner == null || owner.isBlank()
                            ? node.getSimpleName().toString()
                            : owner + "." + node.getSimpleName();
                        if (node.getKind() == Tree.Kind.CLASS) {
                            long fields = node.getMembers().stream().filter(VariableTree.class::isInstance)
                                .map(VariableTree.class::cast)
                                .filter(field -> !field.getModifiers().getFlags().contains(Modifier.STATIC))
                                .count();
                            long methods = node.getMembers().stream().filter(MethodTree.class::isInstance)
                                .map(MethodTree.class::cast)
                                .filter(method -> !method.getName().contentEquals("<init>")).count();
                            if (fields > 25)
                                problems.add(className + " has " + fields + " instance fields");
                            if (methods > 30) {
                                problems.add(className + " has " + methods + " directly declared methods");
                            }
                            node.getMembers().stream().filter(MethodTree.class::isInstance).map(MethodTree.class::cast)
                                .filter(method -> method.getBody() != null).forEach(method -> {
                                    int statements = new StatementCounter().scan(method.getBody(), null);
                                    if (statements > 80) {
                                        problems.add(className + "." + method.getName() + " has " + statements
                                            + " AST statements");
                                    }
                                });
                        }
                        return super.visitClass(node, className);
                    }
                }.scan(declaration, "");
            }
        }
        assertTrue(problems.isEmpty(), () -> "class responsibility limits exceeded: " + problems);
    }

    @Test
    void testPackagesMirrorProductionAndOnlyReviewedEntrypointsExist() throws Exception {
        Path root = projectRoot();
        List<Path> missingMirrors = new ArrayList<>();
        try (Stream<Path> files = activeFiles()) {
            for (Path test : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java"))
                .filter(path -> path.toString()
                    .contains("src" + java.io.File.separator + "test" + java.io.File.separator + "java"))
                .toList()) {
                Path testRoot = sourceRoot(test, "test");
                Path relativePackage = testRoot.relativize(test.getParent());
                Path productionPackage = testRoot.getParent().getParent().resolve("main/java").resolve(relativePackage);
                if (!Files.isDirectory(productionPackage)
                    && !relativePackage.endsWith(Path.of("gold/debug/windowstolinux/app/main/architecture"))) {
                    missingMirrors.add(root.relativize(test));
                }
            }
        }
        assertTrue(missingMirrors.isEmpty(), () -> "test packages without production mirrors: " + missingMirrors);

        SourceModel model = sourceModel();
        List<String> entrypoints = new ArrayList<>();
        for (SourceUnit source : model.units) {
            for (Tree declaration : source.tree.getTypeDecls()) {
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree method, Void unused) {
                        Set<Modifier> modifiers = method.getModifiers().getFlags();
                        if (method.getName().contentEquals("main") && modifiers.contains(Modifier.PUBLIC)
                            && modifiers.contains(Modifier.STATIC) && method.getParameters().size() == 1) {
                            entrypoints.add(source.path.getFileName() + ":" + method.getName());
                        }
                        return super.visitMethod(method, unused);
                    }
                }.scan(declaration, null);
            }
        }
        assertEquals(Set.of("AppMain.java:main", "DeploymentGitWorker.java:main", "ModPackageTool.java:main"),
            new HashSet<>(entrypoints));
        assertEquals(3, entrypoints.size());
    }

    private static Set<String> fileNamesRecursively(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static void assertMaximumLines(Path directory, long maximum) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                long count = file.toString().endsWith(".java")
                    ? JavadocCoverageInspector.implementationLines(Files.readString(file))
                    : Files.readAllLines(file).size();
                long cap = FORMATTED_LINE_CAPS.getOrDefault(file.getFileName().toString(), maximum);
                assertTrue(count <= cap,
                    () -> file + " carries " + count + " implementation lines across declared responsibilities");
            }
        }
    }

    private static void assertMaximumLinesRecursively(Path directory, long maximum) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                try (Stream<String> lines = Files.lines(file)) {
                    long count = lines.count();
                    long cap = FORMATTED_LINE_CAPS.getOrDefault(file.getFileName().toString(), maximum);
                    assertTrue(count <= cap,
                        () -> file + " carries " + count + " lines across declared responsibilities");
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
        if (current == null)
            throw new IllegalStateException("project root was not found");
        return current;
    }

    private record SourceModel(List<Path> sources, List<SourceUnit> units,
                               Map<String, PackageStructureFacts> packages) {
    }

    private record SourceUnit(Path path, String packageName, List<String> imports, CompilationUnitTree tree) {
    }

    private record PackageStructureFacts(String name, int depth, int topLevelTypes) {
    }

    private record PackageDependencyRule(String sourcePackage, Set<String> forbiddenTargetPackages) {
    }

    private static final class PackageStructureState {
        private final String name;

        private final int depth;

        private int topLevelTypes;

        private PackageStructureState(String name, int depth) {
            this.name = name;
            this.depth = depth;
        }
    }

    private static final class StatementCounter extends TreeScanner<Integer, Void> {
        @Override
        public Integer scan(Tree tree, Void unused) {
            if (tree == null)
                return 0;
            int current = tree instanceof StatementTree && !(tree instanceof BlockTree) ? 1 : 0;
            return current + value(super.scan(tree, unused));
        }

        @Override
        public Integer reduce(Integer first, Integer second) {
            return value(first) + value(second);
        }

        private static int value(Integer value) {
            return value == null ? 0 : value;
        }
    }
}
