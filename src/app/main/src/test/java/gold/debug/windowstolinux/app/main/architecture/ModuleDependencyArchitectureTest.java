package gold.debug.windowstolinux.app.main.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/** Checks declared and actual dependencies against the formal document. / 按正式文档检查声明依赖与实际依赖。 */
class ModuleDependencyArchitectureTest {
    private static final String ROOT = "gold.debug.windowstolinux.";

    static final Set<String> UI_RESCUE_TYPES = Set.of(ROOT + "app.linux.ssh.recover.SshRecovery",
        ROOT + "app.linux.ssh.recover.SshRecoverySession", ROOT + "app.linux.ssh.recover.BrowserRecoverySnapshot",
        ROOT + "app.linux.ssh.recover.model.TerminalTarget");

    private static final String DEPENDENCIES_ANCHOR = "<a id=\"module-dependencies\"></a>";

    private static final Set<String> UI_EXECUTION_TYPES = Set.of(ROOT + "appapi.linux.LinuxService",
        ROOT + "appapi.linux.session.RemoteSession", ROOT + "appapi.task.process.ProcessService",
        ROOT + "appapi.task.process.ManagedProcess", ROOT + "appapi.file.FileService",
        ROOT + "appapi.file.query.FileQueryService", ROOT + "appapi.file.action.FileActionService", ROOT + "appapi.file.source.FileSourceService", ROOT + "appapi.file.snapshot.FileSnapshotService", ROOT + "appapi.file.work.FileWorkspaceService", ROOT + "appapi.file.work.FileWorkspace", ROOT + "appapi.file.material.FileMaterialService", ROOT + "appapi.file.material.FileMaterialHold", ROOT + "appapi.secret.SecretService",
        ROOT + "appapi.db.ModDatabase", ROOT + "appapi.db.schema.SchemaAccess");

    @Test
    void productionDependenciesMatchDocumentationAndDirectPomDeclarations() throws Exception {
        String document = Files.readString(ArchitectureSourceChecker.projectRoot().resolve("docs/File.md"));
        var allowed = documentedDependencies(document);
        var declared = declaredDependencies();
        List<String> violations = pomViolations(allowed, declared);
        for (var source : ArchitectureSourceChecker.production()) {
            String owner = moduleFor(source.packageName(), declared.keySet());
            assertNotNull(owner, source.path().toString());
            violations.addAll(referenceViolations(owner, source.references(), allowed, declared).stream()
                .map(message -> source.path() + ": " + message).toList());
            if (owner.equals("app/ui"))
                violations.addAll(executionCalls(source));
        }
        assertTrue(violations.isEmpty(), () -> "module dependency violations: " + violations);

    }

    @Test
    void forbiddenPomDependenciesAndMissingDirectDependenciesCannotPass() throws Exception {
        var allowed = documentedDependencies(
            Files.readString(ArchitectureSourceChecker.projectRoot().resolve("docs/File.md")));
        var declared = new HashMap<>(declaredDependencies());
        declared.put("app/ui", Set.of("app/db"));
        assertFalse(pomViolations(allowed, declared).isEmpty());
        declared = new HashMap<>(declaredDependencies());
        var service = new HashSet<>(declared.get("app/service"));
        service.remove("app/file");
        declared.put("app/service", service);
        assertFalse(referenceViolations("app/service", List.of(ROOT + "app.file.SourceWorkDirectory"),
            allowed, declared).isEmpty());
    }

    @Test
    void fullyQualifiedReferencesCannotBypassTheUiBoundary() throws Exception {
        var allowed = documentedDependencies(
            Files.readString(ArchitectureSourceChecker.projectRoot().resolve("docs/File.md")));
        var declared = declaredDependencies();
        var source = ArchitectureSourceChecker.snippets(Map.of("fixture.Refs", """
            package fixture;
            class Refs {
                gold.debug.windowstolinux.app.linux.ssh.connect.SshConnector transfer;
            }
            """)).getFirst();
        assertFalse(referenceViolations("app/ui", source.references(), allowed, declared).isEmpty());
        assertEquals(List.of(),
            referenceViolations("app/ui", List.of(ROOT + "appapi.task.result.Problem"), allowed, declared));
        assertFalse(referenceViolations("app/ui", List.of(ROOT + "appapi.linux.session.RemoteSession"), allowed, declared)
            .isEmpty());
        var indirect = ArchitectureSourceChecker.snippets(Map.of("fixture.Indirect", """
            package fixture;
            class Indirect {
                gold.debug.windowstolinux.appapi.mod.ModContext context;
                void run() throws gold.debug.windowstolinux.appapi.task.result.ApiException { var linux = context.linux(); linux.connect((gold.debug.windowstolinux.appapi.server.model.ServerId) null, null); }
            }
            """)).getFirst();
        assertFalse(executionCalls(indirect).isEmpty());
        var process = ArchitectureSourceChecker.snippets(Map.of("fixture.ProcessUse", """
            package fixture;
            class ProcessUse {
                void run(gold.debug.windowstolinux.appapi.mod.ModContext context,
                    gold.debug.windowstolinux.appapi.task.run.TaskContext task)
                    throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                    var processes = context.processes(); processes.start(null, task);
                }
            }
            """)).getFirst();
        assertFalse(executionCalls(process).isEmpty());
    }

