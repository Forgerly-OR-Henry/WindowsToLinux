package gold.debug.windowstolinux.app.ui.shell;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopTextArchitectureTest {
    private static final Pattern HAN_TEXT = Pattern.compile("\\p{IsHan}");

    @Test void visibleSwingCopyMustComeFromMessageKeysInEveryLanguage() throws Exception {
        Pattern visibleLiteral = Pattern.compile("(?:new\\s+(?:JLabel|JButton|JCheckBox|JRadioButton)\\s*\\(|\\.(?:primaryButton|secondaryButton|setText|setToolTipText|addItem)\\s*\\()\\s*\"([^\"]*)\"");
        try (var files = Files.walk(repositoryRoot().resolve("src/app/ui/src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var matches = visibleLiteral.matcher(Files.readString(file));
                while (matches.find()) {
                    String literal = matches.group(1);
                    assertFalse(literal.matches(".*[A-Za-z\\p{IsHan}].*") && !literal.startsWith("<html>"),
                            file.getFileName()+" contains directly rendered copy: "+literal);
                }
            }
        }
    }

    @Test void nativeDatabaseDiagnosticsHaveMatchingKeysInsteadOfEnglishProtocolSentences() throws Exception {
        var english = gold.debug.windowstolinux.app.ui.i18n.MessageCatalog.forLanguageTag("en");
        var chinese = gold.debug.windowstolinux.app.ui.i18n.MessageCatalog.forLanguageTag("zh-CN");
        String helper = Files.readString(repositoryRoot().resolve("src/shared/linux-sshd/src/main/resources/gold/debug/windowstolinux/shared/linux/sshd/execution/protocol/helper/fragments/database/10-native-instances.sh"));
        var codes = Pattern.compile("conflicts\\.append\\('([^']+)'").matcher(helper);
        while (codes.find()) {
            String code = codes.group(1).split("\\|",2)[0];
            assertTrue(code.matches("[a-z_]+"));
            assertNotNull(english.text("db.conflict."+code,java.util.Map.of("instance","fixture")));
            assertNotNull(chinese.text("db.conflict."+code,java.util.Map.of("instance","fixture")));
        }
        for (var failure : gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType.values()) {
            if (!failure.phase().equals("database")) continue;
            String key = failure.messageKey();
            assertNotNull(english.text(key)); assertNotNull(chinese.text(key));
        }
    }

    @Test void inputChoicesTranslateLabelsWithoutChangingSubmittedProtocolValues() {
        var field = new gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField("app/type","auto.field.type","auto.help.type","",java.util.List.of("NODE_SERVICE"));
        var presenter = new gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter(gold.debug.windowstolinux.app.ui.i18n.MessageCatalog.forLanguageTag("zh-CN"));
        org.junit.jupiter.api.Assertions.assertEquals(presenter.text("project.type.node_service"),presenter.inputChoice(field,"NODE_SERVICE"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("NODE_SERVICE"),field.choices());
    }

    @Test void restoreAndMigrationStatusesAndWarningsAreMappedInBothLanguages() throws Exception {
        for (String language : java.util.List.of("en","zh-CN")) {
            var catalog = gold.debug.windowstolinux.app.ui.i18n.MessageCatalog.forLanguageTag(language);
            for (var status : gold.debug.windowstolinux.shared.backup.restore.BackupRestoreStatus.values())
                assertFalse(catalog.text("backup.restore.status."+status.name().toLowerCase(java.util.Locale.ROOT)).equals(status.name()));
            for (var status : gold.debug.windowstolinux.shared.backup.execution.migration.OfflineMigrationStatus.values())
                assertFalse(catalog.text("backup.migration.status."+status.name().toLowerCase(java.util.Locale.ROOT)).equals(status.name()));
            for (var status : gold.debug.windowstolinux.app.service.backup.ManagedRestoreControlState.values())
                assertFalse(catalog.text("backup.restore.control."+status.name().toLowerCase(java.util.Locale.ROOT)).equals(status.name()));
            for (String file : java.util.List.of("ManagedRestoreUseCase.java","ManagedOfflineMigrationUseCase.java")) {
                String source = Files.readString(repositoryRoot().resolve("src/app/service/src/main/java/gold/debug/windowstolinux/app/service/backup/"+file));
                var warnings = Pattern.compile("warnings\\.add\\(\"([^\"]+)\"\\)").matcher(source);
                while (warnings.find()) {
                    assertTrue(warnings.group(1).startsWith("backup.warning."));
                    assertNotNull(catalog.text(warnings.group(1)));
                }
            }
        }
    }

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
