package gold.debug.windowstolinux.app.main.architecture;

import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceFailureType;
import gold.debug.windowstolinux.app.main.diagnostic.DesktopSystemFailureType;
import gold.debug.windowstolinux.app.secret.SecretStoreFailureType;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceFailureType;
import gold.debug.windowstolinux.shared.ai.AiAnalysisFailureType;
import gold.debug.windowstolinux.shared.config.ConfigurationFailureType;
import gold.debug.windowstolinux.shared.deploy.error.DeploymentExecutionFailureType;
import gold.debug.windowstolinux.shared.git.GitSnapshotFailureType;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentApprovalFailureType;
import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.source.archive.SourceArchiveFailureType;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretFailureType;
import gold.debug.windowstolinux.app.windows.update.DesktopUpdateFailureType;
import gold.debug.windowstolinux.app.windows.uninstall.DesktopUninstallFailureType;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseFailureType;
import com.sun.source.tree.Tree;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the shared failure vocabulary without centralizing module-specific interpretation. / 在不集中模块特定解释的前提下守护共用失败词汇。 */
class FailureContractArchitectureTest {
    private static final Pattern CODE = Pattern.compile(
            "^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*$");
    private static final Pattern MESSAGE_KEY = Pattern.compile(
            "^[a-z][a-z0-9]*\\.error\\.[a-z][A-Za-z0-9]*$");
    private static final Pattern EMPTY_CATCH = Pattern.compile(
            "catch\\s*\\([^)]*\\)\\s*\\{\\s*}", Pattern.MULTILINE);
    private static final Pattern IGNORED_CATCH = Pattern.compile(
            "catch\\s*\\([^)]*\\b(?:ignored|alsoIgnored)\\b[^)]*\\)");

    private static final Map<Class<? extends Enum<?>>, String> FAILURE_TYPES = Map.ofEntries(
            Map.entry(AiAnalysisFailureType.class, "ai"),
            Map.entry(BackupFailureType.class, "backup"),
            Map.entry(BackupSecretFailureType.class, "secret"),
            Map.entry(DesktopUpdateFailureType.class, "windows"),
            Map.entry(DesktopUninstallFailureType.class, "windows"),
            Map.entry(NativeDatabaseFailureType.class, "linux"),
            Map.entry(ApplicationServiceFailureType.class, "service"),
            Map.entry(ConfigurationFailureType.class, "configuration"),
            Map.entry(DeploymentApprovalFailureType.class, "deployment"),
            Map.entry(DeploymentExecutionFailureType.class, "deployment"),
            Map.entry(DesktopPersistenceFailureType.class, "persistence"),
            Map.entry(DesktopSystemFailureType.class, "desktop"),
            Map.entry(GitSnapshotFailureType.class, "git"),
            Map.entry(LinuxOperationFailureType.class, "linux"),
            Map.entry(SecretStoreFailureType.class, "secret"),
            Map.entry(SourceArchiveFailureType.class, "source"),
            Map.entry(WindowsWorkspaceFailureType.class, "windows"));

    @Test
    void failureDefinitionsHaveStableUniqueCodesAndLocalizedMessages() throws Exception {
        List<FailureDefinition> definitions = definitions();
        Set<String> codes = new HashSet<>();
        Properties english = messages("Messages.properties");
        Properties chinese = messages("Messages_zh_CN.properties");

        assertEquals(english.stringPropertyNames(), chinese.stringPropertyNames(),
                "English and Simplified Chinese message catalogs must have identical keys");
        for (FailureDefinition definition : definitions) {
            Class<?> owner = definition.getClass();
            String expectedDomain = FAILURE_TYPES.get(owner);
            assertTrue(owner.getSimpleName().endsWith("FailureType"), owner::getName);
            assertTrue(CODE.matcher(definition.code()).matches(), definition::code);
            assertTrue(codes.add(definition.code()), () -> "duplicate failure code: " + definition.code());
            assertEquals(expectedDomain, definition.domain(), owner::getName);
            assertEquals(expectedDomain, definition.code().substring(0, definition.code().indexOf('.')),
                    definition::code);
            assertTrue(MESSAGE_KEY.matcher(definition.messageKey()).matches(), definition::messageKey);
            assertTrue(english.containsKey(definition.messageKey()), definition::messageKey);
            assertTrue(chinese.containsKey(definition.messageKey()), definition::messageKey);
            assertFalse(definition.phase().isBlank(), definition::code);
        }
        assertFalse(definitions.isEmpty());
        writeCatalog(definitions);
    }