    @Test
    void uiCanUseControlledRescueButCannotReachTransportBrowserOrHostPorts() throws Exception {
        var allowed = documentedDependencies(
            Files.readString(ArchitectureSourceChecker.projectRoot().resolve("docs/File.md")));
        var declared = declaredDependencies();
        assertEquals(List.of(), referenceViolations("app/ui", List.of(ROOT + "app.linux.ssh.recover.SshRecoverySession",
                ROOT + "app.linux.ssh.recover.SshRecovery.Server", ROOT + "app.linux.ssh.recover.BrowserRecoverySnapshot.Action"),
            allowed, declared));
        for (String type : List.of("ssh.connect.SshConnector", "ssh.recover.BrowserRecoverySession",
            "ssh.recover.browser.BrowserTerminalSession", "ssh.recover.browser.TaskBrowserSession", "ssh.recover.RecoveryHost",
            "ssh.recover.ai.PlatformRecoveryModels", "sftp.SftpTransfer"))
            assertFalse(referenceViolations("app/ui", List.of(ROOT + "app.linux." + type), allowed, declared).isEmpty(), type);
    }

    @Test
    void uiCannotInvokeAnyGroupedFileCapabilityDirectly() throws Exception {
        var allowed = documentedDependencies(Files.readString(ArchitectureSourceChecker.projectRoot().resolve("docs/File.md")));
        var declared = declaredDependencies();
        for (var entry : Map.of("query.FileQueryService", "readFile(null, 1, null)",
            "action.FileActionService", "createFile(null, null)",
            "source.FileSourceService", "read(null, null, null)",
            "snapshot.FileSnapshotService", "snapshot(null, null, null)",
            "work.FileWorkspaceService", "workspace(null)", "material.FileMaterialService", "find(null, null, null)").entrySet()) {
            var source = ArchitectureSourceChecker.snippets(Map.of("fixture.FileUse", """
                package fixture;
                class FileUse {
                    void run(gold.debug.windowstolinux.appapi.file.%s capability)
                        throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                        var observed = capability.%s;
                    }
                }
                """.formatted(entry.getKey(), entry.getValue()))).getFirst();
            assertFalse(referenceViolations("app/ui", source.references(), allowed, declared).isEmpty(), entry.getKey());
            assertFalse(executionCalls(source).isEmpty(), entry.getKey());
        }
    }

    @Test
    void platformApiAndExtensionsHaveIndependentDependencyBoundaries() throws Exception {
        var modules = declaredDependencies();
        assertEquals(Set.of(), modules.get("appapi"));
        assertEquals(Set.of("appapi", "mod/git"), modules.get("mod/deployment"));
        assertEquals(Set.of("appapi"), modules.get("mod/git"));
        for (var entry : modules.entrySet()) {
            if (entry.getKey().startsWith("app/"))
                assertTrue(entry.getValue().stream().noneMatch(target -> target.startsWith("mod/") || target.startsWith("moddev/")), entry.getKey());
            assertAcyclic(entry.getKey(), modules, new HashSet<>(), new HashSet<>());
        }
        assertTrue(modules.containsKey("app/main"));
        assertFalse(modules.keySet().stream().anyMatch(path -> path.startsWith("shared/") || path.startsWith("web/")));
    }

