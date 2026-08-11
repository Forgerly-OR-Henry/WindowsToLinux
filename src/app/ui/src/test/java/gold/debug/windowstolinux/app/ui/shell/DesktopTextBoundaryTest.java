package gold.debug.windowstolinux.app.ui.shell;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopTextBoundaryTest {
    private static final Pattern HAN_TEXT = Pattern.compile("\\p{IsHan}");

    @Test
    void keepsHanTextOutOfProductionValuesWhileAllowingBilingualJavaComments() throws Exception {
        Path repositoryRoot = repositoryRoot();
        try (Stream<Path> files = Files.walk(repositoryRoot.resolve("src"))) {
            for (Path file : files.filter(Files::isRegularFile).filter(this::isProductionText).toList()) {
                String content = Files.readString(file);
                boolean containsForbiddenHan = file.toString().endsWith(".java")
                        ? containsHanOutsideJavaComments(content)
                        : HAN_TEXT.matcher(content).find();
                assertFalse(containsForbiddenHan,
                        () -> repositoryRoot.relativize(file)
                                + " must keep Han text out of production values; only bilingual Java comments and "
                                + "Messages_zh_CN.properties may contain it");
            }
        }
    }

    @Test
    void distinguishesBilingualCommentsFromProductionValues() {
        assertFalse(containsHanOutsideJavaComments("// English explanation. 中文说明。\nString value = \"English\";"));
        assertFalse(containsHanOutsideJavaComments("/** English explanation. 中文说明。 */\nchar value = 'E';"));
        assertTrue(containsHanOutsideJavaComments("String value = \"中文值\";"));
        assertTrue(containsHanOutsideJavaComments("char value = '中';"));
        assertTrue(containsHanOutsideJavaComments("String value = \"\"\"中文文本块\"\"\";"));
    }

    private static boolean containsHanOutsideJavaComments(String source) {
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;
        boolean character = false;
        boolean textBlock = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            if (lineComment) {
                lineComment = current != '\n' && current != '\r';
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (textBlock) {
                if (current == '"' && next == '"' && index + 2 < source.length()
                        && source.charAt(index + 2) == '"') {
                    textBlock = false;
                    index += 2;
                } else if (HAN_TEXT.matcher(String.valueOf(current)).find()) {
                    return true;
                }
                continue;
            }
            if (string || character) {
                if (HAN_TEXT.matcher(String.valueOf(current)).find()) {
                    return true;
                }
                if (current == '\\') {
                    index++;
                } else if (string && current == '"') {
                    string = false;
                } else if (character && current == '\'') {
                    character = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                index++;
            } else if (current == '"' && next == '"' && index + 2 < source.length()
                    && source.charAt(index + 2) == '"') {
                textBlock = true;
                index += 2;
            } else if (current == '"') {
                string = true;
            } else if (current == '\'') {
                character = true;
            } else if (HAN_TEXT.matcher(String.valueOf(current)).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean isProductionText(Path path) {
        String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        return normalized.contains("/src/main/")
                && !normalized.endsWith("/Messages_zh_CN.properties");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !(Files.isDirectory(current.resolve("src/shared"))
                && Files.isRegularFile(current.resolve("docs/File.md")))) {
            current = current.getParent();
        }
        assertNotNull(current, "repository root could not be located from the Maven test directory");
        return current;
    }
}
