package gold.debug.windowstolinux.app.ui.window;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class DesktopTextArchitectureTest {
    private static final Pattern HAN_TEXT = Pattern.compile("\\p{IsHan}");

    @Test
    void visibleSwingCopyMustComeFromMessageKeysInEveryLanguage() throws Exception {
        Pattern visibleLiteral = Pattern.compile(
            "(?:new\\s+(?:JLabel|JButton|JCheckBox|JRadioButton|ToggleSwitch)\\s*\\(|\\.(?:primaryButton|secondaryButton|setText|setToolTipText|addItem)\\s*\\()\\s*\"([^\"]*)\"");
        try (var files = Files.walk(repositoryRoot().resolve("src/app/ui/src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var matches = visibleLiteral.matcher(Files.readString(file));
                while (matches.find()) {
                    String literal = matches.group(1);
                    assertFalse(literal.matches(".*[A-Za-z\\p{IsHan}].*") && !literal.startsWith("<html>"),
                        file.getFileName() + " contains directly rendered copy: " + literal);
                }
            }
        }
    }

    @Test
    void nativeDatabaseDiagnosticsHaveMatchingKeysInsteadOfEnglishProtocolSentences() throws Exception {
        var english = gold.debug.windowstolinux.app.ui.language.MessageCatalog.forLanguageTag("en");
        var chinese = gold.debug.windowstolinux.app.ui.language.MessageCatalog.forLanguageTag("zh-CN");
        String helper = Files.readString(repositoryRoot().resolve(
            "src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/linux/execution/protocol/helper/fragments/database/10-native-instances.sh"));
        var codes = Pattern.compile("conflicts\\.append\\('([^']+)'").matcher(helper);
        while (codes.find()) {
            String code = codes.group(1).split("\\|", 2)[0];
            assertTrue(code.matches("[a-z_]+"));
            assertNotNull(english.text("db.conflict." + code, java.util.Map.of("instance", "fixture")));
            assertNotNull(chinese.text("db.conflict." + code, java.util.Map.of("instance", "fixture")));
        }
        for (var failure : gold.debug.windowstolinux.app.service.error.ApplicationServiceFailureType
            .values()) {
            if (!failure.phase().equals("database"))
                continue;
            String key = failure.messageKey();
            assertNotNull(english.text(key));
            assertNotNull(chinese.text(key));
        }
    }

    @Test
    void inputChoicesTranslateLabelsWithoutChangingSubmittedProtocolValues() {
        var choice = new javax.swing.JComboBox<>(gold.debug.windowstolinux.app.ui.display.ThemeMode.values());
        var presenter = new gold.debug.windowstolinux.app.ui.error.PageMessagePresenter(
            gold.debug.windowstolinux.app.ui.language.MessageCatalog.forLanguageTag("zh-CN"));
        presenter.localize(choice, "theme.");
        choice.setSelectedItem(gold.debug.windowstolinux.app.ui.display.ThemeMode.DARK);
        var label = (javax.swing.JLabel) choice.getRenderer().getListCellRendererComponent(new javax.swing.JList<>(),
            gold.debug.windowstolinux.app.ui.display.ThemeMode.DARK, 2, false, false);
        org.junit.jupiter.api.Assertions.assertEquals(presenter.text("theme.dark"), label.getText());
        org.junit.jupiter.api.Assertions.assertEquals(gold.debug.windowstolinux.app.ui.display.ThemeMode.DARK,
            choice.getSelectedItem());
        org.junit.jupiter.api.Assertions.assertEquals("DARK", ((Enum<?>) choice.getSelectedItem()).name());
    }

    @Test
    void keepsHanTextOutOfProductionValuesWhileAllowingBilingualSourceComments() throws Exception {
        Path repositoryRoot = repositoryRoot();
        for (Path module : activeModules(repositoryRoot))
            try (Stream<Path> files = Files.walk(module.resolve("src/main"))) {
                for (Path file : files.filter(Files::isRegularFile).filter(this::isProductionText).toList()) {
                    String content = Files.readString(file);
                    if (file.toString().endsWith(".sh"))
                        content = content.replaceAll("(?m)^\\s*#[^\\r\\n]*", "");
                    if (file.endsWith(Path.of("gold/debug/windowstolinux/app/db/log/LogRecordCodec.java")))
                        // Exact input-recognition tokens are security data, not localized UI copy. / 精确输入识别词属于安全数据，不是本地化界面文案。
                        content = content.replace("|密码|密钥)", "|password|secret)");
                    boolean containsForbiddenHan = file.toString().endsWith(".java")
                        ? containsHanOutsideJavaComments(content)
                        : HAN_TEXT.matcher(content).find();
                    assertFalse(containsForbiddenHan, () -> repositoryRoot.relativize(file)
                        + " must keep Han text out of production values; only bilingual source comments and "
                        + "Chinese locale properties may contain it");
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
                if (current == '"' && next == '"' && index + 2 < source.length() && source.charAt(index + 2) == '"') {
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
        return normalized.contains("/src/main/") && !normalized.matches(".*_zh(?:_CN)?\\.properties$");
    }

    private static java.util.List<Path> activeModules(Path root) throws Exception {
        var result = new java.util.ArrayList<Path>();
        visitModules(root, root.resolve("pom.xml"), new java.util.HashSet<>(), result);
        return java.util.List.copyOf(result);
    }

    private static void visitModules(Path root, Path pom, java.util.Set<Path> visited, java.util.List<Path> modules)
        throws Exception {
        pom = pom.toAbsolutePath().normalize();
        assertTrue(pom.startsWith(root) && Files.isRegularFile(pom) && visited.add(pom),
            "Invalid or repeated Maven aggregation: " + pom);
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
        boolean aggregate = false;
        for (var node = project.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof org.w3c.dom.Element element && element.getTagName().equals("modules")) {
                aggregate = true;
                for (var child = element.getFirstChild(); child != null; child = child.getNextSibling())
                    if (child instanceof org.w3c.dom.Element module && module.getTagName().equals("module"))
                        visitModules(root, pom.getParent().resolve(module.getTextContent().trim()).resolve("pom.xml"),
                            visited, modules);
            }
        if (!aggregate && Files.isDirectory(pom.getParent().resolve("src/main")))
            modules.add(pom.getParent());
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !(Files.isRegularFile(current.resolve("pom.xml"))
            && Files.isRegularFile(current.resolve("docs/File.md")))) {
            current = current.getParent();
        }
        assertNotNull(current, "repository root could not be located from the Maven test directory");
        return current;
    }
}
