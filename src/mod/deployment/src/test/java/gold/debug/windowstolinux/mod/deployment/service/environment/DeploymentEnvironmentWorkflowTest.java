package gold.debug.windowstolinux.mod.deployment.service.environment;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.task.TaskAuthorCheckpoint;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import org.junit.jupiter.api.Test;

/** Real isolated H2 task/checkpoint behavior with a controlled SSH state peer; no actual package or security command runs. / 真实隔离 H2 任务及检查点与受控 SSH 状态 peer，不运行实际安装或安全命令。 */
class DeploymentEnvironmentWorkflowTest extends DeploymentRuntimeFixture {
    Predicate<ActionIntent> policy = ignored -> true;

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return (PlatformInteractionService.Prompts) Proxy.newProxyInstance(PlatformInteractionService.Prompts.class.getClassLoader(),
            new Class<?>[]{PlatformInteractionService.Prompts.class}, (proxy, method, args) -> {
                if (method.getName().equals("approve")) return policy.test((ActionIntent) args[0]);
                throw new AssertionError("Unexpected fixture prompt: " + method.getName());
            });
    }

    ServerInfo saved(PlatformRuntime runtime) throws Exception {
        var server = new ServerId("environment-server");
        runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile(server.id(), "127.0.0.1", 22,
            "root", "environment-fixture-password")), "environment-fixture-password".toCharArray());
        return new ServerInfo(server, "Controlled peer", "127.0.0.1", 22, "root", Optional.empty(), Optional.empty());
    }

    ModContext bound(PlatformRuntime runtime, Peer peer) throws Exception {
        var original = runtime.modContexts().get(OWNER).context();
        runtime.tasks().revokeOwnerServices(OWNER, VERSION);
        var tasks = runtime.tasks().bind(OWNER, VERSION, false);
        var fresh = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> method.getName().equals("tasks") ? tasks : method.invoke(original, args));
        return peer.wrap(fresh);
    }

    static final class Peer {
        final AtomicInteger authentications = new AtomicInteger();

        final AtomicInteger closes = new AtomicInteger();

        final AtomicInteger commands = new AtomicInteger();

        final Map<String, Integer> sends = new HashMap<>();

        final String fingerprint;

        final String profile;

        UUID boot = UUID.randomUUID();

        String config = "none", security = "apparmor:Y", selinux = "NOT_APPLICABLE";

        String packagePhase = "ABSENT", packageRequest = "none", prepareRequest = "none", enforceRequest = "none", commitRequest = "none";

        boolean active, losePackages, receiptMissing, failLoginAfterPackages, reboot = true, failedEnforce;

        boolean helperInstalled = true, loseHelper, helperReceiptMissing;

        int helperInstalls;

        String helperReceipt = "";

        Peer(String profile) {
            this(profile, "SHA256:" + "B".repeat(43));
        }

        Peer(String profile, String fingerprint) {
            this.profile = profile;
            this.fingerprint = fingerprint;
            if (profile.startsWith("centos")) {
                config = "c".repeat(64);
                security = "Permissive";
                selinux = "UNPREPARED";
            }
        }

        ModContext wrap(ModContext original) {
            var linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(), new Class<?>[]{LinuxService.class},
                (proxy, method, args) -> {
                    assertEquals("connect", method.getName());
                    assertInstanceOf(RemoteTarget.class, args[0]);
                    var target = (RemoteTarget) args[0];
                    assertEquals(((TaskContext) args[1]).id(), target.taskId());
                    assertEquals("root", target.username());
                    if (authentications.get() > 0) assertEquals(Optional.of(fingerprint), target.fingerprint());
                    if (failLoginAfterPackages && packagePhase.equals("SUCCEEDED"))
                        throw problem(ProblemCode.ACCESS_DENIED, "SSH_AUTHENTICATION_FAILED");
                    assertFalse(active, "Previous physical session must close before any fresh login");
                    active = true;
                    authentications.incrementAndGet();
                    return session(original, (TaskContext) args[1]);
                });
            return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> method.getName().equals("linux") ? linux : method.invoke(original, args));
        }

        RemoteSession session(ModContext context, TaskContext task) {
            return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "facts" -> new RemoteFacts("", "", fingerprint);
                    case "close" -> {
                        assertTrue(active);
                        active = false;
                        closes.incrementAndGet();
                        yield null;
                    }
                    case "execute" -> execute(context, task, (CommandRequest) args[0]);
                    default -> throw new AssertionError("Unexpected peer method: " + method.getName());
                });
        }

        OperationResult<CommandResult> execute(ModContext context, TaskContext task, CommandRequest request) throws Exception {
            commands.incrementAndGet();
            if (request.stdin().length == 0)
                return helperInstalled ? ok("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION
                    + "\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n")
                    : new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(1), "", "", false)), Optional.empty());
            String input = new String(request.stdin(), StandardCharsets.UTF_8);
            if (!input.contains("expected_profile=")) {
                var original = DeploymentEnvironmentJournal.find(context.tasks(), task).orElseThrow().read();
                if (input.contains("prepare_stage=platform-packages")) {
                    helperInstalls++;
                    helperInstalled = true;
                    helperReceipt = gold.debug.windowstolinux.mod.deployment.linux.DeploymentHelperInstallerScript.receipt(task.id(),
                        original.operation(), DeploymentEnvironmentScripts.digest(original.target().server().id()),
                        DeploymentEnvironmentScripts.digest(gold.debug.windowstolinux.mod.deployment.linux.DeploymentHelperInstallerScript.render()), "SUCCEEDED");
                    if (loseHelper)
                        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem(ProblemCode.UNKNOWN, "HELPER_REPLY_LOST").problem()));
                    return ok("");
                }
                return helperReceiptMissing ? new OperationResult<>(TaskResult.SUCCEEDED,
                    Optional.of(new CommandResult(Optional.of(3), "", "", false)), Optional.empty()) : ok(helperReceipt);
            }
            String stage = assignment(input, "stage");
            String digest = assignment(input, "request_sha");
            sends.merge(stage, 1, Integer::sum);
            if (stage.equals("prepare")) {
                prepareRequest = digest;
                selinux = "REBOOT_PENDING";
                config = "d".repeat(64);
            }
            if (stage.equals("probe") && selinux.equals("REBOOT_PENDING") && reboot) {
                boot = UUID.randomUUID();
                selinux = "READY_TO_ENFORCE";
            }
            if (stage.equals("enforce")) {
                enforceRequest = digest;
                if (failedEnforce)
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(64), "", "fixture AVC rejection", false)), Optional.empty());
                security = "Enforcing";
                selinux = "ENFORCEMENT_PENDING";
            }
            if (stage.equals("commit")) {
                commitRequest = digest;
                selinux = "COMPLETE";
                config = "e".repeat(64);
            }
            if (stage.equals("packages")) {
                packageRequest = digest;
                packagePhase = "SUCCEEDED";
                if (losePackages)
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem(ProblemCode.UNKNOWN, "REPLY_LOST").problem()));
            }
            var state = DeploymentEnvironmentJournal.find(context.tasks(), task).orElseThrow().read();
            return ok(output(state));
        }

        String output(DeploymentEnvironmentState state) {
            return "ENVIRONMENT_FACTS_BEGIN\nTASK=" + state.target().taskId() + "\nOPERATION=" + state.operation()
                + "\nTARGET_SHA256=" + DeploymentEnvironmentScripts.targetDigest(state.target()) + "\nBODY_SHA256=" + state.bodySha()
                + "\nPROFILE=" + profile + "\nBOOT=" + boot + "\nCONFIG_SHA256=" + config + "\nSECURITY=" + security
                + "\nFIREWALL=none\nSELINUX_PHASE=" + selinux + "\nPACKAGE_PHASE=" + (receiptMissing && packagePhase.equals("SUCCEEDED") ? "STARTED" : packagePhase)
                + "\nPACKAGE_REQUEST=" + packageRequest + "\nPREPARE_REQUEST=" + prepareRequest + "\nENFORCE_REQUEST=" + enforceRequest + "\nCOMMIT_REQUEST=" + commitRequest + "\n";
        }

        static String assignment(String script, String key) {
            return script.lines().filter(line -> line.startsWith(key + "='")).findFirst().orElseThrow().substring(key.length() + 2).replaceFirst("'$", "");
        }

        int sent(String stage) {
            return sends.getOrDefault(stage, 0);
        }
    }

    static ApiException problem(ProblemCode code, String event) {
        return new ApiException(new Problem(code, event, "Controlled environment state", Map.of(), Optional.empty()));
    }

    static OperationResult<CommandResult> ok(String output) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), output, "", false)), Optional.empty());
    }

    @Test
    void initialDefaultRefusalDoesNotConnect() throws Exception {
        enable();
        policy = ignored -> false;
        try (var runtime = open()) {
            start(runtime, true);
            var peer = new Peer("ubuntu-24.04");
            var workflow = new DeploymentEnvironmentWorkflow(bound(runtime, peer));
            assertEquals(TaskResult.CANCELLED, workflow.submit(saved(runtime)).completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(0, peer.authentications.get());
        }
    }

    @Test
    void aptInstallRequiresFreshLoginAndCapabilitiesAndClosesEverySession() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var peer = new Peer("ubuntu-24.04");
            var workflow = new DeploymentEnvironmentWorkflow(bound(runtime, peer));
            var handle = workflow.submit(saved(runtime));
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(1, peer.sent("packages"));
            assertEquals(0, peer.sent("prepare"));
            assertEquals(1, peer.sent("verify"));
            assertTrue(peer.authentications.get() >= 4);
            assertEquals(peer.authentications.get(), peer.closes.get());
            assertFalse(peer.active);
        }
    }

    @Test
    void centosPreparationRebootsOnceAndReauthenticatesEnforcingBeforeCommit() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var peer = new Peer("centos-9");
            var workflow = new DeploymentEnvironmentWorkflow(bound(runtime, peer));
            assertEquals(TaskResult.SUCCEEDED, workflow.submit(saved(runtime)).completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            for (String stage : List.of("prepare", "enforce", "commit", "packages", "verify"))
                assertEquals(1, peer.sent(stage), stage);
            assertEquals("Enforcing", peer.security);
            assertEquals("COMPLETE", peer.selinux);
            assertEquals(peer.authentications.get(), peer.closes.get());
        }
    }

    @Test
    void independentSelinuxRefusalLeavesSecurityAndPackagesUntouched() throws Exception {
        enable();
        policy = action -> !action.type().equals("deployment.environment.prepare");
        try (var runtime = open()) {
            start(runtime, true);
            var peer = new Peer("centos-9");
            var workflow = new DeploymentEnvironmentWorkflow(bound(runtime, peer));
            assertEquals(TaskResult.CANCELLED, workflow.submit(saved(runtime)).completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(0, peer.sent("prepare"));
            assertEquals(0, peer.sent("packages"));
            assertEquals("Permissive", peer.security);
            assertFalse(peer.active);
        }
    }

    @Test
    void lostInstallReceiptRetainsOriginalServerAndReconciliationNeverReinstalls() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var peer = new Peer("ubuntu-24.04");
            peer.losePackages = true;
            peer.receiptMissing = true;
            var context = bound(runtime, peer);
            var workflow = new DeploymentEnvironmentWorkflow(context);
            var original = saved(runtime);
            var handle = workflow.submit(original);
            awaitUnknown(handle);
            var otherRan = new java.util.concurrent.atomic.AtomicBoolean();
            var waiting = context.tasks().submit(new TaskRequest("other", "other", List.of(original.ref().id()), true), task -> {
                otherRan.set(true);
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(TaskState.QUEUED, waiting.snapshot().state());
            assertFalse(otherRan.get());
            waiting.requestCancel();
            assertEquals(TaskResult.CANCELLED, waiting.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            try (var registration = context.tasks().registerRecovery(DeploymentEnvironmentWorkflow.TASK_TYPE, workflow::reconcile)) {
                assertEquals(TaskState.UNKNOWN, reconcileReady(runtime, handle).state());
                assertEquals(1, peer.sent("packages"));
                peer.receiptMissing = false;
                assertEquals(TaskState.ENDED, reconcileReady(runtime, handle).state());
            }
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(1, peer.sent("packages"));
            assertEquals(peer.authentications.get(), peer.closes.get());
        }
    }

    @Test
    void installedPackagesWithFailedFreshLoginRemainUnverifiedUntilOriginalRecovery() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var peer = new Peer("ubuntu-24.04");
            peer.failLoginAfterPackages = true;
            var context = bound(runtime, peer);
            var workflow = new DeploymentEnvironmentWorkflow(context);
            var handle = workflow.submit(saved(runtime));
            awaitUnknown(handle);
            assertFalse(handle.completion().toCompletableFuture().isDone());
            assertEquals(0, peer.sent("verify"));
            peer.failLoginAfterPackages = false;
            try (var registration = context.tasks().registerRecovery(DeploymentEnvironmentWorkflow.TASK_TYPE, workflow::reconcile)) {
                assertEquals(TaskState.ENDED, reconcileReady(runtime, handle).state());
            }
            assertEquals(1, peer.sent("packages"));
            assertEquals(1, peer.sent("verify"));
        }
    }

    @Test
    void missingHelperLostReplyReconcilesSameEnvironmentOperationWithoutNewCheckpointOrInstall() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var peer = new Peer("ubuntu-24.04");
            peer.helperInstalled = false;
            peer.loseHelper = true;
            peer.helperReceiptMissing = true;
            var context = bound(runtime, peer);
            var workflow = new DeploymentEnvironmentWorkflow(context);
            var handle = workflow.submit(saved(runtime));
            awaitUnknown(handle);
            try (var registration = context.tasks().registerRecovery(DeploymentEnvironmentWorkflow.TASK_TYPE, workflow::reconcile)) {
                assertEquals(TaskState.UNKNOWN, reconcileReady(runtime, handle).state());
                assertEquals(1, peer.helperInstalls);
                peer.helperReceiptMissing = false;
                assertEquals(TaskState.ENDED, reconcileReady(runtime, handle).state());
            }
            assertEquals(1, peer.helperInstalls);
            assertEquals(1, peer.sent("packages"));
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
        }
    }

    @Test
    void cancelledKnownInstallationReauthenticatesAndClosesWithoutSendingAnyFurtherCommand() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var peer = new Peer("ubuntu-24.04");
            peer.helperInstalled = false;
            peer.failLoginAfterPackages = true;
            var context = bound(runtime, peer);
            var workflow = new DeploymentEnvironmentWorkflow(context);
            var handle = workflow.submit(saved(runtime));
            awaitUnknown(handle);
            assertFalse(workflow.awaitingConsent(handle.id()));
            var checkpoint = new TaskAuthorCheckpoint(runtime.persistence().database());
            var before = checkpoint.find(handle.id(), OWNER, VERSION, DeploymentEnvironmentJournal.KEY).orElseThrow().snapshot();
            assertTrue(before.payload().contains("\"stage\":\"PACKAGES_INSTALLED\""));
            assertFalse(before.released());
            var commands = Map.copyOf(peer.sends);
            int allCommands = peer.commands.get();
            int authentications = peer.authentications.get();
            handle.requestCancel();
            peer.failLoginAfterPackages = false;
            var retained = new java.util.concurrent.atomic.AtomicReference<gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot>();
            try (var registration = context.tasks().registerRecovery(DeploymentEnvironmentWorkflow.TASK_TYPE, (previous, task) -> {
                var result = workflow.reconcile(previous, task);
                retained.set(checkpoint.find(task.id(), OWNER, VERSION, DeploymentEnvironmentJournal.KEY).orElseThrow().snapshot());
                return result;
            })) {
                assertEquals(TaskState.ENDED, reconcileReady(runtime, handle).state());
            }
            assertEquals(TaskResult.CANCELLED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            var after = retained.get();
            assertEquals(before.payload(), after.payload(), "Cancelled partial preparation retains original installed facts");
            assertTrue(after.released());
            assertEquals(commands, peer.sends, "Known cancelled recovery sends zero additional commands");
            assertEquals(allCommands, peer.commands.get(), "Every execute, including helper probes, remains unsent");
            assertEquals(0, peer.helperInstalls);
            assertEquals(authentications + 1, peer.authentications.get());
            assertEquals(peer.authentications.get(), peer.closes.get());
            assertFalse(peer.active);
        }
    }

    @Test
    void cancelledUnknownInstallationOnlyReadsOriginalReceiptAndNeverInstallsTheMissingHelper() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var peer = new Peer("ubuntu-24.04");
            peer.helperInstalled = false;
            peer.losePackages = true;
            peer.receiptMissing = true;
            var context = bound(runtime, peer);
            var workflow = new DeploymentEnvironmentWorkflow(context);
            var handle = workflow.submit(saved(runtime));
            awaitUnknown(handle);
            var checkpoint = new TaskAuthorCheckpoint(runtime.persistence().database());
            var before = checkpoint.find(handle.id(), OWNER, VERSION, DeploymentEnvironmentJournal.KEY).orElseThrow().snapshot();
            handle.requestCancel();
            var retained = new java.util.concurrent.atomic.AtomicReference<gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot>();
            try (var registration = context.tasks().registerRecovery(DeploymentEnvironmentWorkflow.TASK_TYPE, (previous, task) -> {
                var result = workflow.reconcile(previous, task);
                retained.set(checkpoint.find(task.id(), OWNER, VERSION, DeploymentEnvironmentJournal.KEY).orElseThrow().snapshot());
                return result;
            })) {
                assertEquals(TaskState.UNKNOWN, reconcileReady(runtime, handle).state());
                var unresolved = checkpoint.find(handle.id(), OWNER, VERSION, DeploymentEnvironmentJournal.KEY).orElseThrow().snapshot();
                assertEquals(before.payload(), unresolved.payload());
                assertFalse(unresolved.released());
                peer.receiptMissing = false;
                assertEquals(TaskState.ENDED, reconcileReady(runtime, handle).state());
            }
            assertEquals(TaskResult.CANCELLED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            var after = retained.get();
            assertTrue(after.payload().contains("\"stage\":\"PACKAGES_INSTALLED\""));
            assertTrue(after.payload().contains(peer.packageRequest));
            assertTrue(after.released());
            assertEquals(1, peer.sent("packages"));
            assertEquals(0, peer.sent("verify"));
            assertEquals(0, peer.helperInstalls);
            assertEquals(peer.authentications.get(), peer.closes.get());
        }
    }

    @Test
    void cancelledOriginalRebootKeepsUnknownAndNeverSendsEnforcementOrAnotherReboot() throws Exception {
        enable();
        var runtime = open();
        Throwable originalFailure = null;
        try {
            start(runtime, true);
            var peer = new Peer("centos-9");
            peer.reboot = false;
            var context = bound(runtime, peer);
            var handle = boundedReboot(context, peer, saved(runtime));
            awaitUnknown(handle);
            var checkpoint = new TaskAuthorCheckpoint(runtime.persistence().database());
            var before = checkpoint.find(handle.id(), OWNER, VERSION, DeploymentEnvironmentJournal.KEY).orElseThrow().snapshot();
            var commands = Map.copyOf(peer.sends);
            int allCommands = peer.commands.get();
            handle.requestCancel();
            peer.reboot = true;
            var workflow = new DeploymentEnvironmentWorkflow(context);
            try (var registration = context.tasks().registerRecovery(DeploymentEnvironmentWorkflow.TASK_TYPE, workflow::reconcile)) {
                assertEquals(TaskState.UNKNOWN, reconcileReady(runtime, handle).state());
                assertEquals(TaskState.UNKNOWN, reconcileReady(runtime, handle).state());
            }
            var after = checkpoint.find(handle.id(), OWNER, VERSION, DeploymentEnvironmentJournal.KEY).orElseThrow().snapshot();
            assertEquals(before.payload(), after.payload());
            assertFalse(after.released());
            assertEquals(commands, peer.sends);
            assertEquals(allCommands, peer.commands.get());
            assertEquals(1, peer.sent("prepare"));
            assertEquals(0, peer.sent("enforce"));
            assertEquals(0, peer.sent("commit"));
            assertEquals(0, peer.sent("packages"));
            assertFalse(handle.completion().toCompletableFuture().isDone());
            assertFalse(peer.active);
        } catch (Exception | AssertionError failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            if (originalFailure == null) {
                var refusal = assertThrows(ApiException.class, runtime::close);
                assertEquals(ProblemCode.BUSY, refusal.problem().code(), "The retained original unknown operation must prevent normal Mod shutdown");
            } else {
                try {
                    runtime.close();
                } catch (Exception | AssertionError cleanup) {
                    originalFailure.addSuppressed(cleanup);
                }
            }
        }
    }

    @Test
    void unchangedBootBudgetKeepsOriginalPreparationAndRecoveryDoesNotRebootTwice() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var peer = new Peer("centos-9");
            peer.reboot = false;
            var context = bound(runtime, peer);
            var server = saved(runtime);
            var handle = boundedReboot(context, peer, server);
            awaitUnknown(handle);
            assertEquals(1, peer.sent("prepare"));
            assertEquals(0, peer.sent("enforce"));
            assertEquals(0, peer.sent("packages"));
            peer.reboot = true;
            var workflow = new DeploymentEnvironmentWorkflow(context);
            try (var registration = context.tasks().registerRecovery(DeploymentEnvironmentWorkflow.TASK_TYPE, workflow::reconcile)) {
                assertEquals(TaskState.ENDED, reconcileReady(runtime, handle).state());
            }
            assertEquals(1, peer.sent("prepare"));
            assertEquals(1, peer.sent("enforce"));
            assertEquals(1, peer.sent("commit"));
        }
    }

    static TaskHandle<Void> boundedReboot(ModContext context, Peer peer, ServerInfo server) throws ApiException {
        return context.tasks().submit(new TaskRequest(DeploymentEnvironmentWorkflow.TASK_TYPE, "Bounded reboot observation", List.of(server.ref().id()), true), task -> {
            var target = new RemoteTarget(server.ref(), task.id(), server.host(), server.port(), server.username(), Optional.of(peer.fingerprint));
            var journal = DeploymentEnvironmentJournal.begin(context.tasks(), task, target);
            var state = journal.read();
            var observed = new DeploymentEnvironmentObservation(peer.profile, peer.boot, peer.config, peer.security, "none", peer.selinux, "ABSENT", "none", "none", "none", "none");
            journal.advance(state, state.next("OBSERVED", "none", observed));
            return new DeploymentEnvironmentRunner(context, (fixed, revision, request, original) -> context.interaction().approve(RemoteActions.command(fixed.server(), revision, request), original),
                task, journal, Duration.ofMillis(10), Duration.ofMillis(1)).run(false);
        });
    }

    static void awaitUnknown(TaskHandle<?> handle) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (handle.snapshot().state() != TaskState.UNKNOWN) {
            assertTrue(System.nanoTime() < until, handle.snapshot().toString());
            Thread.sleep(10);
        }
    }
}
