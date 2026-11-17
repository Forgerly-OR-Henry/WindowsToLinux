package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.command.SshCommandFrameProtocol;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.linux.helper.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;

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
        for (boolean mirrored : List.of(false, true)) {
            try (var fixture = new MirroredInstallerTestFixture(); var assisted = new Assisted(fixture)) {
                if (!mirrored) DeploymentMirrorTasks.forget(fixture.task);
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
                    assertTrue(impact.path("sudoPackagePurpose").asText().contains("sudo-prefixed managed application usage"));
                    assertEquals(mirrored, impact.has("mirrorShellFunctions"));
                    assertTrue(impact.path("limits").asText().contains("service effects remain possible"));
                    boolean scoped = kind.equals("TRACKED") && mirrored;
                    assertEquals(scoped, impact.has("platformPackageScope"));
                    assertEquals(!scoped, impact.has("dnfEffects"));
                    assertEquals(scoped ? List.of("apt-get:configured-dependency-closure:managed-helper")
                        : List.of("apt-get:configured-dependency-closure:managed-helper", "dnf:configured-dependency-closure:managed-helper"),
                        new ObjectMapper().convertValue(impact.at("/nativeTransactions/targets"),
                            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }));
                    assertTrue(impact.at("/nativeTransactions/effects").asText().contains("maintainer scripts"));
                    assertArrayEquals(request.stdin(), DeploymentInstallerEvidence.reconstruct(json.at(impact.path("executableEvidencePointer").asText())).getBytes(StandardCharsets.UTF_8));
                    assertFalse(impact.has("permission"));
                    assertEquals(0, assisted.hosts.get());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "NO_UPGRADE", "PLATFORM_GUARD", "WRAPPER", "MISSING_MIRROR", "TASK", "RECEIPT_SERVER", "SERVER", "TARGET", "TRANSACTION_TARGET", "LAUNCHER", "MODEL_ROUTE", "MISSING_WORKFLOW", "CLOSED"})
    void changedNativeMaterialOrOriginalAuthorityCannotProducePackageFacts(String mutation) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new Assisted(fixture)) {
            String executable = fixture.installer("TRACKED", true);
            if (mutation.equals("SOURCE")) executable += "printf changed\\n\n";
            if (mutation.equals("NO_UPGRADE")) executable = executable.replace("--no-upgrade ", "");
            if (mutation.equals("PLATFORM_GUARD")) executable = executable.replace("= 'APT_UBUNTU' ]", "= 'DNF_FEDORA' ]");
            if (mutation.equals("WRAPPER")) executable = executable.replace("WTL_MIRROR_BUDGET_SECONDS=7200", "WTL_MIRROR_BUDGET_SECONDS=7199");
            if (mutation.equals("MISSING_MIRROR")) executable = fixture.installer("TRACKED", false);
            if (mutation.equals("TASK")) executable = executable.replace("\nTASK=" + fixture.task.id(), "\nTASK=" + UUID.randomUUID());
            if (mutation.equals("RECEIPT_SERVER")) executable = executable.replace("SERVER_SHA256=" + MirroredInstallerTestFixture.sha(fixture.server.id()), "SERVER_SHA256=" + "0".repeat(64));
            try (var request = new CommandRequest(mutation.equals("LAUNCHER") ? DeploymentHelperInstallerScript.STATUS_LAUNCHER : DeploymentHelperInstallerScript.LAUNCHER,
                    executable.getBytes(StandardCharsets.UTF_8), java.time.Duration.ofHours(2), 131072)) {
                var intent = fixture.intent("TRACKED", request);
                if (mutation.equals("SERVER")) intent = RemoteActions.command(new ServerId("other-server"), intent.revision(), request).withSemantics(intent.semantics().orElseThrow());
                if (mutation.equals("TARGET") || mutation.equals("TRANSACTION_TARGET")) {
                    var targets = new HashSet<>(intent.semantics().orElseThrow().targets());
                    var removed = mutation.equals("TARGET") ? new ActionTarget(fixture.server.id(), "package-manager", "apt-get")
                        : new ActionTarget(fixture.server.id(), "system-package-transaction", "apt-get:configured-dependency-closure:managed-helper");
                    assertTrue(targets.remove(removed));
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
    @EnumSource(value = DownloadPoint.class, names = {"APT_.*", "DNF_.*"}, mode = EnumSource.Mode.MATCH_ANY)
    void frozenRepositoryPointBindsOnlyItsManagerAndTheReceiptBody(DownloadPoint point) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(point); var assisted = new Assisted(fixture);
             var request = fixture.request(fixture.installer("TRACKED", true))) {
            String executable = new String(request.stdin(), StandardCharsets.UTF_8);
            String original = DeploymentMirrorTasks.buildInput(executable).orElseThrow().original();
            boolean apt = point.name().startsWith("APT_");
            assertTrue(original.contains("[ \"$(wtl_system_point)\" = '" + point.name() + "' ]"));
            assertTrue(original.indexOf("PREPARE_REJECT=platform-package-scope") < original.indexOf("prepare_stage=installer-receipt"));
            assertEquals(apt, original.contains("wtl_apt -o DPkg::Lock::Timeout=300 install"));
            assertEquals(!apt, original.contains("wtl_dnf install -y"));
            var intent = fixture.intent("TRACKED", request);
            var facts = new ObjectMapper().valueToTree(fixture.facts("TRACKED", request));
            var impact = new ObjectMapper().valueToTree(assisted.scope.facts(intent, request, fixture.task)).path("helperPackageImpact");
            assertEquals(point.name(), impact.at("/platformPackageScope/frozenRepositoryPoint").asText());
            assertFalse(facts.at(impact.at("/platformPackageScope/policyPointer").asText()).isEmpty());
            assertEquals(apt, impact.has("aptEffects"));
            if (!apt) assertTrue(impact.path("dnfEffects").asText().contains("may upgrade or obsolete packages"));
            String bodyDigest = DeploymentHelperInstallerScript.bodyDigest(fixture.layout, Optional.of(point));
            String serverDigest = MirroredInstallerTestFixture.sha(fixture.server.id());
            String requestDigest = MirroredInstallerTestFixture.sha(executable);
            assertEquals(bodyDigest, facts.at("/completeExecutableInput/digestScopes/bodyDigest/value").asText());
            assertTrue(DeploymentHelperInstallerScript.matchesTrackedRequest(fixture.task, fixture.operation,
                serverDigest, bodyDigest, requestDigest, fixture.layout, false));
            String priorBody = DeploymentHelperInstallerScript.bodyDigest(fixture.layout, Optional.empty());
            String priorRequest = DeploymentMirrorTasks.installerRequestDigest(DeploymentHelperInstallerScript.renderTracked(
                fixture.task.id(), fixture.operation, serverDigest, priorBody, fixture.layout), fixture.task);
            assertTrue(DeploymentHelperInstallerScript.matchesTrackedRequest(fixture.task, fixture.operation,
                serverDigest, priorBody, priorRequest, fixture.layout, true));
            assertFalse(DeploymentHelperInstallerScript.matchesTrackedRequest(fixture.task, fixture.operation,
                serverDigest, priorBody, priorRequest, fixture.layout, false));
            assertFalse(DeploymentHelperInstallerScript.matchesTrackedRequest(fixture.task, fixture.operation,
                serverDigest, priorBody, requestDigest, fixture.layout, true));
        }
    }

    @Test
    void readyObservationAndPackageFreeInstallUseSeparateExactReviewsAndGrants() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new Assisted(fixture)) {
            var sent = new ArrayList<String>();
            var launchers = new ArrayList<String>();
            var usedGrants = new HashSet<UUID>();
            var session = (RemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RemoteSession.class}, (p, m, a) -> {
                assertEquals("execute", m.getName());
                UUID grant = ((ApprovalGrant) a[1]).id();
                assertTrue(assisted.grants.contains(grant));
                assertTrue(usedGrants.add(grant));
                var request = (CommandRequest) a[0];
                launchers.add(request.script());
                sent.add(new String(request.stdin(), StandardCharsets.UTF_8));
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0),
                    sent.size() == 1 ? "APT_PREREQUISITES=READY\n" : "", "", false)), Optional.empty());
            });
            var ai = new DeploymentAiJson(assisted.context, fixture.conversation());
            var helper = new DeploymentManagedHelper(session, assisted.host, fixture.server, fixture.task, fixture.model.configurationRevision,
                gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard.NONE,
                (intent, request, task) -> DeploymentFixedCommandApproval.approve(assisted.context, assisted.host, intent, request, task,
                    fixture.model.configurationRevision, "AUTOMATIC", false, DeploymentCommandReviewModel.submission(ai), assisted.scope, true));
            var point = DownloadPoint.APT_UBUNTU;
            var serverDigest = MirroredInstallerTestFixture.sha(fixture.server.id());
            String bodyDigest = DeploymentHelperInstallerScript.readyBodyDigest(fixture.layout, point);
            String ready = DeploymentHelperInstallerScript.renderTrackedReady(fixture.task.id(), fixture.operation,
                serverDigest, bodyDigest, fixture.layout, point);
            for (String id : List.of("ready-observation", "ready-installer"))
                fixture.model.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall(id, "submit_result",
                    "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled independent verdict\"}"));
            assertEquals(DeploymentManagedHelper.PrerequisiteState.READY, helper.observeAptPrerequisites(point).value().orElseThrow());
            var transitions = new AtomicInteger();
            assertEquals(TaskResult.SUCCEEDED, helper.installTracked(ready.getBytes(StandardCharsets.UTF_8),
                transitions::incrementAndGet).outcome());
            assertEquals(1, transitions.get());
            assertEquals(List.of(DeploymentHelperInstallerScript.renderPrerequisiteObservation(point), ready), sent);
            var protectedLauncher = DeploymentHelperInstallerScript.PERL_STARTUP_LAUNCHER;
            assertEquals(List.of(protectedLauncher, protectedLauncher), launchers);
            assertEquals(2, usedGrants.size());
            var observeIntent = assisted.grantedIntents.getFirst();
            assertEquals(DeploymentManagedHelper.PREREQUISITE_OBSERVATION, observeIntent.semantics().orElseThrow().operation());
            var metadataExecutables = Set.of("/usr/bin/perl", "/usr/bin/stat", "/usr/bin/readlink");
            for (String executable : metadataExecutables) {
                assertTrue(observeIntent.semantics().orElseThrow().targets().contains(new ActionTarget(
                    fixture.server.id(), "file", executable)));
            }
            var observationFacts = new ObjectMapper().readTree(fixture.model.requests.getFirst().messages().getLast().text());
            assertEquals("/usr/bin/perl", observationFacts.at("/helperPrerequisiteObservation/metadataToolGuard/bootstrapInterpreter").asText());
            assertEquals("perl-base", observationFacts.at("/helperPrerequisiteObservation/metadataToolGuard/bootstrapPackage").asText());
            assertEquals(SshCommandFrameProtocol.HELPER_PERL_STARTUP_HEADER.stripTrailing(),
                observationFacts.at("/helperPrerequisiteObservation/metadataToolGuard/startupAdmission/marker").asText());
            var prerequisiteFacts = observationFacts.path("helperPrerequisiteObservation");
            assertEquals("BEFORE_REMOTE_EXECUTION", prerequisiteFacts.path("reviewPhase").asText());
            assertFalse(prerequisiteFacts.has("acceptedResult"));
            var contract = prerequisiteFacts.path("successContract");
            assertEquals("EXACTLY_ONE_COMPLETE_CASE", contract.path("stdoutMatch").asText());
            assertEquals(0, contract.path("requiredExitCode").asInt(-1));
            assertEquals("", contract.path("requiredStderr").asText(null));
            assertTrue(contract.path("requiresUntruncatedStreams").asBoolean());
            var cases = contract.path("allowedCompleteStdoutByState");
            assertTrue(cases.isObject());
            assertEquals(2, cases.size());
            assertEquals("APT_PREREQUISITES=READY\n", cases.path("READY").asText());
            assertEquals("APT_PREREQUISITES=MISSING\n", cases.path("MISSING").asText());
            assertTrue(fixture.model.requests.getFirst().messages().stream()
                .anyMatch(message -> message.text().contains("The pending observation has not run.")));
            assertEquals(MirroredInstallerTestFixture.sha(sent.getFirst()),
                observationFacts.at("/helperPrerequisiteObservation/stdinSha256").asText());
            var installIntent = assisted.grantedIntents.getLast();
            assertFalse(installIntent.semantics().orElseThrow().targets().stream().anyMatch(target ->
                Set.of("package-manager", "package-set", "system-package-index", "system-package-transaction").contains(target.kind())));
            try (var request = new CommandRequest(protectedLauncher, ready.getBytes(StandardCharsets.UTF_8),
                    java.time.Duration.ofHours(2), 131072)) {
                var json = new ObjectMapper().valueToTree(assisted.scope.facts(installIntent, request, fixture.task));
                assertEquals("VERIFIED_EXISTING_APT_PREREQUISITES", json.at("/helperPackageImpact/packageMode").asText());
                assertEquals("/usr/bin/perl", json.at("/helperPackageImpact/metadataToolGuard/bootstrapInterpreter").asText());
                assertTrue(json.at("/helperPackageImpact/nativeTransactions/targets").isEmpty());
            }
            for (String launcher : List.of(DeploymentHelperInstallerScript.LAUNCHER, protectedLauncher + "\n")) {
                try (var request = new CommandRequest(launcher, sent.getFirst().getBytes(StandardCharsets.UTF_8),
                        java.time.Duration.ofSeconds(60), 1024)) {
                    var intent = RemoteActions.command(fixture.server, observeIntent.revision(), request)
                        .withSemantics(observeIntent.semantics().orElseThrow());
                    assertThrows(IllegalArgumentException.class, () ->
                        DeploymentManagedHelper.prerequisiteObservationEvidence(intent, request));
                }
                try (var request = new CommandRequest(launcher, ready.getBytes(StandardCharsets.UTF_8),
                        java.time.Duration.ofHours(2), 131072)) {
                    var intent = RemoteActions.command(fixture.server, installIntent.revision(), request)
                        .withSemantics(installIntent.semantics().orElseThrow());
                    assertThrows(IllegalArgumentException.class, () ->
                        DeploymentCommandEvidence.facts(intent, request, fixture.layout));
                    assertThrows(IllegalArgumentException.class, () -> assisted.scope.facts(intent, request, fixture.task));
                }
            }
            assertTrue(DeploymentHelperInstallerScript.matchesTrackedRequest(fixture.task, fixture.operation, serverDigest,
                bodyDigest, DeploymentHelperInstallerScript.readyRequestDigest(ready), fixture.layout, true));
            assertFalse(DeploymentHelperInstallerScript.matchesTrackedRequest(fixture.task, fixture.operation, serverDigest,
                bodyDigest, DeploymentMirrorTasks.installerRequestDigest(ready, fixture.task), fixture.layout, true));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APT_PREREQUISITES=MISSING\n", "APT_PREREQUISITES=READY\nAPT_PREREQUISITES=MISSING\n",
        "APT_PREREQUISITES=MISSING\nAPT_PREREQUISITES=READY\n"})
    void prerequisiteObservationAcceptsOnlyOneCompleteStdoutCase(String stdout) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new Assisted(fixture)) {
            var session = (RemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RemoteSession.class}, (p, m, a) -> {
                assertEquals("execute", m.getName());
                assertTrue(assisted.grants.contains(((ApprovalGrant) a[1]).id()));
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), stdout, "", false)),
                    Optional.empty());
            });
            var ai = new DeploymentAiJson(assisted.context, fixture.conversation());
            var helper = new DeploymentManagedHelper(session, assisted.host, fixture.server, fixture.task, fixture.model.configurationRevision,
                gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard.NONE,
                (intent, request, task) -> DeploymentFixedCommandApproval.approve(assisted.context, assisted.host, intent, request, task,
                    fixture.model.configurationRevision, "AUTOMATIC", false, DeploymentCommandReviewModel.submission(ai), assisted.scope, true));
            fixture.model.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("observe", "submit_result",
                "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled independent verdict\"}"));
            var result = helper.observeAptPrerequisites(DownloadPoint.APT_UBUNTU);
            assertEquals(1, assisted.hosts.get());
            if (stdout.equals("APT_PREREQUISITES=MISSING\n")) {
                assertEquals(TaskResult.SUCCEEDED, result.outcome());
                assertEquals(DeploymentManagedHelper.PrerequisiteState.MISSING, result.value().orElseThrow());
            } else {
                assertEquals(TaskResult.UNKNOWN, result.outcome());
                assertTrue(result.value().isEmpty());
                assertEquals("DEPLOYMENT_HELPER_PREREQUISITES_UNCONFIRMED", result.problem().orElseThrow().eventCode());
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
                byte[] body = DeploymentMirrorTasks.buildInput(fixture.installer("TRACKED", true)).orElseThrow()
                    .original().getBytes(StandardCharsets.UTF_8);
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
