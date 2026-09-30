package gold.debug.windowstolinux.app.main.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import com.sun.source.tree.Tree;
import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceFailureType;
import gold.debug.windowstolinux.app.main.diagnostic.DesktopSystemFailureType;
import gold.debug.windowstolinux.app.secret.SecretStoreFailureType;
import gold.debug.windowstolinux.app.service.contract.result.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.windows.uninstall.DesktopUninstallFailureType;
import gold.debug.windowstolinux.app.windows.update.DesktopUpdateFailureType;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceFailureType;
import org.junit.jupiter.api.Test;

/** Guards the shared failure vocabulary without centralizing module-specific interpretation. / 在不集中模块特定解释的前提下守护共用失败词汇。 */
class FailureContractArchitectureTest {
    // These SQL rollback signals are translated by the task host before reaching callers. / 这些 SQL 回滚信号在到达调用方前由任务宿主转换。
    private static final Set<String> SQL_BRIDGE_TYPES = Set.of(
            "gold.debug.windowstolinux.app.db.task.TaskLedger.DependencyBusyException",
            "gold.debug.windowstolinux.app.db.task.TaskLedger.FinalizationException",
            "gold.debug.windowstolinux.app.db.task.TaskAuthorCheckpoint.RejectedException",
            "gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime.AdmissionRejectedException");

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*$");

