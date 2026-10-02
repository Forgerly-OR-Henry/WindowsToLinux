package gold.debug.windowstolinux.mod.deployment.service.agent;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.FileSnapshot;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationRef;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentExecutionRepository;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentApplicationReader;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises real CLASS ownership, source snapshots, channels and H2; the SSH peer is controlled. / 验证真实 CLASS 归属、来源快照、频道及 H2；SSH 对等端受控。 */
class DeploymentAgentWorkflowTest extends DeploymentRuntimeFixture {
    enum ScenarioType {
        SUCCESS, WHOLE_HEALTH_FAILURE, COMMAND_UNKNOWN, PUBLISH_UNKNOWN, APPROVAL_DRIFT, AI_APPROVAL_DRIFT, APPROVAL_MISSING, DUPLICATE_JSON, TRAILING_JSON, CANCELLED, BUDGET, HISTORY_INSPECT, MODEL_REFRESH
    }

    private final AtomicReference<String> revision = new AtomicReference<>("a".repeat(64));

    private final AtomicInteger calls = new AtomicInteger();

    private final List<Integer> budgets = new CopyOnWriteArrayList<>();

    private final List<String> approvals = new CopyOnWriteArrayList<>();

    private ScenarioType scenario;

    private final AtomicReference<DeploymentAgentWorkflow> liveWorkflow = new AtomicReference<>();

    private final AtomicReference<CompletableFuture<Void>> modelRefresh = new AtomicReference<>();

    private final List<String> commandReviews = new CopyOnWriteArrayList<>();

    /** Actual exact manual command proposals under the original root. / 原根下实际精确人工命令提案。 */
    private final List<ActionIntent> commandIntents = new CopyOnWriteArrayList<>();

    private final List<UUID> commandReviewTasks = new CopyOnWriteArrayList<>();

    private final AtomicReference<FileSnapshot> frozenMaterial = new AtomicReference<>();

    private final AtomicInteger independentReviews = new AtomicInteger();

    private boolean singleUnsupported;

    private Boolean editFrozenSource;

    private final AtomicReference<Path> frozenDirectory = new AtomicReference<>();

    private boolean cancelAfterCommit;

    private final AtomicReference<TaskHandle<?>> boundaryHandle = new AtomicReference<>();

    private final AtomicBoolean cancelledKnownCommit = new AtomicBoolean();

    private final AtomicReference<Map<String, String>> committedCurrent = new AtomicReference<>();

