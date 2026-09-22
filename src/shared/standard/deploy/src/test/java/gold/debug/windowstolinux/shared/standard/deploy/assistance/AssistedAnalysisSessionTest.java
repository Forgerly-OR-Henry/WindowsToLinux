package gold.debug.windowstolinux.shared.standard.deploy.assistance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.source.browse.SourceBrowser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssistedAnalysisSessionTest {
    @TempDir
    Path root;

    final DeploymentInputField field = new DeploymentInputField("app/type", "type", "help", "",
            List.of("NODE_SERVICE", "STATIC_SITE"));

    final AssistedDeploymentAdvice empty = new AssistedDeploymentAdvice("", List.of(), List.of());

    SourceBrowser browser() throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"start\":\"node server.js\"},\"port\":18080}");
        return new SourceBrowser(root);
    }

    AssistedAnalysisDecision read(String revision) {
        return new AssistedAnalysisDecision("READ", revision, "package.json", 0, 20, empty);
    }

    AssistedAnalysisDecision advice(String revision, String id, String value, String ref) {
        return new AssistedAnalysisDecision("ADVISE", revision, "", 0, 1,
                new AssistedDeploymentAdvice("observed declaration",
                        List.of(new AssistedDeploymentAdvice.Candidate(id, value, List.of(ref))), List.of()));
    }

    @Test
    void selectsOneOfSeveralCandidatesUsingActualNumberedEvidence() throws Exception {
        var source = browser();
        var calls = new AtomicInteger();
        var budget = new AssistedDecisionBudget();
        var session = new AssistedAnalysisSession(source, (context, observations, left) -> {
            assertEquals(source.revision(), context.get("sourceRevision"));
            if (calls.getAndIncrement() == 0)
                return read(source.revision());
            assertEquals("package.json", observations.getFirst().get("pathOrQuery"));
            assertTrue(observations.getFirst().get("content").startsWith("1: "));
            return advice(source.revision(), field.id(), "NODE_SERVICE", "source/0");
        }, budget);
        assertEquals("NODE_SERVICE", session.analyze(List.of(field), Map.of()).suggestions().getFirst().candidate());
        assertEquals(28, budget.remaining());
    }

    @Test
    void acceptsEvidencedOpenTechnicalFieldButRejectsShellAndPrivilegeFields() throws Exception {
        var source = browser();
        var port = new DeploymentInputField("app/port", "port", "help", "", List.of());
        var calls = new AtomicInteger();
        var session = new AssistedAnalysisSession(source,
                (c, o, n) -> calls.getAndIncrement() == 0
                        ? read(source.revision())
                        : advice(source.revision(), port.id(), "18080", "source/0"),
                new AssistedDecisionBudget());
        assertEquals("18080", session.analyze(List.of(port), Map.of()).suggestions().getFirst().candidate());
        assertFalse(AssistedParameterPolicy
                .accepts(new DeploymentInputField("app/secondary", "entry", "help", "", List.of()), "x; touch /tmp/y"));
        for (String key : List.of("rootBuild", "exposure", "databaseDetails", "secrets", "configuration", "arguments"))
            assertFalse(AssistedParameterPolicy.allows("app/" + key));
        assertFalse(AssistedParameterPolicy
                .accepts(new DeploymentInputField("app/healthMode", "health", "help", "", List.of()), "COMMAND"));
    }

    @Test
    void fabricatedEvidenceAndUnlistedCandidatesNeverEnterThePlan() throws Exception {
        for (String ref : List.of("source/99", "source/0")) {
            var source = browser();
            var session = new AssistedAnalysisSession(source,
                    (c, o, n) -> advice(source.revision(), field.id(), "NODE_SERVICE", ref),
                    new AssistedDecisionBudget());
            assertThrows(SecurityException.class, () -> session.analyze(List.of(field), Map.of()));
        }
        var source = browser();
        var calls = new AtomicInteger();
        var session = new AssistedAnalysisSession(source,
                (c, o, n) -> calls.getAndIncrement() == 0
                        ? read(source.revision())
                        : advice(source.revision(), field.id(), "SHELL", "source/0"),
                new AssistedDecisionBudget());
        assertThrows(SecurityException.class, () -> session.analyze(List.of(field), Map.of()));
    }

    @Test
    void changedSnapshotAndWrongRevisionInvalidateAdvice() throws Exception {
        var source = browser();
        var session = new AssistedAnalysisSession(source, (c, o, n) -> {
            Files.writeString(root.resolve("package.json"), "changed");
            return read(source.revision());
        }, new AssistedDecisionBudget());
        assertThrows(SecurityException.class, () -> session.analyze(List.of(field), Map.of()));
        var fresh = browser();
        var wrong = new AssistedAnalysisSession(fresh, (c, o, n) -> read("wrong"), new AssistedDecisionBudget());
        assertThrows(SecurityException.class, () -> wrong.analyze(List.of(field), Map.of()));
    }

    @Test
    void traversalSecretsAndExecutionToolsAreRejected() throws Exception {
        Files.writeString(root.resolve(".env"), "PASSWORD=secret-value");
        var source = browser();
        for (String path : List.of("../outside", ".env")) {
            var session = new AssistedAnalysisSession(source,
                    (c, o, n) -> new AssistedAnalysisDecision("READ", source.revision(), path, 0, 10, empty),
                    new AssistedDecisionBudget());
            assertThrows(Exception.class, () -> session.analyze(List.of(field), Map.of()));
        }
        for (String tool : List.of("EXECUTE_COMMAND", "PATCH_SOURCE", "SSH", "WRITE"))
            assertThrows(IllegalArgumentException.class,
                    () -> new AssistedAnalysisDecision(tool, source.revision(), "", 0, 1, empty));
    }

    @Test
    void repeatedReadsAndSharedBudgetStopTheLoop() throws Exception {
        var source = browser();
        var session = new AssistedAnalysisSession(source, (c, o, n) -> read(source.revision()),
                new AssistedDecisionBudget());
        assertThrows(IllegalStateException.class, () -> session.analyze(List.of(field), Map.of()));
        var budget = new AssistedDecisionBudget();
        for (int i = 0; i < 29; i++)
            budget.consume();
        var exhausted = new AssistedAnalysisSession(source, (c, o, n) -> read(source.revision()), budget);
        assertThrows(IllegalStateException.class, () -> exhausted.analyze(List.of(field), Map.of()));
        assertEquals(0, budget.remaining());
    }

    @Test
    void directoryNamesAloneCannotSupportAParameter() throws Exception {
        var source = browser();
        var calls = new AtomicInteger();
        var session = new AssistedAnalysisSession(source,
                (c, o, n) -> calls.getAndIncrement() == 0
                        ? new AssistedAnalysisDecision("LIST", source.revision(), "", 0, 10, empty)
                        : advice(source.revision(), field.id(), "NODE_SERVICE", "source/0"),
                new AssistedDecisionBudget());
        assertThrows(SecurityException.class, () -> session.analyze(List.of(field), Map.of()));
    }
}
