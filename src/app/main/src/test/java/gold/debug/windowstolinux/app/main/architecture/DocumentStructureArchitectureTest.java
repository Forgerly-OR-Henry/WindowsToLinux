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

import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import org.junit.jupiter.api.Test;

/** Keeps implemented packages and planned document nodes distinct. / 区分已实现包与文档中的规划节点。 */
class DocumentStructureArchitectureTest {
    private static final String CURRENT_TREE_ANCHOR = "<a id=\"current-source-tree\"></a>";

    @Test
    void everyProductionPackageHasADocumentedCurrentNode() throws Exception {
        String current = document();
        var violations = structureViolations(current, productionPackages(),
            ModuleDependencyArchitectureTest.declaredDependencies().keySet());
        assertTrue(violations.isEmpty(), () -> "document structure violations: " + violations);
        var presentation = presentationViolations(currentTree(current), expectedNodeKinds());
        assertTrue(presentation.isEmpty(), () -> "current tree presentation violations: " + presentation);
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

    @Test
    void wrongNodeKindsMissingChineseResponsibilitiesAndMisalignmentFail() {
        String root = "WindowsToLinux/";
        String child = "└─ src/";
        String tree = root + " ".repeat(24 - root.length()) + "# [聚合]  测试工程\n"
            + child + " ".repeat(24 - child.length()) + "# [目录]  测试源码\n";
        Map<String, String> expected = Map.of("", "[聚合]", "src", "[目录]");
        assertEquals(List.of(), presentationViolations(tree, expected));
        assertFalse(presentationViolations(tree.replace("[目录]", "[模块]"), expected).isEmpty());
        assertFalse(presentationViolations(tree.replace("# [目录]", "#"), expected).isEmpty());
        assertFalse(presentationViolations(tree.replace("测试源码", "source"), expected).isEmpty());
        assertFalse(presentationViolations(tree.replace("└─ src/ ", "└─ src/"), expected).isEmpty());
        assertFalse(presentationViolations(tree.replace("[目录]  ", "[目录] "), expected).isEmpty());
        assertFalse(presentationViolations(tree.replace("└─ src/ ", "└─ src/\t"), expected).isEmpty());
    }

    private static Map<String, String> expectedNodeKinds() throws Exception {
        var root = ArchitectureSourceChecker.projectRoot();
        Map<String, String> kinds = new LinkedHashMap<>();
        kinds.put("docs", "[目录]");
        kinds.put("history-src", "[归档]");
        kinds.put("src", "[目录]");
        for (var module : MavenModuleGraph.read(root)) {
            String path = root.relativize(module.pom().getParent()).toString().replace('\\', '/');
            kinds.put(path, module.aggregate() ? "[聚合]" : "[模块]");
            kinds.put(path.isEmpty() ? "pom.xml" : path + "/pom.xml", "[构建文件]");
            if (!path.matches("src/(mod|moddev)/.*") || !Files.isRegularFile(module.pom().getParent().resolve("mod.json")))
                continue;
            kinds.put(path + "/Mod.java", "[入口类]");
            var descriptor = ManifestReader.read(Files.readAllBytes(module.pom().getParent().resolve("mod.json")));
            String base = descriptor.rootPackage();
            for (String name : descriptor.exports()) {
                assertTrue(name.startsWith(base + "."), "Mod export must belong to its root package");
                kinds.put(path + name.substring(base.length()).replace('.', '/'), "[公开包]");
            }
        }
        for (String path : productionPackages())
            kinds.putIfAbsent(path, path.startsWith("src/appapi/") ? "[公开包]" : "[包]");
        return kinds;
    }

    private static List<String> presentationViolations(String tree, Map<String, String> kinds) {
        var node = Pattern.compile("^([│ ]*)(?:├─|└─) ([^\\s]+).*$");
        var presentation = Pattern.compile("^(.+?) +# (\\[[^\\]]+\\]) +(.*)$");
        List<String> stack = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        Integer commentColumn = null;
        Integer responsibilityColumn = null;
        for (String line : tree.lines().filter(value -> !value.isBlank()).toList()) {
            String path = "";
            var entry = node.matcher(line);
            if (entry.matches()) {
                int depth = entry.group(1).length() / 3;
                while (stack.size() > depth)
                    stack.removeLast();
                if (depth != stack.size()) {
                    violations.add("Invalid node indentation: " + line);
                    continue;
                }
                stack.add(entry.group(2).replaceFirst("/$", ""));
                path = String.join("/", stack);
            }
            var match = presentation.matcher(line);
            if (!match.matches()) {
                violations.add("Node needs a type and Chinese responsibility: " + line);
                continue;
            }
            String expected = kinds.getOrDefault(path, path.startsWith("src/") && entry.group(2).endsWith("/")
                ? "[包]" : "");
            if (!match.group(2).equals(expected))
                violations.add("Incorrect node type for " + path + ": " + match.group(2) + " expected " + expected);
            if (!match.group(3).matches(".*\\p{IsHan}.*"))
                violations.add("Node needs a Chinese responsibility: " + line);
            int comment = displayWidth(line.substring(0, line.indexOf('#')));
            int responsibility = displayWidth(line.substring(0, match.start(3)));
            if (commentColumn == null) {
                commentColumn = comment;
                responsibilityColumn = responsibility;
            } else if (comment != commentColumn || responsibility != responsibilityColumn)
                violations.add("Comment columns must align: " + line);
            if (line.indexOf('\t') >= 0)
                violations.add("Tree padding must use spaces: " + line);
        }
        return violations;
    }

    private static int displayWidth(String value) {
        return value.codePoints().map(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN
            ? 2 : 1).sum();
    }

    private static String document() throws Exception {
        return Files.readString(ArchitectureSourceChecker.projectRoot().resolve("docs/reference/File.md"));
    }

    private static Set<String> productionPackages() throws Exception {
        Set<String> packages = new HashSet<>();
        var root = ArchitectureSourceChecker.projectRoot();
        for (var source : ArchitectureSourceChecker.production()) {
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

    private static String currentTree(String document) {
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
        return section.substring(start + fence.length() + 5, end);
    }

    private static Map<String, Boolean> targetNodes(String document) {
        String tree = currentTree(document);
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
