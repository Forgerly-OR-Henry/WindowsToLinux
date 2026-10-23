package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.helper.*;
import gold.debug.windowstolinux.mod.deployment.service.core.setup.DeploymentProtocolBootstrapActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentAssistedHelperStatusEvidenceRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void bindsTheOriginalDispatchedJournalWithoutObservingSuccessOrGrantingAuthority() throws Exception {
        var h = new Harness();
        var before = h.journal().snapshot();
        try (var request = h.request("VALID")) {
            var intent = h.intent(request, "VALID");
            var facts = JSON.valueToTree(h.scope.facts(intent, request, h.fixture.task))
                .path("originalHelperInstallationStatus");
            assertEquals(h.fixture.task.id().toString(), facts.path("task").asText());
            assertEquals(h.operation.toString(), facts.path("operation").asText());
            assertEquals(h.server.id(), facts.path("server").asText());
            assertEquals("DISPATCHED", facts.path("phase").asText());
            assertEquals(h.layout.digest(), facts.path("remoteLayoutSha256").asText());
            assertEquals(DeploymentManagedHelperBundle.helperSha256(h.layout), facts.path("helperDigest").asText());
            assertEquals(h.layout.helperInstallOperationsRoot() + "/" + h.operation + ".receipt", facts.path("receiptPath").asText());
            assertEquals(DeploymentHelperInstallerScript.receipt(h.fixture.task.id(), h.operation,
                h.serverDigest, h.bodyDigest, "SUCCEEDED", h.layout), facts.path("expectedReceipt").asText());
            assertTrue(facts.path("meaning").asText().contains("not an observed receipt or installation proof"));
            assertTrue(facts.at("/clientVerification/afterReceiptMatch").asText().contains("separately reviewed helper.probePlatform"));
            assertTrue(facts.at("/clientVerification/statusResultRequirements").toString().contains("stdout.equals(expectedReceipt)"));
            assertFalse(facts.has("observedReceipt"));
            assertFalse(facts.has("installationSucceeded"));
            assertFalse(facts.has("commandGrant"));
            assertArrayEquals(DeploymentHelperInstallerScript.renderStatus(h.operation, h.layout).getBytes(StandardCharsets.UTF_8), request.stdin());
        }
        assertEquals(before, h.journal().snapshot());
        assertEquals(0, h.hostCalls.get());
        assertTrue(h.fixture.requests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TASK_PAYLOAD", "TASK_OBJECT", "OPERATION", "SERVER", "BODY_DIGEST", "REQUEST_DIGEST",
        "HELPER_DIGEST", "SERVER_DIGEST", "PHASE_INTENT", "PHASE_SUCCEEDED", "PHASE_FAILED", "MISSING_JOURNAL",
        "LAYOUT", "STDIN", "LAUNCHER", "TIMEOUT", "OUTPUT_LIMIT", "TARGET_MISSING", "TARGET_EXTRA", "TARGET_SERVER",
        "RECEIPT_TARGET", "EXACT_ACTION"})
    void rejectsChangedOriginalBindingBeforeAnyModelOrHostCall(String mutation) throws Exception {
        var h = new Harness();
        h.mutateJournal(mutation);
        try (var request = h.request(mutation)) {
            var intent = h.intent(request, mutation);
            if (mutation.equals("TASK_OBJECT")) {
                TaskContext other = proxy(TaskContext.class, (p, m, a) -> {
                    if (m.getName().equals("id")) return h.fixture.task.id();
                    throw new AssertionError(m.getName());
                });
                assertThrows(IllegalArgumentException.class, () -> h.scope.facts(intent, request, other));
            } else {
                assertThrows(ApiException.class, () -> h.approve(intent, request));
            }
        }
        assertTrue(h.fixture.requests.isEmpty());
        assertEquals(0, h.hostCalls.get());
    }

    @Test
    void independentDenialIsFinalAndRetainsTheOriginalUnknownJournal() throws Exception {
        var h = new Harness();
        var before = h.journal().snapshot();
        h.fixture.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("status-denied", "submit_result",
            "{\"decision\":\"DENY\",\"reason\":\"Original scope still refuses receipt reconciliation\",\"requiresUserConfirmation\":false}"));
        try (var request = h.request("VALID")) {
            var failure = assertThrows(ApiException.class, () -> h.approve(h.intent(request, "VALID"), request));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", failure.problem().eventCode());
            assertEquals("Original scope still refuses receipt reconciliation", failure.problem().context().get("reviewReason"));
        }
        assertEquals(1, h.fixture.requests.size());
        var reviewed = JSON.readTree(h.fixture.requests.getFirst().messages().getLast().text());
        assertEquals(h.operation.toString(), reviewed.at("/currentCommandEvidence/originalHelperInstallationStatus/operation").asText());
        assertEquals(before, h.journal().snapshot());
        assertFalse(h.journal().snapshot().released());
        assertEquals(0, h.hostCalls.get());
    }

    private static final class Harness {
        final NativeAiFixture fixture = new NativeAiFixture();
        final AtomicInteger hostCalls = new AtomicInteger();
        final Map<String, Checkpoint> checkpoints = new HashMap<>();
        final ServerId server = new ServerId("fixture-status-server");
        final UUID operation = UUID.randomUUID();
        final DeploymentRemoteLayout layout = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            "/opt/status-fixture/bin", "/srv/status-fixture/apps", "/var/lib/status-fixture", "/etc/status-fixture", "/srv/status-fixture/data"));
        final String serverDigest = DeploymentModelCommandIdentity.digest(server.id());
        final String bodyDigest = DeploymentModelCommandIdentity.digest(DeploymentHelperInstallerScript.render(layout));
        final InteractionService host = proxy(InteractionService.class, (p, m, a) -> {
            hostCalls.incrementAndGet();
            throw new AssertionError("No host permission may be requested: " + m.getName());
        });
        final TaskService tasks = proxy(TaskService.class, (p, m, a) -> {
            assertSame(fixture.task, a[0], "Checkpoint capability must preserve the authentic task object");
            String key = (String) a[1];
            if (m.getName().equals("findCheckpoint")) return Optional.ofNullable(checkpoints.get(key));
            if (m.getName().equals("beginCheckpoint")) {
                var value = new Checkpoint((String) a[2]);
                assertNull(checkpoints.putIfAbsent(key, value), "Original checkpoint cannot be replaced");
                return value;
            }
            throw new AssertionError("No other task capability admitted: " + m.getName());
        });
        final UiService ui = proxy(UiService.class, (p, m, a) -> {
            assertEquals("environment", m.getName());
            return new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false);
        });
        final ModContext context = proxy(ModContext.class, (p, m, a) -> switch (m.getName()) {
            case "tasks" -> tasks;
            case "interaction" -> host;
            case "ui" -> ui;
            default -> invoke(fixture.context, m, a);
        });
        final DeploymentAssistedScope scope = new DeploymentAssistedScope(context, fixture.task,
            fixture.configurationRevision, "AUTOMATIC", true);

        Harness() throws Exception {
            DeploymentConfigCapture.remoteLayout(tasks, fixture.task, layout);
            var log = DeploymentProtocolBootstrapActionLog.begin(tasks, fixture.task, server, operation,
                serverDigest, bodyDigest, DeploymentModelCommandIdentity.digest(DeploymentHelperInstallerScript.renderTracked(
                    fixture.task.id(), operation, serverDigest, bodyDigest, layout)), layout);
            log.dispatched();
        }

        Checkpoint journal() {
            return checkpoints.get(DeploymentProtocolBootstrapActionLog.KEY);
        }

        void mutateJournal(String mutation) throws Exception {
            if (mutation.equals("MISSING_JOURNAL")) {
                checkpoints.remove(DeploymentProtocolBootstrapActionLog.KEY);
                return;
            }
            var node = (ObjectNode) JSON.readTree(journal().snapshot.payload());
            switch (mutation) {
                case "TASK_PAYLOAD" -> node.put("taskId", UUID.randomUUID().toString());
                case "BODY_DIGEST" -> node.put("bodyDigest", "0".repeat(64));
                case "REQUEST_DIGEST" -> node.put("requestDigest", "0".repeat(64));
                case "HELPER_DIGEST" -> node.put("helperDigest", "0".repeat(64));
                case "SERVER_DIGEST" -> node.put("serverDigest", "0".repeat(64));
                case "PHASE_INTENT", "PHASE_SUCCEEDED", "PHASE_FAILED" -> node.put("phase", mutation.substring(6));
                case "LAYOUT" -> {
                    checkpoints.remove("deployment.config.remote-layout.v1");
                    DeploymentConfigCapture.remoteLayout(tasks, fixture.task, DeploymentRemoteLayout.original());
                }
                default -> { }
            }
            journal().snapshot = new TaskCheckpoint.Snapshot(1, node.toString(), false);
        }

        CommandRequest request(String mutation) {
            String input = DeploymentHelperInstallerScript.renderStatus(mutation.equals("OPERATION") ? UUID.randomUUID() : operation, layout);
            return new CommandRequest(mutation.equals("LAUNCHER") ? "bash -s" : "exec /bin/bash --noprofile --norc -s",
                (input + (mutation.equals("STDIN") ? "\n" : "")).getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(mutation.equals("TIMEOUT") ? 31 : 30), mutation.equals("OUTPUT_LIMIT") ? 4096 : 2048);
        }

        ActionIntent intent(CommandRequest request, String mutation) {
            var targetServer = mutation.equals("SERVER") ? new ServerId("other-server") : server;
            var targets = new HashSet<>(DeploymentManagedHelper.installationStatusTargets(targetServer, layout, operation));
            if (mutation.equals("TARGET_MISSING")) targets.removeIf(target -> target.kind().equals("directory"));
            if (mutation.equals("TARGET_EXTRA")) targets.add(new ActionTarget(server.id(), "file", "/etc/extra"));
            if (mutation.equals("TARGET_SERVER")) targets.add(new ActionTarget("other-server", "file", "/etc/extra"));
            if (mutation.equals("RECEIPT_TARGET")) {
                targets.removeIf(target -> target.kind().equals("file"));
                targets.add(new ActionTarget(server.id(), "file", layout.helperInstallOperationsRoot() + "/" + UUID.randomUUID() + ".receipt"));
            }
            var intent = RemoteActions.command(targetServer, "deployment-helper-bootstrap-v1", request)
                .withSemantics(new ActionSemantics("deployment.helper.install.status.v1", targets));
            return mutation.equals("EXACT_ACTION") ? new ActionIntent(intent.type(), intent.serverIds(), intent.target(), intent.revision(),
                intent.exactAction() + "changed", intent.effectSummary()).withSemantics(intent.semantics().orElseThrow()) : intent;
        }

        OperationResult<ApprovalGrant> approve(ActionIntent intent, CommandRequest request) throws ApiException {
            return DeploymentFixedCommandApproval.approve(scope, host, intent, request, fixture.task,
                fixture.configurationRevision, "AUTOMATIC", true);
        }
    }

    /** Strict original-row CAS fixture; it supplies no interaction or remote execution authority. */
    private static final class Checkpoint implements TaskCheckpoint {
        Snapshot snapshot;

        Checkpoint(String payload) { snapshot = new Snapshot(0, payload, false); }

        public Snapshot snapshot() { return snapshot; }

        public Snapshot advance(Snapshot expected, String next) {
            assertEquals(snapshot, expected);
            assertFalse(snapshot.released());
            return snapshot = new Snapshot(snapshot.revision() + 1, next, false);
        }

        public void close() { snapshot = new Snapshot(snapshot.revision() + 1, snapshot.payload(), true); }
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