    @Test
    void modInternalFreedomCannotReachPlatformOrPrivateModImplementations() throws Exception {
        var allowed = documentedDependencies(
            Files.readString(ArchitectureSourceChecker.projectRoot().resolve("docs/File.md")));
        var declared = declaredDependencies();
        for (String reference : List.of(ROOT + "app.file.SourceWorkDirectory",
            ROOT + "app.db.connect.PlatformDatabase", ROOT + "mod.backup.service.PrivateBackup"))
            assertFalse(referenceViolations("mod/deployment", List.of(reference), allowed, declared).isEmpty(),
                reference);
        assertEquals(List.of(), referenceViolations("mod/deployment",
            List.of(ROOT + "appapi.file.FileService", ROOT + "mod.deployment.service.impl.CustomWorkflow"),
            allowed, declared));
    }

    @Test
    void declaredModProviderPermitsOnlyItsExactExportedApi() throws Exception {
        var allowed = documentedDependencies(Files.readString(ArchitectureSourceChecker.projectRoot().resolve("docs/File.md")));
        var declared = declaredDependencies();
        assertEquals(List.of(), referenceViolations("mod/deployment", List.of(ROOT + "mod.git.api.GitSourceService"), allowed, declared));
        for (String ref : List.of(ROOT + "mod.git.service.GitSourceProvider", ROOT + "mod.git.file.GitArchive",
            ROOT + "mod.git.apiPrivate.Secret", ROOT + "mod.git.Mod"))
            assertFalse(referenceViolations("mod/deployment", List.of(ref), allowed, declared).isEmpty(), ref);
        assertFalse(referenceViolations("mod/git", List.of(ROOT + "mod.deployment.api.DeploymentMode"), allowed, declared).isEmpty());
        var noDirect = new HashMap<>(declared);
        noDirect.put("mod/deployment", Set.of("appapi"));
        assertFalse(referenceViolations("mod/deployment", List.of(ROOT + "mod.git.api.GitSourceService"), allowed, noDirect).isEmpty());
    }

    @Test
    void inferredUiReturnValuesCannotLeakDatabaseTypesThroughServiceGenerics() throws Exception {
        var sources = ArchitectureSourceChecker.snippets(Map.of(
            ROOT + "app.db.fixture.Storage", """
                package gold.debug.windowstolinux.app.db.fixture;
                public final class Storage { public record Row(String value) {} }
                """,
            ROOT + "app.service.fixture.Observations", """
                package gold.debug.windowstolinux.app.service.fixture;
                public interface Observations {
                    java.util.List<gold.debug.windowstolinux.app.db.fixture.Storage.Row> read();
                }
                """,
            ROOT + "app.ui.fixture.Screen", """
                package gold.debug.windowstolinux.app.ui.fixture;
                public final class Screen {
                    String show(gold.debug.windowstolinux.app.service.fixture.Observations service) {
                        var row = service.read().getFirst();
                        return row.value();
                    }
                }
                """));
        var screen = sources.stream().filter(source -> source.packageName().equals(ROOT + "app.ui.fixture"))
            .findFirst().orElseThrow();
        assertTrue(screen.references().contains(ROOT + "app.db.fixture.Storage.Row"));
        var allowed = documentedDependencies(Files.readString(ArchitectureSourceChecker.projectRoot().resolve("docs/File.md")));
        assertFalse(referenceViolations("app/ui", screen.references(), allowed, declaredDependencies()).isEmpty());
    }

    @Test
    void assistedAnalysisCannotReachExecutionOrWritableSourceCapabilities() throws Exception {
        var analysis = ArchitectureSourceChecker.production().stream()
            .filter(source -> source.path().getFileName().toString().equals("DeploymentAssistedAnalysis.java"))
            .toList();
        assertEquals(1, analysis.size());
        assertTrue(assistedViolations(analysis.getFirst()).isEmpty());
        var bypass = ArchitectureSourceChecker.snippets(Map.of("fixture.Analysis", """
            package fixture;
            class Analysis {
                gold.debug.windowstolinux.appapi.mod.ModContext context;
                void run() { context.linux(); context.file(); context.secrets(); }
            }
            """)).getFirst();
        assertEquals(3, assistedViolations(bypass).size());
        var browser = ArchitectureSourceChecker.production().stream()
            .filter(source -> source.path().getFileName().toString().equals("DeploymentSourceBrowser.java"))
            .findFirst().orElseThrow();
        String source = Files.readString(browser.path());
        assertFalse(source.matches("(?s).*\\b(?:write|delete|move|copy|createFile|createDirectory)\\s*\\(.*"));
        assertTrue(source.contains("public List<String> list(") && source.contains("public List<String> read(")
            && source.contains("public List<String> search("));
    }

