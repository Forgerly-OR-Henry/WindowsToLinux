package gold.debug.windowstolinux.app.main.architecture;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageStructureArchitectureTest {
    private static final Set<String> LEGACY_FILES = Set.of(
            "StaticProjectAnalyzer.java", "DeploymentProjectAnalyzer.java", "DeploymentRuntimeInference.java",
            "DesktopPages.java", "DesktopDatabase.java", "DesktopRepository.java", "GitSnapshotService.java",
            "DeploymentBuildSupport.java", "SystemdRuntimeExecutor.java", "DeploymentSystemdUnitRenderer.java",
            "ManagedPrivilegeHelper.java", "ManagedSpringBootAnalysisCoordinator.java",
            "GradleSpringBootDeploymentInspector.java", "SpringBootProjectInspector.java",
            "ProjectAssessment.java", "SupportDecision.java", "SourceProjectFacts.java", "SourcePreparation.java",
            "DeploymentRequest.java", "ManagedDeploymentService.java", "DeploymentUseCase.java",
            "RemoteBuildResult.java", "LinuxBuildOperations.java", "LinuxReleaseOperations.java",
            "MavenBuildExecutor.java", "MavenBuildSupport.java", "ManagedReleaseProtocolExecutor.java",
            "ServiceDeploymentInspector.java", "DebianFamilySetupCatalog.java", "EnterpriseLinuxSetupCatalog.java");
    private static final Set<String> ANALYSIS_CORE = Set.of(
            "DeploymentAnalysisCoordinator.java", "ProjectLanguageInspector.java");
    private static final Set<String> DESKTOP_SHELL = Set.of(
            "DesktopDisplayChangeHandler.java", "DesktopFrame.java", "DesktopPageCoordinator.java",
            "DesktopViewState.java", "PageNavigationController.java");
    private static final Set<String> DEPLOYMENT_SHARED = Set.of(
            "ReviewContext.java");
    private static final Set<String> DEPLOYMENT_SINGLE = Set.of(
            "DeploymentAnalysisPresenter.java", "DeploymentForm.java", "DeploymentPage.java",
            "DeploymentPageState.java", "DeploymentInputDialog.java");
    private static final Set<String> DEPLOYMENT_MULTI = Set.of(
            "MultiComponentDraftController.java", "MultiComponentFormState.java",
            "MultiComponentPage.java", "MultiComponentPageState.java",
            "MultiComponentResultPresenter.java");
    private static final Set<String> REPOSITORIES = Set.of(
            "AiProfileRepository.java", "ApplicationSecretRepository.java", "ConfigurationSnapshotRepository.java",
            "DesktopPreferenceRepository.java", "EncryptedSecretRepository.java", "ManagedApplicationRepository.java",
            "ManagedApplicationGraphRepository.java", "RepositoryTransactionExecutor.java", "ServerProfileRepository.java");
    private static final Set<String> HELPER_FRAGMENTS = Set.of("21-workspace-volume.sh", "22-restricted-build.sh", "23-container-builder.sh", "24-build-entry.sh", "25-workspace-recovery.sh", "26-build-output.sh", "12-container-image-input.sh", "61-dynamic-identity.sh", "64-database-client.sh",
            "00-protocol-foundation.sh", "10-typed-release.sh", "15-deployment-input.sh", "17-managed-content.sh", "20-candidate-workspace.sh",
            "35-ecosystem-dispatch.sh", "40-typed-runtime.sh", "50-container-release.sh", "52-container-recovery.sh", "55-podman-quadlet.sh", "60-lifecycle.sh",
            "54-restore-candidate.sh", "56-restore-commit.sh", "65-database-backup.sh", "66-database-activation.sh",
            "67-managed-backup.sh", "70-command-dispatch.sh", "10-release-metadata.py", "20-installation-boundaries.py", "30-managed-installation.py", "40-binding-protocol.py", "10-native-instances.sh", "20-native-targets.sh");
    private static final Pattern PERIOD_NAME = Pattern.compile("(?i)(?:phase|stage)[-_]?[0-9]+|(?:一期|二期|三期|四期|五期)");
    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "(?m)^(?:public\\s+)?(?:(?:final|abstract|sealed|non-sealed)\\s+)?(?:class|record|interface|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern UNNORMALIZED_ACRONYM = Pattern.compile("[A-Z]{2,}(?![a-z])");
    private static final Set<String> FORBIDDEN_TYPE_SUFFIXES = Set.of(
            "UseCases", "Locks", "Components", "Stores", "Transactions", "Profiles", "Checks",
            "Operations", "Settings", "Fixtures", "Impl");
    private static final Set<String> BUILD_ARCHITECTURE_PACKAGE_NAMES = Set.of(
            "bundler", "cargo", "cmake", "composer", "dotnetsdk", "gradle", "gomodule", "jar", "jdk",
            "kotlinc", "maven", "npm", "phpcli", "pip", "pipenv", "pnpm", "poetry", "rubycli", "uv",
            "yarn");
    private static final Set<String> FORBIDDEN_PACKAGE_SEGMENTS = Set.of("util", "common", "misc", "impl");
    private static final Set<String> ENUM_SUFFIXES = Set.of(
            "Action", "Decision", "Disposition", "Event", "Kind", "Level", "Mode", "Profile", "Scope",
            "Source", "State", "Status", "Type");
    private static final Set<String> OBSOLETE_ENUM_NAMES = Set.of(
            "Access", "AiCollaborationRole", "AiResponseLanguage", "ContainerEngine", "DeploymentBuildTool",
            "DeploymentStep", "EvidenceConfidence", "HostSupport", "LanguageEcosystem", "LanguageFact",
            "LinuxDistro", "LinuxSecurityModule", "RuntimeInput", "SetupExpectation", "Severity", "SourceLanguage");
    private static final Set<String> RESTRICTED_TYPE_TOKENS = Set.of(
            "Base", "Bean", "Checker", "Common", "Concrete", "Controller", "Data", "Generic", "Handler",
            "Helper", "Impl", "Implementation", "Info", "Legacy", "Manager", "Misc", "New", "Object", "Old",
            "Processor", "Support", "Temp", "Temporary", "Util", "Utils");
    private static final Set<String> ALLOWED_RESTRICTED_TYPE_NAMES = Set.of(
            "AlmaLinuxSupportPolicy", "CentosStreamSupportPolicy", "ComponentDataPath", "DebianSupportPolicy",
            "ToolchainSupportCatalog", "ToolchainSupportCatalogTest", "DeploymentSupportCatalog", "DeploymentSupportLevel", "DeploymentSupportProfile",
            "DesktopDisplayChangeHandler", "DesktopWindowController", "DistributionSupportEvaluator",
            "DistributionSupportEvaluatorTest", "DistributionSupportPolicy", "DistributionSupportRules",
            "HostSupportDecision", "HostSupportEvaluator", "HostSupportEvaluatorTest", "HostSupportStatus",
            "ManagedHelperBundle", "ManagedHelperBundleTest", "ManagedHelperProtocol", "ManagedHelperProtocolVersion",
            "MultiComponentDraftController", "OracleLinuxSupportPolicy", "PageNavigationController",
            "RockyLinuxSupportPolicy", "UbuntuSupportPolicy", "WindowsCredentialManagerSecretStore");
    private static final Set<String> FORBIDDEN_RESOURCE_TOKENS = Set.of(
            "bean", "checker", "common", "concrete", "generic", "impl", "implementation", "legacy", "misc",
            "new", "object", "old", "processor", "temp", "temporary", "util", "utils");
    private static final Set<String> DISTRO_CLASSIFICATION_PACKAGES = Set.of(
            "apt", "contract", "dnf", "extension", "generation");
    private static final Set<String> ECOSYSTEM_CLASSIFICATION_PACKAGES = Set.of(
            "c", "java", "node", "python", "go", "rust", "dotnet", "kotlin", "php", "ruby");
    private static final Map<String, Set<String>> FUNCTIONAL_GROUP_RESPONSIBILITIES = Map.of(
            "contract", Set.of("capability", "definition", "policy", "profile", "result", "spi", "validation"),
            "generation", Set.of("prompt", "renderer", "script", "template"),
            "extension", Set.of("adapter", "registry"),
            "execution", Set.of("collection", "environment", "lifecycle", "migration", "protocol", "transaction", "transfer"),
            "persistence", Set.of("connection", "repository", "serialization"));
    private static final Map<String, String> RESPONSIBILITY_FUNCTIONAL_GROUPS = Map.ofEntries(
            Map.entry("capability", "contract"), Map.entry("definition", "contract"),
            Map.entry("policy", "contract"), Map.entry("profile", "contract"),
            Map.entry("result", "contract"), Map.entry("spi", "contract"),
            Map.entry("validation", "contract"), Map.entry("prompt", "generation"),
            Map.entry("renderer", "generation"), Map.entry("script", "generation"),
            Map.entry("template", "generation"), Map.entry("adapter", "extension"),
            Map.entry("registry", "extension"), Map.entry("environment", "execution"),
            Map.entry("lifecycle", "execution"), Map.entry("migration", "execution"),
            Map.entry("protocol", "execution"), Map.entry("transaction", "execution"),
            Map.entry("transfer", "execution"), Map.entry("connection", "persistence"),
            Map.entry("repository", "persistence"), Map.entry("serialization", "persistence"));
    private static final Set<String> UNGROUPED_RESPONSIBILITY_EXCEPTIONS = Set.of(
            "gold.debug.windowstolinux.shared.linux.sshd.capability",
            "gold.debug.windowstolinux.shared.linux.sshd.connection",
            "gold.debug.windowstolinux.shared.model.capability",
            "gold.debug.windowstolinux.shared.model.lifecycle");
    private static final Set<String> DELETED_WRAPPERS = Set.of(
            "GoServiceDeploymentInspector", "RustServiceDeploymentInspector", "DotNetServiceDeploymentInspector",
            "RustBuildRenderer", "DotNetBuildRenderer", "KotlinBuildRenderer",
            "PhpBuildRenderer", "RubyBuildRenderer", "UbuntuSetup", "DebianSetup", "CentosStreamSetup",
            "RockyLinuxSetup", "AlmaLinuxSetup", "OracleLinuxSetup", "ServiceBuildRenderer",
            "SpringBootBuildRenderer", "NodeBuildRenderer", "PythonBuildRenderer", "EcosystemBuildScript");
    private static final List<String> OLD_PACKAGE_PREFIXES = List.of(
            "gold.debug.windowstolinux.shared.model.db",
            "gold.debug.windowstolinux.shared.analyze.db",
            "gold.debug.windowstolinux.shared.linux.db",
            "gold.debug.windowstolinux.shared.linux.sshd.db",
            "gold.debug.windowstolinux.shared.linux.sshd.ecosystem",
            "gold.debug.windowstolinux.app.db.connection",
            "gold.debug.windowstolinux.app.db.migration",
            "gold.debug.windowstolinux.app.db.repository",
            "gold.debug.windowstolinux.app.service.environment",
            "gold.debug.windowstolinux.app.service.lifecycle",
            "gold.debug.windowstolinux.shared.ai.prompt",
            "gold.debug.windowstolinux.shared.analyze.policy",
            "gold.debug.windowstolinux.shared.analyze.registry",
            "gold.debug.windowstolinux.shared.analyze.spi",
            "gold.debug.windowstolinux.shared.config.definition",
            "gold.debug.windowstolinux.shared.deploy.adapter",
            "gold.debug.windowstolinux.shared.deploy.environment",
            "gold.debug.windowstolinux.shared.deploy.lifecycle",
            "gold.debug.windowstolinux.shared.deploy.registry",
            "gold.debug.windowstolinux.shared.deploy.result",
            "gold.debug.windowstolinux.shared.deploy.spi",
            "gold.debug.windowstolinux.shared.deploy.transaction",
            "gold.debug.windowstolinux.shared.source.validation",
            "gold.debug.windowstolinux.shared.linux.sshd.build.registry",
            "gold.debug.windowstolinux.shared.linux.sshd.build.script",
            "gold.debug.windowstolinux.shared.linux.sshd.build.spi",
            "gold.debug.windowstolinux.shared.linux.sshd.distro.profile",
            "gold.debug.windowstolinux.shared.linux.sshd.distro.registry",
            "gold.debug.windowstolinux.shared.linux.sshd.distro.script",
            "gold.debug.windowstolinux.shared.linux.sshd.protocol",
            "gold.debug.windowstolinux.shared.linux.sshd.transfer",
            "gold.debug.windowstolinux.app.secret.api", "gold.debug.windowstolinux.app.secret.store",
            "gold.debug.windowstolinux.app.secret.windows", "gold.debug.windowstolinux.shared.git.reference",
            "gold.debug.windowstolinux.shared.git.remote", "gold.debug.windowstolinux.shared.analyze.source.metadata",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.jvm",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.ProjectLanguageInspector",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.ServiceDeploymentInspector",
            "gold.debug.windowstolinux.shared.analyze.workload.container",
            "gold.debug.windowstolinux.shared.analyze.workload.staticweb",
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
            "gold.debug.windowstolinux.shared.analyze.ecosystem.java.JavaJarDeploymentInspector",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.dotnet.DotNetSdkDeploymentInspector",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.dotnet.DotNetSdkFacts",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.go.GoModuleDeploymentInspector",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.go.GoModuleFacts",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.KotlinGradleDeploymentInspector",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.KotlinGradleFacts",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.php.PhpComposerDeploymentInspector",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.php.PhpComposerFacts",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.ruby.RubyBundlerDeploymentInspector",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.ruby.RubyBundlerFacts",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.rust.RustCargoDeploymentInspector",
            "gold.debug.windowstolinux.shared.analyze.ecosystem.rust.RustCargoFacts",
            "gold.debug.windowstolinux.shared.linux.sshd.distro.setup",
            "gold.debug.windowstolinux.shared.linux.sshd.protocol.workspace",
            "gold.debug.windowstolinux.shared.linux.sshd.runtime.container",
            "gold.debug.windowstolinux.shared.linux.sshd.runtime.dispatch",
            "gold.debug.windowstolinux.app.service.port",
            "gold.debug.windowstolinux.app.service.locking",
            "gold.debug.windowstolinux.app.ui.settings",
            "gold.debug.windowstolinux.shared.ai.client.RoleChatTransport",
            "gold.debug.windowstolinux.shared.ai.client.HttpRoleChatTransport",
            "gold.debug.windowstolinux.shared.ai.client.RoleChatResult",
            "gold.debug.windowstolinux.shared.ai.parser.AiStructuralAssessment",
            "gold.debug.windowstolinux.shared.linux.sshd.distro.execution");
    private static final Set<String> MIGRATED_PACKAGE_PREFIXES = Set.of(
            "gold.debug.windowstolinux.shared.model.db",
            "gold.debug.windowstolinux.shared.analyze.db",
            "gold.debug.windowstolinux.shared.linux.db",
            "gold.debug.windowstolinux.shared.linux.sshd.db",
            "gold.debug.windowstolinux.shared.linux.sshd.ecosystem",
            "gold.debug.windowstolinux.app.db.connection",
            "gold.debug.windowstolinux.app.db.migration",
            "gold.debug.windowstolinux.app.db.repository",
            "gold.debug.windowstolinux.app.service.environment",
            "gold.debug.windowstolinux.app.service.lifecycle",
            "gold.debug.windowstolinux.shared.ai.prompt",
            "gold.debug.windowstolinux.shared.analyze.policy",
            "gold.debug.windowstolinux.shared.analyze.registry",
            "gold.debug.windowstolinux.shared.analyze.spi",
            "gold.debug.windowstolinux.shared.config.definition",
            "gold.debug.windowstolinux.shared.deploy.adapter",
            "gold.debug.windowstolinux.shared.deploy.environment",
            "gold.debug.windowstolinux.shared.deploy.lifecycle",
            "gold.debug.windowstolinux.shared.deploy.registry",
            "gold.debug.windowstolinux.shared.deploy.result",
            "gold.debug.windowstolinux.shared.deploy.spi",
            "gold.debug.windowstolinux.shared.deploy.transaction",
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
    private static final Set<String> RESPONSIBILITY_ROOTS = Set.of(
            "gold.debug.windowstolinux.shared.ai.collaboration",
            "gold.debug.windowstolinux.shared.deploy.contract.result",
            "gold.debug.windowstolinux.shared.model.project",
            "gold.debug.windowstolinux.shared.model.language",
            "gold.debug.windowstolinux.shared.model.server",
            "gold.debug.windowstolinux.app.service.deployment",
            "gold.debug.windowstolinux.app.ui.deployment");
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
                    Set.of("gold.debug.windowstolinux.app.ui.deployment.single",
                            "gold.debug.windowstolinux.app.ui.deployment.multi")),
            new PackageDependencyRule("gold.debug.windowstolinux.app.ui.deployment.single",
                    Set.of("gold.debug.windowstolinux.app.ui.deployment.multi")),
            new PackageDependencyRule("gold.debug.windowstolinux.app.ui.deployment.multi",
                    Set.of("gold.debug.windowstolinux.app.ui.deployment.single")),
            new PackageDependencyRule("gold.debug.windowstolinux.app.service.deployment.single",
                    Set.of("gold.debug.windowstolinux.app.service.deployment",
                            "gold.debug.windowstolinux.app.service.deployment.multi")),
            new PackageDependencyRule("gold.debug.windowstolinux.app.service.deployment.multi",
                    Set.of("gold.debug.windowstolinux.app.service.deployment",
                            "gold.debug.windowstolinux.app.service.deployment.single")),
            new PackageDependencyRule("gold.debug.windowstolinux.shared.deploy.contract.result.compatibility",
                    Set.of("gold.debug.windowstolinux.shared.deploy.contract.result.deployment",
                            "gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle")),
            new PackageDependencyRule("gold.debug.windowstolinux.shared.deploy.contract.result.deployment",
                    Set.of("gold.debug.windowstolinux.shared.deploy.contract.result.compatibility",
                            "gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle")),
            new PackageDependencyRule("gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle",
                    Set.of("gold.debug.windowstolinux.shared.deploy.contract.result.compatibility",
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
        try (Stream<Path> files = Files.walk(root.resolve("src"))) {
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
        Path core = root.resolve("src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/core");
        Path shell = root.resolve("src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/shell");
        Path deploymentShared = root.resolve("src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/deployment");
        Path deploymentSingle = deploymentShared.resolve("single");
        Path deploymentMulti = deploymentShared.resolve("multi");
        Path repositories = root.resolve(
                "src/app/db/src/main/java/gold/debug/windowstolinux/app/db/persistence/repository");
        Path fragments = root.resolve(
                "src/shared/linux-sshd/src/main/resources/gold/debug/windowstolinux/shared/linux/sshd");

        assertEquals(ANALYSIS_CORE, fileNames(core));
        assertEquals(DESKTOP_SHELL, fileNames(shell));
        assertEquals(DEPLOYMENT_SHARED, fileNames(deploymentShared));
        assertEquals(DEPLOYMENT_SINGLE, fileNames(deploymentSingle));
        assertEquals(DEPLOYMENT_MULTI, fileNames(deploymentMulti));
        assertEquals(REPOSITORIES, fileNames(repositories));
        Set<String> expectedResources = new HashSet<>(HELPER_FRAGMENTS);
        expectedResources.add("selinux-preparation.sh");
        assertEquals(expectedResources, fileNamesRecursively(fragments));
        assertEquals(Set.of("selinux-preparation.sh"), fileNames(fragments.resolve("distro/dnf")));
        assertEquals(Set.of("10-native-instances.sh", "20-native-targets.sh", "64-database-client.sh",
                        "65-database-backup.sh", "66-database-activation.sh"),
                fileNames(fragments.resolve("execution/protocol/helper/fragments/database")));
        assertFalse(Files.exists(fragments.resolve("ecosystem")), "native DB resources belong under protocol helper fragments/database");
        assertFalse(Files.exists(fragments.resolve("db")), "old root DB resources must not return");
        assertMaximumLines(core, 400);
        assertMaximumLines(shell, 400);
        assertMaximumLines(deploymentShared, 500);
        assertMaximumLines(deploymentSingle, 500);
        assertMaximumLines(deploymentMulti, 500);
        assertMaximumLines(repositories, 320);
        assertMaximumLinesRecursively(fragments, 300);
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
        for (String required : List.of("ecosystem.java.jar", "build.ecosystem", "build.workload",
                "capability.ecosystem", "contract.result", "distro.apt", "distro.dnf",
                "execution.protocol", "fragments/ecosystem", "generation.script", "persistence.repository",
                "persistence.serialization", "ecosystem.db", "execution.protocol.database", "fragments/database")) {
            assertTrue(structure.contains(required), () -> "File.md is missing the current structure: " + required);
        }

        for (String required : List.of(
                "src/shared/model/src/main/java/gold/debug/windowstolinux/shared/model/ecosystem/db",
                "src/shared/model/src/main/java/gold/debug/windowstolinux/shared/model/ecosystem/db/sql",
                "src/shared/model/src/main/java/gold/debug/windowstolinux/shared/model/ecosystem/db/other",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/db",
                "src/shared/linux/src/main/java/gold/debug/windowstolinux/shared/linux/ecosystem/db",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/execution/protocol/database",
                "src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/display",
                "src/app/main/src/main/java/gold/debug/windowstolinux/app/main/startup",
                "src/app/service/src/main/java/gold/debug/windowstolinux/app/service/contract",
                "src/app/db/src/main/java/gold/debug/windowstolinux/app/db/execution/migration",
                "src/app/db/src/main/java/gold/debug/windowstolinux/app/db/persistence/connection",
                "src/app/db/src/main/java/gold/debug/windowstolinux/app/db/persistence/repository",
                "src/app/db/src/main/java/gold/debug/windowstolinux/app/db/persistence/serialization",
                "src/app/service/src/main/java/gold/debug/windowstolinux/app/service/execution/environment",
                "src/app/service/src/main/java/gold/debug/windowstolinux/app/service/execution/lifecycle",
                "src/app/service/src/main/java/gold/debug/windowstolinux/app/service/lock",
                "src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/setting",
                "src/app/service/src/main/java/gold/debug/windowstolinux/app/service/deployment/single",
                "src/app/service/src/main/java/gold/debug/windowstolinux/app/service/deployment/multi",
                "src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/deployment/single",
                "src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/deployment/multi",
                "src/shared/ai/src/main/java/gold/debug/windowstolinux/shared/ai/collaboration/advice",
                "src/shared/ai/src/main/java/gold/debug/windowstolinux/shared/ai/collaboration/invocation",
                "src/shared/ai/src/main/java/gold/debug/windowstolinux/shared/ai/collaboration/role",
                "src/shared/ai/src/main/java/gold/debug/windowstolinux/shared/ai/generation/prompt",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/contract/policy",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/contract/spi",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/extension/registry",
                "src/shared/config/src/main/java/gold/debug/windowstolinux/shared/config/contract/definition",
                "src/shared/model/src/main/java/gold/debug/windowstolinux/shared/model/language",
                "src/shared/model/src/main/java/gold/debug/windowstolinux/shared/model/server/security",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/contract/result/compatibility",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/contract/result/deployment",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/contract/result/lifecycle",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/contract/spi",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/execution/environment",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/execution/lifecycle",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/execution/transaction",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/extension/adapter",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/extension/registry",
                "src/shared/source/src/main/java/gold/debug/windowstolinux/shared/source/contract/validation",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/contract/spi",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/generation/script",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/ecosystem",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/ecosystem/java",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/ecosystem/kotlin",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/ecosystem/node",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/ecosystem/php",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/ecosystem/python",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/ecosystem/ruby",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/extension/registry",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/workload",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/capability/ecosystem",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/preview",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/service",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/c/cmake",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/java",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/java/maven",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/java/gradle",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/java/jar",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/java/jdk",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/dotnet/dotnetsdk",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/go/gomodule",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/kotlin/gradle",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/kotlin/kotlinc",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/node/npm",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/node/pnpm",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/node/yarn",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/php/composer",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/php/phpcli",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/python/pip",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/python/pipenv",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/python/poetry",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/python/uv",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/ruby/bundler",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/ruby/rubycli",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/ecosystem/rust/cargo",
                "src/shared/ai/src/main/java/gold/debug/windowstolinux/shared/ai/transport",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/distro/apt",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/distro/dnf",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/distro/contract/profile",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/distro/extension/registry",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/distro/generation/script",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/execution/protocol",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/execution/transfer",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/support",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/support/distro",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/support/runtime",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/command")) {
            assertTrue(Files.isDirectory(root.resolve(required)), () -> "current responsibility package is missing: " + required);
        }
        assertTrue(Files.isDirectory(root.resolve(
                "src/shared/linux-sshd/src/main/resources/gold/debug/windowstolinux/shared/linux/sshd/execution/protocol/helper/fragments/ecosystem")),
                "helper ecosystem resource group is missing");
        assertFalse(Files.exists(root.resolve(
                "src/shared/linux-sshd/src/main/resources/gold/debug/windowstolinux/shared/linux/sshd/execution/protocol/helper/fragments/runtime/35-ecosystem-dispatch.sh")),
                "old helper ecosystem resource path returned");
    }

    private static Map<String, String> responsibilityPackages() {
        Map<String, String> packages = new LinkedHashMap<>();
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration",
                "AiDecisionCoordinator", "AiCollaborationDecision", "CollaborationDisposition",
                "DeterministicDecision");
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration.advice",
                "AiAdviceDecision", "RoleAdviceAssessment");
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration.invocation",
                "AiInvocationEvidence", "AiInvocationStatus", "AiRoleInvocationResult");
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration.role",
                "AiCollaborationRoleKind", "AiRoleBinding", "AiRoleContext", "ProjectAnalysisRoleContext",
                "DeploymentRiskRoleContext", "ErrorExplanationRoleContext");

        register(packages, "gold.debug.windowstolinux.app.ui.deployment",
                "ReviewContext");
        register(packages, "gold.debug.windowstolinux.app.ui.deployment.single",
                "DeploymentAnalysisPresenter", "DeploymentForm", "DeploymentPage", "DeploymentPageState");
        register(packages, "gold.debug.windowstolinux.app.ui.deployment.multi",
                "MultiComponentDraftController", "MultiComponentFormState",
                "MultiComponentPage", "MultiComponentPageState",
                "MultiComponentResultPresenter");

        register(packages, "gold.debug.windowstolinux.app.service.deployment.automatic",
                "AutomaticDatabaseUseCase", "AutomaticDeploymentUseCase", "AutomaticInputCompletion", "AutomaticRuntimeResolver",
                "DatabaseInstanceResolver", "DeploymentRuntimeParser", "DeploymentFormUseCase", "ComponentFormUseCase");
        register(packages, "gold.debug.windowstolinux.app.service.config", "DeploymentConfigurationParser");
        register(packages, "gold.debug.windowstolinux.app.ui.deployment.single", "DeploymentInputDialog");
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration.role", "DeploymentInputRoleContext");
        register(packages, "gold.debug.windowstolinux.app.service.deployment",
                "ManagedApplicationIdentityResolver", "ReviewedDeploymentUseCase", "MultiComponentDeploymentUseCase",
                "MultiComponentLifecycleUseCase");
        register(packages, "gold.debug.windowstolinux.app.service.deployment.single",
                "DeploymentHandoff", "DeploymentOutcome");
        register(packages, "gold.debug.windowstolinux.app.service.deployment.multi",
                "ManagedMultiComponentApplication", "ReviewedComponentApplication",
                "ReviewedMultiComponentApplication");

        register(packages, "gold.debug.windowstolinux.shared.deploy.contract.result.compatibility",
                "HostSupportStatus", "HostSupportDecision");
        register(packages, "gold.debug.windowstolinux.shared.deploy.contract.result.deployment",
                "ComponentDeploymentResult", "ComponentTransactionState", "DeploymentEvent", "DeploymentResult",
                "MultiComponentDeploymentResult");
        register(packages, "gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle",
                "ComponentLifecycleResult", "LifecycleActionResult", "MultiComponentLifecycleResult");

        register(packages, "gold.debug.windowstolinux.shared.model.language",
                "LanguageEcosystemType", "LanguageFactKind", "ProjectLanguageFacts", "SourceLanguageType");
        register(packages, "gold.debug.windowstolinux.shared.model.project",
                "DeploymentArchitectureType", "DeploymentBuildToolType", "DeploymentProjectFacts", "DeploymentProjectType",
                "DeploymentRuntimeSpecification", "DeploymentRuntimeAssessment", "DeploymentSupportCatalog", "RuntimeIdentityMode",
                "DeploymentSupportLevel", "DeploymentSupportProfile", "SourceRevision", "ValidatedDeploymentTarget");
        register(packages, "gold.debug.windowstolinux.shared.model.project.component",
                "ComponentDataPath", "ComponentIsolationSpecification", "DeploymentComponent");
        register(packages, "gold.debug.windowstolinux.shared.model.server",
                "CpuMicroarchitectureLevel", "LinuxDistroType", "ManagedHelperProtocolVersion", "ServerIdentity");
        register(packages, "gold.debug.windowstolinux.shared.model.server.security",
                "LinuxSecurityModuleType", "LinuxSecurityState", "LinuxSecurityPosture", "LinuxFirewallKind",
                "LinuxFirewallState", "SelinuxPreparationPlan", "SelinuxPreparationState");
        return Map.copyOf(packages);
    }

    private static Map<String, String> previousResponsibilityPackages() {
        Map<String, String> packages = new LinkedHashMap<>();
        register(packages, "gold.debug.windowstolinux.shared.ai.collaboration",
                "AiAdviceDecision", "RoleAdviceAssessment", "AiInvocationEvidence", "AiInvocationStatus",
                "AiRoleInvocationResult", "AiCollaborationRoleKind", "AiRoleBinding", "AiRoleContext",
                "ProjectAnalysisRoleContext", "DeploymentRiskRoleContext", "ErrorExplanationRoleContext");
        register(packages, "gold.debug.windowstolinux.app.ui.deployment",
                "DeploymentAnalysisPresenter", "DeploymentForm", "DeploymentPage", "DeploymentPageState",
                "MultiComponentDraftController", "MultiComponentFormState",
                "MultiComponentPage", "MultiComponentPageState",
                "MultiComponentResultPresenter");
        register(packages, "gold.debug.windowstolinux.app.service.deployment",
                "DeploymentHandoff", "DeploymentOutcome", "ManagedMultiComponentApplication",
                "MultiComponentReviewInput", "ReviewedComponentApplication", "ReviewedMultiComponentApplication");
        register(packages, "gold.debug.windowstolinux.shared.deploy.result",
                "HostSupportStatus", "HostSupportDecision", "ComponentDeploymentResult", "ComponentTransactionState",
                "DeploymentEvent", "DeploymentResult", "MultiComponentDeploymentResult",
                "ComponentLifecycleResult", "LifecycleActionResult", "MultiComponentLifecycleResult");
        register(packages, "gold.debug.windowstolinux.shared.model.project",
                "LanguageEcosystemType", "LanguageFactKind", "ProjectLanguageFacts", "SourceLanguageType");
        register(packages, "gold.debug.windowstolinux.shared.model.server",
                "LinuxSecurityModuleType", "LinuxSecurityState", "LinuxSecurityPosture", "LinuxFirewallKind",
                "LinuxFirewallState");
        return Map.copyOf(packages);
    }

    private static void register(Map<String, String> packages, String packageName, String... typeNames) {
        for (String typeName : typeNames) {
            String fileName = typeName + ".java";
            String previous = packages.put(fileName, packageName);
            if (previous != null) throw new IllegalStateException("duplicate responsibility type " + fileName);
        }
    }

    private static SourceModel sourceModel() throws IOException {
        SourceModel current = cachedSourceModel;
        if (current != null) return current;
        synchronized (PackageStructureArchitectureTest.class) {
            if (cachedSourceModel == null) cachedSourceModel = loadSourceModel();
            return cachedSourceModel;
        }
    }

    private static SourceModel loadSourceModel() throws IOException {
        List<SourceUnit> units = loadSourceUnits(path -> path.toString().replace('\\', '/').contains("/src/main/java/"));
        List<Path> sources = units.stream().map(SourceUnit::path).toList();

        Map<String, PackageStructureState> packages = new LinkedHashMap<>();
        for (SourceUnit unit : units) {
            Path javaRoot = sourceRoot(unit.path, "main");
            Path module = javaRoot.getParent().getParent().getParent();
            String category = module.getParent().getFileName().toString();
            String moduleName = module.getFileName().toString().replace('-', '.');
            String modulePackage = "gold.debug.windowstolinux." + category + "." + moduleName;
            int depth = unit.packageName.split("\\.").length - modulePackage.split("\\.").length;
            int topLevelTypes = (int) unit.tree.getTypeDecls().stream().filter(ClassTree.class::isInstance).count();
            packages.computeIfAbsent(unit.packageName, ignored -> new PackageStructureState(unit.packageName, depth))
                    .topLevelTypes += topLevelTypes;
        }
        Map<String, PackageStructureFacts> immutablePackages = new LinkedHashMap<>();
        packages.values().forEach(info -> immutablePackages.put(info.name,
                new PackageStructureFacts(info.name, info.depth, info.topLevelTypes)));
        return new SourceModel(List.copyOf(sources), List.copyOf(units), Map.copyOf(immutablePackages));
    }

    private static List<SourceUnit> loadSourceUnits(Predicate<Path> included) throws IOException {
        Path root = projectRoot();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root.resolve("src"))) {
            sources = files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java"))
                    .filter(included).sorted().toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("PackageStructureArchitectureTest requires a JDK compiler");
        List<SourceUnit> units = new ArrayList<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> javaFiles = fileManager.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null,
                    List.of("-proc:none", "-Xlint:none"), null, javaFiles);
            for (CompilationUnitTree tree : task.parse()) {
                Path path = Path.of(tree.getSourceFile().toUri()).toAbsolutePath().normalize();
                String packageName = tree.getPackageName().toString();
                Set<String> references = new LinkedHashSet<>();
                tree.getImports().stream().map(ImportTree::getQualifiedIdentifier).map(Object::toString).forEach(references::add);
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMemberSelect(com.sun.source.tree.MemberSelectTree node, Void unused) {
                        if (node.toString().matches("gold\\.debug\\.windowstolinux\\..*\\.[A-Z][A-Za-z0-9_]*(?:\\..*)?")) references.add(node.toString());
                        return super.visitMemberSelect(node, unused);
                    }
                }.scan(tree, null);
                List<String> imports = List.copyOf(references);
                units.add(new SourceUnit(path, packageName, imports, tree));
            }
        }
        return List.copyOf(units);
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
            if (cycle.isPresent()) return cycle;
        }
        return Optional.empty();
    }

    private static Optional<List<String>> findCycle(String node, Map<String, Set<String>> graph,
                                                    Map<String, Integer> states, List<String> stack) {
        int state = states.getOrDefault(node, 0);
        if (state == 2) return Optional.empty();
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
            if (cycle.isPresent()) return cycle;
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
        for (String name : List.of("CLanguageInspector", "CLanguageInspectorTest", "AiPage",
                "SshSession", "JavaJarManifestInspector")) {
            assertFalse(UNNORMALIZED_ACRONYM.matcher(name).find(), name);
        }
        for (String name : List.of("AILanguageInspector", "SSHSession", "JavaJARInspector",
                "UIPage", "CMAKEInspector", "ProjectHTTP")) {
            assertTrue(UNNORMALIZED_ACRONYM.matcher(name).find(), name);
        }
    }

    @Test
    void sourceAndTestNamesFollowTheUnifiedNamingStandard() throws Exception {
        Path root = projectRoot();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("src"))) {
            for (Path source : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source);
                var declaration = TOP_LEVEL_TYPE.matcher(content);
                if (!declaration.find()) {
                    problems.add(source + " has no top-level type declaration");
                    continue;
                }
                String typeName = declaration.group(1);
                String fileName = source.getFileName().toString();
                if (!fileName.equals(typeName + ".java")) {
                    problems.add(source + " does not match top-level type " + typeName);
                }
                if (UNNORMALIZED_ACRONYM.matcher(typeName).find()) {
                    problems.add(typeName + " contains an unnormalized uppercase abbreviation");
                }
                FORBIDDEN_TYPE_SUFFIXES.stream().filter(typeName::endsWith)
                        .forEach(suffix -> problems.add(typeName + " uses forbidden type suffix " + suffix));
                if (source.toString().contains("src" + java.io.File.separator + "test"
                        + java.io.File.separator + "java")) {
                    if (fileName.endsWith("IT.java")) {
                        problems.add(fileName + " uses the obsolete IT suffix");
                    }
                    boolean executableTest = content.contains("@Test") || content.contains("@ParameterizedTest")
                            || content.contains("@RepeatedTest") || content.contains("@TestFactory");
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
    void allEnumAndRestrictedTypeNamesFollowTheStrictSemanticStandard() throws Exception {
        List<String> problems = new ArrayList<>();
        for (SourceUnit source : loadSourceUnits(ignored -> true)) {
            for (Tree declaration : source.tree.getTypeDecls()) {
                new TreeScanner<Void, String>() {
                    @Override
                    public Void visitClass(ClassTree node, String owner) {
                        String simpleName = node.getSimpleName().toString();
                        if (simpleName.isBlank()) return super.visitClass(node, owner);
                        String qualifiedName = owner == null || owner.isBlank()
                                ? source.packageName + "." + simpleName : owner + "." + simpleName;
                        Set<String> restricted = new LinkedHashSet<>(camelCaseTokens(simpleName));
                        restricted.retainAll(RESTRICTED_TYPE_TOKENS);
                        if (!restricted.isEmpty() && !ALLOWED_RESTRICTED_TYPE_NAMES.contains(simpleName)) {
                            problems.add(qualifiedName + " uses restricted terms " + restricted);
                        }
                        if (PERIOD_NAME.matcher(simpleName).find() || Pattern.compile("(?:^|[A-Z])V[0-9]+(?:$|[A-Z])")
                                .matcher(simpleName).find()) {
                            problems.add(qualifiedName + " uses a phase or version name");
                        }
                        if (node.getKind() == Tree.Kind.ENUM) {
                            if (OBSOLETE_ENUM_NAMES.contains(simpleName)) {
                                problems.add(qualifiedName + " uses an obsolete enum name");
                            }
                            if (ENUM_SUFFIXES.stream().noneMatch(simpleName::endsWith)) {
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
    void topLevelTypeNamesAreUniqueAcrossProductionAndTests() throws Exception {
        Map<String, List<Path>> declarations = new LinkedHashMap<>();
        for (SourceUnit source : loadSourceUnits(ignored -> true)) {
            source.tree.getTypeDecls().stream().filter(ClassTree.class::isInstance).map(ClassTree.class::cast)
                    .forEach(type -> declarations.computeIfAbsent(type.getSimpleName().toString(), ignored -> new ArrayList<>())
                            .add(source.path));
        }
        Map<String, List<Path>> duplicates = new LinkedHashMap<>();
        declarations.forEach((name, paths) -> {
            if (paths.size() > 1) duplicates.put(name, List.copyOf(paths));
        });
        assertTrue(duplicates.isEmpty(), () -> "duplicate top-level type names: " + duplicates);
    }

    @Test
    void maintainedResourceFileNamesUseStableTerms() throws Exception {
        Path root = projectRoot();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("src"))) {
            for (Path resource : files.filter(Files::isRegularFile).filter(path -> {
                String value = path.toString();
                return value.contains("src" + java.io.File.separator + "main" + java.io.File.separator + "resources")
                        || value.contains("src" + java.io.File.separator + "test" + java.io.File.separator + "resources");
            }).toList()) {
                String fileName = resource.getFileName().toString();
                String stem = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
                Set<String> tokens = new LinkedHashSet<>(List.of(stem.toLowerCase(java.util.Locale.ROOT)
                        .split("[^a-z0-9]+")));
                Set<String> forbidden = new LinkedHashSet<>(tokens);
                forbidden.retainAll(FORBIDDEN_RESOURCE_TOKENS);
                if (!forbidden.isEmpty()) problems.add(root.relativize(resource) + " uses forbidden terms " + forbidden);
                if (PERIOD_NAME.matcher(stem).find() || tokens.stream().anyMatch(token -> token.matches("v[0-9]+"))) {
                    problems.add(root.relativize(resource) + " uses a phase or version name");
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "resource naming violations: " + problems);
    }

    private static Set<String> camelCaseTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        var matcher = Pattern.compile("[A-Z](?:[a-z0-9]+|[A-Z]*(?=[A-Z]|$))").matcher(value);
        while (matcher.find()) tokens.add(matcher.group());
        return Set.copyOf(tokens);
    }

    @Test
    void languageInspectorsStayAtEcosystemRootsAndDoNotDependOnBuildArchitectures() throws Exception {
        String root = "gold.debug.windowstolinux.shared.analyze.ecosystem.";
        Map<String, String> inspectors = Map.of(
                "c", "CLanguageInspector", "dotnet", "DotNetLanguageInspector", "go", "GoLanguageInspector",
                "java", "JavaLanguageInspector", "kotlin", "KotlinLanguageInspector", "node", "NodeLanguageInspector",
                "php", "PhpLanguageInspector", "python", "PythonLanguageInspector",
                "ruby", "RubyLanguageInspector", "rust", "RustLanguageInspector");
        Set<String> found = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (SourceUnit unit : sourceModel().units) {
            String file = unit.path.getFileName().toString();
            if (!unit.packageName.startsWith(root) || !file.endsWith("LanguageInspector.java")) continue;
            String ecosystem = unit.packageName.substring(root.length());
            if (!file.equals(inspectors.get(ecosystem) + ".java")) {
                problems.add(unit.path + " must be the language inspector at its ecosystem root");
            }
            found.add(ecosystem);
            for (String dependency : unit.imports) {
                if ((dependency.startsWith(root)
                        && dependency.substring(root.length()).split("\\.").length > 2)
                        || dependency.startsWith("java.util.jar.")) {
                    problems.add(unit.path + " depends on build or archive parsing: " + dependency);
                }
            }
        }
        assertEquals(ECOSYSTEM_CLASSIFICATION_PACKAGES, found, "every language ecosystem needs its own inspector");
        assertTrue(problems.isEmpty(), () -> "language/build boundary violations: " + problems);
    }

    @Test
    void packageTreeIsFlatBoundedAndFreeOfObsoletePaths() throws Exception {
        SourceModel model = sourceModel();
        List<String> problems = new ArrayList<>();
        model.packages.values().forEach(info -> {
            if (info.depth > 3) problems.add(info.name + " is deeper than three package levels");
            verifyFunctionalGroupPackage(info.name, problems);
            Set<String> segments = Set.of(info.name.split("\\."));
            Set<String> forbidden = new HashSet<>(segments);
            forbidden.retainAll(FORBIDDEN_PACKAGE_SEGMENTS);
            if (!forbidden.isEmpty()) problems.add(info.name + " uses forbidden package segments " + forbidden);
            String ecosystemRoot = "gold.debug.windowstolinux.shared.analyze.ecosystem.";
            if (info.name.startsWith(ecosystemRoot)) {
                String[] classification = info.name.substring(ecosystemRoot.length()).split("\\.");
                String axis = classification[0];
                if (!axis.equals("db") && !ECOSYSTEM_CLASSIFICATION_PACKAGES.contains(axis)) {
                    problems.add(info.name + " creates an unapproved analysis ecosystem axis");
                }
                if (!axis.equals("db") && classification.length == 2
                        && !BUILD_ARCHITECTURE_PACKAGE_NAMES.contains(classification[1])) {
                    problems.add(info.name + " does not use a reviewed build architecture package name");
                }
            }
            for (String module : List.of("model", "linux")) {
                String root = "gold.debug.windowstolinux.shared." + module + ".ecosystem";
                if ((info.name.equals(root) || info.name.startsWith(root + "."))
                        && !info.name.equals(root + ".db") && !info.name.startsWith(root + ".db.")) {
                    problems.add(info.name + " creates an unapproved module ecosystem axis; only db is reviewed");
                }
            }
            int databaseCategory = info.name.indexOf(".ecosystem.db.");
            if (databaseCategory >= 0
                    && !Set.of("sql", "document", "other").contains(
                            info.name.substring(databaseCategory + ".ecosystem.db.".length()))) {
                problems.add(info.name + " does not use a reviewed database category");
            }
            verifyExecutionEcosystemAxis(info.name,
                    "gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.", problems);
            verifyExecutionEcosystemAxis(info.name,
                    "gold.debug.windowstolinux.shared.linux.sshd.capability.ecosystem.", problems);
            String distroRoot = "gold.debug.windowstolinux.shared.linux.sshd.distro.";
            if (info.name.startsWith(distroRoot)) {
                String axis = info.name.substring(distroRoot.length()).split("\\.")[0];
                if (!DISTRO_CLASSIFICATION_PACKAGES.contains(axis)) {
                    problems.add(info.name + " creates an unapproved distribution classification axis");
                }
                if ((axis.equals("apt") || axis.equals("dnf")) && !info.name.equals(distroRoot + axis)) {
                    problems.add(info.name + " must place distribution classes directly in the package-manager package");
                }
            }
        });
        Path projectRootPath = projectRoot();
        try (Stream<Path> directories = Files.walk(projectRootPath.resolve("src"))) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                String normalized = projectRootPath.relativize(directory).toString()
                        .replace(java.io.File.separatorChar, '/');
                if (!normalized.contains("/src/main/java/") && !normalized.contains("/src/test/java/")) continue;
                for (String prefix : MIGRATED_PACKAGE_PREFIXES) {
                    String oldPath = "/" + prefix.replace('.', '/');
                    if (normalized.endsWith(oldPath) || normalized.contains(oldPath + "/")) {
                        problems.add(directory + " retains migrated package path " + prefix);
                    }
                }
            }
        }
        for (SourceUnit unit : model.units) {
            String fileName = unit.path.getFileName().toString();
            Path physicalPath = sourceRoot(unit.path, "main")
                    .resolve(unit.packageName.replace('.', java.io.File.separatorChar)).resolve(fileName)
                    .toAbsolutePath().normalize();
            if (!physicalPath.equals(unit.path)) {
                problems.add(fileName + " is stored at " + unit.path + " rather than " + physicalPath);
            }
            boolean governed = RESPONSIBILITY_ROOTS.stream().anyMatch(root -> unit.packageName.equals(root)
                    || unit.packageName.startsWith(root + "."));
            if (!governed) continue;
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
            OLD_PACKAGE_PREFIXES.stream().filter(content::contains)
                    .forEach(prefix -> problems.add(source + " references old package " + prefix));
            PREVIOUS_RESPONSIBILITY_PACKAGES.forEach((fileName, oldPackage) -> {
                String oldType = oldPackage + "." + fileName.substring(0, fileName.length() - ".java".length());
                if (content.contains(oldType)) problems.add(source + " references old type " + oldType);
            });
            DELETED_WRAPPERS.stream().filter(name -> content.contains(name + ".") || content.contains("new " + name + "("))
                    .forEach(name -> problems.add(source + " references deleted wrapper " + name));
        });
        assertTrue(problems.isEmpty(), () -> "package structure violations: " + problems);
    }

    private static void verifyFunctionalGroupPackage(String packageName, List<String> problems) {
        String[] segments = packageName.split("\\.");
        for (int index = 0; index < segments.length; index++) {
            Set<String> allowedResponsibilities = FUNCTIONAL_GROUP_RESPONSIBILITIES.get(segments[index]);
            if (allowedResponsibilities != null && index + 1 < segments.length
                    && !allowedResponsibilities.contains(segments[index + 1])) {
                problems.add(packageName + " places " + segments[index + 1]
                        + " directly below functional group " + segments[index]);
            }
            String expectedGroup = RESPONSIBILITY_FUNCTIONAL_GROUPS.get(segments[index]);
            boolean hasFunctionalGroupAncestor = false;
            for (int ancestor = 0; ancestor < index; ancestor++) {
                if (FUNCTIONAL_GROUP_RESPONSIBILITIES.containsKey(segments[ancestor])) {
                    hasFunctionalGroupAncestor = true;
                    break;
                }
            }
            if (expectedGroup != null && (index == 0 || !expectedGroup.equals(segments[index - 1]))
                    && !hasFunctionalGroupAncestor
                    && !isUngroupedResponsibilityException(packageName)) {
                problems.add(packageName + " leaves responsibility " + segments[index]
                        + " outside functional group " + expectedGroup);
            }
        }
    }

    private static boolean isUngroupedResponsibilityException(String packageName) {
        String remoteContractRoot = "gold.debug.windowstolinux.shared.linux";
        String sshdImplementationRoot = remoteContractRoot + ".sshd";
        if (packageName.equals(remoteContractRoot)
                || packageName.startsWith(remoteContractRoot + ".")
                && !packageName.equals(sshdImplementationRoot)
                && !packageName.startsWith(sshdImplementationRoot + ".")) {
            return true;
        }
        return UNGROUPED_RESPONSIBILITY_EXCEPTIONS.stream()
                .anyMatch(prefix -> packageName.equals(prefix) || packageName.startsWith(prefix + "."));
    }

    private static void verifyExecutionEcosystemAxis(String packageName, String root, List<String> problems) {
        if (!packageName.startsWith(root)) return;
        String classification = packageName.substring(root.length());
        if (classification.contains(".") || !ECOSYSTEM_CLASSIFICATION_PACKAGES.contains(classification)) {
            problems.add(packageName + " creates an unapproved ecosystem execution axis");
        }
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
        assertTrue(cycle.isEmpty(), () -> "production package dependency cycle: "
                + String.join(" -> ", cycle.orElseThrow()));
    }

    @Test
    void responsibilityPackagesDoNotReverseTheirDependencyDirection() throws Exception {
        SourceModel model = sourceModel();
        List<String> problems = new ArrayList<>();
        for (SourceUnit unit : model.units) {
            for (String imported : unit.imports) {
                resolveImportedPackage(imported, model.packages.keySet()).ifPresent(target ->
                        FORBIDDEN_RESPONSIBILITY_DEPENDENCIES.stream()
                                .filter(rule -> rule.sourcePackage.equals(unit.packageName))
                                .filter(rule -> rule.forbiddenTargetPackages.contains(target))
                                .forEach(rule -> problems.add(unit.path + " reverses dependency direction to " + target)));
            }
        }
        assertTrue(problems.isEmpty(), () -> "responsibility dependency direction violations: " + problems);
    }

    @Test
    void linuxAndUiHonorTheNarrowExecutionAndServiceBoundaries() throws Exception {
        var allowedUiServiceTypes = Set.of(
                "gold.debug.windowstolinux.app.service.ai.AiProviderProfile",
                "gold.debug.windowstolinux.app.service.ai.AiRoleAssignment",
                "gold.debug.windowstolinux.app.service.backup.BackupArchiveInspection",
                "gold.debug.windowstolinux.app.service.backup.CreatedBackupArchive",
                "gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment",
                "gold.debug.windowstolinux.app.service.backup.ManagedOfflineMigrationOutcome",
                "gold.debug.windowstolinux.app.service.backup.ManagedRestoreOutcome",
                "gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate",
                "gold.debug.windowstolinux.app.service.backup.PreparedBackupSecrets",
                "gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication",
                "gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff",
                "gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot",
                "gold.debug.windowstolinux.app.service.server.ServerProfile",
                "gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource",
                "gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation");
        List<String> problems = new ArrayList<>();
        for (SourceUnit unit : sourceModel().units) {
            for (String reference : unit.imports) {
                if (unit.packageName.startsWith("gold.debug.windowstolinux.shared.linux")
                        && reference.startsWith("gold.debug.windowstolinux.shared.config."))
                    problems.add(unit.path + " depends on configuration policy: " + reference);
                if (unit.packageName.startsWith("gold.debug.windowstolinux.app.ui.")) {
                    if (reference.startsWith("gold.debug.windowstolinux.app.db.")
                            || reference.startsWith("gold.debug.windowstolinux.shared.linux."))
                        problems.add(unit.path + " reaches a database implementation or remote port: " + reference);
                    if (reference.startsWith("gold.debug.windowstolinux.app.service.")
                            && !reference.startsWith("gold.debug.windowstolinux.app.service.contract.")
                            && allowedUiServiceTypes.stream().noneMatch(type -> (reference.equals(type) || reference.startsWith(type + "."))))
                        problems.add(unit.path + " calls service internals: " + reference);
                }
            }
        }
        String pom = Files.readString(projectRoot().resolve("src/shared/linux/pom.xml"));
        var dependencies = Pattern.compile("<artifactId>(windowstolinux-[^<]+)</artifactId>").matcher(
                pom.substring(pom.indexOf("<dependencies>")));
        while (dependencies.find()) assertEquals("windowstolinux-shared-model", dependencies.group(1));
        assertTrue(problems.isEmpty(), () -> "execution/service boundary violations: " + problems);
    }

    @Test
    void relocatedTestsAndRemovedDirectoriesMatchTheirResponsibilities() throws Exception {
        Path root = projectRoot();
        for (var entry : Map.of("DeploymentRuntimeParserTest", "deployment/automatic",
                "DeploymentConfigurationParserTest", "config").entrySet()) {
            Path test = root.resolve("src/app/service/src/test/java/gold/debug/windowstolinux/app/service/"
                    + entry.getValue() + "/" + entry.getKey() + ".java");
            assertTrue(Files.isRegularFile(test));
            assertTrue(Files.readString(test).contains("package gold.debug.windowstolinux.app.service."
                    + entry.getValue().replace('/', '.') + ";"));
            assertFalse(Files.exists(root.resolve("src/app/ui/src/test/java/gold/debug/windowstolinux/app/ui/deployment/"
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
                                ? node.getSimpleName().toString() : owner + "." + node.getSimpleName();
                        if (node.getKind() == Tree.Kind.CLASS) {
                            long fields = node.getMembers().stream().filter(VariableTree.class::isInstance)
                                    .map(VariableTree.class::cast)
                                    .filter(field -> !field.getModifiers().getFlags().contains(Modifier.STATIC)).count();
                            long methods = node.getMembers().stream().filter(MethodTree.class::isInstance)
                                    .map(MethodTree.class::cast)
                                    .filter(method -> !method.getName().contentEquals("<init>")).count();
                            if (fields > 25) problems.add(className + " has " + fields + " instance fields");
                            if (methods > 30 && !className.endsWith("DesktopApplicationFacade")) {
                                problems.add(className + " has " + methods + " directly declared methods");
                            }
                            node.getMembers().stream().filter(MethodTree.class::isInstance)
                                    .map(MethodTree.class::cast).filter(method -> method.getBody() != null)
                                    .forEach(method -> {
                                        int statements = new StatementCounter().scan(method.getBody(), null);
                                        if (statements > 80) {
                                            problems.add(className + "." + method.getName()
                                                    + " has " + statements + " AST statements");
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
    void testPackagesMirrorProductionAndAppMainIsTheOnlyEntrypoint() throws Exception {
        Path root = projectRoot();
        List<Path> missingMirrors = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("src"))) {
            for (Path test : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("src" + java.io.File.separator + "test"
                            + java.io.File.separator + "java")).toList()) {
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
        assertEquals(List.of("AppMain.java:main"), entrypoints);
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
                try (Stream<String> lines = Files.lines(file)) {
                    long count = lines.count();
                    assertTrue(count <= maximum, () -> file + " carries " + count + " lines across declared responsibilities");
                }
            }
        }
    }

    private static void assertMaximumLinesRecursively(Path directory, long maximum) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
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

    private record SourceModel(List<Path> sources, List<SourceUnit> units, Map<String, PackageStructureFacts> packages) {
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
            if (tree == null) return 0;
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
