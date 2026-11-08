package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.linux.helper.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentNativeHelperPackageImpactTest {
    static final String WORKFLOW = "Deploy the selected fixture source, including required toolchains, build/runtime dependencies and managed helper prerequisites on the selected server";

    /** Real workflow authority and assisted evidence, with only in-memory host grants and refusing capabilities. */
    static final class Assisted implements AutoCloseable {
        final AtomicInteger hosts = new AtomicInteger();
        final Set<UUID> grants = new HashSet<>();
        final List<ActionIntent> grantedIntents = new ArrayList<>();
        final InteractionService host;
        final ModContext context;
        final DeploymentAssistedScope scope;
        final WorkflowGrant permission;

        Assisted(MirroredInstallerTestFixture fixture) throws Exception {
            host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (p, m, a) -> {
                if (m.getName().equals("openReviewedWorkflow")) {
                    assertSame(fixture.task, a[4]);
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of((WorkflowGrant) () -> { }), Optional.empty());
                }
                assertEquals("approveReviewedAction", m.getName());
                assertSame(fixture.task, a[4]);
                var request = ((Optional<?>) a[1]).orElseThrow();
                assertInstanceOf(CommandRequest.class, request);
                var intent = (ActionIntent) a[0];
                assertEquals(RemoteActions.command(fixture.server, intent.revision(), (CommandRequest) request).withSemantics(intent.semantics().orElseThrow()), intent);
                hosts.incrementAndGet();
                grantedIntents.add(intent);
                var grant = new ApprovalGrant(UUID.randomUUID());
                assertTrue(grants.add(grant.id()));
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(grant), Optional.empty());
            });
            context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (p, m, a) -> {
                if (m.getName().equals("interaction")) return host;
                if (m.getName().equals("identity")) return fixture.model.context.identity();
                try { return m.invoke(fixture.context, a); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            scope = new DeploymentAssistedScope(context, fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false);
            permission = DeploymentWorkflowAuthorization.open(context, fixture.task, fixture.server,
                fixture.model.configurationRevision, "AUTOMATIC", false, WORKFLOW);
            scope.workflowOpened(permission);
        }

        public void close() throws ApiException { permission.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAIN", "FILES", "TRACKED"})
    void nativePackageFactsRequireTheWholeInstallerAndActualWorkflow(String kind) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new Assisted(fixture)) {
            for (boolean mirrored : List.of(false, true)) {
                try (var request = fixture.request(fixture.installer(kind, mirrored))) {
                    var intent = fixture.intent(kind, request);
                    var facts = new LinkedHashMap<String, Object>(fixture.facts(kind, request));
                    facts.put("currentCommandEvidence", assisted.scope.facts(intent, request, fixture.task));
                    var json = new ObjectMapper().valueToTree(facts);
                    var impact = json.at("/currentCommandEvidence/helperPackageImpact");
                    assertEquals(fixture.task.id().toString(), impact.path("task").asText());
                    assertEquals(fixture.server.id(), impact.path("server").asText());
                    assertEquals(intent.revision(), impact.path("businessRevision").asText());
                    assertEquals(WORKFLOW, json.at(impact.path("workflowRequestPointer").asText()).asText());
                    assertEquals(fixture.model.configurationRevision, json.at("/currentCommandEvidence/workflowAuthorization/modelRevision").asText());
                    assertEquals(MirroredInstallerTestFixture.sha(new String(request.stdin(), StandardCharsets.UTF_8)), impact.path("stdinSha256").asText());
                    assertEquals(List.of(DeploymentHelperInstallerScript.APT_PACKAGES.split(" ")),
                        new ObjectMapper().convertValue(impact.path("aptRootPackages"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }));
                    assertTrue(impact.path("aptEffects").asText().contains("dependencies may still be installed or upgraded"));
                    assertTrue(impact.path("limits").asText().contains("service effects remain possible"));
                    assertArrayEquals(request.stdin(), DeploymentInstallerEvidence.reconstruct(json.at(impact.path("executableEvidencePointer").asText())).getBytes(StandardCharsets.UTF_8));
                    assertFalse(impact.has("permission"));
                    assertEquals(0, assisted.hosts.get());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "NO_UPGRADE", "WRAPPER", "TASK", "RECEIPT_SERVER", "SERVER", "TARGET", "LAUNCHER", "MODEL_ROUTE", "MISSING_WORKFLOW", "CLOSED"})
    void changedNativeMaterialOrOriginalAuthorityCannotProducePackageFacts(String mutation) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new Assisted(fixture)) {
            String executable = fixture.installer("TRACKED", true);
            if (mutation.equals("SOURCE")) executable += "printf changed\\n\n";
            if (mutation.equals("NO_UPGRADE")) executable = executable.replace("--no-upgrade ", "");
            if (mutation.equals("WRAPPER")) executable = executable.replace("WTL_MIRROR_BUDGET_SECONDS=7200", "WTL_MIRROR_BUDGET_SECONDS=7199");
            if (mutation.equals("TASK")) executable = executable.replace("\nTASK=" + fixture.task.id(), "\nTASK=" + UUID.randomUUID());
            if (mutation.equals("RECEIPT_SERVER")) executable = executable.replace("SERVER_SHA256=" + MirroredInstallerTestFixture.sha(fixture.server.id()), "SERVER_SHA256=" + "0".repeat(64));
            try (var request = new CommandRequest(mutation.equals("LAUNCHER") ? "exec /bin/bash -s" : DeploymentHelperInstallerScript.LAUNCHER,
                    executable.getBytes(StandardCharsets.UTF_8), java.time.Duration.ofHours(2), 131072)) {
                var intent = fixture.intent("TRACKED", request);
                if (mutation.equals("SERVER")) intent = RemoteActions.command(new ServerId("other-server"), intent.revision(), request).withSemantics(intent.semantics().orElseThrow());
                if (mutation.equals("TARGET")) {
                    var targets = new HashSet<>(intent.semantics().orElseThrow().targets());
                    targets.remove(new ActionTarget(fixture.server.id(), "package-manager", "apt-get"));
                    intent = intent.withSemantics(new ActionSemantics("deployment.helper.install.tracked.v1", targets));
                }
                var scope = assisted.scope;
                if (mutation.equals("MODEL_ROUTE")) {
                    scope = new DeploymentAssistedScope(assisted.context, fixture.task, "changed-model-route", "AUTOMATIC", false);
                    scope.workflowOpened(assisted.permission);
                }
                if (mutation.equals("MISSING_WORKFLOW"))
                    scope = new DeploymentAssistedScope(assisted.context, fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false);
                if (mutation.equals("CLOSED")) assisted.permission.close();
                var checkedScope = scope;
                var checkedIntent = intent;
                if (mutation.equals("MODEL_ROUTE")) {
                    var changed = assertThrows(ApiException.class, () -> checkedScope.facts(checkedIntent, request, fixture.task));
                    assertEquals("DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED", changed.problem().eventCode());
                } else assertThrows(IllegalArgumentException.class, () -> checkedScope.facts(checkedIntent, request, fixture.task));
                assertEquals(0, assisted.hosts.get());
                assertTrue(fixture.model.requests.isEmpty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DENY", "APPROVE"})
    void independentDenyNeverDispatchesAndEveryApproveNeedsAFreshExactHostGrant(String decision) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new Assisted(fixture)) {
            var dispatches = new AtomicInteger();
            var transitions = new AtomicInteger();
            var sentGrants = new HashSet<UUID>();
            var session = (RemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RemoteSession.class}, (p, m, a) -> {
                assertEquals("execute", m.getName());
                assertTrue(sentGrants.add(((ApprovalGrant) a[1]).id()));
                assertTrue(assisted.grants.contains(((ApprovalGrant) a[1]).id()));
                dispatches.incrementAndGet();
                return new OperationResult<CommandResult>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            var ai = new DeploymentAiJson(assisted.context, fixture.conversation());
            var helper = new DeploymentManagedHelper(session, assisted.host, fixture.server, fixture.task, fixture.model.configurationRevision,
                gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard.NONE,
                (intent, request, task) -> DeploymentFixedCommandApproval.approve(assisted.context, assisted.host, intent, request, task,
                    fixture.model.configurationRevision, "AUTOMATIC", false, DeploymentCommandReviewModel.submission(ai), assisted.scope, true));
            int count = decision.equals("APPROVE") ? 2 : 1;
            for (int index = 0; index < count; index++) {
                fixture.model.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("native-package-" + index, "submit_result",
                    "{\"decision\":\"" + decision + "\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled independent package verdict\"}"));
                byte[] body = fixture.installer("TRACKED", false).getBytes(StandardCharsets.UTF_8);
                try {
                    if (decision.equals("DENY")) {
                        var denied = assertThrows(ApiException.class, () -> helper.installTracked(body, transitions::incrementAndGet));
                        assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", denied.problem().eventCode());
                    } else assertEquals(TaskResult.SUCCEEDED, helper.installTracked(body, transitions::incrementAndGet).outcome());
                } finally { Arrays.fill(body, (byte) 0); }
            }
            int allowed = decision.equals("APPROVE") ? count : 0;
            assertEquals(allowed, assisted.hosts.get());
            assertEquals(allowed, transitions.get());
            assertEquals(allowed, dispatches.get());
            assertEquals(allowed, sentGrants.size());
            assertEquals(count, fixture.model.requests.size());
            for (var captured : fixture.model.requests)
                assertTrue(MirroredInstallerTestFixture.restoreCarrier(captured.messages()).at("/currentCommandEvidence").has("helperPackageImpact"));
        }
    }
}
