package gold.debug.windowstolinux.app.main.architecture;

import java.nio.file.*;
import java.util.*;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

/** Resolves real Maven aggregation and artifact identities, including nested modules. */
final class MavenModuleGraph {
    record Module(Path pom, String path, String artifact, boolean aggregate, Set<String> artifacts) {
    }
    private MavenModuleGraph() {
    }

    static List<Module> read(Path root) throws Exception {
        var result = new ArrayList<Module>();
        visit(root.toAbsolutePath().normalize(), root.toAbsolutePath().normalize().resolve("pom.xml"), new HashSet<>(),
                new HashSet<>(), result);
        return List.copyOf(result);
    }

    private static void visit(Path root, Path pom, Set<Path> visited, Set<String> artifacts, List<Module> result)
            throws Exception {
        pom = pom.toAbsolutePath().normalize();
        if (!pom.startsWith(root) || !Files.isRegularFile(pom) || !visited.add(pom))
            throw new IllegalStateException("invalid or cyclic Maven aggregation: " + pom);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
        String artifact = value(project, "artifactId");
        if (artifact.isBlank() || !artifacts.add(artifact))
            throw new IllegalStateException("duplicate or missing artifact: " + artifact);
        var dependencies = new LinkedHashSet<String>();
        var dep = child(project, "dependencies");
        if (dep != null)
            for (var node = dep.getFirstChild(); node != null; node = node.getNextSibling())
                if (node instanceof Element item && item.getTagName().equals("dependency")) {
                    if (value(item, "groupId").equals("gold.debug.windowstolinux")
                            && !value(item, "scope").equals("test"))
                        dependencies.add(value(item, "artifactId"));
                }
        String path = root.relativize(pom.getParent()).toString().replace('\\', '/').replaceFirst("^src/", "");
        result.add(
                new Module(pom, path, artifact, value(project, "packaging").equals("pom"), Set.copyOf(dependencies)));
        var modules = child(project, "modules");
        if (modules != null)
            for (var node = modules.getFirstChild(); node != null; node = node.getNextSibling())
                if (node instanceof Element item && item.getTagName().equals("module"))
                    visit(root, pom.getParent().resolve(item.getTextContent().strip()).resolve("pom.xml"), visited,
                            artifacts, result);
    }

    private static Element child(Element element, String name) {
        for (var n = element.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && e.getTagName().equals(name))
                return e;
        return null;
    }

    private static String value(Element element, String name) {
        var found = child(element, name);
        return found == null ? "" : found.getTextContent().strip();
    }
}