    private static final Pattern MESSAGE_KEY = Pattern.compile("^[a-z][a-z0-9]*\\.error\\.[a-z][A-Za-z0-9]*$");

    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*}", Pattern.MULTILINE);

    private static final Pattern IGNORED_CATCH = Pattern
            .compile("catch\\s*\\([^)]*\\b(?:ignored|alsoIgnored)\\b[^)]*\\)");

    private static final Map<Class<? extends Enum<?>>, String> FAILURE_TYPES = Map.ofEntries(
            Map.entry(DesktopUpdateFailureType.class, "windows"),
            Map.entry(DesktopUninstallFailureType.class, "windows"),
            Map.entry(ApplicationServiceFailureType.class, "service"),
            Map.entry(DesktopPersistenceFailureType.class, "persistence"),
            Map.entry(DesktopSystemFailureType.class, "desktop"),
            Map.entry(WindowsWorkspaceFailureType.class, "windows"));

    @Test
    void failureDefinitionsHaveStableUniqueCodesAndLocalizedMessages() throws Exception {
        List<FailureMetadata> definitions = definitions();
        Set<String> codes = new HashSet<>();
        Properties english = messages("Messages.properties");
        Properties chinese = messages("Messages_zh_CN.properties");

        assertEquals(english.stringPropertyNames(), chinese.stringPropertyNames(),
                "English and Simplified Chinese message catalogs must have identical keys");
        for (FailureMetadata definition : definitions) {
            Class<?> owner = definition.owner();
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
        for (var type : SecretStoreFailureType.values()) {
            assertTrue(CODE.matcher(type.eventCode()).matches(), type::eventCode);
            assertTrue(codes.add(type.eventCode()), type::eventCode);
            assertTrue(english.containsKey("problem." + type.eventCode()), type::eventCode);
            assertTrue(chinese.containsKey("problem." + type.eventCode()), type::eventCode);
            assertTrue(type.problemCode() != null);
        }
        writeCatalog(definitions);
    }

    @Test
    void customExceptionsExposeOnlyTheStructuredFailureContract() throws Exception {
        List<String> violations = exceptionViolations(ArchitectureSourceInspector.production());
        assertTrue(violations.isEmpty(), () -> "custom exception violations: " + violations);
        assertTrue(ArchitectureSourceInspector.productionPaths().stream()
                .noneMatch(path -> Set.of("LocalizedFailure.java", "LocalizedOperationException.java")
                        .contains(path.getFileName().toString())));
    }

    @Test
    void everyProductionFailureDefinitionMustBeRegistered() throws Exception {
        assertEquals(Set.of(), registrationDifferences(ArchitectureSourceInspector.production(), FAILURE_TYPES.keySet()
                .stream().map(Class::getCanonicalName).collect(java.util.stream.Collectors.toSet())));
    }

    @Test
    void missingAndStaleRegistrationsCannotPass() throws Exception {
        var sources = ArchitectureSourceInspector.production();
        var registered = new HashSet<>(FAILURE_TYPES.keySet().stream().map(Class::getCanonicalName).toList());
        registered.remove(DesktopUpdateFailureType.class.getCanonicalName());
        assertEquals(Set.of(DesktopUpdateFailureType.class.getCanonicalName()),
                registrationDifferences(sources, registered));
        registered.add(DesktopUpdateFailureType.class.getCanonicalName());
        registered.add("unavailable.StaleFailureType");
        assertEquals(Set.of("unavailable.StaleFailureType"), registrationDifferences(sources, registered));
    }

    @Test
    void newlyAddedNestedFailureDefinitionsCannotEscapeRegistration() throws Exception {
        var sources = ArchitectureSourceInspector.snippets(Map.of("fixture.FailureFixtures", """
                package fixture;
                import gold.debug.windowstolinux.appapi.task.Problem;
                class FailureFixtures {
                    enum AddedFailureType {
                        NEW;
                        public String code() { return "fixture.operation.added"; }
                        public String domain() { return "fixture"; }
                        public String phase() { return "operation"; }
                        public String messageKey() { return "fixture.error.added"; }
                        public Problem problem(java.util.UUID operation, String diagnostic) { return null; }
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
                    static class StructuredException extends RuntimeException {
                        public gold.debug.windowstolinux.appapi.task.Problem problem() { return null; }
                    }
                    static class InheritedException extends StructuredException { }
                    static class WrongName extends StructuredException { }
                }
                """));
        var violations = exceptionViolations(sources);
        assertTrue(violations.stream().anyMatch(value -> value.contains("MissingContractException")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("IndirectException")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("WrongName") && value.contains("name")));
        assertFalse(violations.stream()
                .anyMatch(value -> value.contains("StructuredException") || value.contains("InheritedException")));
    }

    private static Set<String> registrationDifferences(List<ArchitectureSourceInspector.JavaSource> sources,
            Set<String> registered) {
        Set<String> discovered = new HashSet<>();
        Set<String> invalid = new HashSet<>();
        sources.stream().flatMap(source -> source.types().stream())
                .filter(type -> type.kind() != Tree.Kind.INTERFACE
                        && (type.problemFactory() || type.simpleName().endsWith("FailureType")))
                .filter(type -> !type.name().equals(SecretStoreFailureType.class.getCanonicalName())).forEach(type -> {
                    assertEquals(Tree.Kind.ENUM, type.kind(), type.name() + " must be an enum");
                    discovered.add(type.name());
                    if (!type.problemFactory())
                        invalid.add(type.name());
                });
        Set<String> differences = new HashSet<>(discovered);
        differences.removeAll(registered);
        Set<String> stale = new HashSet<>(registered);
        stale.removeAll(discovered);
        differences.addAll(stale);
        differences.addAll(invalid);
        return differences;
    }

    @Test
    void registeredFailureNamesCannotReplaceTheActualSdkFactory() throws Exception {
        var sources = ArchitectureSourceInspector.snippets(Map.of("fixture.InvalidFailures", """
                package fixture;
                class InvalidFailures {
                    enum MissingFailureType { BAD }
                    enum ForeignFailureType {
                        BAD;
                        public SameNamed.Problem problem(java.util.UUID id) { return null; }
                    }
                    enum WrongIdentityFailureType {
                        BAD;
                        public gold.debug.windowstolinux.appapi.task.Problem problem(String id) { return null; }
                    }
                }
                class SameNamed { record Problem(String value) { } }
                """));
        var names = Set.of("fixture.InvalidFailures.MissingFailureType", "fixture.InvalidFailures.ForeignFailureType",
                "fixture.InvalidFailures.WrongIdentityFailureType");
        assertEquals(names, registrationDifferences(sources, names));
    }

    private static List<String> exceptionViolations(List<ArchitectureSourceInspector.JavaSource> sources) {
        List<String> violations = new ArrayList<>();
        sources.stream().flatMap(source -> source.types().stream())
                .filter(ArchitectureSourceInspector.JavaType::throwable).forEach(type -> {
                    if (!type.publicProblem() && !(type.sqlException() && SQL_BRIDGE_TYPES.contains(type.name())))
                        violations.add(type.name() + " does not expose an actual public SDK Problem contract");
                    if (!type.simpleName().matches("[A-Z][A-Za-z0-9]+Exception"))
                        violations.add(type.name() + " does not name its failure boundary");
                });
        return violations;
    }

    @Test
    void platformProblemsRequireAnActualPublicInstanceAccessorAndSqlSignalsRemainExplicit() throws Exception {
        var sources = ArchitectureSourceInspector.snippets(Map.of("fixture.PlatformFailures", """
                package fixture;
                import gold.debug.windowstolinux.appapi.task.Problem;
                class PlatformFailures {
                    static class ClassifiedException extends Exception {
                        public gold.debug.windowstolinux.appapi.task.Outcome outcome() { return null; }
                        public java.util.Optional<Problem> problem() { return java.util.Optional.empty(); }
                    }
                    static class MissingOutcomeException extends Exception {
                        public java.util.Optional<Problem> problem() { return java.util.Optional.empty(); }
                    }
                    static class WrongOptionalException extends Exception {
                        public gold.debug.windowstolinux.appapi.task.Outcome outcome() { return null; }
                        public java.util.Optional<String> problem() { return java.util.Optional.empty(); }
                    }
                    static class PrivateOutcomeException extends Exception {
                        private gold.debug.windowstolinux.appapi.task.Outcome outcome() { return null; }
                        public java.util.Optional<Problem> problem() { return java.util.Optional.empty(); }
                    }
                    static class StaticOutcomeException extends Exception {
                        public static gold.debug.windowstolinux.appapi.task.Outcome outcome() { return null; }
                        public java.util.Optional<Problem> problem() { return java.util.Optional.empty(); }
                    }
                    static class ForeignOutcomeException extends Exception {
                        public SameNamed.Outcome outcome() { return null; }
                        public java.util.Optional<Problem> problem() { return java.util.Optional.empty(); }
                    }
                    static class ForeignProblemException extends Exception {
                        public gold.debug.windowstolinux.appapi.task.Outcome outcome() { return null; }
                        public java.util.Optional<SameNamed.Problem> problem() { return java.util.Optional.empty(); }
                    }
                    static class PublicProblemException extends Exception {
                        public Problem problem() { return null; }
                    }
                    static class InheritedProblemException extends PublicProblemException { }
                    static class WrongReturnException extends Exception {
                        public String problem() { return "raw text"; }
                    }
                    static class PrivateProblemException extends Exception {
                        private Problem problem() { return null; }
                    }
                    static class StaticProblemException extends Exception {
                        public static Problem problem() { return null; }
                    }
                    static class UntranslatedSqlException extends java.sql.SQLException { }
                }
                class SameNamed {
                    enum Outcome { CANCELLED }
                    record Problem(String message) { }
                }
                """));
        var violations = exceptionViolations(sources);
        assertEquals(10, violations.size());
        for (String name : List.of("WrongReturnException", "PrivateProblemException", "StaticProblemException",
                "UntranslatedSqlException", "MissingOutcomeException", "WrongOptionalException",
                "PrivateOutcomeException", "StaticOutcomeException", "ForeignOutcomeException",
                "ForeignProblemException"))
            assertTrue(violations.stream().anyMatch(value -> value.contains(name)), name);
        var registered = ArchitectureSourceInspector.production().stream().flatMap(source -> source.types().stream())
                .filter(type -> SQL_BRIDGE_TYPES.contains(type.name())).toList();
        assertEquals(SQL_BRIDGE_TYPES, registered.stream().map(ArchitectureSourceInspector.JavaType::name)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(registered.stream().allMatch(ArchitectureSourceInspector.JavaType::sqlException));
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
                if (!explanationWindow.contains("//") && !explanationWindow.contains("/*")) {
                    violations.add(projectRoot().relativize(source)
                            + " silently captures a failure without an explanatory comment");
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "failure boundary violations: " + violations);
    }

    private record FailureMetadata(Class<?> owner, String code, String domain, String phase, String messageKey,
            String severity, String recoveryAction) {
    }

    private static List<FailureMetadata> definitions() throws Exception {
        var definitions = new ArrayList<FailureMetadata>();
        for (var owner : FAILURE_TYPES.keySet())
            for (var value : owner.getEnumConstants()) {
                var identity = java.util.UUID.randomUUID();
                var factory = java.util.Arrays.stream(owner.getMethods())
                        .filter(method -> method.getName().equals("problem")
                                && method.getReturnType() == gold.debug.windowstolinux.appapi.task.Problem.class)
                        .findFirst().orElseThrow();
                Object[] arguments = factory.getParameterCount() == 3
                        ? new Object[]{identity, Map.of(), "fixture"}
                        : new Object[]{identity, "fixture"};
                var problem = (gold.debug.windowstolinux.appapi.task.Problem) factory.invoke(value, arguments);
                var metadata = new FailureMetadata(owner, text(owner, value, "code"), text(owner, value, "domain"),
                        text(owner, value, "phase"), text(owner, value, "messageKey"), text(owner, value, "severity"),
                        text(owner, value, "recoveryAction"));
                assertEquals(metadata.code(), problem.eventCode());
                assertEquals(identity, problem.diagnosticId().orElseThrow());
                assertEquals(metadata.messageKey(), problem.context().get("messageKey"));
                assertEquals(metadata.phase(), problem.context().get("phase"));
                definitions.add(metadata);
            }
        definitions.sort(Comparator.comparing(FailureMetadata::code));
        return List.copyOf(definitions);
    }

    private static String text(Class<?> owner, Object value, String accessor) throws Exception {
        return owner.getMethod(accessor).invoke(value).toString();
    }

    private static Properties messages(String fileName) throws IOException {
        Path resource = projectRoot()
                .resolve("src/app/ui/src/main/resources/gold/debug/windowstolinux/app/ui/i18n/messages")
                .resolve(fileName);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(resource, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void writeCatalog(List<FailureMetadata> definitions) throws IOException {
        Path target = Path.of("target").toAbsolutePath().normalize();
        Files.createDirectories(target);
        List<String> lines = new ArrayList<>(
                List.of("# Failure catalog", "", "Generated by FailureContractArchitectureTest; do not commit.", "",
                        "| Code | Owner | Phase | Severity | Recovery | Message key |",
                        "| --- | --- | --- | --- | --- | --- |"));
        definitions.forEach(value -> lines
                .add("| " + value.code() + " | " + value.owner().getSimpleName() + " | " + value.phase() + " | "
                        + value.severity() + " | " + value.recoveryAction() + " | " + value.messageKey() + " |"));
        Files.write(target.resolve("failure-catalog.md"), lines, StandardCharsets.UTF_8);
    }

    private static List<Path> productionSources() throws IOException {
        return ArchitectureSourceInspector.production().stream().map(ArchitectureSourceInspector.JavaSource::path)
                .toList();
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !(Files.isRegularFile(current.resolve("pom.xml"))
                && Files.isRegularFile(current.resolve("docs/File.md")))) {
            current = current.getParent();
        }
        if (current == null)
            throw new IllegalStateException("project root was not found");
        return current;
    }
}
