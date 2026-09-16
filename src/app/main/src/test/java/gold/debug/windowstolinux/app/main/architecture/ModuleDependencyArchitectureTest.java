package gold.debug.windowstolinux.app.main.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
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

import static org.junit.jupiter.api.Assertions.*;

/** Checks declared and actual dependencies against the formal document. / 按正式文档检查声明依赖与实际依赖。 */
class ModuleDependencyArchitectureTest {
    private static final String ROOT = "gold.debug.windowstolinux.";
    private static final Set<String> UI_DATA_TYPES = Set.of(
            ROOT + "shared.ai.collaboration.role.AiCollaborationRoleKind",
            ROOT + "shared.ai.collaboration.invocation.AiRoleInvocationResult",
            ROOT + "shared.ai.collaboration.role.ProjectAnalysisRoleContext",
            ROOT + "shared.ai.collaboration.role.DeploymentInputRoleContext",
            ROOT + "shared.analyze.component.ComponentAnalysisRequest",
            ROOT + "shared.deploy.contract.ApplicationHealthGate",
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
        var allowed = documentedDependencies(Files.readString(ArchitectureSourceInspector.projectRoot().resolve("docs/File.md")));
        var declared = new HashMap<>(declaredDependencies());
        declared.put("app/ui", Set.of("shared/linux"));
        assertFalse(pomViolations(allowed, declared).isEmpty());
        declared = new HashMap<>(declaredDependencies());
        var service = new HashSet<>(declared.get("app/service"));
        service.remove("shared/source");
        declared.put("app/service", service);
        assertFalse(referenceViolations("app/service",
                List.of(ROOT + "shared.source.SourceDirectorySnapshot"), allowed, declared).isEmpty());
    }

    @Test
    void fullyQualifiedReferencesCannotBypassTheUiBoundary() throws Exception {
        var allowed = documentedDependencies(Files.readString(ArchitectureSourceInspector.projectRoot().resolve("docs/File.md")));
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
        assertFalse(referenceViolations("app/ui",
                List.of(ROOT + "shared.config.ConfigurationSnapshot"), allowed, declared).isEmpty());
    }

    private static List<String> pomViolations(Map<String, Set<String>> allowed, Map<String, Set<String>> declared) {
        List<String> violations = new ArrayList<>();
        declared.forEach((owner, dependencies) -> {
            if (!allowed.containsKey(owner)) violations.add(owner + " is undocumented");
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
            if (!reference.startsWith(ROOT)) continue;
            String dependency = moduleFor(reference, declared.keySet());
            if (owner.equals(dependency)) continue;
            if (owner.equals("app/ui") && UI_DATA_TYPES.stream()
                    .anyMatch(type -> reference.equals(type) || reference.startsWith(type + "."))) continue;
            if (dependency == null || !allowed.getOrDefault(owner, Set.of()).contains(dependency)
                    || !declared.getOrDefault(owner, Set.of()).contains(dependency))
                violations.add(owner + " references " + reference + " without an allowed direct dependency");
        }
        return violations;
    }

    static Map<String, Set<String>> declaredDependencies() throws Exception {
        Path root = ArchitectureSourceInspector.projectRoot();
        Map<String, Set<String>> result = new HashMap<>();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var files = Files.walk(root.resolve("src"), 3)) {
            for (Path pom : files.filter(path -> path.getFileName().toString().equals("pom.xml")).toList()) {
                String owner = root.resolve("src").relativize(pom.getParent()).toString().replace('\\', '/');
                if (!owner.contains("/")) continue;
                Set<String> dependencies = new HashSet<>();
                var project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
                for (var child = project.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (!(child instanceof Element element) || !element.getTagName().equals("dependencies")) continue;
                    var nodes = element.getElementsByTagName("dependency");
                    for (int i = 0; i < nodes.getLength(); i++) {
                        Element dependency = (Element) nodes.item(i);
                        if (value(dependency, "scope").equals("test")
                                || !value(dependency, "groupId").equals("gold.debug.windowstolinux")) continue;
                        dependencies.add(value(dependency, "artifactId").replaceFirst("^windowstolinux-", "")
                                .replaceFirst("-", "/"));
                    }
                }
                result.put(owner, Set.copyOf(dependencies));
            }
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
        String[] groups = {"shared", "app", "web"};
        Map<String, Set<String>> result = new HashMap<>();
        result.put("shared/model", new HashSet<>());
        int index = 0;
        while (blocks.find()) {
            String group = groups[index++];
            for (String line : blocks.group(1).lines().toList()) {
                var arrow = Pattern.compile("^([a-z-]+)\\s+──→\\s+(.+)$").matcher(line);
                if (!arrow.matches()) continue;
                var dependencies = result.computeIfAbsent(group + "/" + arrow.group(1), unused -> new HashSet<>());
                String target = arrow.group(2).strip();
                String prefix = target.startsWith("shared/") ? "shared/" : group + "/";
                target = target.replaceFirst("^shared/", "").replace("{", "").replace("}", "");
                for (String name : target.split(","))
                    if (name.matches("[a-z-]+")) dependencies.add(prefix + name);
            }
        }
        assertEquals(3, index, "All three dependency diagrams must be present");
        return result;
    }
}