    @Test
    void customExceptionsExposeOnlyTheStructuredFailureContract() throws Exception {
        List<String> violations = exceptionViolations(ArchitectureSourceInspector.production());
        assertTrue(violations.isEmpty(), () -> "custom exception violations: " + violations);
        assertFalse(Files.exists(projectRoot().resolve(
                "src/shared/model/src/main/java/gold/debug/windowstolinux/shared/model/message/LocalizedFailure.java")));
        assertFalse(Files.exists(projectRoot().resolve(
                "src/shared/model/src/main/java/gold/debug/windowstolinux/shared/model/message/LocalizedOperationException.java")));
    }

    @Test
    void everyProductionFailureDefinitionMustBeRegistered() throws Exception {
        assertEquals(Set.of(), registrationDifferences(ArchitectureSourceInspector.production(),
                FAILURE_TYPES.keySet().stream().map(Class::getCanonicalName).collect(java.util.stream.Collectors.toSet())));
    }

    @Test
    void missingAndStaleRegistrationsCannotPass() throws Exception {
        var sources = ArchitectureSourceInspector.production();
        var registered = new HashSet<>(FAILURE_TYPES.keySet().stream().map(Class::getCanonicalName).toList());
        registered.remove(BackupFailureType.class.getCanonicalName());
        assertEquals(Set.of(BackupFailureType.class.getCanonicalName()), registrationDifferences(sources, registered));
        registered.add(BackupFailureType.class.getCanonicalName());
        registered.add("unavailable.StaleFailureType");
        assertEquals(Set.of("unavailable.StaleFailureType"), registrationDifferences(sources, registered));
    }

    @Test
    void newlyAddedNestedFailureDefinitionsCannotEscapeRegistration() throws Exception {
        var sources = ArchitectureSourceInspector.snippets(Map.of("fixture.FailureFixtures", """
                package fixture;
                import gold.debug.windowstolinux.shared.model.failure.*;
                class FailureFixtures {
                    enum AddedFailureType implements FailureDefinition {
                        NEW;
                        public String code() { return "fixture.operation.added"; }
                        public String domain() { return "fixture"; }
                        public String phase() { return "operation"; }
                        public String messageKey() { return "fixture.error.added"; }
                        public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
                        public FailureRecoveryAction recoveryAction() { return FailureRecoveryAction.NONE; }
                    }
                }
                """));
        assertEquals(Set.of("fixture.FailureFixtures.AddedFailureType"), registrationDifferences(sources, Set.of()));
    }

    @Test
    void nestedAndIndirectExceptionsAreCheckedByType() throws Exception {
        var sources = ArchitectureSourceInspector.snippets(Map.of("fixture.ExceptionFixtures", """
                package fixture;
                class ExceptionFixtures {
                    static class MissingContractException extends RuntimeException { }
                    static class IndirectException extends MissingContractException { }
                    static class StructuredException extends RuntimeException
                            implements gold.debug.windowstolinux.shared.model.failure.FailureCarrier {
                        public gold.debug.windowstolinux.shared.model.failure.FailureDescriptor failure() { return null; }
                    }
                    static class InheritedException extends StructuredException { }
                    static class WrongName extends StructuredException { }
                }
                """));
        var violations = exceptionViolations(sources);
        assertTrue(violations.stream().anyMatch(value -> value.contains("MissingContractException")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("IndirectException")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("WrongName") && value.contains("name")));
        assertFalse(violations.stream().anyMatch(value -> value.contains("StructuredException") || value.contains("InheritedException")));
    }

