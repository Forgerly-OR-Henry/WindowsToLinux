package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.ApplicationSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentSourceComparisonTest {
    @TempDir
    Path root;

    private static final String REVISION = "a".repeat(64);

    private final AtomicInteger calls = new AtomicInteger(), questions = new AtomicInteger();

    private final List<String> progress = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(strings = {"STATIC", "AI"})
    void ownershipDeclarationMustBeReadBeforeDeterminingEitherResult(String decision) throws Exception {
        Files.writeString(root.resolve(ApplicationSpec.FILE), "version=1\nprojectType=GO_SERVICE\n");
        String declarationRead = read().replace("README.md", ApplicationSpec.FILE);
        var result = compare(List.of(read(), identify("NODE_SERVICE"), conclude(decision), declarationRead,
            conclude(decision)), recognized(), "STATIC");
        assertEquals(decision.equals("STATIC") ? DeploymentProjectKind.GO_SERVICE : DeploymentProjectKind.NODE_SERVICE,
            result.getFirst().types().getFirst());
        assertEquals(5, calls.get());
        assertEquals(0, questions.get());
        assertEquals(ApplicationSpec.FILE, observedFacts.get(2).path("unreviewedDeclarations").get(0).asText());
        assertTrue(observedFacts.get(3).path("observations").toString().contains("CALIBRATION_EVIDENCE_INCOMPLETE"));
        assertEquals(5, observedFacts.get(3).path("remaining").asInt());
        assertTrue(observedFacts.get(4).path("unreviewedDeclarations").isEmpty());
    }

    @Test
    void declarationReadDuringIndependentScanNeedsNoExtraCalibrationRead() throws Exception {
        Files.writeString(root.resolve(ApplicationSpec.FILE), "version=1\nprojectType=GO_SERVICE\n");
        var result = compare(List.of(read(), read().replace("README.md", ApplicationSpec.FILE),
            identify("NODE_SERVICE"), conclude("AI")), recognized(), "STATIC");
        assertEquals(DeploymentProjectKind.NODE_SERVICE, result.getFirst().types().getFirst());
        assertEquals(4, calls.get());
        assertEquals(0, questions.get());
        assertTrue(observedFacts.get(3).path("unreviewedDeclarations").isEmpty());
    }

    @Test
    void unreadOwnershipCannotResetCalibrationBudgetOrSilentlyChooseStatic() throws Exception {
        Files.writeString(root.resolve(ApplicationSpec.FILE), "version=1\nprojectType=GO_SERVICE\n");
        var replies = new ArrayList<String>(List.of(read(), identify("NODE_SERVICE")));
        replies.addAll(Collections.nCopies(6, conclude("STATIC")));
        var result = compare(replies, recognized(), "AI");
        assertEquals(DeploymentProjectKind.NODE_SERVICE, result.getFirst().types().getFirst());
        assertEquals(8, calls.get());
        assertEquals(1, questions.get());
        assertEquals(1, observedFacts.getLast().path("remaining").asInt());
        assertTrue(progress.stream().anyMatch(text -> text.contains("UNRESOLVED budget exhausted")));
    }

    @Test
    void unresolvedDeclarationCanAskUserWithoutPretendingItWasRead() throws Exception {
        Files.writeString(root.resolve(ApplicationSpec.FILE), "version=1\nprojectType=GO_SERVICE\n");
        assertEquals(recognized(), compare(List.of(read(), identify("NODE_SERVICE"), conclude("UNRESOLVED")),
            recognized(), "STATIC"));
        assertEquals(3, calls.get());
        assertEquals(1, questions.get());
        assertEquals(ApplicationSpec.FILE, observedFacts.getLast().path("unreviewedDeclarations").get(0).asText());
    }

    @Test
    void missingReadCanBeCorrectedButCannotBecomeIdentificationEvidence() throws Exception {
        String missingRead = read().replace("README.md", "absent.txt");
        String inventedEvidence = identify("GO_SERVICE").replace("README.md", "absent.txt");
        assertEquals(recognized(), compare(List.of(missingRead, inventedEvidence, read(), identify("GO_SERVICE")), recognized(), "AI"));
        assertEquals(4, calls.get());
        assertEquals(0, questions.get());
        var missing = observedFacts.get(1).path("observations").get(0);
        assertEquals("FILE_NOT_FOUND", missing.path("feedback").asText());
        assertFalse(missing.has("output"));
        assertTrue(observedFacts.get(2).path("observations").toString().contains("UNREAD_EVIDENCE"));
    }

    @Test
    void missingCalibrationReadCanBeCorrectedWithinItsOriginalBudget() throws Exception {
        assertEquals(recognized(), compare(List.of(read(), identify("NODE_SERVICE"),
            read().replace("README.md", "absent.txt"), conclude("STATIC")), recognized(), "AI"));
        assertEquals(4, calls.get());
        assertEquals(0, questions.get());
        assertTrue(observedFacts.get(3).path("observations").toString().contains("FILE_NOT_FOUND"));
        assertEquals(5, observedFacts.get(3).path("remaining").asInt());
    }

    @Test
    void missingQueriesCannotResetTheTwelveTurnBudget() throws Exception {
        var replies = java.util.stream.IntStream.range(0, 12)
            .mapToObj(i -> read().replace("README.md", "absent-" + i + ".txt")).toList();
        assertThrows(java.io.IOException.class, () -> compare(replies, recognized(), "AI"));
        assertEquals(12, calls.get());
        assertEquals(0, questions.get());
        assertTrue(progress.isEmpty());
        assertEquals(1, observedFacts.getLast().path("remaining").asInt());
    }

    @Test
    void agreementAlwaysScansAndDoesNotRequestCalibrationOrUserInput() throws Exception {
        var result = compare(List.of(read(), identify("GO_SERVICE")), recognized(), "AI");
        assertEquals(recognized(), result);
        assertEquals(2, calls.get());
        assertEquals(0, questions.get());
        assertTrue(progress.stream().anyMatch(s -> s.contains("MATCH")));
    }

    @Test
    void nativeIdentificationNeedsNoLegacyBodyEnvelope() throws Exception {
        String invalid = "{\"components\":[{\"id\":\"main\",\"root\":\".\",\"type\":\"GO_SERVICE\",\"evidence\":[\"README.md\"]}]}";
        assertEquals(recognized(), compare(List.of(read(), invalid, identify("GO_SERVICE")), recognized(), "AI"));
        assertEquals(2, calls.get());
        assertEquals(0, questions.get());
    }

    @Test
    void mismatchCalibratesAndCanResolveToStaticWithoutAskingUser() throws Exception {
        var result = compare(List.of(read(), identify("NODE_SERVICE"), conclude("STATIC")), recognized(), "AI");
        assertEquals(recognized(), result);
        assertEquals(3, calls.get());
        assertEquals(0, questions.get());
        assertTrue(progress.stream().anyMatch(s -> s.contains("CALIBRATION STATIC")));
    }

    @Test
    void mismatchCalibratesAndCanResolveToAiWithoutAskingUser() throws Exception {
        var result = compare(List.of(read(), identify("NODE_SERVICE"), conclude("AI")), recognized(), "STATIC");
        assertEquals(DeploymentProjectKind.NODE_SERVICE, result.getFirst().types().getFirst());
        assertEquals(0, questions.get());
    }

    @Test
    void OnlyUnresolvedCalibrationRequestsUserSelection() throws Exception {
        var result = compare(List.of(read(), identify("NODE_SERVICE"), conclude("UNRESOLVED")), recognized(), "STATIC");
        assertEquals(recognized(), result);
        assertEquals(3, calls.get());
        assertEquals(1, questions.get());
    }

    @Test
    void unreadIdentificationMustBeReadAndResubmittedBeforeComparison() throws Exception {
        assertEquals(recognized(), compare(List.of(identify("GO_SERVICE"), read(), identify("GO_SERVICE")), recognized(), "STATIC"));
        assertEquals(3, calls.get());
        assertEquals(0, questions.get());
        var discarded = observedFacts.get(1).path("observations").get(0);
        assertEquals("UNREAD_EVIDENCE", discarded.path("feedback").asText());
        assertTrue(discarded.path("readPaths").isEmpty());
        assertFalse(discarded.has("output"));
    }

    @Test
    void persistentUnreadIdentificationNeverStartsCalibrationOrResetsTheBudget() throws Exception {
        assertThrows(java.io.IOException.class, () -> compare(Collections.nCopies(12, identify("GO_SERVICE")), recognized(), "STATIC"));
        assertEquals(12, calls.get());
        assertEquals(0, questions.get());
        assertTrue(progress.isEmpty());
    }

    @Test
    void modelCanCorrectAnOversizedReadWithinTheExistingScanBudget() throws Exception {
        assertEquals(recognized(), compare(List.of(invalidRead(), read(), identify("GO_SERVICE")), recognized(), "AI"));
        assertEquals(3, calls.get());
        assertEquals(0, questions.get());
        assertTrue(observedFacts.get(1).path("observations").toString().contains("INVALID_PAGE"));
        assertFalse(observedFacts.get(1).path("observations").get(0).has("output"));
    }

    @Test
    void discardedPageDoesNotAdmitEvidenceAndPersistentErrorsDoNotResetTheBudget() throws Exception {
        var unread = new ArrayList<String>(List.of(invalidRead()));
        unread.addAll(Collections.nCopies(11, identify("GO_SERVICE")));
        assertThrows(java.io.IOException.class, () -> compare(unread, recognized(), "AI"));
        assertEquals(12, calls.get());
        assertTrue(observedFacts.get(11).path("observations").toString().contains("UNREAD_EVIDENCE"));
        calls.set(0);
        observedFacts.clear();
        assertThrows(java.io.IOException.class,
            () -> compare(Collections.nCopies(12, invalidRead()), recognized(), "AI"));
        assertEquals(12, calls.get());
        assertEquals(0, questions.get());
    }

    @Test
    void calibrationCanCorrectAnInvalidPageWithoutAskingTheUser() throws Exception {
        assertEquals(recognized(), compare(List.of(read(), identify("NODE_SERVICE"), invalidRead(), conclude("STATIC")), recognized(), "AI"));
        assertEquals(4, calls.get());
        assertEquals(0, questions.get());
        assertTrue(observedFacts.get(3).path("observations").toString().contains("INVALID_PAGE"));
    }

    @Test
    void calibrationMustReadAndResubmitUnreadEvidenceWithinItsOriginalBudget() throws Exception {
        Files.writeString(root.resolve("EXTRA.md"), "Go component declaration.\n");
        String conclusion = conclude("STATIC").replace("README.md", "EXTRA.md");
        String additionalRead = read().replace("README.md", "EXTRA.md");
        assertEquals(recognized(), compare(List.of(read(), identify("NODE_SERVICE"), conclusion, additionalRead, conclusion), recognized(), "AI"));
        assertEquals(5, calls.get());
        assertEquals(0, questions.get());
        assertTrue(observedFacts.get(3).path("observations").toString().contains("UNREAD_EVIDENCE"));
    }

    @Test
    void persistentUnreadCalibrationOnlyAsksUserAfterItsOriginalSixTurns() throws Exception {
        var replies = new ArrayList<String>(List.of(read(), identify("NODE_SERVICE")));
        replies.addAll(Collections.nCopies(6, conclude("AI").replace("README.md", "EXTRA.md")));
        assertEquals(recognized(), compare(replies, recognized(), "STATIC"));
        assertEquals(8, calls.get());
        assertEquals(1, questions.get());
        assertTrue(progress.stream().anyMatch(text -> text.contains("UNRESOLVED budget exhausted")));
    }

    @Test
    void missingPageKeysRequireAnActualCorrectedResponseBeforeReading() throws Exception {
        String invalid = "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\"}}";
        assertEquals(recognized(), compare(List.of(invalid, read(), identify("GO_SERVICE")), recognized(), "AI"));
        assertEquals(3, calls.get());
        assertEquals(0, questions.get());
        var discarded = observedFacts.get(1).path("observations").get(0);
        assertEquals("INVALID_ARGUMENTS", discarded.path("feedback").textValue());
        assertFalse(discarded.has("output"));
        assertEquals(3, discarded.path("requiredKeys").size());
    }

    @Test
    void extraCalibrationKeysAreRejectedAndPersistentErrorsRemainBounded() throws Exception {
        String invalid = "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":10,\"execute\":true}}";
        var replies = new ArrayList<String>(List.of(read(), identify("NODE_SERVICE")));
        replies.addAll(Collections.nCopies(6, invalid));
        assertEquals(recognized(), compare(replies, recognized(), "STATIC"));
        assertEquals(8, calls.get());
        assertEquals(1, questions.get(), "Only exhausted calibration requests user choice");
        assertTrue(observedFacts.get(7).path("observations").toString().contains("INVALID_ARGUMENTS"));
    }

    private final List<com.fasterxml.jackson.databind.JsonNode> observedFacts = new ArrayList<>();

    private static String invalidRead() {
        return "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":200}}";
    }

    private List<DiscoveredProjectPart> compare(List<String> replies, List<DiscoveredProjectPart> recognized,
                                                String answer) throws Exception {
        Files.writeString(root.resolve("README.md"), "Go service; build with go build.\n");
        var ai = (AiService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AiService.class}, (p, m, a) -> {
            if (m.getName().equals("configurationRevision")) return REVISION;
            assertEquals("call", m.getName());
            var request = (AiRequest) a[0];
            assertFalse(request.jsonObjectResponse());
            assertTrue(request.tools().stream().anyMatch(tool->tool.name().equals("read")));
            var facts = new ObjectMapper().readTree(request.messages().getLast().text());
            observedFacts.add(facts);
            int call = calls.getAndIncrement();
            if (facts.has("projectTypes")) {
                assertFalse(facts.has("static"));
                assertFalse(facts.has("independentAi"));
            } else {
                assertTrue(facts.has("static"));
                assertTrue(facts.has("independentAi"));
                assertTrue(facts.path("static").isArray());
                assertEquals(".", facts.path("static").get(0).path("root").asText());
                assertEquals(recognized.getFirst().types().getFirst().name(), facts.path("static").get(0).path("projectTypes").get(0).asText());
            }
            var node=new ObjectMapper().readTree(replies.get(call));
            String name=node.has("tool")?node.get("tool").asText():facts.has("projectTypes")?"identify":"conclude";
            String args=(node.has("arguments")?node.get("arguments"):node).toString();
            return success(new AiResponse("", "controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                List.of(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("submission_"+call,name,args)),Optional.empty()));
        });
        var interaction = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (p, m, a) -> {
            assertEquals("requestText", m.getName());
            questions.incrementAndGet();
            return success(answer);
        });
        var ui = (gold.debug.windowstolinux.appapi.ui.UiService) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class}, (p, m, a) -> {
                assertEquals("environment", m.getName());
                return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH, "LIGHT", false);
            });
        var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (p, m, a) -> switch (m.getName()) {
            case "ai" -> ai;
            case "interaction" -> interaction;
            case "ui" -> ui;
            case "logs" -> (gold.debug.windowstolinux.appapi.task.log.LogService) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.log.LogService.class},(peer,method,args)->method.getName().equals("register")?new gold.debug.windowstolinux.appapi.task.log.LogTarget(java.util.UUID.randomUUID()):null);
            default -> throw new AssertionError(m.getName());
        });
        var task = (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class}, (p, m, a) -> switch (m.getName()) {
            case "cancelToken" -> (CancelToken) () -> false;
            case "id" -> java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
            case "progress" -> {
                progress.add((String) a[0]);
                yield null;
            }
            default -> throw new AssertionError(m.getName());
        });
        return DeploymentSourceComparison.compare(context, new ServerId("server"), new DeploymentSourceBrowser(root, REVISION), recognized, task);
    }

    private static List<DiscoveredProjectPart> recognized() {
        return List.of(new DiscoveredProjectPart("app", Path.of(""), List.of(DeploymentProjectKind.GO_SERVICE)));
    }

    private static String read() {
        return "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":10}}";
    }

    private static String identify(String type) {
        return "{\"tool\":\"identify\",\"arguments\":{\"components\":[{\"id\":\"main\",\"root\":\".\",\"type\":\"" + type + "\",\"evidence\":[\"README.md\"]}]}}";
    }

    private static String conclude(String decision) {
        return "{\"tool\":\"conclude\",\"arguments\":{\"decision\":\"" + decision + "\",\"reason\":\"Observed project metadata\",\"evidence\":[\"README.md\"]}}";
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
