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

import gold.debug.windowstolinux.shared.analyze.ecosystem.jvm.SpringBootDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspection;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.renderer.SpringBootBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdHealthChecker;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdLifecycleExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdOwnershipObserver;

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

class PackageStructureBoundaryTest {
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
    private static final Set<String> ANALYSIS_CORE = Set.of("DeploymentAnalysisCoordinator.java");
    private static final Set<String> DESKTOP_SHELL = Set.of(
            "DesktopDisplayChangeListener.java", "DesktopFrame.java", "DesktopPageCoordinator.java",
            "DesktopViewState.java", "PageNavigator.java");
    private static final Set<String> DEPLOYMENT_PAGE = Set.of(
            "DeploymentAnalysisPresenter.java", "DeploymentConfigurationParser.java", "DeploymentPage.java",
            "DeploymentForm.java", "DeploymentPageState.java", "DeploymentRuntimeParser.java", "ReviewContext.java",
            "MultiComponentDraft.java", "MultiComponentFormState.java", "MultiComponentHealthMode.java",
            "MultiComponentEditor.java", "MultiComponentPage.java", "MultiComponentPageState.java",
            "MultiComponentResultPresenter.java");
    private static final Set<String> REPOSITORIES = Set.of(
            "AiProfileRepository.java", "ApplicationSecretRepository.java", "ConfigurationSnapshotRepository.java",
            "DesktopPreferenceRepository.java", "EncryptedSecretRepository.java", "ManagedApplicationRepository.java",
            "ManagedApplicationGraphRepository.java", "RepositoryTransactions.java", "ServerProfileRepository.java");
    private static final Set<String> HELPER_FRAGMENTS = Set.of(
            "00-common.sh", "10-typed-release.sh", "15-deployment-input.sh", "20-candidate-workspace.sh", "30-ordinary-release.sh",
            "35-ecosystem-dispatch.sh", "40-typed-runtime.sh", "50-container-release.sh", "55-podman-quadlet.sh", "60-lifecycle.sh",
            "70-command-dispatch.sh");
    private static final Pattern PERIOD_NAME = Pattern.compile("(?i)(?:phase|stage)[-_]?[0-9]+|(?:一期|二期|三期|四期|五期)");
    private static final Set<String> ALLOWED_DEPTH_TWO_SINGLE_TYPE_PACKAGES = Set.of(
            "gold.debug.windowstolinux.shared.linux.sshd.build.spi",
            "gold.debug.windowstolinux.shared.linux.sshd.protocol.helper");
    private static final Set<String> FORBIDDEN_PACKAGE_SEGMENTS = Set.of("util", "common", "misc", "impl");
    private static final Set<String> DELETED_WRAPPERS = Set.of(
            "GoServiceDeploymentInspector", "RustServiceDeploymentInspector", "DotNetServiceDeploymentInspector",
            "KotlinServiceDeploymentInspector", "PhpServiceDeploymentInspector", "RubyServiceDeploymentInspector",
            "GoBuildRenderer", "RustBuildRenderer", "DotNetBuildRenderer", "KotlinBuildRenderer",
            "PhpBuildRenderer", "RubyBuildRenderer", "UbuntuSetup", "DebianSetup", "CentosStreamSetup",
            "RockyLinuxSetup", "AlmaLinuxSetup", "OracleLinuxSetup");
    private static final List<String> OLD_PACKAGE_PREFIXES = List.of(
            "gold.debug.windowstolinux.app.secret.api", "gold.debug.windowstolinux.app.secret.store",
            "gold.debug.windowstolinux.app.secret.windows", "gold.debug.windowstolinux.shared.git.reference",
            "gold.debug.windowstolinux.shared.git.remote", "gold.debug.windowstolinux.shared.analyze.source.metadata",
            "gold.debug.windowstolinux.shared.analyze.workload.container",
            "gold.debug.windowstolinux.shared.analyze.workload.staticweb",
            "gold.debug.windowstolinux.shared.deploy.adapter.service",
            "gold.debug.windowstolinux.shared.deploy.adapter.workload",
            "gold.debug.windowstolinux.shared.deploy.support.distro",
            "gold.debug.windowstolinux.shared.linux.sshd.ecosystem",
            "gold.debug.windowstolinux.shared.linux.sshd.workload",
            "gold.debug.windowstolinux.shared.linux.sshd.build.config",
            "gold.debug.windowstolinux.shared.linux.sshd.build.shell",
            "gold.debug.windowstolinux.shared.linux.sshd.build.registry",
            "gold.debug.windowstolinux.shared.linux.sshd.capability.probe",
            "gold.debug.windowstolinux.shared.linux.sshd.distro.profile",
            "gold.debug.windowstolinux.shared.linux.sshd.distro.registry",
            "gold.debug.windowstolinux.shared.linux.sshd.distro.spi",
            "gold.debug.windowstolinux.shared.linux.sshd.protocol.workspace",
            "gold.debug.windowstolinux.shared.linux.sshd.runtime.container",
            "gold.debug.windowstolinux.shared.linux.sshd.runtime.dispatch");
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
    }

    @Test
    void coreShellRepositoriesAndHelperResourcesStayFocused() throws Exception {
        Path root = projectRoot();
        Path core = root.resolve("src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/core");
        Path shell = root.resolve("src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/shell");
        Path deploymentPage = root.resolve("src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/deployment");
        Path repositories = root.resolve("src/app/db/src/main/java/gold/debug/windowstolinux/app/db/repository");
        Path fragments = root.resolve(
                "src/shared/linux-sshd/src/main/resources/gold/debug/windowstolinux/shared/linux/sshd");

        assertEquals(ANALYSIS_CORE, fileNames(core));
        assertEquals(DESKTOP_SHELL, fileNames(shell));
        assertEquals(DEPLOYMENT_PAGE, fileNames(deploymentPage));
        assertEquals(REPOSITORIES, fileNames(repositories));
        assertEquals(HELPER_FRAGMENTS, fileNamesRecursively(fragments));
        assertMaximumLines(core, 400);
        assertMaximumLines(shell, 400);
        assertMaximumLines(deploymentPage, 500);
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
        for (String required : List.of("DeploymentAnalysisCoordinator", "SpringBootDeploymentInspector",
                "DesktopPageCoordinator", "DesktopPersistence", "GitSnapshotPreparer", "DeploymentBuildRenderer",
                "SpringBootBuildRenderer", "SystemdHealthChecker", "SystemdOwnershipObserver",
                "SystemdLifecycleExecutor", "ManagedHelperBundle", "DesktopDisplaySettings",
                "BuildConfigEnvironment", "PreviewInspector", "HostSupportChecker", "EnvironmentSetup",
                "DistributionSetupProfiles")) {
            assertTrue(structure.contains(required), () -> "File.md is missing the current responsibility: " + required);
        }

        for (String required : List.of(
                "src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/display",
                "src/app/main/src/main/java/gold/debug/windowstolinux/app/main/startup",
                "src/app/service/src/main/java/gold/debug/windowstolinux/app/service/locking",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/build/script",
                "src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/preview",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/distro/setup",
                "src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/support",
                "src/shared/linux-sshd/src/main/java/gold/debug/windowstolinux/shared/linux/sshd/command")) {
            assertTrue(Files.isDirectory(root.resolve(required)), () -> "current responsibility package is missing: " + required);
        }
    }

    private static SourceModel sourceModel() throws IOException {
        SourceModel current = cachedSourceModel;
        if (current != null) return current;
        synchronized (PackageStructureBoundaryTest.class) {
            if (cachedSourceModel == null) cachedSourceModel = loadSourceModel();
            return cachedSourceModel;
        }
    }

    private static SourceModel loadSourceModel() throws IOException {
        Path root = projectRoot();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root.resolve("src"))) {
            sources = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("src" + java.io.File.separator + "main"
                            + java.io.File.separator + "java"))
                    .sorted().toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("PackageStructureBoundaryTest requires a JDK compiler");
        List<SourceUnit> units = new ArrayList<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> javaFiles = fileManager.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null,
                    List.of("-proc:none", "-Xlint:none"), null, javaFiles);
            for (CompilationUnitTree tree : task.parse()) {
                Path path = Path.of(tree.getSourceFile().toUri()).toAbsolutePath().normalize();
                String packageName = tree.getPackageName().toString();
                List<String> imports = tree.getImports().stream().map(ImportTree::getQualifiedIdentifier)
                        .map(Object::toString).toList();
                units.add(new SourceUnit(path, packageName, imports, tree));
            }
        }

        Map<String, MutablePackageInfo> packages = new LinkedHashMap<>();
        for (SourceUnit unit : units) {
            Path javaRoot = sourceRoot(unit.path, "main");
            Path module = javaRoot.getParent().getParent().getParent();
            String category = module.getParent().getFileName().toString();
            String moduleName = module.getFileName().toString().replace('-', '.');
            String modulePackage = "gold.debug.windowstolinux." + category + "." + moduleName;
            int depth = unit.packageName.split("\\.").length - modulePackage.split("\\.").length;
            int topLevelTypes = (int) unit.tree.getTypeDecls().stream().filter(ClassTree.class::isInstance).count();
            packages.computeIfAbsent(unit.packageName, ignored -> new MutablePackageInfo(unit.packageName, depth))
                    .topLevelTypes += topLevelTypes;
        }
        Map<String, PackageInfo> immutablePackages = new LinkedHashMap<>();
        packages.values().forEach(info -> immutablePackages.put(info.name,
                new PackageInfo(info.name, info.depth, info.topLevelTypes)));
        return new SourceModel(List.copyOf(sources), List.copyOf(units), Map.copyOf(immutablePackages));
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
    void packageTreeIsFlatBoundedAndFreeOfObsoletePaths() throws Exception {
        SourceModel model = sourceModel();
        assertTrue(model.packages.size() >= 105 && model.packages.size() <= 115,
                () -> "production package count must remain within the reviewed range: " + model.packages.size());

        List<String> problems = new ArrayList<>();
        model.packages.values().forEach(info -> {
            if (info.depth > 2) problems.add(info.name + " is deeper than two package levels");
            if (info.topLevelTypes > 15) problems.add(info.name + " has " + info.topLevelTypes + " top-level types");
            if (info.depth == 2 && info.topLevelTypes == 1
                    && !ALLOWED_DEPTH_TWO_SINGLE_TYPE_PACKAGES.contains(info.name)) {
                problems.add(info.name + " is an unapproved depth-two single-type package");
            }
            Set<String> segments = Set.of(info.name.split("\\."));
            Set<String> forbidden = new HashSet<>(segments);
            forbidden.retainAll(FORBIDDEN_PACKAGE_SEGMENTS);
            if (!forbidden.isEmpty()) problems.add(info.name + " uses forbidden package segments " + forbidden);
        });
        model.sources.forEach(source -> {
            String content;
            try {
                content = Files.readString(source);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            OLD_PACKAGE_PREFIXES.stream().filter(content::contains)
                    .forEach(prefix -> problems.add(source + " references old package " + prefix));
            DELETED_WRAPPERS.stream().filter(name -> content.contains(name + ".") || content.contains("new " + name + "("))
                    .forEach(name -> problems.add(source + " references deleted wrapper " + name));
        });
        assertTrue(problems.isEmpty(), () -> "package structure violations: " + problems);
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
                            if (methods > 30 && !className.endsWith("DesktopApplicationService")) {
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

    private record SourceModel(List<Path> sources, List<SourceUnit> units, Map<String, PackageInfo> packages) {
    }

    private record SourceUnit(Path path, String packageName, List<String> imports, CompilationUnitTree tree) {
    }

    private record PackageInfo(String name, int depth, int topLevelTypes) {
    }

    private static final class MutablePackageInfo {
        private final String name;
        private final int depth;
        private int topLevelTypes;

        private MutablePackageInfo(String name, int depth) {
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
