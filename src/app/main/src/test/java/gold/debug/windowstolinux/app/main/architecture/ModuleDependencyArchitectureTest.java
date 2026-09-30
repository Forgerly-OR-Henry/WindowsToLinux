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

    private static final Set<String> UI_EXECUTION_TYPES = Set.of(ROOT + "appapi.linux.LinuxService",
            ROOT + "appapi.linux.RemoteSession", ROOT + "appapi.process.ProcessService",
            ROOT + "appapi.process.ProcessChannel", ROOT + "appapi.source.SourceService",
            ROOT + "appapi.secret.SecretService", ROOT + "appapi.db.ModDatabase");

    @Test
    void productionDependenciesMatchDocumentationAndDirectPomDeclarations() throws Exception {
        String document = Files.readString(ArchitectureSourceInspector.projectRoot().resolve("docs/File.md"));
        var allowed = documentedDependencies(document);
        var declared = declaredDependencies();
        List<String> violations = pomViolations(allowed, declared);
        for (var source : ArchitectureSourceInspector.production()) {
            String owner = moduleFor(source.packageName(), declared.keySet());
            assertNotNull(owner, source.path().toString());
            violations.addAll(referenceViolations(owner, source.references(), allowed, declared));
            if (owner.equals("app/ui"))
                violations.addAll(executionCalls(source));
        }
        assertTrue(violations.isEmpty(), () -> "module dependency violations: " + violations);

    }

    @Test
    void forbiddenPomDependenciesAndMissingDirectDependenciesCannotPass() throws Exception {
        var allowed = documentedDependencies(
                Files.readString(ArchitectureSourceInspector.projectRoot().resolve("docs/File.md")));
        var declared = new HashMap<>(declaredDependencies());
        declared.put("app/ui", Set.of("app/linux"));
        assertFalse(pomViolations(allowed, declared).isEmpty());
        declared = new HashMap<>(declaredDependencies());
        var service = new HashSet<>(declared.get("app/service"));
        service.remove("app/source");
        declared.put("app/service", service);
        assertFalse(referenceViolations("app/service", List.of(ROOT + "app.source.snapshot.SourceWorkDirectory"),
                allowed, declared).isEmpty());
    }

    @Test
    void fullyQualifiedReferencesCannotBypassTheUiBoundary() throws Exception {
        var allowed = documentedDependencies(
                Files.readString(ArchitectureSourceInspector.projectRoot().resolve("docs/File.md")));
        var declared = declaredDependencies();
        var source = ArchitectureSourceInspector.snippets(Map.of("fixture.References", """
                package fixture;
                class References {
                    gold.debug.windowstolinux.app.linux.ssh.SshConnector transfer;
                }
                """)).getFirst();
        assertFalse(referenceViolations("app/ui", source.references(), allowed, declared).isEmpty());
        assertEquals(List.of(),
                referenceViolations("app/ui", List.of(ROOT + "appapi.task.Problem"), allowed, declared));
        assertFalse(referenceViolations("app/ui", List.of(ROOT + "appapi.linux.RemoteSession"), allowed, declared)
                .isEmpty());
        var indirect = ArchitectureSourceInspector.snippets(Map.of("fixture.Indirect", """
                package fixture;
                class Indirect {
                    gold.debug.windowstolinux.appapi.mod.ModContext context;
                    void run() throws gold.debug.windowstolinux.appapi.task.ApiException { var linux = context.linux(); linux.connect(null, null); }
                }
                """)).getFirst();
        assertFalse(executionCalls(indirect).isEmpty());
    }

    @Test
    void platformApiAndExtensionsHaveIndependentDependencyBoundaries() throws Exception {
        var modules = declaredDependencies();
        assertEquals(Set.of(), modules.get("appapi"));
        assertEquals(Set.of("appapi"), modules.get("mod/deployment"));
        for (var entry : modules.entrySet()) {
            if (entry.getKey().startsWith("app/"))
                assertTrue(entry.getValue().stream().noneMatch(target -> target.startsWith("mod/")), entry.getKey());
            assertAcyclic(entry.getKey(), modules, new HashSet<>(), new HashSet<>());
        }
        assertTrue(modules.containsKey("app/main"));
        assertFalse(modules.keySet().stream().anyMatch(path -> path.startsWith("shared/") || path.startsWith("web/")));
    }

    @Test
    void assistedAnalysisCannotReachExecutionOrWritableSourceCapabilities() throws Exception {
        var analysis = ArchitectureSourceInspector.production().stream()
                .filter(source -> source.path().getFileName().toString().equals("DeploymentAssistedAnalysis.java"))
                .toList();
        assertEquals(1, analysis.size());
        assertTrue(assistedViolations(analysis.getFirst()).isEmpty());
        var bypass = ArchitectureSourceInspector.snippets(Map.of("fixture.Analysis", """
                package fixture;
                class Analysis {
                    gold.debug.windowstolinux.appapi.mod.ModContext context;
                    void run() { context.linux(); context.source(); context.secrets(); }
                }
                """)).getFirst();
        assertEquals(3, assistedViolations(bypass).size());
        var browser = ArchitectureSourceInspector.production().stream()
                .filter(source -> source.path().getFileName().toString().equals("DeploymentSourceBrowser.java"))
                .findFirst().orElseThrow();
        String source = Files.readString(browser.path());
        assertFalse(source.matches("(?s).*\\b(?:write|delete|move|copy|createFile|createDirectory)\\s*\\(.*"));
        assertTrue(source.contains("public List<String> list(") && source.contains("public List<String> read(")
                && source.contains("public List<String> search("));
    }

    private static List<String> assistedViolations(ArchitectureSourceInspector.JavaSource source) {
        return source.calls().stream()
                .filter(call -> call.startsWith(ROOT + "appapi.mod.ModContext#")
                        && !Set.of("ai", "owner").contains(call.substring(call.indexOf('#') + 1))
                        || UI_EXECUTION_TYPES.stream().anyMatch(type -> call.startsWith(type + "#")))
                .toList();
    }

    private static List<String> executionCalls(ArchitectureSourceInspector.JavaSource source) {
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
            Map<String, Set<String>> allowed, Map<String, Set<String>> declared) {
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
            if (dependency == null || !allowed.getOrDefault(owner, Set.of()).contains(dependency)
                    || !declared.getOrDefault(owner, Set.of()).contains(dependency))
                violations.add(owner + " references " + reference + " without an allowed direct dependency");
        }
        return violations;
    }

    static Map<String, Set<String>> declaredDependencies() throws Exception {
        var modules = MavenModuleGraph.read(ArchitectureSourceInspector.projectRoot());
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
                .filter(module -> name.equals(ROOT + module.replace('/', '.').replace('-', '.'))
                        || name.startsWith(ROOT + module.replace('/', '.').replace('-', '.') + "."))
                .findFirst().orElse(null);
    }

    private static Map<String, Set<String>> documentedDependencies(String document) {
        String section = document.substring(document.indexOf("## 5. 依赖方向"), document.indexOf("## 6. 桌面端数据目录"));
        String fence = String.valueOf((char) 96).repeat(3);
        var blocks = Pattern.compile(fence + "text\\R(.*?)" + fence, Pattern.DOTALL).matcher(section);
        Map<String, Set<String>> result = new HashMap<>();
        while (blocks.find())
            for (String line : blocks.group(1).lines().toList()) {
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
        return result;
    }
}
