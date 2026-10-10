package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiRole;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationId;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentApplicationReader;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
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

    /** Original algorithm fixture worker and its last proposed protected source identity. / 原算法夹具工作任务及其最近提案的受保护来源身份。 */
    private final AtomicReference<UUID> originalAiTask = new AtomicReference<>();

    private final AtomicReference<String> proposedSourceRevision = new AtomicReference<>();

    /** Actual per-component protected revisions offered to the planning worker. / 实际提供给计划工作线程的各组件受保护修订。 */
    private final AtomicReference<Map<String, String>> proposedRemoteRevisions = new AtomicReference<>(Map.of());

    private enum RiskJourney {REPLACE, REPEAT}

    private RiskJourney riskJourney;

    private String riskAuthorization = "GENERAL";

    private static final String HIGH_SCRIPT = "rm -rf ./build-cache";

    private final AtomicInteger highQuestions = new AtomicInteger(), highCandidateReviews = new AtomicInteger();

    private final AtomicInteger lowerRiskFeedback = new AtomicInteger();

    /** First installer refusal is independent of an ordinary free-script risk. / 首次安装器拒绝与普通自由脚本风险分开。 */
    private boolean highInitialInstaller;

    /** A later refused open retains the genuinely created original candidate. / 后续拒绝打开保留真实已创建的原候选。 */
    private boolean highPreparedOpen;

    private final AtomicInteger installerReviews = new AtomicInteger(), installerQuestions = new AtomicInteger();

    private final AtomicInteger aiApprovalDrifts = new AtomicInteger();

    private boolean singleUnsupported;

    private Boolean editFrozenSource;

    private final AtomicReference<Path> frozenDirectory = new AtomicReference<>();

    private boolean cancelAfterCommit;

    private final AtomicReference<TaskHandle<?>> boundaryHandle = new AtomicReference<>();

    private final AtomicBoolean cancelledKnownCommit = new AtomicBoolean();

    private final AtomicReference<Map<String, String>> committedCurrent = new AtomicReference<>();

    /** Exercises actual preselection followed by fresh root authorization and one freeze. / 验证实际预选后根任务重新授权及一次冻结。 */
    private boolean preselectedSource;

    private final AtomicReference<UUID> sourceTask = new AtomicReference<>();

    private final AtomicInteger sourceSnapshots = new AtomicInteger(), sourceArchives = new AtomicInteger();

    private final List<String> boundarySends = new CopyOnWriteArrayList<>();

    /** Validates model evidence separately from actual opaque host grants. / 分别核验模型依据与真实不透明宿主许可。 */
    private FixedCommandFixture fixedCommands;

    /** Carries strict peer failures through the host's UNKNOWN classification. / 将严格对等端失败原因带过宿主的UNKNOWN分类。 */
    private final AtomicReference<Throwable> fixtureFailure = new AtomicReference<>();

    /** Exact ended original allowed for a fresh terminal inspection query. / 允许新终态检查查询读取的精确已结束原任务。 */
    private final AtomicReference<UUID> terminalInspection = new AtomicReference<>();

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict c, ServerId s, TaskSnapshot t,
                                                           CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall s, ServerId target, TaskSnapshot t,
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
                if (highInitialInstaller && fixedCommands.isInitialInstaller(intent, task.id())) {
                    assertNotEquals("FULL", riskAuthorization);
                    assertEquals(originalAiTask.get(), task.id());
                    assertFalse(token.requested());
                    assertEquals(1, installerQuestions.incrementAndGet());
                    return false;
                }
                if (riskJourney != null && fixedCommands.isHigh(intent, task.id())) {
                    assertNotEquals("FULL", riskAuthorization);
                    assertEquals(originalAiTask.get(), task.id());
                    assertFalse(token.requested());
                    highQuestions.incrementAndGet();
                    return false;
                }
                boolean command = intent.type().equals("platform.ssh.execute");
                String operation = intent.semantics().map(value -> value.operation()).orElse("");
                if (command && operation.equals("deployment.candidate.verify.v1")
                    && editFrozenSource != null && commandIntents.isEmpty()) {
                    var proof = fixedCommands.reviewed.get(fixedCommands.key(task.id(), intent.revision(), intent.exactAction()));
                    assertNotNull(proof, "The source-change hook follows the actual independently reconstructed archive verification");
                    assertEquals(proof.operation(), operation);
                    assertEquals(List.of("server"), intent.serverIds());
                    assertEquals("server", intent.target());
                    commandIntents.add(intent);
                    commandReviewTasks.add(task.id());
                    Path directory = editFrozenSource
                        ? Objects.requireNonNull(frozenDirectory.get())
                        : selectedSourceDirectory();
                    try {
                        Files.writeString(directory.resolve("README.md"),
                            "Changed during the exact archive verification approval\n");
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }
                if (scenario == ScenarioType.APPROVAL_DRIFT && command
                    && operation.equals("deployment.agent.helper.v1") && intent.exactAction().contains("'agent-run'")) {
                    assertNotNull(fixedCommands.reviewed.get(fixedCommands.key(task.id(), intent.revision(), intent.exactAction())),
                        "Route drift follows independent AI and the genuine typed agent-run human question");
                    commandIntents.add(intent);
                    commandReviewTasks.add(task.id());
                    revision.set("b".repeat(64));
                }
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

    /** Refuses one exact high-risk request through the actual host, then executes a new low-risk action. / 经实际宿主拒绝一次精确高危请求，再执行新的低风险动作。
     * @param authorization original authorization repetition / 原授权重复策略
     * @throws Exception on actual workflow or fixture failure / 实际流程或夹具失败时 */
    @ParameterizedTest
    @ValueSource(strings = {"ONCE", "GENERAL"})
    void automaticOnceOrGeneralRefusesExactHighThenIndependentlyReviewsAndPublishesDifferentLow(String authorization) throws Exception {
        riskJourney = RiskJourney.REPLACE;
        riskAuthorization = authorization;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void automaticFullNeverAsksOrSendsHighAndPublishesNewLow() throws Exception {
        riskJourney = RiskJourney.REPLACE;
        riskAuthorization = "FULL";
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void automaticFullStopsKnownWhenModelRepeatsTheSameRefusedScript() throws Exception {
        riskJourney = RiskJourney.REPEAT;
        riskAuthorization = "FULL";
        verifyScenario(ScenarioType.SUCCESS);
    }

    /** Refuses the exact first installer without any remote effect or repeated review shopping. / 拒绝精确首次安装器，不产生远端效果或重复审核寻优。
     * @param authorization original explicit repetition strategy / 原明确重复策略
     * @throws Exception on actual root or strict evidence failure / 实际根或严格依据失败时 */
    @ParameterizedTest
    @ValueSource(strings = {"ONCE", "GENERAL", "FULL"})
    void firstHighInstallerStopsKnownWithoutAnyRemoteEffectOrRepeatedApproval(String authorization) throws Exception {
        highInitialInstaller = true;
        riskAuthorization = authorization;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void highOpenAfterActualCandidateCreationRetainsUnknownAndOnlyOriginalRecoveryCleans() throws Exception {
        highPreparedOpen = true;
        riskAuthorization = "FULL";
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void unsupportedSingleComponentUsesItsOriginalCandidateAndReachesCompleteManagedInventory() throws Exception {
        singleUnsupported = true;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void completedPickerReauthorizesChangedLiveSourceAndPublishesOnlyTheNewRootFreeze() throws Exception {
        preselectedSource = true;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void removedPreselectedDirectoryFailsNewRootAdmissionBeforeAnyModelArchiveOrRemoteCommand() throws Exception {
        scenario = ScenarioType.SUCCESS;
        preselectedSource = true;
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("README.md"), "Original picker source\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")), "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var peer = new DeploymentAgentTestPeer(ScenarioType.SUCCESS);
            var bound = bind(actual, tasks, peer, scriptedAi());
            var workflow = new DeploymentAgentWorkflow(bound);
            var page = new DeploymentAgentPageService(bound, workflow);
            try (var recovery = tasks.registerRecovery(DeploymentAgentWorkflow.TASK_TYPE, workflow)) {
                var picker = page.sources().select("Original local source", Optional.empty());
                var picked = picker.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(TaskResult.SUCCEEDED, picked.outcome());
                assertEquals(TaskState.ENDED, picker.snapshot().state());
                var location = picked.value().orElseThrow();
                Files.delete(selectedSourceDirectory().resolve("README.md"));
                Files.delete(selectedSourceDirectory());
                var handle = page.submitLocal(new ServerId("server"), new DeploymentAgentWorkflow.Request(
                    Map.of("goal", "Read the selected original source"), "MANUAL_REVIEW", "GENERAL", 65_536, 100, 65_536), location);
                var result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(TaskResult.FAILED, result.outcome(), result.toString());
                assertEquals(TaskState.ENDED, handle.snapshot().state());
                assertNotEquals(picker.id(), handle.id());
                assertEquals(0, calls.get());
                assertEquals(0, peer.commands.get());
                assertEquals(0, sourceSnapshots.get());
                assertEquals(0, sourceArchives.get());
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
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
                var handle = workflow.submitLocal(new ServerId("server"), new DeploymentAgentWorkflow.Request(
                    Map.of("goal", "Build the observed api and web"), "MANUAL_REVIEW", "GENERAL", 65536, 100, 65536));
                boundaryHandle.set(handle);
                waitReturned(handle);
                assertNotNull(frozenDirectory.get());
                assertNotEquals(selectedSourceDirectory().toRealPath(),
                    frozenDirectory.get().toAbsolutePath().normalize());
                if (Boolean.TRUE.equals(editFrozenSource)) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertTrue(handle.snapshot().outcome().isEmpty());
                    assertEquals(1, commandReviews.size(),
                        "The first api proposal is independently reviewed, then changed frozen bytes stop its actual agent-run");
                    assertEquals(1, commandIntents.size());
                    var changedAt = commandIntents.getFirst();
                    assertEquals("platform.ssh.execute", changedAt.type());
                    assertEquals(List.of("server"), changedAt.serverIds());
                    assertEquals("server", changedAt.target());
                    assertEquals("deployment.candidate.verify.v1", changedAt.semantics().orElseThrow().operation());
                    var originalVerify = fixedCommands.reviewed.get(fixedCommands.key(handle.id(), changedAt.revision(), changedAt.exactAction()));
                    assertNotNull(originalVerify);
                    assertEquals(originalVerify.exact(), changedAt.exactAction());
                    assertTrue(changedAt.exactAction().contains("candidate-query-task") && changedAt.exactAction().contains("/mutable/source.tar.gz"));
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
                    var resolved = reconcileReady(runtime, handle);
                    assertEquals(TaskState.ENDED, resolved.state(), diagnostic(runtime, handle));
                    assertEquals(Optional.of(TaskResult.FAILED), resolved.outcome());
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
                        var settled = reconcileReady(runtime, handle);
                        assertEquals(TaskState.ENDED, settled.state(), diagnostic(runtime, handle));
                        assertEquals(Optional.of(TaskResult.SUCCEEDED), settled.outcome());
                        assertEquals(decisions, calls.get());
                        assertEquals(effects, peer.commands.get());
                        assertEquals(current, peer.current);
                    }
                    assertEquals(TaskState.ENDED, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(Optional.of(TaskResult.SUCCEEDED), handle.snapshot().outcome());
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
                        assertEquals("Changed during the exact archive verification approval\n",
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
                var location = new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentSourceSelectionService.LocalLocation(
                    selectedSourceDirectory().toRealPath().toUri(), "input");
                UUID pickerId = null;
                if (preselectedSource) {
                    var picker = page.sources().select("Original autonomous source", Optional.empty());
                    var picked = picker.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                    assertEquals(TaskResult.SUCCEEDED, picked.outcome());
                    assertEquals(TaskState.ENDED, picker.snapshot().state());
                    pickerId = picker.id();
                    location = picked.value().orElseThrow();
                    assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + pickerId + "' AND released_at IS NULL"));
                    assertEquals(0, sourceSnapshots.get(), "Preselection returns position metadata without freezing source");
                    Files.writeString(selectedSourceDirectory().resolve("README.md"), "Changed after original picker closed\n");
                }
                var handle = page.submitLocal(new ServerId("server"), new DeploymentAgentWorkflow.Request(
                    Map.of("goal",
                        singleUnsupported
                            ? "Build the observed unsupported api"
                            : "Build the observed api and web"),
                    riskJourney != null || highInitialInstaller || highPreparedOpen ? "AUTOMATIC" : singleUnsupported
                        ? "FULL_CONTROL"
                        : selected == ScenarioType.AI_APPROVAL_DRIFT
                        || selected == ScenarioType.APPROVAL_MISSING ? "AUTOMATIC" : "MANUAL_REVIEW",
                    selected == ScenarioType.APPROVAL_DRIFT ? "ONCE" : riskAuthorization, 65536, 100, 65536), location);
                waitReturned(handle);
                if (preselectedSource) {
                    assertNotEquals(pickerId, handle.id());
                    assertEquals(handle.id(), sourceTask.get());
                    assertEquals(1, sourceSnapshots.get(), "Only the new original root freezes source");
                    assertEquals(1, sourceArchives.get(), "The actual archive derives from that same root freeze");
                }
                if (modelRefresh.get() != null)
                    modelRefresh.get().get(10, TimeUnit.SECONDS);
                if (highInitialInstaller) {
                    assertEquals(TaskState.ENDED, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(TaskResult.FAILED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                    assertEquals(4, calls.get(), "Two distinct ordinary proposals end within the original stalled-turn bound");
                    assertEquals(2, independentReviews.get(), "Both new ordinary proposals receive their own independent review");
                    assertEquals(1, installerReviews.get(), "The exact immutable refused installer is independently reviewed only once");
                    assertEquals(riskAuthorization.equals("FULL") ? 0 : 1, installerQuestions.get());
                    assertEquals(1, lowerRiskFeedback.get());
                    assertEquals(0, fixedCommands.sends.get());
                    assertEquals(0, peer.commands.get());
                    assertTrue(boundarySends.isEmpty(), "Neither helper, candidate nor free command is sent");
                    assertTrue(peer.present.isEmpty());
                    assertTrue(peer.current.isEmpty());
                    assertFalse(handle.snapshot().outcome().isEmpty());
                    assertTrue(page.tasks().isEmpty());
                    assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                    var evidence = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    assertEquals(2, evidence.stream().filter(event -> event.type().equals("PREPARE_NOT_SENT")
                        && event.detail().equals("LOWER_RISK_REQUIRED")).count());
                    assertTrue(evidence.stream().noneMatch(event -> Set.of("CANDIDATE", "COMMAND_EXECUTING", "GRAPH_PROPOSAL").contains(event.type())));
                    assertEquals(sourceText, Files.readString(selectedSourceDirectory().resolve("README.md")));
                    return;
                }
                if (highPreparedOpen) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(3, calls.get());
                    assertEquals(1, independentReviews.get());
                    assertEquals(0, installerQuestions.get());
                    assertEquals(Map.of("demo-api", true), peer.present);
                    assertEquals(0, peer.commands.get());
                    assertTrue(boundarySends.stream().anyMatch(script -> script.contains("candidate-create-task")));
                    assertTrue(boundarySends.stream().noneMatch(script -> script.contains("'agent-open'") || script.contains("'agent-run'")));
                    assertEquals(fixedCommands.sends.get() + 1, fixedCommands.reviews.get(), "The actual later refused open has no send");
                    assertTrue(scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                    var evidence = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    assertEquals(1, evidence.stream().filter(event -> event.type().equals("PREPARE_EXECUTING")).count());
                    assertTrue(evidence.stream().noneMatch(event -> Set.of("PREPARE_NOT_SENT", "PREPARE_RESULT", "COMMAND_EXECUTING").contains(event.type())));
                    var recovered = reconcileReady(runtime, handle);
                    assertEquals(handle.id(), recovered.id());
                    assertEquals(TaskState.ENDED, recovered.state(), diagnostic(runtime, handle));
                    assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
                    assertEquals(3, calls.get(), "Original recovery does not replan or replay preparation");
                    assertEquals(0, peer.commands.get());
                    assertEquals(Map.of("demo-api", false), peer.present);
                    assertEquals(1, boundarySends.stream().filter(script -> script.contains("candidate-create-task")).count());
                    assertEquals(1, boundarySends.stream().filter(script -> script.contains("candidate-cleanup-task")).count());
                    assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                    return;
                }
                if (Set.of(ScenarioType.COMMAND_UNKNOWN, ScenarioType.PUBLISH_UNKNOWN, ScenarioType.APPROVAL_DRIFT,
                    ScenarioType.AI_APPROVAL_DRIFT, ScenarioType.HISTORY_INSPECT).contains(selected)) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                    if (selected == ScenarioType.AI_APPROVAL_DRIFT)
                        assertEquals(1, aiApprovalDrifts.get(), "The original candidate exists before its fixed command's actual independent response changes the route");
                    assertTrue(handle.snapshot().outcome().isEmpty());
                    assertEquals(1, page.tasks().size());
                    assertTrue(scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                    assertTrue(peer.present.values().stream().anyMatch(Boolean::booleanValue));
                    if (selected == ScenarioType.APPROVAL_DRIFT) {
                        assertEquals(1, commandIntents.size());
                        assertEquals(List.of(handle.id()), commandReviewTasks);
                        var drifted = commandIntents.getFirst();
                        assertEquals("platform.ssh.execute", drifted.type());
                        assertEquals(List.of("server"), drifted.serverIds());
                        assertEquals("server", drifted.target());
                        assertEquals("deployment.agent.helper.v1", drifted.semantics().orElseThrow().operation());
                        assertTrue(drifted.exactAction().contains("'agent-run'"));
                        assertEquals(0, peer.commands.get(), "The post-human route guard prevents the actual agent-run send");
                        try (var access = scope(runtime, true); var connection = access.openConnection();
                             var query = connection.prepareStatement("SELECT event_type FROM deployment_agent_event WHERE task_id=? AND event_type IN ('COMMAND_EXECUTING','COMMAND_RESULT') ORDER BY event_id")) {
                            query.setObject(1, handle.id());
                            try (var events = query.executeQuery()) {
                                assertTrue(events.next());
                                assertEquals("COMMAND_EXECUTING", events.getString(1));
                                assertFalse(events.next(), "The original executing attempt has no fabricated dispatch result");
                            }
                        }
                    }
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
                        var inspected = reconcileReady(runtime, handle);
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
                    var still = reconcileReady(runtime, handle);
                    assertEquals(TaskState.UNKNOWN, still.state());
                    assertEquals(sends, peer.commands.get());
                    assertEquals(decisions, calls.get());
                    peer.active = false;
                    peer.recoveryKnown = true;
                    revision.set("a".repeat(64));
                    var recovered = reconcileReady(runtime, handle);
                    assertEquals(TaskState.ENDED, recovered.state());
                    assertEquals(
                        Optional.of(selected == ScenarioType.PUBLISH_UNKNOWN ? TaskResult.SUCCEEDED : TaskResult.FAILED),
                        recovered.outcome());
                    assertEquals(sends, peer.commands.get(), "Recovery cannot rerun the candidate build");
                    assertEquals(decisions, calls.get(), "Recovery cannot ask a fresh model");
                    if (selected == ScenarioType.HISTORY_INSPECT)
                        verifyTerminalInspection(runtime, page, peer, handle.id());
                } else {
                    assertEquals(TaskState.ENDED, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(
                        Optional.of((selected == ScenarioType.SUCCESS && riskJourney != RiskJourney.REPEAT) || selected == ScenarioType.MODEL_REFRESH
                            ? TaskResult.SUCCEEDED
                            : selected == ScenarioType.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED),
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
                assertEquals((selected == ScenarioType.SUCCESS && riskJourney != RiskJourney.REPEAT) || selected == ScenarioType.PUBLISH_UNKNOWN
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
                if ((selected == ScenarioType.SUCCESS && riskJourney != RiskJourney.REPEAT) || selected == ScenarioType.MODEL_REFRESH)
                    assertEquals(singleUnsupported ? 2 : 3, peer.commands.get(),
                        "One known failed build retries only the same candidate");
                if ((selected == ScenarioType.SUCCESS && riskJourney != RiskJourney.REPEAT) || selected == ScenarioType.MODEL_REFRESH)
                    fixedCommands.completed();
                if ((selected == ScenarioType.SUCCESS && riskJourney != RiskJourney.REPEAT) || selected == ScenarioType.MODEL_REFRESH)
                    assertEquals(riskJourney == RiskJourney.REPLACE ? 4 : selected == ScenarioType.MODEL_REFRESH ? 4 : singleUnsupported ? 2 : 3,
                        independentReviews.get(), "Every manual candidate, including the discarded old-model proposal, requires its independent exact APPROVAL request");
                if (selected == ScenarioType.APPROVAL_MISSING || selected == ScenarioType.DUPLICATE_JSON
                    || selected == ScenarioType.TRAILING_JSON || selected == ScenarioType.CANCELLED || selected == ScenarioType.BUDGET)
                    assertEquals(0, independentReviews.get(), "Rejected admission or an invalid non-command proposal cannot obtain a command review");
                if (selected == ScenarioType.APPROVAL_DRIFT || selected == ScenarioType.AI_APPROVAL_DRIFT)
                    assertEquals(1, independentReviews.get(), "The changed original approval must not authorize another candidate");
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
                if (riskJourney != null) verifyRiskJourney(runtime, handle, peer);
            }
        }
    }

    private String diagnostic(PlatformRuntime runtime, TaskHandle<?> handle) throws Exception {
        var details = new StringBuilder(handle.snapshot().toString());
        if (fixtureFailure.get() != null) {
            var trace = new java.io.StringWriter();
            fixtureFailure.get().printStackTrace(new java.io.PrintWriter(trace));
            details.append("\nStrict fixture failure:\n").append(trace);
        }
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

    /** Reads original durable observations after the real root has settled. / 在真实根结清后读取原持久观察。
     * @param runtime actual host / 实际宿主
     * @param handle original admitted root / 原准入根
     * @param peer actual controlled remote endpoint / 实际受控远端
     * @throws Exception if original evidence is unavailable / 原依据不可用时 */
    private void verifyRiskJourney(PlatformRuntime runtime, TaskHandle<?> handle, DeploymentAgentTestPeer peer) throws Exception {
        assertEquals(1, highCandidateReviews.get());
        assertEquals(1, lowerRiskFeedback.get(), "The next actual LANGUAGE request receives the refused-action observation");
        assertEquals(riskAuthorization.equals("FULL") ? 0 : 1, highQuestions.get());
        assertEquals(riskAuthorization.equals("FULL") ? 0 : 1, fixedCommands.denied.get());
        var events = new ArrayList<DeploymentAgentActionLog.Event>();
        try (var access = scope(runtime, true); var connection = access.openConnection();
             var query = connection.prepareStatement("SELECT event_id,event_type,action_id,event_detail FROM deployment_agent_event WHERE task_id=? ORDER BY event_id")) {
            query.setString(1, handle.id().toString());
            try (var rows = query.executeQuery()) {
                while (rows.next())
                    events.add(new DeploymentAgentActionLog.Event(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4)));
            }
        }
        var observations = events.stream().filter(value -> value.type().equals("AGENT_OBSERVATION") && value.action().equals("LOWER_RISK_REQUIRED")).toList();
        assertEquals(riskJourney == RiskJourney.REPEAT ? 2 : 1, observations.size());
        assertTrue(observations.stream().allMatch(value -> value.detail().matches("[0-9a-f]{64}")));
        assertTrue(DeploymentAgentActionLog.effectsSettled(events));
        var unsent = events.stream().filter(value -> value.type().equals("COMMAND_NOT_SENT")).toList();
        assertEquals(riskAuthorization.equals("FULL") ? 0 : 1, unsent.size());
        if (!unsent.isEmpty()) {
            assertEquals("LOWER_RISK_REQUIRED", unsent.getFirst().detail());
            assertEquals(fixedCommands.sha(("api:" + "c".repeat(64) + ":" + HIGH_SCRIPT).getBytes(StandardCharsets.UTF_8)), unsent.getFirst().action());
        }
        if (riskJourney == RiskJourney.REPEAT) {
            assertEquals(4, calls.get());
            assertEquals(List.of(79, 78, 77, 76), budgets);
            assertEquals(1, independentReviews.get(), "The repeated rejected script is refused before another independent review");
            assertEquals(0, peer.commands.get());
            assertTrue(peer.present.isEmpty());
            assertTrue(fixedCommands.agentInputDigests.isEmpty());
            assertTrue(events.stream().noneMatch(value -> Set.of("CANDIDATE", "COMMAND_EXECUTING", "COMMAND_APPROVED").contains(value.type())));
            assertEquals("DEPLOYMENT_LOW_RISK_PATH_UNAVAILABLE", handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).problem().orElseThrow().eventCode());
        } else {
            assertEquals(9, calls.get());
            assertEquals(List.of(79, 78, 77, 76, 75, 74, 73, 72, 71), budgets);
            assertEquals(4, independentReviews.get());
            assertEquals(3, peer.commands.get());
            assertEquals(Collections.nCopies(3, fixedCommands.sha("printf artifact > $WTL_OUTPUT/run.sh".getBytes(StandardCharsets.UTF_8))), fixedCommands.agentInputDigests);
            assertEquals(Set.of("demo-api", "demo-web"), peer.current.keySet());
            assertTrue(events.stream().anyMatch(value -> value.type().equals("GRAPH_HEALTH_RESULT") && value.detail().equals("HEALTHY")));
        }
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
    }

    private void verifyTerminalInspection(PlatformRuntime runtime, DeploymentAgentPageService page,
                                          DeploymentAgentTestPeer peer, UUID original) throws Exception {
        var entry = page.history().completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow()
            .stream().filter(value -> value.id().equals(original)).findFirst().orElseThrow();
        int effects = peer.commands.get();
        int languageCalls = calls.get(), reviews = fixedCommands.reviews.get(), sends = fixedCommands.sends.get();
        terminalInspection.set(original);
        var query = page.inspect(entry);
        assertNotEquals(original, query.id());
        final OperationResult<Map<String, String>> inspected;
        try {
            inspected = query.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (TimeoutException unsettled) {
            throw new AssertionError(diagnostic(runtime, query), unsettled);
        }
        assertEquals(TaskState.ENDED, query.snapshot().state(), diagnostic(runtime, query));
        assertEquals(TaskResult.SUCCEEDED, inspected.outcome(), inspected.problem().toString());
        assertEquals(languageCalls, calls.get(), "Fresh inspection never replans the original work");
        assertEquals(reviews + 2, fixedCommands.reviews.get(), "The helper probe and original api candidate receive fresh independent reviews");
        assertEquals(sends + 2, fixedCommands.sends.get(), "Each query-owned grant sends only its original-target observation");
        assertTrue(inspected.value().orElseThrow().values().stream().allMatch(value -> value.equals("ABSENT")));
        assertEquals(effects, peer.commands.get());
        assertTrue(peer.present.values().stream().noneMatch(Boolean::booleanValue));
        runtime.servers().saveServerProfile(
            ServerProfile
                .fromStored(new StoredServerProfile("server", "127.0.0.2", 22, "root", "fixture-password")),
            "fixture-password".toCharArray());
        int connections = peer.connections.get();
        assertEquals(TaskResult.FAILED,
            page.inspect(entry).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        assertEquals(connections, peer.connections.get(), "Endpoint drift is rejected before connection");
        runtime.servers().saveServerProfile(
            ServerProfile
                .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
            "fixture-password".toCharArray());
        peer.fingerprint = "SHA256:changed-host";
        assertEquals(TaskResult.FAILED,
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
        fixedCommands = new FixedCommandFixture(actual, tasks);
        var interaction = fixedCommands.observe(actual.interaction());
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                var original = peer.session(task);
                return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                    new Class<?>[]{RemoteSession.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("execute"))
                            fixedCommands.beforeSend(task, (CommandRequest) arguments[0], (ApprovalGrant) arguments[1]);
                        if (method.getName().equals("execute"))
                            boundarySends.add(((CommandRequest) arguments[0]).script());
                        if (cancelAfterCommit && method.getName().equals("close") && !peer.current.isEmpty()
                            && cancelledKnownCommit.compareAndSet(false, true)) {
                            var executions = new DeploymentExecutionStore(actual.db());
                            var record = executions.findForTask(task).orElseThrow();
                            assertEquals("COMMITTED", record.phase());
                            assertTrue(record.businessCommitted());
                            assertEquals(Optional.of(TaskResult.SUCCEEDED), record.outcome());
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
                            fixtureFailure.compareAndSet(null, failure.getCause());
                            throw failure.getCause();
                        }
                    });
            }

            public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                return List.of();
            }
        };
        var source = gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture.create(FileService.class.getClassLoader(), (proxy, method, arguments) -> {
            try {
                var value = gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture.invoke(method, actual.file(), arguments);
                if (value instanceof FileSnapshot snapshot && preselectedSource
                    && arguments[1] instanceof gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest request) {
                    var task = (TaskContext) arguments[2];
                    if (request.relativePaths().contains("README.md")) {
                        sourceSnapshots.incrementAndGet();
                        assertTrue(sourceTask.compareAndSet(null, task.id()));
                        assertEquals("Changed after original picker closed\n", Files.readString(snapshot.directory().resolve("README.md")));
                        Files.writeString(selectedSourceDirectory().resolve("README.md"), "Late live change after original root freeze\n");
                    } else if (request.relativePaths().equals(List.of("source.tar.gz"))) {
                        sourceArchives.incrementAndGet();
                        assertEquals(sourceTask.get(), task.id());
                        try (var gzip = new java.util.zip.GZIPInputStream(Files.newInputStream(snapshot.directory().resolve("source.tar.gz")))) {
                            byte[] bytes = gzip.readNBytes(65_537);
                            assertTrue(bytes.length <= 65_536);
                            String tar = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                            assertTrue(tar.contains("Changed after original picker closed\n"));
                            assertFalse(tar.contains("Late live change after original root freeze\n"));
                        }
                    }
                } else if (value instanceof FileSnapshot snapshot && editFrozenSource != null
                    && arguments[1] instanceof gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest request
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
                case "interaction" -> interaction;
                case "file" ->
                    preselectedSource || editFrozenSource != null || cancelAfterCommit ? source : actual.file();
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
            @Override
            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            @Override
            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, String expectedRevision,
                gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            @Override
            public gold.debug.windowstolinux.appapi.ai.voice.VoiceSession openVoice(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceOptions options, gold.debug.windowstolinux.appapi.ai.voice.VoiceListener listener,
                gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            public String configurationRevision(AiPurpose purpose, TaskContext task) throws ApiException {
                originalAiTask.compareAndSet(null, task.id());
                if (!originalAiTask.get().equals(task.id())) {
                    assertTrue(Set.of(AiPurpose.LANGUAGE, AiPurpose.APPROVAL).contains(purpose), "Terminal inspection verifies both original role revisions without calling the LANGUAGE planner");
                    fixedCommands.requireInspection(task);
                }
                if (purpose == AiPurpose.APPROVAL) {
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
                if (riskJourney != null)
                    assertFalse(task.cancelToken().requested(), "Refusing a HIGH action is not cancelling the original workflow");
                if (request.purpose() == AiPurpose.APPROVAL) {
                    return exactIndependentReview(request, task);
                }
                assertEquals(AiPurpose.LANGUAGE, request.purpose());
                assertEquals(originalAiTask.get(), task.id(), "A fresh inspection may verify LANGUAGE configuration but must never send a planner request");
                int turn = calls.getAndIncrement();
                JsonNode data;
                try {
                    data = new ObjectMapper().readTree(request.messages().getLast().text());
                } catch (Exception invalid) {
                    throw new AssertionError(invalid);
                }
                proposedSourceRevision.set(data.path("state").path("sourceRevision").asText());
                var remoteRevisions = new LinkedHashMap<String, String>();
                data.path("state").path("remoteRevisions").fields().forEachRemaining(entry -> {
                    assertTrue(entry.getValue().isTextual());
                    assertEquals("c".repeat(64), entry.getValue().textValue());
                    remoteRevisions.put(entry.getKey(), entry.getValue().textValue());
                });
                proposedRemoteRevisions.set(Map.copyOf(remoteRevisions));
                budgets.add(data.path("remaining").intValue());
                if (editFrozenSource != null)
                    assertEquals(frozenMaterial.get().sha256(), data.path("state").path("sourceRevision").asText(),
                        "The captured material must be the source browser's actual approved revision");
                if (scenario == ScenarioType.CANCELLED)
                    return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
                String output = switch (scenario) {
                    case DUPLICATE_JSON -> "{\"tool\":\"deliver\",\"tool\":\"inspect\",\"arguments\":{}}";
                    case TRAILING_JSON -> "{\"tool\":\"inspect\",\"arguments\":{}} {}";
                    case BUDGET ->
                        "{\"tool\":\"list\",\"arguments\":{\"path\":\".\",\"offset\":" + turn + ",\"limit\":1}}";
                    default -> proposal(turn, data);
                };
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(
                    new AiResponse(output, "controlled", "stop", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))),
                    Optional.empty());
            }
        };
    }

    /** Accepts exact original candidates or independently reconstructed fixed requests, never host authorization. / 仅接纳精确原候选或独立重建的固定请求，绝不冒充宿主授权。 */
    private OperationResult<AiResponse> exactIndependentReview(AiRequest request, TaskContext task) {
        try {
            return exactIndependentReviewUnchecked(request, task);
        } catch (RuntimeException | AssertionError failure) {
            fixtureFailure.compareAndSet(null, failure);
            throw failure;
        }
    }

    private OperationResult<AiResponse> exactIndependentReviewUnchecked(AiRequest request, TaskContext task) {
        if (!originalAiTask.get().equals(task.id())) fixedCommands.requireInspection(task);
        assertEquals(2, request.messages().size());
        assertEquals(AiRole.SYSTEM, request.messages().getFirst().role());
        assertEquals(AiRole.USER, request.messages().getLast().role());
        assertTrue(request.jsonObjectResponse());
        final JsonNode envelope;
        try {
            envelope = new ObjectMapper().readTree(request.messages().getLast().text());
        } catch (Exception invalid) {
            throw new AssertionError("Independent fixture received malformed action evidence", invalid);
        }
        if (request.messages().getFirst().text().startsWith("Review the complete exact registered deployment command and executable input.")) {
            boolean high = fixedCommands.review(envelope, task);
            return approved("Exact original fixed command", high);
        }
        assertTrue(request.messages().getFirst().text().startsWith("Review this exact autonomous deployment action"));
        assertEquals(originalAiTask.get(), task.id(), "A fresh history query cannot propose another autonomous action");
        assertEquals(Set.of("type", "action", "target", "facts"), keys(envelope),
            "Candidate review is a separate exact envelope");
        assertEquals("COMMAND", envelope.path("type").asText());
        assertEquals("server", envelope.path("target").asText());
        var facts = envelope.path("facts");
        assertEquals(Set.of("component", "sourceRevision", "script"), keys(facts));
        String component = facts.path("component").asText();
        assertTrue((singleUnsupported ? Set.of("api") : Set.of("api", "web")).contains(component));
        String source = facts.path("sourceRevision").asText();
        assertTrue(source.matches("[0-9a-f]{64}"));
        assertEquals(proposedRemoteRevisions.get().getOrDefault(component, proposedSourceRevision.get()), source,
            "Review binds the original component's actual protected revision, or the local manifest before preparation");
        String script = facts.path("script").asText();
        boolean high = riskJourney != null && script.equals(HIGH_SCRIPT);
        if (high) {
            assertEquals("api", component);
            assertEquals(1, highCandidateReviews.incrementAndGet(), "A refused original script cannot shop for a new independent approval");
        } else assertEquals(highInitialInstaller && independentReviews.get() == 1
            ? "printf artifact > $WTL_OUTPUT/run2.sh" : "printf artifact > $WTL_OUTPUT/run.sh", script);
        try {
            String action = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((component + ":" + source + ":" + script).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(action, envelope.path("action").asText());
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
        independentReviews.incrementAndGet();
        commandReviews.add(revision.get());
        if (scenario == ScenarioType.MODEL_REFRESH && commandReviews.size() == 1)
            pauseForModelRefresh(task);
        return approved("Exact controlled candidate", high);
    }

    /** Pauses the real worker at its next safe checkpoint after exact independent preflight review. / 精确独立前置审核后在下一个安全检查点暂停真实工作线程。 */
    private void pauseForModelRefresh(TaskContext task) {
        liveWorkflow.get().control(task.id(), DeploymentAgentControl.CommandAction.PAUSE);
        modelRefresh.set(CompletableFuture.runAsync(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (liveWorkflow.get().controlState(task.id()).orElseThrow() != DeploymentAgentControl.State.PAUSED
                    && System.nanoTime() < deadline)
                    Thread.sleep(10);
                assertEquals(DeploymentAgentControl.State.PAUSED, liveWorkflow.get().controlState(task.id()).orElseThrow());
                revision.set("b".repeat(64));
                liveWorkflow.get().control(task.id(), DeploymentAgentControl.CommandAction.REFRESH_MODELS);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }));
    }

    private static OperationResult<AiResponse> approved(String reason, boolean high) {
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new AiResponse("{\"decision\":\"APPROVE\",\"risk\":\"" + (high ? "HIGH" : "NORMAL") + "\",\"reason\":\"" + reason + "\"}",
                "independent", "stop", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))), Optional.empty());
    }

    private static Set<String> keys(JsonNode value) {
        assertTrue(value.isObject());
        var keys = new HashSet<String>();
        value.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** Finite original-workflow model fixture, not a provider of host permissions. / 有限原流程模型夹具，不提供宿主权限。 */
    private final class FixedCommandFixture {
        private final ModContext actual;

        private final TaskService tasks;

        private final Map<List<String>, Proof> reviewed = new ConcurrentHashMap<>();

        private final Map<UUID, Proof> granted = new ConcurrentHashMap<>();

        private final AtomicInteger reviews = new AtomicInteger(), sends = new AtomicInteger(), denied = new AtomicInteger();

        private final List<String> agentInputDigests = new CopyOnWriteArrayList<>();

        private record Proof(UUID task, String operation, String revision, String exact,
                             String inputKind, Optional<String> executable, Optional<String> installed) {
        }

        FixedCommandFixture(ModContext actual, TaskService tasks) {
            this.actual = actual;
            this.tasks = tasks;
        }

        UUID requireInspection(TaskContext task) {
            try {
                assertEquals(ScenarioType.HISTORY_INSPECT, scenario);
                assertEquals(originalAiTask.get(), terminalInspection.get());
                assertNotEquals(terminalInspection.get(), task.id());
                var snapshot = tasks.list(Optional.empty(), 200).items().stream()
                    .filter(value -> value.id().equals(task.id())).findFirst().orElseThrow();
                assertEquals(OWNER, snapshot.ownerId());
                assertEquals(VERSION, snapshot.ownerVersion());
                assertEquals(DeploymentAgentPageService.SERVER_LIST_TASK, snapshot.type());
                assertEquals(TaskState.RUNNING, snapshot.state());
                return terminalInspection.get();
            } catch (ApiException | RuntimeException | AssertionError failure) {
                fixtureFailure.compareAndSet(null, failure);
                throw new AssertionError("Fresh inspection must retain its actual query ownership and exact original target", failure);
            }
        }

        boolean review(JsonNode facts, TaskContext task) {
            try {
                UUID original = originalAiTask.get().equals(task.id()) ? task.id() : requireInspection(task);
                var journal = new DeploymentAgentActionLog(actual.db());
                var identity = journal.find(task, original).orElseThrow().identity();
                assertEquals("server", identity.server());
                assertEquals(identity.approvalMode(), facts.path("policy").textValue());
                assertEquals(scenario == ScenarioType.APPROVAL_DRIFT ? "ONCE" : riskAuthorization, identity.authorizationMode());
                assertEquals(List.of("server"), new ObjectMapper().convertValue(facts.path("targets"), List.class));
                var events = journal.events(task, original);
                var sources = events.stream().filter(value -> value.type().equals("SOURCE")).toList();
                assertEquals(1, sources.size());
                var source = sources.getFirst();
                assertTrue(source.action().matches("[0-9a-f]{64}"));
                assertTrue(source.detail().matches("[0-9a-f]{64}"));
                if (frozenMaterial.get() != null) assertEquals(frozenMaterial.get().sha256(), source.detail());
                String business = facts.path("businessRevision").textValue();
                var expected = new ArrayList<Proof>();
                var routes = new HashSet<String>();
                routes.add(identity.configurationDigest());
                for (var event : events)
                    if (event.type().equals("CONFIGURATION_REPLACED")) {
                        assertEquals("a".repeat(64), event.action());
                        assertEquals("b".repeat(64), event.detail());
                        assertEquals(ScenarioType.MODEL_REFRESH, scenario);
                        routes.add(event.detail());
                    }
                if (routes.contains(business)) {
                    if (original.equals(task.id())) originalCommands(expected, task, business, source.action(), events);
                    else inspectionCommands(expected, task, business, source.action(), events, original);
                }
                var executions = new DeploymentExecutionStore(actual.db());
                var record = executions.findForTask(task);
                if (record.isPresent()) {
                    assertEquals(task.id(), record.orElseThrow().identity().taskId());
                    assertEquals(new ServerId("server"), record.orElseThrow().identity().server());
                    var proposal = executions.publicationTemplate(record.orElseThrow());
                    if (proposal.isPresent())
                        publicationCommands(expected, task, proposal.orElseThrow(), events, source.action());
                }
                var matches = expected.stream().filter(value -> value.operation().equals(facts.path("operation").textValue())
                    && value.revision().equals(business) && value.exact().equals(facts.path("exactAction").textValue())).toList();
                assertEquals(1, matches.size(), "Fixed evidence must match one independently reconstructed original request");
                var proof = matches.getFirst();
                var fields = new HashSet<>(Set.of("policy", "operation", "targets", "businessRevision", "exactAction", "inputKind"));
                proof.executable().ifPresent(value -> fields.add("completeExecutableInput"));
                assertEquals(fields, keys(facts));
                assertEquals(proof.inputKind(), facts.path("inputKind").textValue());
                proof.executable().ifPresent(value -> assertEquals(value, facts.path("completeExecutableInput").textValue()));
                proof.installed().ifPresent(value -> assertTrue(facts.path("completeExecutableInput").textValue()
                    .contains("'" + value.replace("'", "'\"'\"'") + "'"), "Actual installer includes the entire shell-quoted helper"));
                reviewed.put(key(task.id(), proof.revision(), proof.exact()), proof);
                reviews.incrementAndGet();
                if (scenario == ScenarioType.AI_APPROVAL_DRIFT && proof.operation().equals("deployment.agent.helper.v1")
                    && proof.exact().contains("'agent-run'")) {
                    assertEquals(originalAiTask.get(), task.id());
                    assertTrue(events.stream().anyMatch(event -> event.type().equals("CANDIDATE")), "The fault follows an actual original candidate, rather than the earlier unsent free-script review");
                    assertEquals(1, aiApprovalDrifts.incrementAndGet());
                    revision.set("b".repeat(64));
                }
                if (highInitialInstaller && proof.operation().equals("deployment.helper.install.v1")) {
                    assertEquals(1, installerReviews.incrementAndGet());
                    return true;
                }
                if (highPreparedOpen && proof.operation().equals("deployment.agent.helper.v1")
                    && proof.exact().contains("'agent-open'")) return true;
                return proof.executable().filter(HIGH_SCRIPT::equals).isPresent();
            } catch (Exception | AssertionError failure) {
                fixtureFailure.compareAndSet(null, failure);
                throw new AssertionError("Original fixed evidence cannot be independently confirmed", failure);
            }
        }

        InteractionService observe(InteractionService host) {
            return (InteractionService) Proxy.newProxyInstance(InteractionService.class.getClassLoader(),
                new Class<?>[]{InteractionService.class}, (proxy, method, arguments) -> {
                    Proof proof = null;
                    if (Set.of("delegateWorkflow", "reviewedWorkflow").contains(method.getName())
                        && !originalAiTask.get().equals(((TaskContext) arguments[2]).id())) {
                        requireInspection((TaskContext) arguments[2]);
                        var intent = (ActionIntent) arguments[0];
                        assertEquals(List.of("server"), intent.serverIds());
                        assertEquals(((TaskContext) arguments[2]).id().toString(), intent.target());
                        assertTrue(intent.exactAction().contains("inspect-original=" + terminalInspection.get()));
                    }
                    if (Set.of("approve", "approveRequired").contains(method.getName())) {
                        var intent = (ActionIntent) arguments[0];
                        var task = (TaskContext) arguments[1];
                        proof = reviewed.get(key(task.id(), intent.revision(), intent.exactAction()));
                        if (proof != null) {
                            assertEquals("platform.ssh.execute", intent.type());
                            assertEquals(List.of("server"), intent.serverIds());
                            assertEquals("server", intent.target());
                            assertEquals(proof.operation(), intent.semantics().orElseThrow().operation());
                            assertEquals("Execute the exact reviewed script on the selected server.", intent.effectSummary());
                        }
                    }
                    final Object value;
                    try {
                        value = method.invoke(host, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (proof != null && value instanceof OperationResult<?> result && result.outcome() == TaskResult.SUCCEEDED) {
                        var grant = (ApprovalGrant) result.value().orElseThrow();
                        assertNull(granted.putIfAbsent(grant.id(), proof), "Each real host grant has one exact request");
                    }
                    if (proof != null && value instanceof OperationResult<?> result && result.outcome() == TaskResult.CANCELLED) {
                        if (highInitialInstaller) assertEquals("deployment.helper.install.v1", proof.operation());
                        else assertEquals(RiskJourney.REPLACE, riskJourney);
                        assertNotEquals("FULL", riskAuthorization);
                        if (!highInitialInstaller)
                            assertTrue(proof.executable().filter(HIGH_SCRIPT::equals).isPresent());
                        assertEquals("approveRequired", method.getName());
                        assertTrue(result.value().isEmpty());
                        assertFalse(((TaskContext) arguments[1]).cancelToken().requested());
                        assertEquals(1, denied.incrementAndGet(), "Only one real original HIGH refusal is admitted");
                    }
                    return value;
                });
        }

        void beforeSend(TaskContext task, CommandRequest request, ApprovalGrant grant) {
            var proof = granted.remove(grant.id());
            if (riskJourney != null && request.script().contains("'agent-run'")) {
                assertNotNull(proof, "Each new LOW effect is independently reviewed and bound to a genuine host grant");
                byte[] input = request.stdin();
                try {
                    String digest = sha(input);
                    assertNotEquals(sha(HIGH_SCRIPT.getBytes(StandardCharsets.UTF_8)), digest, "The exact rejected HIGH bytes are never sent");
                    agentInputDigests.add(digest);
                } finally {
                    Arrays.fill(input, (byte) 0);
                }
            }
            if (proof == null) return; // Existing source/inspection paths retain their actual host admission.
            assertEquals(proof.task(), task.id());
            assertFalse(proof.executable().filter(HIGH_SCRIPT::equals).isPresent(), "A refused HIGH request must never reach the peer");
            assertEquals(proof.exact(), RemoteActions.command(new ServerId("server"), proof.revision(), request).exactAction());
            byte[] input = request.stdin();
            try {
                proof.executable().ifPresent(value -> assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), input));
                assertEquals(input.length == 0 ? "EMPTY" : proof.executable().isPresent() ? "EXECUTABLE_SCRIPT" : "OPAQUE_BOUND_DATA",
                    proof.inputKind());
            } finally {
                Arrays.fill(input, (byte) 0);
            }
            sends.incrementAndGet();
        }

        void completed() {
            assertTrue(sends.get() > 0, "The actual workflow sent independently reviewed fixed requests");
            assertEquals(reviews.get(), sends.get() + denied.get(), "Known success links each fixed review to an actual send or the exact real non-cancelling HIGH refusal");
            assertTrue(granted.isEmpty());
        }

        private List<String> key(UUID task, String revision, String exact) {
            return List.of(task.toString(), revision, exact);
        }

        boolean isHigh(ActionIntent intent, UUID task) {
            var proof = reviewed.get(key(task, intent.revision(), intent.exactAction()));
            return proof != null && proof.executable().filter(HIGH_SCRIPT::equals).isPresent();
        }

        boolean isInitialInstaller(ActionIntent intent, UUID task) {
            var proof = reviewed.get(key(task, intent.revision(), intent.exactAction()));
            return proof != null && proof.operation().equals("deployment.helper.install.v1");
        }

        private void originalCommands(List<Proof> expected, TaskContext task, String business, String archive,
                                      List<DeploymentAgentActionLog.Event> events) {
            String helper = DeploymentManagedHelperBundle.PATH;
            String body = DeploymentHelperInstallerScript.render();
            String installed = DeploymentManagedHelperBundle.renderScript();
            assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, sha(installed.getBytes(StandardCharsets.UTF_8)));
            add(expected, task, "deployment.helper.install.v1", business, "exec /bin/bash --noprofile --norc -s",
                body.getBytes(StandardCharsets.UTF_8), Duration.ofHours(2), 131072, Optional.of(body), Optional.of(installed));
            String probe = "set -euo pipefail\n" + "printf '%s  %s\\n' '" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "' '"
                + helper + "' | /usr/bin/sha256sum --check --status\n" + "'" + helper + "' probe\n" + "printf 'SHA256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\\n'\n";
            empty(expected, task, "deployment.helper.probe.v1", business, probe, 30, 4096);
            empty(expected, task, "deployment.candidate.host-probe.v1", business, DeploymentPlatformCapabilityProbe.render(), 120, 65536);
            empty(expected, task, "deployment.candidate.capacity.v1", business,
                "set -euo pipefail\ntest -d '/var/lib' && test ! -L '/var/lib'\nstat -f -c '%a %S' -- '/var/lib'\n", 20, 1024);
            for (String unit : singleUnsupported ? List.of("demo-api") : List.of("demo-api", "demo-web")) {
                var workspace = new DeploymentRemoteWorkspace(unit, archive);
                var ids = List.of(unit, workspace.candidateId(), task.id().toString());
                String prefix = quote(helper) + " candidate-query-task " + quoted(ids);
                empty(expected, task, "deployment.candidate.create.v1", business,
                    quote(helper) + " candidate-create-task " + quoted(ids.subList(0, 2)) + " 268435456 " + quote(task.id().toString()), 300, 4096);
                empty(expected, task, "deployment.candidate.query.v1", business, prefix, 20, 4096);
                String remote = quote(workspace.candidateRoot() + "/mutable/source.tar.gz");
                String verify = "set -euo pipefail\n" + prefix + "\ntest -f " + remote + " && test ! -L " + remote
                    + "\ntest \"$(realpath -e -- " + remote + ")\" = " + remote + "\nprintf 'BYTES='; stat -c '%s' -- " + remote
                    + "\nprintf 'SHA256='; sha256sum -- " + remote + " | cut -d ' ' -f1\nprintf 'OWNER='; stat -c '%u' -- " + remote + "\n";
                empty(expected, task, "deployment.candidate.verify.v1", business, verify, 30, 4096);
                agent(expected, task, business, "agent-open", ids, List.of(archive), 120, new byte[0], Optional.empty());
                String source = "c".repeat(64);
                String script = "printf artifact > $WTL_OUTPUT/run.sh";
                String component = unit.substring("demo-".length());
                var current = events.stream().filter(value -> value.type().equals("CURRENT_SOURCE") && value.action().equals(component)).toList();
                assertTrue(current.size() <= 1);
                if (!current.isEmpty()) {
                    assertEquals(source, current.getFirst().detail(), "Only the actual original protected source authorizes this fixed command");
                    agent(expected, task, business, "agent-run", ids, List.of(source, "10", "1048576", "ordinary"),
                        30, script.getBytes(StandardCharsets.UTF_8), Optional.of(script));
                    if (riskJourney == RiskJourney.REPLACE && !riskAuthorization.equals("FULL") && component.equals("api"))
                        agent(expected, task, business, "agent-run", ids, List.of(source, "10", "1048576", "ordinary"),
                            30, HIGH_SCRIPT.getBytes(StandardCharsets.UTF_8), Optional.of(HIGH_SCRIPT));
                    agent(expected, task, business, "agent-seal", ids, List.of(source), 120, new byte[0], Optional.empty());
                }
                agent(expected, task, business, "candidate-query-task", ids, List.of(), 20, new byte[0], Optional.empty());
                agent(expected, task, business, "candidate-cleanup-task", ids, List.of(), 45, new byte[0], Optional.empty());
            }
        }

        /** Reconstructs query-owned probes with the ended original's exact candidate UUIDs. / 使用已结束原任务的精确候选UUID重建查询所属探针。 */
        private void inspectionCommands(List<Proof> expected, TaskContext task, String business, String archive,
                                        List<DeploymentAgentActionLog.Event> events, UUID original) {
            String helper = DeploymentManagedHelperBundle.PATH;
            String probe = "set -euo pipefail\n" + "printf '%s  %s\\n' '" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "' '"
                + helper + "' | /usr/bin/sha256sum --check --status\n" + "'" + helper + "' probe\n" + "printf 'SHA256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\\n'\n";
            empty(expected, task, "deployment.helper.probe.v1", business, probe, 30, 4096);
            var candidates = events.stream().filter(value -> value.type().equals("CANDIDATE")).toList();
            assertEquals(1, candidates.size());
            for (var candidate : candidates) {
                assertEquals("api", candidate.action());
                String unit = "demo-" + candidate.action();
                var workspace = new DeploymentRemoteWorkspace(unit, archive);
                assertEquals(workspace.candidateId(), candidate.detail());
                agent(expected, task, business, "candidate-query-task", List.of(unit, workspace.candidateId(), original.toString()),
                    List.of(), 20, new byte[0], Optional.empty());
            }
        }

        private void publicationCommands(List<Proof> expected, TaskContext task, DeploymentPublishProposal proposal,
                                         List<DeploymentAgentActionLog.Event> events, String archive) {
            assertEquals("demo", proposal.graph().applicationId());
            assertEquals(singleUnsupported ? 1 : 2, proposal.units().size());
            for (var part : proposal.graph().components()) {
                var artifacts = events.stream().filter(value -> value.type().equals("SEALED_ARTIFACT")
                    && value.action().equals(part.componentId())).toList();
                assertEquals(1, artifacts.size());
                String business = artifacts.getFirst().detail();
                assertEquals("d".repeat(64), business);
                var commit = proposal.units().stream().filter(value -> value.unit().equals(part.unit())).findFirst().orElseThrow();
                assertEquals(new ServerId("server"), part.unit().server());
                assertEquals("demo-" + part.componentId(), part.unit().id());
                assertInstanceOf(DeploymentRuntimeDefinition.ManagedProcess.class, part.reviewedRuntime());
                assertTrue(commit.secrets().isEmpty());
                assertTrue(commit.configuration().entries().isEmpty());
                var unit = commit.unit();
                var workspace = new DeploymentRemoteWorkspace(unit.id(), archive);
                for (String format : List.of("systemd", "container")) {
                    byte[] configuration = DeploymentPublishArguments.configuration(commit.configuration(), format);
                    try {
                        var arguments = List.of(unit.id(), commit.configuration().sha256(), format, sha(configuration), Integer.toString(configuration.length));
                        add(expected, task, "deployment.publication.config.v1", business, helper("stage-config", arguments),
                            configuration, Duration.ofSeconds(90), 4096, Optional.empty(), Optional.empty());
                    } finally {
                        Arrays.fill(configuration, (byte) 0);
                    }
                }
                publication(expected, task, business, "snapshot", "snapshot-deployment", List.of(unit.id(), unit.ownershipManifestSha256(), task.id().toString()), 30);
                var publish = new ArrayList<>(List.of(unit.id(), workspace.candidateId(), commit.release().sha256(), unit.ownershipManifestSha256(),
                    "identity-v2", part.reviewedRuntime().identityPolicy().name(), DeploymentWorkloadArguments.payload(part.reviewedRuntime())));
                publish.addAll(DeploymentPublishArguments.inputs(commit.configuration().sha256(), Map.of()));
                publish.addAll(DeploymentPublishArguments.content(unit.id(), part.resources()));
                publish.addAll(DeploymentRuntimeArguments.from(null, part.reviewedRuntime()));
                publication(expected, task, business, "publish", "publish-deployment", publish, 120);
                var health = part.reviewedRuntime().healthCheck();
                assertEquals(10, health.timeoutSeconds());
                publication(expected, task, business, "health", "application-health",
                    List.of(unit.id(), unit.ownershipManifestSha256(), DeploymentWorkloadArguments.healthPayload(health)), health.timeoutSeconds() + 45L);
                assertEquals(health, proposal.graph().healthCheck());
                publication(expected, task, business, "inspect", "inspect-runtime", List.of(unit.id(), unit.ownershipManifestSha256()), 20);
                publication(expected, task, business, "observe", "observe-deployment", List.of(unit.id(), unit.ownershipManifestSha256()), 20);
                publication(expected, task, business, "original-status", "publication-original-status", List.of(unit.id(), unit.ownershipManifestSha256(),
                    task.id().toString(), workspace.candidateId(), commit.release().sha256(), "native", "none"), 30);
                publication(expected, task, business, "rollback", "rollback-deployment-first", List.of(unit.id(), commit.release().sha256(), unit.ownershipManifestSha256()), 120);
                publication(expected, task, business, "maintenance-end", "application-maintenance", List.of("end", unit.id(), unit.ownershipManifestSha256(),
                    "deployment-" + commit.release().sha256(), commit.release().sha256()), 30);
                publication(expected, task, business, "candidate-cleanup", "candidate-cleanup-task", List.of(unit.id(), workspace.candidateId(), task.id().toString()), 90);
                publication(expected, task, business, "snapshot-discard", "snapshot-discard-task", List.of(unit.id(), unit.ownershipManifestSha256(), task.id().toString()), 60);
                publication(expected, task, business, "retain", "retain", List.of(unit.id(), unit.ownershipManifestSha256()), 60);
            }
        }

        private void agent(List<Proof> expected, TaskContext task, String business, String verb, List<String> ids,
                           List<String> arguments, long seconds, byte[] input, Optional<String> executable) {
            var values = new ArrayList<>(ids);
            values.addAll(arguments);
            add(expected, task, "deployment.agent.helper.v1", business, helper(verb, values), input,
                Duration.ofSeconds(seconds), verb.equals("agent-run") ? 1048576 : 4096, executable, Optional.empty());
        }

        private void publication(List<Proof> expected, TaskContext task, String business, String operation, String verb,
                                 List<String> arguments, long seconds) {
            empty(expected, task, "deployment.publication." + operation + ".v1", business, helper(verb, arguments), seconds, 4096);
        }

        private void empty(List<Proof> expected, TaskContext task, String operation, String business, String script,
                           long seconds, long limit) {
            add(expected, task, operation, business, script, new byte[0], Duration.ofSeconds(seconds), limit, Optional.empty(), Optional.empty());
        }

        private void add(List<Proof> expected, TaskContext task, String operation, String business, String script,
                         byte[] input, Duration timeout, long limit, Optional<String> executable, Optional<String> installed) {
            try (var request = new CommandRequest(script, input, timeout, limit)) {
                expected.add(new Proof(task.id(), operation, business, RemoteActions.command(new ServerId("server"), business, request).exactAction(),
                    executable.isPresent() ? "EXECUTABLE_SCRIPT" : input.length == 0 ? "EMPTY" : "OPAQUE_BOUND_DATA", executable, installed));
            }
        }

        private String helper(String verb, List<String> arguments) {
            var values = new ArrayList<>(List.of(DeploymentManagedHelperBundle.PATH, verb));
            values.addAll(arguments);
            return quoted(values);
        }

        private String quoted(List<String> arguments) {
            return arguments.stream().map(this::quote).collect(java.util.stream.Collectors.joining(" "));
        }

        private String quote(String value) {
            assertFalse(value.contains("'"), "Known fixture scalars have no shell interpolation");
            return "'" + value + "'";
        }

        private String sha(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (java.security.NoSuchAlgorithmException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private String proposal(int turn, JsonNode data) {
        if (highInitialInstaller && turn == 3) {
            assertTrue(java.util.stream.StreamSupport.stream(data.path("observations").spliterator(), false)
                .anyMatch(value -> value.path("tool").asText().equals("LOWER_RISK_REQUIRED")
                    && value.path("output").asText().contains("was not sent")));
            assertTrue(proposedRemoteRevisions.get().isEmpty());
            assertEquals(1, lowerRiskFeedback.incrementAndGet());
            return command("api", "printf artifact > $WTL_OUTPUT/run2.sh");
        }
        if (riskJourney != null) {
            if (turn == 2) return command("api", HIGH_SCRIPT);
            if (turn == 3) {
                var observations = data.path("observations");
                assertTrue(observations.isArray());
                assertTrue(java.util.stream.StreamSupport.stream(observations.spliterator(), false)
                    .anyMatch(value -> value.path("tool").asText().equals("LOWER_RISK_REQUIRED") && value.path("output").asText().contains("was not sent")));
                assertEquals(1, lowerRiskFeedback.incrementAndGet());
                assertEquals(riskAuthorization.equals("FULL") ? 0 : 1, fixedCommands.denied.get());
                assertFalse(data.path("state").path("remoteRevisions").has("web"));
                if (riskAuthorization.equals("FULL")) assertTrue(proposedRemoteRevisions.get().isEmpty());
                else assertEquals(Map.of("api", "c".repeat(64)), proposedRemoteRevisions.get());
                if (riskJourney == RiskJourney.REPEAT) return command("api", HIGH_SCRIPT);
            }
            if (riskJourney == RiskJourney.REPEAT && turn > 3)
                throw new AssertionError("The refused script must stop within two stalled turns");
            if (turn >= 3) turn--;
        }
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
        return command(component, "printf artifact > $WTL_OUTPUT/run.sh");
    }

    private static String command(String component, String script) {
        return "{\"tool\":\"command\",\"arguments\":{\"component\":\"" + component
            + "\",\"script\":\"" + script + "\",\"seconds\":10}}";
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
                    .find(new DeploymentApplicationId("demo", new ServerId("server")), task);
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
                var repository = new DeploymentExecutionStore(context.db());
                var record = repository.find(task, executionId).orElseThrow();
                assertEquals("FINISHED", record.phase());
                assertEquals(Optional.of(TaskResult.SUCCEEDED), record.outcome());
                assertTrue(record.businessCommitted());
                assertTrue(record.cleanupComplete());
                var template = repository.publicationTemplate(record).orElseThrow();
                assertEquals(data.graph(), template.graph());
                assertEquals(1, template.units().size());
                assertEquals("demo-api", template.units().getFirst().unit().id());
                assertEquals(publishedRelease, template.units().getFirst().release().sha256());
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            } catch (Exception | AssertionError problem) {
                failure.set(problem);
                throw problem;
            }
        });
        assertEquals(TaskResult.SUCCEEDED, read.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome(),
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