    private static List<String> assistedViolations(ArchitectureSourceChecker.JavaSource source) {
        return source.calls().stream()
            .filter(call -> call.startsWith(ROOT + "appapi.mod.ModContext#")
                && !Set.of("ai", "owner").contains(call.substring(call.indexOf('#') + 1))
                || UI_EXECUTION_TYPES.stream().anyMatch(type -> call.startsWith(type + "#")))
            .toList();
    }

    private static List<String> executionCalls(ArchitectureSourceChecker.JavaSource source) {
        return source.calls().stream()
            .filter(call -> UI_EXECUTION_TYPES.stream().anyMatch(type -> call.startsWith(type + "#")))
            .map(call -> source.path() + " invokes an execution capability " + call).toList();
    }

    private static void assertAcyclic(String node, Map<String, Set<String>> graph, Set<String> stack,
                                      Set<String> done) {
        if (done.contains(node))
            return;
        assertTrue(stack.add(node), "dependency cycle: " + stack + " -> " + node);
        for (String target : graph.getOrDefault(node, Set.of()))
            assertAcyclic(target, graph, stack, done);
        stack.remove(node);
        done.add(node);
    }

    private static List<String> pomViolations(Map<String, Set<String>> allowed, Map<String, Set<String>> declared) {
        List<String> violations = new ArrayList<>();
        declared.forEach((owner, dependencies) -> {
            if (!allowed.containsKey(owner))
                violations.add(owner + " is undocumented");
            dependencies.forEach(dependency -> {
                if (!declared.containsKey(dependency) || !allowed.getOrDefault(owner, Set.of()).contains(dependency))
                    violations.add(owner + " POM forbids " + dependency);
            });
        });
        return violations;
    }

    private static List<String> referenceViolations(String owner, List<String> references,
                                                    Map<String, Set<String>> allowed, Map<String, Set<String>> declared) throws Exception {
        List<String> violations = new ArrayList<>();
        for (String reference : references) {
            if (!reference.startsWith(ROOT))
                continue;
            String dependency = moduleFor(reference, declared.keySet());
            if (owner.equals(dependency))
                continue;
            if (owner.equals("app/ui") && UI_EXECUTION_TYPES.stream()
                .anyMatch(type -> reference.equals(type) || reference.startsWith(type + "."))) {
                violations.add(owner + " directly references an execution capability " + reference);
                continue;
            }
            if (owner.equals("app/ui") && "app/linux".equals(dependency)
                && UI_RESCUE_TYPES.stream().noneMatch(type -> reference.equals(type) || reference.startsWith(type + "."))) {
                violations.add(owner + " directly references a Linux implementation " + reference);
                continue;
            }
            if (dependency == null || !allowed.getOrDefault(owner, Set.of()).contains(dependency)
                || !declared.getOrDefault(owner, Set.of()).contains(dependency))
                violations.add(owner + " references " + reference + " without an allowed direct dependency");
            else if (owner.startsWith("mod/") && dependency.startsWith("mod/") && !exportedModReference(owner, dependency, reference))
                violations.add(owner + " references a private or undeclared provider type " + reference);
        }
        return violations;
    }

    private static boolean exportedModReference(String owner, String provider, String reference) throws Exception {
        var root = ArchitectureSourceChecker.projectRoot().resolve("src");
        var consumer = gold.debug.windowstolinux.app.mod.config.ManifestReader.read(Files.readAllBytes(root.resolve(owner).resolve("mod.json")));
        var supplied = gold.debug.windowstolinux.app.mod.config.ManifestReader.read(Files.readAllBytes(root.resolve(provider).resolve("mod.json")));
        return consumer.dependencies().stream().anyMatch(d -> d.id().equals(supplied.id()) && d.versionRange().contains(supplied.version()))
            && supplied.exports().stream().anyMatch(pkg -> reference.startsWith(pkg + "."));
    }

    static Map<String, Set<String>> declaredDependencies() throws Exception {
        var modules = MavenModuleGraph.read(ArchitectureSourceChecker.projectRoot());
        var artifactPaths = new HashMap<String, String>();
        modules.forEach(module -> artifactPaths.put(module.artifact(), module.path()));
        var result = new HashMap<String, Set<String>>();
        for (var module : modules)
            if (!module.aggregate()) {
                var dependencies = new HashSet<String>();
                for (String artifact : module.artifacts()) {
                    String path = artifactPaths.get(artifact);
                    assertNotNull(path, "unresolved reactor artifact " + artifact);
                    dependencies.add(path);
                }
                result.put(module.path(), Set.copyOf(dependencies));
            }
        return result;
    }