    private final List<String> boundarySends = new CopyOnWriteArrayList<>();

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict c, ServerRef s, TaskSnapshot t,
                                                           CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall s, ServerRef target, TaskSnapshot t,
                                                       CancelToken token) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                return "Controlled agent transfer";
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken token) {
                throw new AssertionError();
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                approvals.add(intent.type());
                if (intent.type().equals("deployment.agent.command")) {
                    commandReviews.add(intent.revision());
                    commandIntents.add(intent);
                    commandReviewTasks.add(task.id());
                    if (editFrozenSource != null && commandReviews.size() == 1) {
                        Path directory = editFrozenSource
                            ? Objects.requireNonNull(frozenDirectory.get())
                            : selectedSourceDirectory();
                        try {
                            Files.writeString(directory.resolve("README.md"),
                                "Changed during the exact command approval\n");
                        } catch (java.io.IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    }
                    if (scenario == ScenarioType.MODEL_REFRESH && commandReviews.size() == 1) {
                        liveWorkflow.get().control(task.id(), DeploymentAgentControl.CommandAction.PAUSE);
                        modelRefresh.set(CompletableFuture.runAsync(() -> {
                            try {
                                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                                while (liveWorkflow.get().controlState(task.id())
                                    .orElseThrow() != DeploymentAgentControl.State.PAUSED
                                    && System.nanoTime() < deadline)
                                    Thread.sleep(10);
                                assertEquals(DeploymentAgentControl.State.PAUSED,
                                    liveWorkflow.get().controlState(task.id()).orElseThrow());
                                revision.set("b".repeat(64));
                                liveWorkflow.get().control(task.id(),
                                    DeploymentAgentControl.CommandAction.REFRESH_MODELS);
                            } catch (Exception failure) {
                                throw new CompletionException(failure);
                            }
                        }));
                    }
                }
                if (scenario == ScenarioType.APPROVAL_DRIFT && intent.type().equals("deployment.agent.command"))
                    revision.set("b".repeat(64));
                return true;
            }
        };
    }

    /** Runs the dedicated page service through its real one-shot task and durable business commit. / 经专有页面服务执行真实一次性任务与持久业务提交。
     * @param selected tested classified scenario / 测试分类场景
     * @throws Exception on fixture or real task failure / 夹具或真实任务失败时 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ScenarioType.class, names = {"HISTORY_INSPECT",
        "MODEL_REFRESH"}, mode = EnumSource.Mode.EXCLUDE)
    void originalTaskUsesActualChannelsAndRetainsUnknownEffects(ScenarioType selected) throws Exception {
        verifyScenario(selected);
    }

    @Test
    void inspectionRequestUsesOriginalCallbackAndLeavesNormalRecoveryExplicit() throws Exception {
        verifyScenario(ScenarioType.HISTORY_INSPECT);
    }

    @Test
    void pausedModelRefreshInvalidatesApprovedOldProposalAndKeepsOriginalBudgets() throws Exception {
        verifyScenario(ScenarioType.MODEL_REFRESH);
    }

    @Test
    void unsupportedSingleComponentUsesItsOriginalCandidateAndReachesCompleteManagedInventory() throws Exception {
        singleUnsupported = true;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @ParameterizedTest(name = "frozenSnapshot={0}")
    @ValueSource(booleans = {false, true})
    void commandApprovalRejectsActualFrozenSourceDriftButAllowsLaterOriginalDirectoryEditing(boolean frozen)
        throws Exception {
        editFrozenSource = frozen;
        verifyApprovalBoundary();
    }

    @Test
    void cooperativeCancellationAfterKnownPublicationKeepsTheActualCommittedSuccess() throws Exception {
        cancelAfterCommit = true;
        verifyApprovalBoundary();
    }

    private void verifyApprovalBoundary() throws Exception {
        scenario = ScenarioType.SUCCESS;
        Files.createDirectories(selectedSourceDirectory());
        String originalText = "Build api and web into protected output.\n";
        Files.writeString(selectedSourceDirectory().resolve("README.md"), originalText);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var peer = new DeploymentAgentTestPeer(ScenarioType.SUCCESS);
            var workflow = new DeploymentAgentWorkflow(bind(actual, tasks, peer, scriptedAi()));
            try (var recovery = tasks.registerRecovery(DeploymentAgentWorkflow.TASK_TYPE, workflow)) {
                var handle = workflow.submitLocal(new ServerRef("server"), new DeploymentAgentWorkflow.Request(
                    Map.of("goal", "Build the observed api and web"), "MANUAL_REVIEW", 65536, 100, 65536));
                boundaryHandle.set(handle);
                waitReturned(handle);
                assertNotNull(frozenDirectory.get());
                assertNotEquals(selectedSourceDirectory().toRealPath(),
                    frozenDirectory.get().toAbsolutePath().normalize());
                if (Boolean.TRUE.equals(editFrozenSource)) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertTrue(handle.snapshot().outcome().isEmpty());
                    assertEquals(1, commandReviews.size(),
                        "Actual material bytes change during the first exact api review, before any send");
                    assertEquals(1, commandIntents.size());
                    assertEquals("api", commandIntents.getFirst().target());
                    assertEquals("printf artifact > $WTL_OUTPUT/run.sh", commandIntents.getFirst().exactAction());
                    assertEquals(List.of(handle.id()), commandReviewTasks);
                    assertEquals(0, peer.commands.get(),
                        "Approval cannot dispatch a build after actual frozen source bytes change");
                    assertTrue(peer.current.isEmpty());
                    assertTrue(peer.present.values().stream().anyMatch(Boolean::booleanValue));
                    assertTrue(scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                    assertEquals(originalText, Files.readString(selectedSourceDirectory().resolve("README.md")));
                    int decisions = calls.get();
                    restoreOriginalFrozenBytes(frozenDirectory.get().resolve("README.md"), originalText);
                    var resolved = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20,
                        TimeUnit.SECONDS);
                    assertEquals(TaskState.ENDED, resolved.state(), diagnostic(runtime, handle));
                    assertEquals(Optional.of(Outcome.FAILED), resolved.outcome());
                    assertEquals(decisions, calls.get());
                    assertEquals(0, peer.commands.get());
                    assertTrue(peer.current.isEmpty());
                } else {
                    if (cancelAfterCommit && handle.snapshot().state() == TaskState.UNKNOWN) {
                        assertTrue(handle.snapshot().outcome().isEmpty());
                        assertTrue(cancelledKnownCommit.get());
                        try (var access = scope(runtime, true);
                             var connection = access.openConnection();
                             var statement = connection.prepareStatement(
                                 "SELECT outcome,business_committed,cleanup_complete FROM deployment_execution WHERE platform_task_id=?")) {
                            statement.setObject(1, handle.id());
                            try (var rows = statement.executeQuery()) {
                                assertTrue(rows.next());
                                assertEquals("SUCCEEDED", rows.getString(1));
                                assertTrue(rows.getBoolean(2));
                                assertFalse(rows.getBoolean(3),
                                    "UNKNOWN is permitted only while original final cleanup remains unconfirmed");
                            }
                        }
                        int decisions = calls.get(), effects = peer.commands.get();
                        var current = Map.copyOf(peer.current);
                        var settled = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20,
                            TimeUnit.SECONDS);
                        assertEquals(TaskState.ENDED, settled.state(), diagnostic(runtime, handle));
                        assertEquals(Optional.of(Outcome.SUCCEEDED), settled.outcome());
                        assertEquals(decisions, calls.get());
                        assertEquals(effects, peer.commands.get());
                        assertEquals(current, peer.current);
                    }
                    assertEquals(TaskState.ENDED, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(Optional.of(Outcome.SUCCEEDED), handle.snapshot().outcome());
                    assertEquals(3, peer.commands.get());
                    assertEquals(Set.of("demo-api", "demo-web"), peer.current.keySet());
                    if (cancelAfterCommit) {
                        assertTrue(cancelledKnownCommit.get(),
                            "Cancellation is requested only after actual formal success");
                        assertEquals(committedCurrent.get(), peer.current);
                        assertEquals(2, boundarySends.stream().filter(script -> script.contains("'publish-deployment'"))
                            .count());
                        assertEquals(0, boundarySends.stream().filter(script -> script.contains("'rollback-deployment")
                            || script.contains("'rollback-deployment-first'")).count());
                        try (var access = scope(runtime, true);
                             var connection = access.openConnection();
                             var statement = connection.prepareStatement(
                                 "SELECT phase,outcome,business_committed,cleanup_complete FROM deployment_execution WHERE platform_task_id=?")) {
                            statement.setObject(1, handle.id());
                            try (var rows = statement.executeQuery()) {
                                assertTrue(rows.next());
                                assertEquals("FINISHED", rows.getString(1));
                                assertEquals("SUCCEEDED", rows.getString(2));
                                assertTrue(rows.getBoolean(3));
                                assertTrue(rows.getBoolean(4));
                                assertFalse(rows.next());
                            }
                        }
                    } else
                        assertEquals("Changed during the exact command approval\n",
                            Files.readString(selectedSourceDirectory().resolve("README.md")));
                }
                assertTrue(peer.rollbacks.isEmpty());
                assertTrue(peer.present.values().stream().noneMatch(Boolean::booleanValue));
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
    }

    private void verifyScenario(ScenarioType selected) throws Exception {
        scenario = selected;
        Files.createDirectories(selectedSourceDirectory());
        String sourceText = singleUnsupported
            ? "Custom unsupported builder produces one api executable.\n"
            : "Build api and web into protected output.\n";
        Files.writeString(selectedSourceDirectory().resolve("README.md"), sourceText);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            // Replace only this fixture's idle registration with a fresh real task binding. / 仅将夹具空闲登记替换为新的真实任务绑定。
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var peer = new DeploymentAgentTestPeer(selected);
            var ai = scriptedAi();
            var bound = bind(actual, tasks, peer, ai);
            var workflow = new DeploymentAgentWorkflow(bound);
            liveWorkflow.set(workflow);
            try (var registration = tasks.registerRecovery(DeploymentAgentWorkflow.TASK_TYPE, workflow);
                 var queries = tasks.registerRecovery(DeploymentAgentPageService.SERVER_LIST_TASK,
                     new DeploymentAgentPageService(bound, workflow))) {
                var page = new DeploymentAgentPageService(bound, workflow);
                var handle = page.submitLocal(new ServerRef("server"), new DeploymentAgentWorkflow.Request(
                    Map.of("goal",
                        singleUnsupported
                            ? "Build the observed unsupported api"
                            : "Build the observed api and web"),
                    singleUnsupported
                        ? "FULL_CONTROL"
                        : selected == ScenarioType.AI_APPROVAL_DRIFT
                        || selected == ScenarioType.APPROVAL_MISSING ? "AUTOMATIC" : "MANUAL_REVIEW",
                    65536, 100, 65536));
                waitReturned(handle);
                if (modelRefresh.get() != null)
                    modelRefresh.get().get(10, TimeUnit.SECONDS);
                if (Set.of(ScenarioType.COMMAND_UNKNOWN, ScenarioType.PUBLISH_UNKNOWN, ScenarioType.APPROVAL_DRIFT,
                    ScenarioType.AI_APPROVAL_DRIFT, ScenarioType.HISTORY_INSPECT).contains(selected)) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                    assertTrue(handle.snapshot().outcome().isEmpty());
                    assertEquals(1, page.tasks().size());
                    assertTrue(scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                    assertTrue(peer.present.values().stream().anyMatch(Boolean::booleanValue));
                    int sends = peer.commands.get();
                    int decisions = calls.get();
                    peer.active = selected != ScenarioType.PUBLISH_UNKNOWN;
                    if (selected == ScenarioType.HISTORY_INSPECT) {
                        assertEquals(1, peer.commands.get(),
                            "Inspection must follow an actually dispatched unknown build");
                        assertTrue(peer.active, "The original build is still unconfirmed before inspection");
                        var before = Map.copyOf(peer.present);
                        assertTrue(page.controlState(handle.id()).isEmpty());
                        assertThrows(IllegalStateException.class,
                            () -> page.control(handle.id(), DeploymentAgentControl.CommandAction.REFRESH_MODELS),
                            "UNKNOWN cannot refresh or resume its stopped worker");
                        long request = page.requestInspection(handle.id()).completion().toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).value().orElseThrow();
                        assertEquals(request, page.requestInspection(handle.id()).completion().toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).value().orElseThrow());
                        var detail = page.events(handle.id()).completion().toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).value().orElseThrow();
                        assertEquals(handle.id(), detail.entry().id());
                        assertTrue(DeploymentAgentHistory.pending(detail.events()).isPresent());
                        assertThrows(ApiException.class, () -> page.inspect(detail.entry()),
                            "A new query cannot borrow original UNKNOWN occupancy");
                        var inspected = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20,
                            TimeUnit.SECONDS);
                        assertEquals(TaskState.UNKNOWN, inspected.state());
                        var after = page.events(handle.id()).completion().toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).value().orElseThrow();
                        assertTrue(DeploymentAgentHistory.pending(after.events()).isEmpty());
                        var observed = after.events().stream().filter(event -> event.type().equals("INSPECT_RESULT"))
                            .findFirst().orElseThrow();
                        assertEquals(Long.toString(request), observed.action());
                        assertTrue(observed.detail().contains("BUILD_ACTIVE"), observed.detail());
                        assertEquals(before, peer.present);
                        assertEquals(sends, peer.commands.get());
                        assertEquals(decisions, calls.get());
                        assertTrue(peer.rollbacks.isEmpty());
                        assertTrue(scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                    }
                    var still = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
                    assertEquals(TaskState.UNKNOWN, still.state());
                    assertEquals(sends, peer.commands.get());
                    assertEquals(decisions, calls.get());
                    peer.active = false;
                    peer.recoveryKnown = true;
                    revision.set("a".repeat(64));
                    var recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20,
                        TimeUnit.SECONDS);
                    assertEquals(TaskState.ENDED, recovered.state());
                    assertEquals(
                        Optional.of(selected == ScenarioType.PUBLISH_UNKNOWN ? Outcome.SUCCEEDED : Outcome.FAILED),
                        recovered.outcome());
                    assertEquals(sends, peer.commands.get(), "Recovery cannot rerun the candidate build");
                    assertEquals(decisions, calls.get(), "Recovery cannot ask a fresh model");
                    if (selected == ScenarioType.HISTORY_INSPECT)
                        verifyTerminalInspection(runtime, page, peer, handle.id());
                } else {
                    assertEquals(TaskState.ENDED, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(
                        Optional.of(selected == ScenarioType.SUCCESS || selected == ScenarioType.MODEL_REFRESH
                            ? Outcome.SUCCEEDED
                            : selected == ScenarioType.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED),
                        handle.snapshot().outcome());
                    assertEquals(0, page.tasks().size());
                }
                long committed;
                try (var access = scope(runtime, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM managed_application")) {
                    assertTrue(rows.next());
                    committed = rows.getLong(1);
                }
                assertEquals(selected == ScenarioType.SUCCESS || selected == ScenarioType.PUBLISH_UNKNOWN
                    || selected == ScenarioType.MODEL_REFRESH ? 1 : 0, committed);
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertTrue(peer.present.values().stream().noneMatch(Boolean::booleanValue));
                if (selected == ScenarioType.WHOLE_HEALTH_FAILURE)
                    assertEquals(List.of("demo-web", "demo-api"), peer.rollbacks);
                if (selected == ScenarioType.APPROVAL_DRIFT || selected == ScenarioType.AI_APPROVAL_DRIFT
                    || selected == ScenarioType.APPROVAL_MISSING || selected == ScenarioType.DUPLICATE_JSON
                    || selected == ScenarioType.TRAILING_JSON)
                    assertEquals(0, peer.commands.get());
                if (selected == ScenarioType.BUDGET) {
                    assertEquals(80, calls.get());
                    assertEquals(0, budgets.getLast());
                }
                if (selected == ScenarioType.SUCCESS || selected == ScenarioType.MODEL_REFRESH)
                    assertEquals(singleUnsupported ? 2 : 3, peer.commands.get(),
                        "One known failed build retries only the same candidate");
                if (singleUnsupported) {
                    assertEquals(6, calls.get());
                    assertEquals(List.of(79, 78, 77, 76, 75, 74), budgets);
                    assertEquals(2, independentReviews.get(),
                        "Both actual build attempts require their independent original action review");
                    assertEquals(Set.of("demo-api"), peer.current.keySet());
                    assertEquals(Map.of("demo-api", false), peer.present);
                    assertEquals(sourceText, Files.readString(selectedSourceDirectory().resolve("README.md")));
                    verifySinglePublication(bound, tasks, handle.id(), peer.current.get("demo-api"));
                    assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                }
                if (selected == ScenarioType.MODEL_REFRESH) {
                    assertEquals("a".repeat(64), commandReviews.getFirst());
                    assertTrue(commandReviews.subList(1, commandReviews.size()).stream()
                        .allMatch(value -> value.equals("b".repeat(64))));
                    assertEquals(9, calls.get());
                    assertEquals(List.of(79, 78, 77, 76, 75, 74, 73, 72, 71), budgets);
                    try (var access = scope(runtime, true);
                         var connection = access.openConnection();
                         var statement = connection.createStatement();
                         var rows = statement.executeQuery(
                             "SELECT action_id,event_detail FROM deployment_agent_event WHERE event_type='CONFIGURATION_REPLACED'")) {
                        assertTrue(rows.next());
                        assertEquals("a".repeat(64), rows.getString(1));
                        assertEquals("b".repeat(64), rows.getString(2));
                        assertFalse(rows.next());
                    }
                }
                assertTrue(approvals.stream().noneMatch(value -> value.contains("approval-model")));
            }
        }
    }

    private String diagnostic(PlatformRuntime runtime, TaskHandle<?> handle) throws Exception {
        var details = new StringBuilder(handle.snapshot().toString());
        try (var access = scope(runtime, true); var connection = access.openConnection()) {
            for (String query : List.of("SELECT task_state FROM deployment_agent_task WHERE task_id=?",
                "SELECT event_type,event_detail FROM deployment_agent_event WHERE task_id=? ORDER BY event_id",
                "SELECT phase,outcome,cleanup_complete FROM deployment_execution WHERE platform_task_id=?")) {
                try (var statement = connection.prepareStatement(query)) {
                    statement.setObject(1, query.contains("platform_task_id") ? handle.id() : handle.id().toString());
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            details.append("\n");
                            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                                details.append(rows.getMetaData().getColumnName(i)).append('=')
                                    .append(rows.getString(i)).append(' ');
                        }
                    }
                }
            }
        }
        return details.toString();
    }

    private void verifyTerminalInspection(PlatformRuntime runtime, DeploymentAgentPageService page,
                                          DeploymentAgentTestPeer peer, UUID original) throws Exception {
        var entry = page.history().completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow()
            .stream().filter(value -> value.id().equals(original)).findFirst().orElseThrow();
        int effects = peer.commands.get();
        var inspected = page.inspect(entry).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(Outcome.SUCCEEDED, inspected.outcome(), inspected.problem().toString());
        assertTrue(inspected.value().orElseThrow().values().stream().allMatch(value -> value.equals("ABSENT")));
        assertEquals(effects, peer.commands.get());
        assertTrue(peer.present.values().stream().noneMatch(Boolean::booleanValue));
        runtime.servers().saveServerProfile(
            ServerProfile
                .fromStored(new StoredServerProfile("server", "127.0.0.2", 22, "root", "fixture-password")),
            "fixture-password".toCharArray());
        int connections = peer.connections.get();
        assertEquals(Outcome.FAILED,
            page.inspect(entry).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        assertEquals(connections, peer.connections.get(), "Endpoint drift is rejected before connection");
        runtime.servers().saveServerProfile(
            ServerProfile
                .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
            "fixture-password".toCharArray());
        peer.fingerprint = "SHA256:changed-host";
        assertEquals(Outcome.FAILED,
            page.inspect(entry).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        assertEquals(effects, peer.commands.get());
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
    }

    private void waitReturned(TaskHandle<?> handle) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (handle.snapshot().state() != TaskState.ENDED && handle.snapshot().state() != TaskState.UNKNOWN
            && System.nanoTime() < end)
            Thread.sleep(25);
        assertTrue(handle.snapshot().state() == TaskState.ENDED || handle.snapshot().state() == TaskState.UNKNOWN,
            handle.snapshot().toString());
    }

    private ModContext bind(ModContext actual, TaskService tasks, DeploymentAgentTestPeer peer, AiService ai) {
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(ServerRef server, TaskContext task) throws ApiException {
                var original = peer.session(task);
                if (!cancelAfterCommit)
                    return original;
                return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                    new Class<?>[]{RemoteSession.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("execute"))
                            boundarySends.add(((CommandRequest) arguments[0]).script());
                        if (method.getName().equals("close") && !peer.current.isEmpty()
                            && cancelledKnownCommit.compareAndSet(false, true)) {
                            var executions = new DeploymentExecutionRepository(actual.db());
                            var record = executions.findForTask(task).orElseThrow();
                            assertEquals("COMMITTED", record.phase());
                            assertTrue(record.businessCommitted());
                            assertEquals(Optional.of(Outcome.SUCCEEDED), record.outcome());
                            assertFalse(record.cleanupComplete());
                            var proposal = executions.publicationTemplate(record).orElseThrow();
                            assertEquals(2, proposal.units().size());
                            for (var unit : proposal.units())
                                assertEquals(unit.release().sha256(), peer.current.get(unit.unit().id()));
                            assertTrue(peer.present.values().stream().noneMatch(Boolean::booleanValue),
                                "Remote cleanup is genuinely verified before this local close");
                            assertEquals(2, boundarySends.stream()
                                .filter(script -> script.contains("'publish-deployment'")).count());
                            assertTrue(
                                boundarySends.stream()
                                    .anyMatch(script -> script.contains("'application-health'")),
                                "The known commit follows actual health observations");
                            committedCurrent.set(Map.copyOf(peer.current));
                            Objects.requireNonNull(boundaryHandle.get()).requestCancel();
                            assertTrue(task.cancelToken().requested());
                        }
                        try {
                            return method.invoke(original, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            }

            public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                return List.of();
            }
        };
        var source = (FileService) Proxy.newProxyInstance(FileService.class.getClassLoader(),
            new Class<?>[]{FileService.class}, (proxy, method, arguments) -> {
                try {
                    var value = method.invoke(actual.file(), arguments);
                    if (value instanceof FileSnapshot snapshot && editFrozenSource != null
                        && arguments[1] instanceof gold.debug.windowstolinux.appapi.file.SnapshotRequest request
                        && request.relativePaths().contains("README.md")) {
                        assertTrue(frozenMaterial.compareAndSet(null, snapshot),
                            "Only the authentic original material snapshot may bind this boundary");
                        assertTrue(Files.isRegularFile(snapshot.directory().resolve("README.md")));
                        assertFalse(Files.exists(snapshot.directory().resolve("source.tar.gz")));
                        frozenDirectory.set(snapshot.directory());
                    } else if (value instanceof FileSnapshot snapshot && cancelAfterCommit)
                        frozenDirectory.set(snapshot.directory());
                    return value;
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "tasks" -> tasks;
                case "linux" -> linux;
                case "ai" -> ai;
                case "source" -> editFrozenSource != null || cancelAfterCommit ? source : actual.file();
                default -> {
                    try {
                        yield method.invoke(actual, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            });
    }

    private AiService scriptedAi() {
        return new AiService() {
            public String configurationRevision(AiPurpose purpose, TaskContext task) throws ApiException {
                if (purpose == AiPurpose.APPROVAL) {
                    assertTrue(
                        singleUnsupported || scenario == ScenarioType.AI_APPROVAL_DRIFT
                            || scenario == ScenarioType.APPROVAL_MISSING,
                        "Manual review needs no approval model");
                    if (scenario == ScenarioType.APPROVAL_MISSING)
                        throw new ApiException(new Problem(ProblemCode.UNAVAILABLE, "CONTROLLED_APPROVAL_MISSING",
                            "No independent reviewer configured", Map.of(), Optional.empty()));
                } else
                    assertEquals(AiPurpose.LANGUAGE, purpose);
                return revision.get();
            }

            public OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException {
                return call(request, revision.get(), task);
            }

            public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext task)
                throws ApiException {
                assertEquals(revision.get(), expected);
                if (request.purpose() == AiPurpose.APPROVAL) {
                    if (singleUnsupported)
                        independentReviews.incrementAndGet();
                    else {
                        assertEquals(ScenarioType.AI_APPROVAL_DRIFT, scenario);
                        revision.set("b".repeat(64));
                    }
                    return new OperationResult<>(Outcome.SUCCEEDED,
                        Optional.of(new AiResponse(
                            "{\"decision\":\"APPROVE\",\"risk\":\"NORMAL\",\"reason\":\"Controlled review\"}",
                            "independent", "stop", new AiUsage(Optional.empty(), Optional.empty()))),
                        Optional.empty());
                }
                assertEquals(AiPurpose.LANGUAGE, request.purpose());
                int turn = calls.getAndIncrement();
                JsonNode data;
                try {
                    data = new ObjectMapper().readTree(request.messages().getLast().text());
                } catch (Exception invalid) {
                    throw new AssertionError(invalid);
                }
                budgets.add(data.path("remaining").intValue());
                if (editFrozenSource != null)
                    assertEquals(frozenMaterial.get().sha256(), data.path("state").path("sourceRevision").asText(),
                        "The captured material must be the source browser's actual approved revision");
                if (scenario == ScenarioType.CANCELLED)
                    return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
                String output = switch (scenario) {
                    case DUPLICATE_JSON -> "{\"tool\":\"deliver\",\"tool\":\"inspect\",\"arguments\":{}}";
                    case TRAILING_JSON -> "{\"tool\":\"inspect\",\"arguments\":{}} {}";
                    case BUDGET ->
                        "{\"tool\":\"list\",\"arguments\":{\"path\":\".\",\"offset\":" + turn + ",\"limit\":1}}";
                    default -> proposal(turn, data);
                };
                return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(
                    new AiResponse(output, "controlled", "stop", new AiUsage(Optional.empty(), Optional.empty()))),
                    Optional.empty());
            }
        };
    }

    private String proposal(int turn, JsonNode data) {
        if (scenario == ScenarioType.MODEL_REFRESH && turn >= 3)
            turn--;
        if (turn == 0)
            return "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":10}}";
        if (turn == 1)
            return plan();
        if (turn == 2)
            return command("api");
        if (turn == 3) {
            assertTrue(data.path("observations").toString().contains("exit=1"));
            return command("api");
        }
        if (turn == 4)
            return "{\"tool\":\"seal\",\"arguments\":{\"component\":\"api\"}}";
        if (singleUnsupported)
            return "{\"tool\":\"deliver\",\"arguments\":{}}";
        if (turn == 5)
            return command("web");
        if (turn == 6)
            return "{\"tool\":\"seal\",\"arguments\":{\"component\":\"web\"}}";
        return "{\"tool\":\"deliver\",\"arguments\":{}}";
    }

    private static String command(String component) {
        return "{\"tool\":\"command\",\"arguments\":{\"component\":\"" + component
            + "\",\"script\":\"printf artifact > $WTL_OUTPUT/run.sh\",\"seconds\":10}}";
    }

    private String plan() {
        return "{\"tool\":\"plan\",\"arguments\":{\"applicationId\":\"demo\",\"explanation\":\""
            + (singleUnsupported ? "README declares the custom output" : "README builds both") + "\","
            + "\"components\":[" + component("api", "[]")
            + (singleUnsupported ? "" : "," + component("web", "[\"api\"]"))
            + "],\"applicationHealth\":{\"component\":\"" + (singleUnsupported ? "api" : "web")
            + "\",\"health\":{\"type\":\"PROCESS\",\"timeout\":10,\"stability\":1}}}}";
    }

    private static void verifySinglePublication(ModContext context, TaskService tasks, UUID originalTask,
                                                String publishedRelease) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var read = tasks.submit(new TaskRequest("legacy-single-inventory", "Read original complete publication",
            List.of("server"), true), task -> {
            try {
                var data = new DeploymentApplicationReader(context.db())
                    .find(new DeploymentApplicationRef("demo", new ServerRef("server")), task);
                assertEquals(1, data.graph().components().size());
                var component = data.graph().components().getFirst();
                assertEquals("api", component.componentId());
                assertEquals("demo-api", component.unit().id());
                assertTrue(component.dependencies().isEmpty());
                assertInstanceOf(DeploymentRuntimeDefinition.ManagedProcess.class, component.reviewedRuntime());
                assertEquals(component.reviewedRuntime().healthCheck(), component.runtime().healthCheck());
                assertEquals(data.graph(), data.release().graph());
                assertEquals(1, data.release().bindings().size());
                assertEquals("demo-api", data.release().bindings().getFirst().unitId());
                assertEquals(publishedRelease, data.release().bindings().getFirst().releaseSha256());
                try (var connection = context.db().openConnection(task);
                     var statement = connection.prepareStatement(
                         "SELECT action_id,event_detail FROM deployment_agent_event WHERE task_id=? AND event_type='SEALED_ARTIFACT'")) {
                    statement.setString(1, originalTask.toString());
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("api", rows.getString(1));
                        assertEquals("d".repeat(64), rows.getString(2));
                        assertFalse(rows.next());
                    }
                }
                UUID executionId;
                try (var connection = context.db().openConnection(task);
                     var statement = connection.prepareStatement(
                         "SELECT execution_id FROM deployment_execution WHERE platform_task_id=?")) {
                    statement.setObject(1, originalTask);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        executionId = rows.getObject(1, UUID.class);
                        assertFalse(rows.next());
                    }
                }
                var repository = new DeploymentExecutionRepository(context.db());
                var record = repository.find(task, executionId).orElseThrow();
                assertEquals("FINISHED", record.phase());
                assertEquals(Optional.of(Outcome.SUCCEEDED), record.outcome());
                assertTrue(record.businessCommitted());
                assertTrue(record.cleanupComplete());
                var template = repository.publicationTemplate(record).orElseThrow();
                assertEquals(data.graph(), template.graph());
                assertEquals(1, template.units().size());
                assertEquals("demo-api", template.units().getFirst().unit().id());
                assertEquals(publishedRelease, template.units().getFirst().release().sha256());
                return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
            } catch (Exception | AssertionError problem) {
                failure.set(problem);
                throw problem;
            }
        });
        assertEquals(Outcome.SUCCEEDED, read.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome(),
            () -> String.valueOf(failure.get()));
        if (failure.get() != null)
            throw new AssertionError("Single unsupported formal publication could not be read", failure.get());
        assertEquals(TaskState.ENDED, read.snapshot().state());
    }

    private static String component(String id, String dependencies) {
        return "{\"id\":\"" + id + "\",\"dependencies\":" + dependencies
            + ",\"evidence\":[\"README.md\"],\"backend\":\"PROCESS\",\"mode\":\"DAEMON\",\"entrypoint\":\"run.sh\","
            + "\"arguments\":[],\"workingDirectory\":\"\",\"health\":{\"type\":\"PROCESS\",\"timeout\":10,\"stability\":1},"
            + "\"ports\":[],\"resources\":[],\"configuration\":[]}";
    }

    private static void restoreOriginalFrozenBytes(Path file, String content) throws java.io.IOException {
        var original = Files.readAttributes(file, java.nio.file.attribute.DosFileAttributes.class);
        var view = Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView.class);
        try {
            if (original.isReadOnly()) view.setReadOnly(false);
            Files.writeString(file, content);
            assertEquals(content, Files.readString(file));
        } finally {
            view.setArchive(original.isArchive());
            view.setHidden(original.isHidden());
            view.setSystem(original.isSystem());
            view.setReadOnly(original.isReadOnly());
        }
        var restored = view.readAttributes();
        assertEquals(original.isReadOnly(), restored.isReadOnly());
        assertEquals(original.isHidden(), restored.isHidden());
        assertEquals(original.isSystem(), restored.isSystem());
        assertEquals(original.isArchive(), restored.isArchive());
    }
}