    private static Set<String> registrationDifferences(List<ArchitectureSourceInspector.JavaSource> sources,
                                                       Set<String> registered) {
        Set<String> discovered = new HashSet<>();
        sources.stream().flatMap(source -> source.types().stream())
                .filter(type -> type.failureDefinition() && type.kind() != Tree.Kind.INTERFACE)
                .forEach(type -> {
                    assertEquals(Tree.Kind.ENUM, type.kind(), type.name() + " must be an enum");
                    discovered.add(type.name());
                });
        Set<String> differences = new HashSet<>(discovered);
        differences.removeAll(registered);
        Set<String> stale = new HashSet<>(registered);
        stale.removeAll(discovered);
        differences.addAll(stale);
        return differences;
    }

    private static List<String> exceptionViolations(List<ArchitectureSourceInspector.JavaSource> sources) {
        List<String> violations = new ArrayList<>();
        sources.stream().flatMap(source -> source.types().stream()).filter(ArchitectureSourceInspector.JavaType::throwable)
                .forEach(type -> {
                    if (!type.failureCarrier()) violations.add(type.name() + " does not implement FailureCarrier");
                    if (!type.simpleName().matches("[A-Z][A-Za-z0-9]+Exception"))
                        violations.add(type.name() + " does not name its failure boundary");
                });
        return violations;
    }

    @Test
    void userBoundariesNeverFallbackToRawExceptionMessages() throws Exception {
        List<String> violations = new ArrayList<>();
        Path ui = projectRoot().resolve("src/app/ui/src/main/java");
        try (Stream<Path> files = Files.walk(ui)) {
            for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source);
                if (content.contains("getMessage()")) {
                    violations.add(projectRoot().relativize(source) + " exposes getMessage() at the UI boundary");
                }
            }
        }
        for (Path source : productionSources()) {
            String content = Files.readString(source);
            if (content.contains("new DeploymentEvent(\"")) {
                violations.add(projectRoot().relativize(source) + " uses an untyped deployment step");
            }
            if (EMPTY_CATCH.matcher(content).find()) {
                violations.add(projectRoot().relativize(source) + " contains an empty catch block");
            }
            var matcher = IGNORED_CATCH.matcher(content);
            while (matcher.find()) {
                int end = Math.min(content.length(), matcher.end() + 600);
                String explanationWindow = content.substring(matcher.end(), end);
                if (!explanationWindow.contains("//")) {
                    violations.add(projectRoot().relativize(source)
                            + " silently captures a failure without an explanatory comment");
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "failure boundary violations: " + violations);
    }

    private static List<FailureDefinition> definitions() {
        return FAILURE_TYPES.keySet().stream().flatMap(type -> Stream.of(type.getEnumConstants()))
                .map(FailureDefinition.class::cast).sorted(Comparator.comparing(FailureDefinition::code)).toList();
    }

    private static Properties messages(String fileName) throws IOException {
        Path resource = projectRoot().resolve(
                "src/app/ui/src/main/resources/gold/debug/windowstolinux/app/ui/i18n/messages").resolve(fileName);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(resource, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void writeCatalog(List<FailureDefinition> definitions) throws IOException {
        Path target = Path.of("target").toAbsolutePath().normalize();
        Files.createDirectories(target);
        List<String> lines = new ArrayList<>(List.of(
                "# Failure catalog", "", "Generated by FailureContractArchitectureTest; do not commit.", "",
                "| Code | Owner | Phase | Severity | Recovery | Message key |",
                "| --- | --- | --- | --- | --- | --- |"));
        definitions.forEach(value -> lines.add("| " + value.code() + " | " + value.getClass().getSimpleName()
                + " | " + value.phase() + " | " + value.severity() + " | " + value.recoveryAction()
                + " | " + value.messageKey() + " |"));
        Files.write(target.resolve("failure-catalog.md"), lines, StandardCharsets.UTF_8);
    }

    private static List<Path> productionSources() throws IOException {
        try (Stream<Path> files = Files.walk(projectRoot().resolve("src"))) {
            return files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("src" + java.io.File.separator + "main"
                            + java.io.File.separator + "java")).toList();
        }
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !(Files.isRegularFile(current.resolve("pom.xml"))
                && Files.isRegularFile(current.resolve("docs/File.md")))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root was not found");
        return current;
    }
}
