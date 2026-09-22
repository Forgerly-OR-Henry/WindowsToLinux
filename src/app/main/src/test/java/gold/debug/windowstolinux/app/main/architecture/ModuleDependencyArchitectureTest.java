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

    private static final Set<String> UI_DATA_TYPES = Set.of(
            ROOT + "shared.ai.collaboration.role.AiCollaborationRoleKind",
            ROOT + "shared.ai.collaboration.invocation.AiRoleInvocationResult",
            ROOT + "shared.ai.collaboration.role.ProjectAnalysisRoleContext",
            ROOT + "shared.ai.collaboration.role.DeploymentInputRoleContext",
            ROOT + "shared.standard.analyze.component.ComponentAnalysisRequest",
            ROOT + "shared.deploy.contract.ApplicationHealthGate",
            ROOT + "shared.deploy.contract.AutomaticDeploymentInteraction",
            ROOT + "shared.config.resource.ManagedDatabaseBinding",
            ROOT + "shared.config.resource.ManagedDatabaseConnection",
            ROOT + "shared.deploy.contract.result.deployment.MultiComponentDeploymentResult",
            ROOT + "shared.deploy.contract.result.lifecycle.MultiComponentLifecycleResult");

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
        }
        assertTrue(violations.isEmpty(), () -> "module dependency violations: " + violations);
        for (String type : UI_DATA_TYPES)
            assertTrue(document.contains(type.substring(type.lastIndexOf('.') + 1)), type);
    }

    @Test
    void forbiddenPomDependenciesAndMissingDirectDependenciesCannotPass() throws Exception {
        var allowed = documentedDependencies(
                Files.readString(ArchitectureSourceInspector.projectRoot().resolve("docs/File.md")));
        var declared = new HashMap<>(declaredDependencies());
        declared.put("app/ui", Set.of("shared/linux"));
        assertFalse(pomViolations(allowed, declared).isEmpty());
        declared = new HashMap<>(declaredDependencies());
        var service = new HashSet<>(declared.get("app/service"));
        service.remove("shared/source");
        declared.put("app/service", service);
        assertFalse(referenceViolations("app/service", List.of(ROOT + "shared.source.SourceDirectorySnapshot"), allowed,
                declared).isEmpty());
    }

    @Test
    void fullyQualifiedReferencesCannotBypassTheUiBoundary() throws Exception {
        var allowed = documentedDependencies(
                Files.readString(ArchitectureSourceInspector.projectRoot().resolve("docs/File.md")));
        var declared = declaredDependencies();
        var source = ArchitectureSourceInspector.snippets(Map.of("fixture.References", """
                package fixture;
                class References {
                    gold.debug.windowstolinux.shared.linux.error.LinuxOperationException failure;
                }
                """)).getFirst();
        assertFalse(referenceViolations("app/ui", source.references(), allowed, declared).isEmpty());
        assertEquals(List.of(), referenceViolations("app/ui",
                List.of(ROOT + "shared.config.resource.ManagedDatabaseConnection.Server"), allowed, declared));
        assertFalse(
                referenceViolations("app/ui", List.of(ROOT + "shared.config.ConfigurationSnapshot"), allowed, declared)
                        .isEmpty());
    }

    @Test
    void enginesAreIndependentAndCommonModulesCannotDependOnThem() throws Exception {
        var modules = declaredDependencies();
        for (var entry : modules.entrySet()) {
            String owner = entry.getKey();
            var dependencies = entry.getValue();
            if (owner.startsWith("shared/standard/"))
                assertFalse(dependencies.contains("shared/agent"), owner);
            else if (owner.equals("shared/agent"))
                assertTrue(dependencies.stream().noneMatch(d -> d.startsWith("shared/standard/")), owner);
            else if (owner.startsWith("shared/"))
                assertTrue(dependencies.stream()
                        .noneMatch(d -> d.equals("shared/agent") || d.startsWith("shared/standard/")), owner);
            assertAcyclic(owner, modules, new HashSet<>(), new HashSet<>());
        }
        var graph = MavenModuleGraph.read(ArchitectureSourceInspector.projectRoot());
        assertTrue(graph.stream().anyMatch(m -> m.path().equals("shared/standard") && m.aggregate()));
        assertTrue(modules.containsKey("shared/standard/analyze"));
        assertTrue(modules.containsKey("shared/standard/deploy"));
    }

    @Test
    void assistedAnalysisCannotReachExecutionOrWritableSourceCapabilities() throws Exception {
        int checked = 0;
        for (var source : ArchitectureSourceInspector.production()) {
            if (!source.packageName().equals(ROOT + "shared.standard.deploy.assistance")
                    || !source.path().getFileName().toString().startsWith("AssistedAnalysis"))
                continue;
            checked++;
            for (String reference : source.references())
                assertTrue(List
                        .of(ROOT + "shared.agent.", ROOT + "shared.linux.", ROOT + "shared.deploy.", ROOT + "app.",
                                "java.nio.file.", "java.io.", "java.lang.Process")
                        .stream().noneMatch(reference::startsWith), source.path() + ": " + reference);
        }
        assertEquals(3, checked);
        assertEquals(Set.of("revision", "verify", "list", "read", "search"), java.util.Arrays
                .stream(gold.debug.windowstolinux.shared.source.browse.SourceReadPort.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet()));
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
            if (owner.equals("app/ui") && UI_DATA_TYPES.stream()
                    .anyMatch(type -> reference.equals(type) || reference.startsWith(type + ".")))
                continue;
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
                var arrow = Pattern.compile("^((?:shared|app|web)/[a-z/-]+)\\s+──→\\s+(.+)$").matcher(line);
                if (!arrow.matches())
                    continue;
                var dependencies = result.computeIfAbsent(arrow.group(1), unused -> new HashSet<>());
                String target = arrow.group(2).strip();
                if (target.equals("none"))
                    continue;
                for (String path : target.split(",\\s*")) {
                    if (!path.matches("(?:shared|app|web)/[a-z/-]+"))
                        throw new IllegalArgumentException("invalid documented module path: " + path);
                    dependencies.add(path);
                }
            }
        return result;
    }
}