    private static String value(Element element, String tag) {
        var nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    private static String moduleFor(String name, Set<String> modules) {
        return modules.stream().sorted(Comparator.comparingInt(String::length).reversed())
            .filter(module -> name.equals(ROOT + module.replaceFirst("^moddev/", "mod/").replace('/', '.').replace('-', '.'))
                || name.startsWith(ROOT + module.replaceFirst("^moddev/", "mod/").replace('/', '.').replace('-', '.') + "."))
            .findFirst().orElse(null);
    }

    @Test
    void dependencyGraphUsesStableAnchorRegardlessOfSectionNamesOrEarlierExamples() {
        String fence = String.valueOf((char) 96).repeat(3);
        String example = fence + "text\napp/ui ──→ app/linux\n" + fence;
        String graph = fence + "text\nappapi ──→ none\napp/ui ──→ appapi\n" + fence;
        String document = "## Earlier example\n" + example + "\n## Renamed dependency section\n"
            + DEPENDENCIES_ANCHOR + "\n" + graph + "\n## Different following section\n" + example;
        Map<String, Set<String>> expected = Map.of("appapi", Set.of(), "app/ui", Set.of("appapi"));
        assertEquals(expected, documentedDependencies(document));
        assertEquals(expected, documentedDependencies(document.replace("\n", "\r\n")));
    }

    @Test
    void missingAmbiguousOrMalformedDependencyGraphCannotReadAnotherSection() {
        String fence = String.valueOf((char) 96).repeat(3);
        String graph = fence + "text\nappapi ──→ none\n" + fence;
        assertThrows(AssertionError.class, () -> documentedDependencies("## Dependencies\n" + graph));
        assertThrows(AssertionError.class,
            () -> documentedDependencies(DEPENDENCIES_ANCHOR + "\n" + graph + "\n" + DEPENDENCIES_ANCHOR));
        assertThrows(AssertionError.class,
            () -> documentedDependencies(DEPENDENCIES_ANCHOR + "\n## Another section\n" + graph));
        assertThrows(AssertionError.class,
            () -> documentedDependencies(DEPENDENCIES_ANCHOR + "\n" + fence + "text\nappapi ──→ none\n"));
        assertThrows(AssertionError.class,
            () -> documentedDependencies(DEPENDENCIES_ANCHOR + "\n" + fence + "text\nnot a module graph\n" + fence));
    }

    private static Map<String, Set<String>> documentedDependencies(String document) {
        document = document.replace("\r\n", "\n");
        int anchor = document.indexOf(DEPENDENCIES_ANCHOR);
        assertTrue(anchor >= 0, "Module dependency anchor must exist");
        assertEquals(anchor, document.lastIndexOf(DEPENDENCIES_ANCHOR), "Module dependency anchor must be unique");
        int nextSection = document.indexOf("\n## ", anchor);
        String section = document.substring(anchor, nextSection < 0 ? document.length() : nextSection);
        String fence = String.valueOf((char) 96).repeat(3);
        int start = section.indexOf(fence + "text\n");
        assertTrue(start >= 0, "Module dependency graph must exist in the anchored section");
        int end = section.indexOf(fence, start + fence.length());
        assertTrue(end >= 0, "Module dependency graph fence must close in the anchored section");
        String graph = section.substring(start + fence.length() + 5, end);
        Map<String, Set<String>> result = new HashMap<>();
        for (String line : graph.lines().toList()) {
            var arrow = Pattern.compile("^((?:appapi|(?:app|mod)/[a-z/-]+))\\s+──→\\s+(.+)$").matcher(line);
            if (!arrow.matches())
                continue;
            var dependencies = result.computeIfAbsent(arrow.group(1), unused -> new HashSet<>());
            String target = arrow.group(2).strip();
            if (target.equals("none"))
                continue;
            for (String path : target.split(",\\s*")) {
                if (!path.matches("(?:appapi|(?:app|mod)/[a-z/-]+)"))
                    throw new IllegalArgumentException("invalid documented module path: " + path);
                dependencies.add(path);
            }
        }
        assertFalse(result.isEmpty(), "Anchored dependency graph must contain actual module declarations");
        return result;
    }
}
