package gold.debug.windowstolinux.app.main.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

class JavadocCoverageTest {
    @Test
    void responsibilityLineLimitsExcludeOnlyActualDocumentation() throws Exception {
        String source = "class Example {\n    String script = \"\"\"\n/** protocol text */\n\"\"\";\n\n// implementation rationale\nvoid run() {}\n}\n";
        assertEquals(8, JavadocCoverageInspector.implementationLines(source));
        assertEquals(8, JavadocCoverageInspector.implementationLines(
                source.replace("void run()", "/**\n * Operation documentation.\n */\nvoid run()")));
        assertEquals(8, JavadocCoverageInspector
                .implementationLines(source.replace("void run()", "/** Inline documentation. */ void run()")));
        assertEquals(9, JavadocCoverageInspector
                .implementationLines(source.replace("void run()", "int additionalState;\nvoid run()")));
    }

    @Test
    void everyExplicitProductionDeclarationHasBilingualDocumentationAndTags() throws Exception {
        var result = JavadocCoverageInspector.production();
        Path report = Path.of("target/javadoc-coverage.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report,
                "Explicit declarations: " + result.declarations() + "\n" + String.join("\n", result.violations()));
        assertTrue(result.violations().isEmpty(),
                () -> String.join("\n", result.violations().stream().limit(80).toList()) + "\nFull report: "
                        + report.toAbsolutePath());
    }

    @Test
    void missingMonolingualAndIncompleteTagsFailWithoutRequiringSyntheticMembers() throws Exception {
        assertFalse(
                JavadocCoverageInspector.snippets("class Example { int value; void run() {} }").violations().isEmpty());
        assertFalse(JavadocCoverageInspector.snippets("/** English only. */ class Example {}").violations().isEmpty());
        String valid = """
                /** Identity container. / 身份容器。
                 * @param <T> payload type / 载荷类型
                 * @param value stored payload / 保存的载荷
                 */
                record Example<T>(T value) {
                    /** Returns the caller input. / 返回调用方输入。
                     * @param input caller input / 调用方输入
                     * @return unchanged input / 未变的输入
                     * @throws java.io.IOException if a future input adapter fails / 输入适配器失败时
                     */
                    private String text(String input) throws java.io.IOException { int local = 1; return input; }
                }
                """;
        var result = JavadocCoverageInspector.snippets(valid);
        assertEquals(2, result.declarations());
        assertTrue(result.violations().isEmpty(), result.violations().toString());
        var annotated = JavadocCoverageInspector
                .snippets(valid.replace("(T value)", "(@SuppressWarnings(\"private\") T value)"));
        assertEquals(2, annotated.declarations());
        assertTrue(annotated.violations().isEmpty(), annotated.violations().toString());
        for (String missing : List.of("@param <T> payload type / 载荷类型", "@param value stored payload / 保存的载荷",
                "@param input caller input / 调用方输入", "@return unchanged input / 未变的输入",
                "@throws java.io.IOException if a future input adapter fails / 输入适配器失败时"))
            assertFalse(JavadocCoverageInspector.snippets(valid.replace(missing, "")).violations().isEmpty(), missing);
        assertFalse(JavadocCoverageInspector.snippets(valid.replace("unchanged input / 未变的输入", "unchanged input"))
                .violations().isEmpty());
    }

    @Test
    void privateAnonymousAndEnumDeclarationsAreIncluded() throws Exception {
        String source = """
                /** Fixture owner. / 夹具持有者。 */
                class Example {
                    private int count;
                    private Example() {}
                    /** Lifecycle kinds. / 生命周期类型。 */
                    enum Kind { READY }
                    /** Deferred operation. / 延迟操作。 */
                    Runnable action = new Runnable() { public void run() {} };
                }
                """;
        var result = JavadocCoverageInspector.snippets(source);
        assertEquals(7, result.declarations());
        assertEquals(4, result.violations().stream().filter(v -> v.endsWith("missing Javadoc")).count());
    }

    @Test
    void jdk21DoclintAcceptsProductionSyntaxAndReferences() throws Exception {
        assertEquals(21, Runtime.version().feature(), "Use the documented JDK 21 toolchain");
        var tool = ToolProvider.getSystemDocumentationTool();
        assertNotNull(tool);
        var diagnostics = new StringWriter();
        try (var manager = tool.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var options = List.of("-quiet", "-private", "-notimestamp", "-encoding", "UTF-8", "-docencoding", "UTF-8",
                    "-Xdoclint:all,-missing", "-Werror", "-d", "target/production-javadoc", "-classpath",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
            boolean valid = tool.getTask(diagnostics, manager, null, null, options,
                    manager.getJavaFileObjectsFromPaths(JavadocCoverageInspector.productionPaths())).call();
            Files.writeString(Path.of("target/javadoc-doclint.txt"), diagnostics.toString());
            assertTrue(valid,
                    () -> diagnostics.toString().substring(0, Math.min(12000, diagnostics.toString().length())));
        }
    }
}
