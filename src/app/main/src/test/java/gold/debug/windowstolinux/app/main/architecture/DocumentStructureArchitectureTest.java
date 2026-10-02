package gold.debug.windowstolinux.app.main.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Keeps implemented packages and planned document nodes distinct. / 区分已实现包与文档中的规划节点。 */
class DocumentStructureArchitectureTest {
    private static final String CURRENT_TREE_ANCHOR = "<a id=\"current-source-tree\"></a>";

    @Test
    void everyProductionPackageHasADocumentedCurrentNode() throws Exception {
        var violations = structureViolations(document(), productionPackages(),
            ModuleDependencyArchitectureTest.declaredDependencies().keySet());
        assertTrue(violations.isEmpty(), () -> "document structure violations: " + violations);
    }

    @Test
    void missingNodesFailAndExplicitPlansDoNotRequireEmptyPackages() {
        String fence = String.valueOf((char) 96).repeat(3);
        String tree = "## 1. 当前活动源码结构\n" + CURRENT_TREE_ANCHOR + "\n" + fence + "text\nWindowsToLinux/\n"
            + "└─ src/\n   └─ shared/\n      └─ deploy/\n         ├─ error/\n" + "         └─ future/ [PLANNED]\n"
            + fence + "\n";
        Set<String> actual = Set.of("src/shared/deploy/error");
        Set<String> modules = Set.of("shared/deploy");
        assertEquals(List.of(), structureViolations(tree, actual, modules));
        assertFalse(structureViolations(tree.replace("         ├─ error/\n", ""), actual, modules).isEmpty());
        assertFalse(structureViolations(tree.replace(" [PLANNED]", ""), actual, modules).isEmpty());
    }

    @Test
    void currentTreeUsesStableAnchorRegardlessOfHeadingOrEarlierExamples() {
        String fence = String.valueOf((char) 96).repeat(3);
        String document = "## Earlier example\n" + fence + "text\n└─ candidate/\n" + fence
            + "\n## Renamed current section\n" + CURRENT_TREE_ANCHOR + "\n" + fence
            + "text\n└─ src/\n" + fence + "\n## Future design\n" + fence + "text\n└─ future/\n" + fence;
        assertEquals(Map.of("src", false), targetNodes(document));
        assertEquals(Map.of("src", false), targetNodes(document.replace("\n", "\r\n")));
    }

    @Test
    void missingAmbiguousOrMalformedCurrentTreeFailsInsteadOfReadingAnotherSection() {
        String fence = String.valueOf((char) 96).repeat(3);
        String tree = fence + "text\n└─ src/\n" + fence;
        assertThrows(AssertionError.class, () -> targetNodes("## 1. 完整目标结构\n" + tree));
        assertThrows(AssertionError.class,
            () -> targetNodes(CURRENT_TREE_ANCHOR + "\n" + tree + "\n" + CURRENT_TREE_ANCHOR));
        assertThrows(AssertionError.class,
            () -> targetNodes(CURRENT_TREE_ANCHOR + "\n## Future design\n" + tree));
        assertThrows(AssertionError.class,
            () -> targetNodes(CURRENT_TREE_ANCHOR + "\n" + fence + "text\n└─ src/\n"));
    }

    private static String document() throws Exception {
        return Files.readString(ArchitectureSourceInspector.projectRoot().resolve("docs/File.md"));
    }

    private static Set<String> productionPackages() throws Exception {
        Set<String> packages = new HashSet<>();
        var root = ArchitectureSourceInspector.projectRoot();
        for (var source : ArchitectureSourceInspector.production()) {
            String relative = root.relativize(source.path()).toString().replace('\\', '/');
            String module = relative.substring(0, relative.indexOf("/src/main/java/"));
            String base = "gold.debug.windowstolinux." + module.substring(4).replace('/', '.').replace('-', '.');
            assertTrue(source.packageName().equals(base) || source.packageName().startsWith(base + "."), relative);
            packages.add(module + source.packageName().substring(base.length()).replace('.', '/'));
        }
        return packages;
    }

    private static List<String> structureViolations(String document, Set<String> actual, Set<String> modules) {
        Map<String, Boolean> nodes = targetNodes(document);
        List<String> violations = new ArrayList<>();
        actual.stream().filter(path -> !nodes.containsKey(path)).sorted()
            .forEach(path -> violations.add("Missing production package " + path));
        nodes.forEach((path, planned) -> {
            if (planned || modules.stream()
                .noneMatch(module -> path.equals("src/" + module) || path.startsWith("src/" + module + "/")))
                return;
            if (actual.stream().noneMatch(value -> value.equals(path) || value.startsWith(path + "/")))
                violations.add("Unimplemented node requires [PLANNED]: " + path);
        });
        return violations;
    }

    private static Map<String, Boolean> targetNodes(String document) {
        document = document.replace("\r\n", "\n");
        String fence = String.valueOf((char) 96).repeat(3);
        int anchor = document.indexOf(CURRENT_TREE_ANCHOR);
        assertTrue(anchor >= 0, "Current source tree anchor must exist");
        assertEquals(anchor, document.lastIndexOf(CURRENT_TREE_ANCHOR), "Current source tree anchor must be unique");
        int nextSection = document.indexOf("\n## ", anchor);
        String section = document.substring(anchor, nextSection < 0 ? document.length() : nextSection);
        int start = section.indexOf(fence + "text\n");
        assertTrue(start >= 0, "Current source tree must exist in the anchored section");
        int end = section.indexOf(fence, start + fence.length());
        assertTrue(end >= 0, "Current source tree fence must close in the anchored section");
        String tree = section.substring(start + fence.length() + 5, end);
        var pattern = Pattern.compile("^([│ ]*)(?:├─|└─) ([^\\s]+)(.*)$");
        List<String> stack = new ArrayList<>();
        List<Boolean> plans = new ArrayList<>();
        Map<String, Boolean> nodes = new LinkedHashMap<>();
        for (String line : tree.lines().toList()) {
            var match = pattern.matcher(line);
            if (!match.matches())
                continue;
            int depth = match.group(1).length() / 3;
            while (stack.size() > depth) {
                stack.removeLast();
                plans.removeLast();
            }
            assertEquals(depth, stack.size(), "Invalid tree indentation: " + line);
            String name = match.group(2);
            boolean planned = match.group(3).contains("[PLANNED]") || (!plans.isEmpty() && plans.getLast());
            stack.add(name.replaceFirst("/$", ""));
            plans.add(planned);
            if (name.endsWith("/")) {
                String path = String.join("/", stack);
                assertNull(nodes.put(path, planned), "Duplicate target path: " + path);
            }
        }
        return nodes;
    }
}
