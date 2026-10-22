package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
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
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperReadiness;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHostProbe;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishArguments;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentRuntimeArguments;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentWorkloadArguments;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.control.DeploymentAgentControl;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentHistoryReader;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationReader;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises real CLASS ownership, source snapshots, channels and H2; the SSH peer is controlled. / 验证真实 CLASS 归属、来源快照、频道及 H2；SSH 对等端受控。 */
class DeploymentAgentWorkflowTest extends DeploymentRuntimeFixture {
    enum ScenarioType {
        SUCCESS, WHOLE_HEALTH_FAILURE, COMMAND_UNKNOWN, PUBLISH_UNKNOWN, APPROVAL_DRIFT, AI_APPROVAL_DRIFT, APPROVAL_MISSING, DUPLICATE_JSON, TRAILING_JSON, INVALID_TOOL, REPEATED_BROWSE, REPEATED_FEEDBACK, FEEDBACK_AFTER_REJECTION, NOVEL_READ_PROGRESS, CANCELLED, BUDGET, HISTORY_INSPECT, MODEL_REFRESH, STATIC_DENIAL_REPEAT
    }

    private final AtomicReference<String> revision = new AtomicReference<>("a".repeat(64));

    private final AtomicInteger calls = new AtomicInteger();

    private boolean baselineJourney;

    private boolean baselineInspectProposed;

    private boolean baselineCheckProposed;

    private final List<Integer> budgets = new CopyOnWriteArrayList<>();

    private boolean fourCallAttemptBudget;

    private boolean missingSourceQuery;

    private static final String STATIC_DENIED_SCRIPT = "rm -rf /\nprintf fixture-sensitive-static-body";

    private boolean staticDenialJourney, staticDenialRepeat;

    private String staticDeniedCallId;

    private com.fasterxml.jackson.databind.node.ObjectNode staticDeniedArguments;

    private int staticDenialFeedbackRequests, staticDenialWireRequests, staticDeniedNativeCalls;

    private int staticDenialBeforeSends, staticDenialBeforeApprovals;

    private boolean budgetPreflightJourney;

    private String budgetRejectedCallId;

    private int budgetFeedbackRequests, budgetWireRequests;

    private final com.fasterxml.jackson.databind.node.ObjectNode budgetCorrectionFacts = new ObjectMapper().createObjectNode();

    private boolean hostEnvelopeDenialJourney;

    private static final String HOST_ENVELOPE_DENIED_SCRIPT = "# fixture-private-host-marker\nrm -rf .";

    private static final String HOST_ENVELOPE_LEGAL_SCRIPT = "builtin printf 'WTL_CONTROLLED_HOST_PREREQUISITE_OK\\n'";

    private static final String HOST_ENVELOPE_REASON = "Controlled host prerequisite";

    private String hostEnvelopeRejectedCallId;

    private TaskContext hostEnvelopeTask;

    private int hostEnvelopeFeedbackRequests, hostEnvelopeWireRequests, hostEnvelopeIndependentReviews;

    private int hostEnvelopeRejectedSends, hostEnvelopeLegalSends, hostEnvelopeReceiptQueries;

    private final Map<String, String> hostEnvelopeReceipts = new ConcurrentHashMap<>();

    private final List<JsonNode> hostEnvelopeCheckpointSnapshots = new CopyOnWriteArrayList<>();

    private final com.fasterxml.jackson.databind.node.ObjectNode hostEnvelopeFacts = new ObjectMapper().createObjectNode();

    private boolean differentPlanRejections;

    private int planRejectionTurns;

    private final List<String> approvals = new CopyOnWriteArrayList<>();

    private ScenarioType scenario;
    enum FinalizationFault { FILES, FORMAL, ARCHIVE }
    private FinalizationFault finalizationFault;
    private final AtomicBoolean finalizationFaultInjected = new AtomicBoolean();

    @ParameterizedTest
    @EnumSource(FinalizationFault.class)
    void successfulPublicationWithUnconfirmedFinalizationStillBlocksSameTargetAfterRestart(FinalizationFault fault) throws Exception {
        for (String phase : List.of("produce", "reopen")) {
            var output = root.resolve("finalization-" + fault + "-" + phase + ".log");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                "-Djdk.net.unixdomain.tmpdir=" + root, "-cp", System.getProperty("java.class.path"),
                DeploymentAgentWorkflowTest.class.getName(), phase, root.toString(), fault.name())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertTrue(process.waitFor(150, TimeUnit.SECONDS), "Finalization worker timed out: " + phase);
                assertEquals(0, process.exitValue(), Files.readString(output));
                assertTrue(Files.readString(output).contains("FINALIZATION_" + phase.toUpperCase(Locale.ROOT) + "_VERIFIED"));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned finalization worker must exit");
                }
            }
        }
    }

    public static void main(String[] arguments) throws Exception {
        var fixture = new DeploymentAgentWorkflowTest();
        fixture.root = Path.of(arguments[1]);
        fixture.finalizationFault = FinalizationFault.valueOf(arguments[2]);
        if (arguments[0].equals("produce")) fixture.verifyScenario(ScenarioType.SUCCESS);
        else {
            assertEquals("reopen", arguments[0]);
            var before = new ObjectMapper().readTree(Files.readString(fixture.root.resolve("finalization.json")));
            try (var runtime = fixture.open()) {
                var pages = new gold.debug.windowstolinux.app.ui.mod.ModPageRegistry(new gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageHost() {
                    public void show(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key, gold.debug.windowstolinux.appapi.ui.page.PageSpec spec, javax.swing.JComponent component) { throw new AssertionError("No page is opened during recovery verification"); }
                    public void focus(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key) { }
                    public void remove(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key) { }
                    public void environment(gold.debug.windowstolinux.appapi.ui.page.UiEnvironment environment) { }
                }, new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
                var report = runtime.startDevelopmentMods(fixture.workspace(), pages);
                assertFalse(report.problems().isEmpty());
                assertTrue(report.problems().stream().allMatch(problem -> problem.code() == ProblemCode.BUSY
                    && problem.eventCode().equals("MOD_RECOVERY_READY")), report.problems().toString());
                var original = runtime.tasks().snapshots().stream().filter(task -> task.id().toString().equals(before.path("task").asText())).findFirst().orElseThrow();
                assertEquals(TaskState.UNKNOWN, original.state());
                var actual = runtime.modContexts().get(OWNER).context();
                assertEquals(gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode.RECOVERY, actual.launchMode());
                var workflow = new DeploymentAgentWorkflow(actual);
                long admittedTasks = fixture.scalar(runtime, "SELECT COUNT(*) FROM platform_task");
                var denied = assertThrows(ApiException.class, () -> workflow.submitLocal(new ServerId("server"),
                    new DeploymentAgentInputs.Request(Map.of("goal", "same target must stay occupied"), "FULL_CONTROL", false, 65536, 100, 65536),
                    new gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService.LocalLocation(
                        fixture.selectedSourceDirectory().toUri(), "input")));
                assertEquals(ProblemCode.UNAVAILABLE, denied.problem().code());
                assertEquals("TASK_ADMISSION_CLOSED", denied.problem().eventCode());
                assertEquals(admittedTasks, fixture.scalar(runtime, "SELECT COUNT(*) FROM platform_task"));
                assertEquals(before.path("agentRows").asLong(), fixture.modScalar(runtime, "SELECT COUNT(*) FROM deployment_agent_task"));
                assertEquals(1, fixture.modScalar(runtime, "SELECT COUNT(*) FROM deployment_execution WHERE outcome='SUCCEEDED' AND business_committed=TRUE"));
                assertEquals(0, fixture.modScalar(runtime, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                System.out.println("FINALIZATION_REOPEN_VERIFIED fault=" + fixture.finalizationFault + " original=" + original.id() + " blocker=" + denied.problem().eventCode());
                System.exit(0); // Preserve the original unresolved task; do not invent fixture recovery.
            }
        }
    }

    private boolean finalizerFrame(String type, String method) {
        return Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame -> frame.getClassName().endsWith(type)
            && frame.getMethodName().equals(method));
    }

    private final AtomicReference<DeploymentAgentWorkflow> liveWorkflow = new AtomicReference<>();

    private final AtomicReference<CompletableFuture<Void>> modelRefresh = new AtomicReference<>();

    private final List<String> commandReviews = new CopyOnWriteArrayList<>();

    /** Actual exact manual command proposals under the original root. / 原根下实际精确人工命令提案。 */
    private final List<ActionIntent> commandIntents = new CopyOnWriteArrayList<>();

    private final List<UUID> commandReviewTasks = new CopyOnWriteArrayList<>();

    private final AtomicReference<FileSnapshot> frozenMaterial = new AtomicReference<>();

    private final AtomicInteger independentReviews = new AtomicInteger();

    private final Map<String, StringBuilder> nativeApprovalFacts = new HashMap<>();

    private final Map<String, StringBuilder> nativeApprovalBodies = new HashMap<>();

    private final Map<String, Long> uploadedArchiveSizes = new ConcurrentHashMap<>();

    private final Map<UUID, FileSnapshot> reviewSnapshots = new ConcurrentHashMap<>();

    private final Map<UUID, Path> capturedSnapshotPaths = new ConcurrentHashMap<>();

    private long controlledWorkspaceBytes = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().workspaceBytes();

    private final Set<String> nativeApprovalPageReceipts = new HashSet<>();

    private int nativeApprovalEvidencePages, nativeApprovalPagedReviews, nativeApprovalMultiPageReviews, nativeApprovalVerdicts;

    /** Original algorithm fixture worker and its last proposed protected source identity. / 原算法夹具工作任务及其最近提案的受保护来源身份。 */
    private final AtomicReference<UUID> originalAiTask = new AtomicReference<>();

    private final AtomicReference<String> proposedSourceRevision = new AtomicReference<>();

    /** Actual per-component protected revisions offered to the planning worker. / 实际提供给计划工作线程的各组件受保护修订。 */
    private final AtomicReference<Map<String, String>> proposedRemoteRevisions = new AtomicReference<>(Map.of());

    private enum RiskJourney {REPLACE, REPEAT}

    private RiskJourney riskJourney;

    private boolean independentCommandDenial;

    private boolean invalidCompleteCommandReview;

    private boolean completeDenialApprovalDrift;

    private String selectedApprovalPolicy;

    private static final String INDEPENDENT_DENIAL_REASON = "Retain snapshots.db and choose a different output path.\napi_key=fixture-private-review-value\n"
        + "evidence ".repeat(100);

    private final AtomicInteger denialToolFeedback = new AtomicInteger();

    private boolean rememberAuthorization = true;

    private static final String HIGH_SCRIPT = "rm -rf ./build-cache";

    private final AtomicInteger highQuestions = new AtomicInteger(), highCandidateReviews = new AtomicInteger();

    private final AtomicInteger lowerRiskFeedback = new AtomicInteger();

    /** First installer refusal is independent of an ordinary free-script risk. / 首次安装器拒绝与普通自由脚本风险分开。 */
    private boolean highInitialInstaller;

    /** A later refused open retains the genuinely created original candidate. / 后续拒绝打开保留真实已创建的原候选。 */
    private boolean highPreparedOpen;

    private final AtomicInteger installerReviews = new AtomicInteger(), installerQuestions = new AtomicInteger();

    private final AtomicBoolean bootstrapFilesAvailable = new AtomicBoolean();

    private final AtomicInteger readinessSends = new AtomicInteger(), filesInstallerSends = new AtomicInteger();

    private final AtomicInteger aiApprovalDrifts = new AtomicInteger();

    private boolean singleUnsupported;

    private Boolean editFrozenSource;

    private final AtomicReference<Path> frozenDirectory = new AtomicReference<>();

    private boolean cancelAfterCommit;

    private final AtomicReference<TaskHandle<?>> boundaryHandle = new AtomicReference<>();

    private final AtomicBoolean cancelledKnownCommit = new AtomicBoolean();

    private final AtomicReference<Map<String, String>> committedCurrent = new AtomicReference<>();

    /** Exercises actual preselection followed by fresh root rememberAuthorization and one freeze. / 验证实际预选后根任务重新授权及一次冻结。 */
    private boolean preselectedSource;

    private final AtomicReference<UUID> sourceTask = new AtomicReference<>();

    private final AtomicInteger sourceSnapshots = new AtomicInteger(), sourceArchives = new AtomicInteger();

    private final List<String> boundarySends = new CopyOnWriteArrayList<>();

    private final List<String> modelScripts = new CopyOnWriteArrayList<>();

    private final AtomicInteger publicationDecisions = new AtomicInteger();

    private final AtomicInteger proposalDecisions = new AtomicInteger();

    private boolean independentIdentification;

    private String progressBrowseTool = "read";

    private boolean emptyProgressPage;

    private boolean sourceReadPagination;

    private boolean missingRecipeAnalysis;

    private int preflightSends = -1, preflightApprovals = -1;

    private String invalidCapabilityAssessmentTool;

    private boolean invalidCapabilityAssessmentFeedback;

    private final AtomicInteger candidateUploads = new AtomicInteger();

    private Long candidateAvailableBytes;

    private boolean planCapacityApiFailure;

    private boolean planCapacityBreakdownJourney;

    private String planCapacityRejectedCall, planCapacityAcceptedCall;

    private boolean planCapacityLegalPlanObserved;

    private int planCapacityFeedbackRequests;

    private final AtomicReference<JsonNode> planCapacityBreakdown = new AtomicReference<>();

    private final AtomicReference<String> planCapacityRequestSha = new AtomicReference<>(), planCapacityResultSha = new AtomicReference<>();

    private final com.fasterxml.jackson.databind.node.ObjectNode planCapacityBindingFacts = new ObjectMapper().createObjectNode();

    private static final String CAPACITY_API_EVENT = "CONTROLLED_CAPACITY_API_FAILURE";

    private static final String CAPACITY_API_MESSAGE = "password=fixture-private-capacity-value";

    private final AtomicReference<String> capacityApiRequestSha = new AtomicReference<>();

    private final AtomicReference<UUID> capacityApiTask = new AtomicReference<>();

    private final AtomicReference<ApiException> capacityApiOriginalFailure = new AtomicReference<>();

    private final AtomicInteger candidateCapacitySends = new AtomicInteger(), reviewedCandidateCreates = new AtomicInteger();

    private final AtomicReference<String> firstReadPageDigest = new AtomicReference<>();

    private boolean evictedFeedbackRecovery;

    private boolean evictedRejectionFeedback;

    private Boolean distinctRejectedParameters;

    private final List<String> rejectedParameterPaths = new ArrayList<>();

    private final List<String> sameTextRejectionFeedback = new ArrayList<>();

    private final AtomicReference<String> previouslyQueriedBody = new AtomicReference<>();

    /** Validates model evidence separately from actual opaque host grants. / 分别核验模型依据与真实不透明宿主许可。 */
    private FixedCommandFixture fixedCommands;

    /** Carries strict peer failures through the host's UNKNOWN classification. / 将严格对等端失败原因带过宿主的UNKNOWN分类。 */
    private final AtomicReference<Throwable> fixtureFailure = new AtomicReference<>();

    private final List<String> rejectedModelFeedback = new CopyOnWriteArrayList<>();

    private boolean acceptNativeFailure;

    private boolean continueNativeFailure;

    private boolean nativeFailureRouteJourney;

    private final AtomicReference<JsonNode> nativeFailureNextStep = new AtomicReference<>();

    private final AtomicReference<JsonNode> nativeFailureResultEvidence = new AtomicReference<>();

    private final AtomicBoolean nativeFailureContinuationOffered = new AtomicBoolean();

    private final AtomicReference<UUID> successorRoot = new AtomicReference<>();

    private DeploymentAgentMainOnlyFixture successorDiagnostic;

    private final List<UUID> successorReviewBindings = new CopyOnWriteArrayList<>();

    private final Map<UUID, Integer> successorLanguageCalls = new ConcurrentHashMap<>();

    private final AtomicBoolean childFailureFactsObserved = new AtomicBoolean();

    private String failureCleanupMode;

    private final List<Path> failureSourceDirectories = new CopyOnWriteArrayList<>();

    private final AtomicInteger failureRemoteClosed = new AtomicInteger();

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
                    assertNotNull(rememberAuthorization);
                    assertEquals(originalAiTask.get(), task.id());
                    assertFalse(token.requested());
                    assertEquals(1, installerQuestions.incrementAndGet());
                    return false;
                }
                if ((riskJourney != null || highPreparedOpen) && fixedCommands.isHigh(intent, task.id())) {
                    assertNotNull(rememberAuthorization);
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
        "MODEL_REFRESH", "PUBLISH_UNKNOWN", "APPROVAL_DRIFT", "AI_APPROVAL_DRIFT"}, mode = EnumSource.Mode.EXCLUDE)
    void originalTaskUsesActualChannelsAndRetainsUnknownEffects(ScenarioType selected) throws Exception {
        verifyScenario(selected);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ScenarioType.class, names = {"APPROVAL_DRIFT", "AI_APPROVAL_DRIFT"})
    void originalChangedReviewRouteStaysBoundThroughReconciliation(ScenarioType selected) throws Exception {
        verifyScenario(selected);
    }

    @Test
    void agentExecutesConfiguredDistinctSourceQueriesAcrossConfiguredModelResponsesThenStops() throws Exception {
        fourCallAttemptBudget = true;
        verifyScenario(ScenarioType.BUDGET);
        assertEquals(2048, nativeIds, "The original session receives exactly four calls per response");
        assertEquals(512, calls.get(), "The configured main response allowance stops the original session");
        assertEquals(java.util.stream.IntStream.range(0, 512).map(i -> 511 - i).boxed().toList(), budgets);
        assertTrue(boundarySends.isEmpty(), "Actual local source queries do not create server authority or dispatch");
    }

    @Test
    void selectsSharedGraphPublicationThenIndependentlyVerifiesAndFinishes() throws Exception {
        sharedPublication = true;
        controlledWorkspaceBytes = 1073741824L;
        verifyScenario(ScenarioType.SUCCESS);
        assertEquals(2, readinessSends.get());
        assertEquals(1, filesInstallerSends.get());
    }

    @Test
    void modelCreatesOneFreshSuccessorAfterRealFailedCleanupAndGoalReferencesSuccessfulChild() throws Exception {
        sharedPublication = true;
        acceptNativeFailure = true;
        continueNativeFailure = true;
        controlledWorkspaceBytes = 1073741824L;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void nativeFailedPublicationNextStepOffersGoalContinuationAndAttemptOnlyFinish() throws Exception {
        nativeProtocol = gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS;
        sharedPublication = true;
        acceptNativeFailure = true;
        nativeFailureRouteJourney = true;
        controlledWorkspaceBytes = 1073741824L;
        verifyScenario(ScenarioType.SUCCESS);
        assertTrue(nativeFailureContinuationOffered.get(), "The actual restored-failure request offers the original Goal continuation tool");
        var nextStep = Objects.requireNonNull(nativeFailureNextStep.get(), "The actual restored-failure request must expose nextStep");
        var routes = java.util.stream.StreamSupport.stream(nextStep.path("routes").spliterator(), false)
            .map(JsonNode::asText).toList();
        assertTrue(routes.stream().anyMatch(route -> route.contains("continue_deployment") && route.contains("optional")),
            "The actual nextStep must present offered continue_deployment as an optional route subject to original admission");
        assertTrue(routes.stream().anyMatch(route -> route.contains("finish_failure") && route.contains("only this attempt")),
            "The actual nextStep must distinguish ending this failed attempt from accomplishing the deployment goal");
        var evidence = Objects.requireNonNull(nativeFailureResultEvidence.get(), "Read actual original task events before successful failure cleanup removes them");
        var events = java.util.stream.StreamSupport.stream(evidence.path("events").spliterator(), false).toList();
        var nativeEvents = events.stream().filter(event -> event.path("type").asText().equals("AI_PUBLICATION_NATIVE_RESULT")).toList();
        assertEquals(1, nativeEvents.size(), "The actual native failed publication must persist its safe result cause before the next model request");
        var nativeEvent = nativeEvents.getFirst();
        var metadata = nativeEvent.path("metadata");
        var fields = new HashSet<String>();
        metadata.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("phase", "outcome", "problemCode", "problemEventCodeSha256", "rollbackConfirmed", "task", "publicationEvent", "rollbackEvent"), fields);
        assertEquals("publish", metadata.path("phase").asText());
        assertEquals("FAILED", metadata.path("outcome").asText());
        assertEquals("INCOMPATIBLE", metadata.path("problemCode").asText());
        assertEquals(gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash("DEPLOYMENT_HEALTH_FAILED"),
            metadata.path("problemEventCodeSha256").asText());
        assertTrue(metadata.path("rollbackConfirmed").asBoolean());
        assertEquals(evidence.path("task").asText(), metadata.path("task").asText());
        var publication = events.stream().filter(event -> event.path("type").asText().equals("GRAPH_PROPOSAL")).findFirst().orElseThrow();
        var rollback = events.stream().filter(event -> event.path("type").asText().equals("GRAPH_ROLLBACK_RESULT")).findFirst().orElseThrow();
        assertEquals(publication.path("id").asLong(), metadata.path("publicationEvent").asLong());
        assertEquals(rollback.path("id").asLong(), metadata.path("rollbackEvent").asLong());
        assertEquals(publication.path("action").asText(), nativeEvent.path("action").asText());
        assertEquals(publication.path("action").asText(), rollback.path("action").asText());
        assertTrue(rollback.path("restored").asBoolean());
        assertTrue(publication.path("id").asLong() < rollback.path("id").asLong());
        assertTrue(rollback.path("id").asLong() < nativeEvent.path("id").asLong());
    }

    @Test
    void planCapacityTransportFailurePersistsSafeOriginalProblemAndPreservesUnknown() throws Exception {
        nativeProtocol = gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS;
        planCapacityApiFailure = true;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void modelAcceptsActualNativeRollbackThenOriginalTaskEndsFailedAfterAllCleanup() throws Exception {
        sharedPublication = true;
        acceptNativeFailure = true;
        controlledWorkspaceBytes = 1073741824L;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status-unknown", "foreign-candidate", "absence-unknown", "reference-unknown"})
    void acceptedNativeFailureKeepsOriginalTaskUnknownWhenCleanupCannotBeProven(String mode) throws Exception {
        sharedPublication = true;
        acceptNativeFailure = true;
        controlledWorkspaceBytes = 1073741824L;
        failureCleanupMode = mode;
        var blocked = assertThrows(ApiException.class, () -> verifyScenario(ScenarioType.SUCCESS));
        assertEquals("MOD_STOP_BLOCKED", blocked.problem().eventCode());
    }

    @Test
    void readyHelperIsReusedWithoutInstallingOrRegisteringRecovery() throws Exception {
        bootstrapFilesAvailable.set(true);
        sharedPublication = true;
        verifyScenario(ScenarioType.SUCCESS);
        assertEquals(1, readinessSends.get());
        assertEquals(0, filesInstallerSends.get());
        assertEquals(2, reviewedCandidateCreates.get(), "Both small-source candidates use the configured workspace bytes in independently reviewed exact requests");
    }

    @Test
    void candidateCapacityReservesPublicationCopyBeforeCreatingOrUploading() throws Exception {
        bootstrapFilesAvailable.set(true);
        candidateAvailableBytes = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().workspaceBytes();
        var busy = assertThrows(ApiException.class, () -> verifyScenario(ScenarioType.SUCCESS));
        assertEquals(ProblemCode.BUSY, busy.problem().code());
        assertEquals("MOD_STOP_BLOCKED", busy.problem().eventCode());
        assertNull(fixtureFailure.get());
        assertEquals(1, candidateCapacitySends.get());
        assertEquals(0, reviewedCandidateCreates.get());
        assertEquals(0, candidateUploads.get());
        assertTrue(boundarySends.stream().noneMatch(script -> script.contains("candidate-create-task")
            || script.contains("'agent-open'") || script.contains("'agent-run'")));
    }

    @Test
    void planHostCapacityRefusalExposesBoundBreakdownForIndependentModelChoice() throws Exception {
        nativeProtocol = gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS;
        planCapacityBreakdownJourney = sharedPublication = true;
        controlledWorkspaceBytes = 1073741824L;
        candidateAvailableBytes = 4 * controlledWorkspaceBytes + gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().workspacePolicy().reserveBytes();
        verifyScenario(ScenarioType.SUCCESS);
    }

    private void assertPlanCapacityBreakdown() throws Exception {
        assertEquals(1, planCapacityFeedbackRequests);
        var metadata = Objects.requireNonNull(planCapacityBreakdown.get());
        assertTrue(metadata.isObject(), "The original rejected call must retain complete bounded DATA for the next independent model choice");
        assertEquals("WORKSPACE_HOST_INSUFFICIENT", metadata.path("code").asText());
        for (var entry : List.of("task", "sourceSha256", "configurationSha256", "layoutSha256", "capacityRequestSha256", "capacityResultSha256"))
            assertEquals(planCapacityBindingFacts.path(entry), metadata.path(entry), "Original observation binding: " + entry);
        assertEquals(candidateAvailableBytes.longValue(), metadata.path("availableBytes").asLong());
        long reserve = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().workspacePolicy().reserveBytes();
        assertEquals(reserve, metadata.path("hostReserveBytes").asLong());
        assertEquals(8 * controlledWorkspaceBytes + reserve, metadata.path("requiredBytes").asLong());
        assertEquals(8 * controlledWorkspaceBytes, metadata.path("aggregateHostCostBytes").asLong());
        assertEquals("AGENT", metadata.path("cost").asText());
        assertEquals(2, metadata.path("hostCostMultiplier").asInt());
        assertEquals(2, metadata.path("componentCount").asInt());
        assertFalse(metadata.path("truncated").asBoolean());
        assertTrue(metadata.path("newInputsNotCreatedByRejectedPlan").asBoolean());
        var inputs = new ArrayList<gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input>();
        for (var component : metadata.path("components")) {
            assertTrue(Set.of("api", "web").contains(component.path("id").asText()));
            assertEquals("EXPLICIT", component.path("mode").asText());
            assertEquals(0, component.path("minimumBuildBytes").asLong());
            assertEquals(0, component.path("preferredBuildBytes").asLong());
            assertEquals(2 * controlledWorkspaceBytes, component.path("explicitBytes").asLong());
            long sourceFloor = gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.sourceFloor(
                metadata.path("archiveBytes").asLong(), metadata.path("unpackedBytes").asLong(), 2);
            assertEquals(sourceFloor, component.path("sourceFloorBytes").asLong());
            inputs.add(new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input(component.path("id").asText(),
                metadata.path("archiveBytes").asLong(), sourceFloor,
                gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.explicit(2 * controlledWorkspaceBytes)));
        }
        assertEquals(2, inputs.size());
        assertEquals(evidenceDigestBytes(new ObjectMapper().writeValueAsString(inputs)), metadata.path("inputsSha256").asText());
        var fields = new HashSet<String>();
        metadata.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("code", "task", "sourceSha256", "configurationSha256", "layoutSha256", "capacityRequestSha256", "capacityResultSha256",
            "inputsSha256", "archiveBytes", "unpackedBytes", "availableBytes", "hostReserveBytes", "requiredBytes", "aggregateHostCostBytes", "cost", "hostCostMultiplier",
            "componentCount", "components", "truncated", "newInputsNotCreatedByRejectedPlan"), fields);
        assertEquals(0, reviewedCandidateCreates.get());
        assertEquals(0, candidateUploads.get());
        System.out.println("PLAN_CAPACITY_BREAKDOWN_VERIFIED " + metadata);
    }

    @Test
    void compressedSourceFootprintIsRejectedBeforeAnyHelperOrCapacityRequest() throws Exception {
        long workspace = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().workspaceBytes();
        verifySourceCapacityRejection(1, workspace / 2 + 1);
    }

    @Test
    void overflowingSourceFootprintIsRejectedBeforeAnyHelperOrCapacityRequest() throws Exception {
        verifySourceCapacityRejection(1, Long.MAX_VALUE);
    }

    private void verifySourceCapacityRejection(long compressedBytes, long uncompressedBytes) throws Exception {
        scenario = ScenarioType.SUCCESS;
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("README.md"), "Controlled immutable source\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile.fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var actual = runtime.modContexts().get(OWNER).context();
            var peer = new DeploymentAgentTestPeer(ScenarioType.SUCCESS);
            var bound = bind(actual, tasks, peer, scriptedAi());
            var failure = new AtomicReference<Throwable>();
            var handle = tasks.submit(new TaskRequest("agent-source-capacity", "Check source footprint before dispatch", List.of("server"), true), task -> {
                try {
                    var selected = bound.file().sources().selectDirectory(selectedSourceDirectory(), task);
                    try (var material = new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceSetup(bound.file())
                        .prepare(selected, 65536, 100, task);
                         var archive = new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiver(bound.file())
                             .prepare(material, 65536, task)) {
                        var measured = new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive(archive.snapshot(), archive.sha256(),
                            compressedBytes, uncompressedBytes, archive.originSnapshotId(), archive.sourceManifestSha256(), archive.relativeRoot());
                        var declaration = new ObjectMapper().readTree(plan()).path("arguments");
                        declaration.path("components").forEach(node -> {
                            var component = (com.fasterxml.jackson.databind.node.ObjectNode) node;
                            component.put("sourceRoot", ".");
                            component.putArray("databases");
                            component.putArray("secretReferences");
                        });
                        var part = gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlanProtocol
                            .parse(declaration).components().getFirst();
                        int sourceApprovals = approvals.size();
                        try (var remote = peer.session(task);
                             var tools = new gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentRemoteTools(bound,
                                 remote, bound.interaction(), new ServerId("server"), task, measured, material, revision.get(), revision::get,
                                 new DeploymentAgentActionLog(bound.db()), Optional.empty(), "MANUAL_REVIEW", false)) {
                            var rejected = assertThrows(IllegalStateException.class, () -> tools.prepare("demo", part));
                            assertTrue(rejected.getMessage().contains("source"), "The local source capacity guard reports its own rejection");
                        }
                        assertEquals(sourceApprovals, approvals.size(), "Source selection authority cannot authorize a preparation request");
                    }
                    assertEquals(0, peer.commands.get());
                    assertEquals(0, readinessSends.get());
                    assertEquals(0, filesInstallerSends.get());
                    assertEquals(0, candidateCapacitySends.get());
                    assertEquals(0, candidateUploads.get());
                    assertTrue(boundarySends.isEmpty());
                    assertEquals(0, fixedCommands.reviews.get());
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of("Local source footprint refused before dispatch"), Optional.empty());
                } catch (Exception | AssertionError problem) {
                    failure.set(problem);
                    throw problem;
                } finally {
                    try {
                        bound.file().closeTaskResources(task);
                    } catch (Exception cleanup) {
                        failure.compareAndSet(null, cleanup);
                        throw cleanup;
                    }
                }
            });
            waitReturned(handle, 20);
            assertNull(failure.get(), () -> "Actual source-capacity task failure: " + failure.get());
            assertEquals(TaskState.ENDED, handle.snapshot().state(), diagnostic(runtime, handle));
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome(),
                () -> String.valueOf(failure.get()));
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void agentBaselineInspectPrepareAndCheckPublishWithoutBroadToolQueries() throws Exception {
        baselineJourney = true;
        sharedPublication = true;
        verifyScenario(ScenarioType.SUCCESS);
        assertTrue(baselineInspectProposed);
        assertTrue(baselineCheckProposed);
        assertEquals(4, boundarySends.stream().filter(DeploymentAgentHostProbe.baseline()::equals).count(),
            "Inspect, check and both actual component preparations use the same limited Agent observation");
        assertTrue(nativeApprovalPagedReviews > 0, "Independent approval reads the actual complete facts through native evidence receipts");
        assertTrue(nativeApprovalMultiPageReviews > 0, "Large executable evidence is reconstructed from all actual pages before review");
        assertTrue(nativeApprovalEvidencePages > nativeApprovalPagedReviews);
        assertEquals(nativeApprovalPagedReviews, nativeApprovalVerdicts, "Every completed independent review submits the current six-field native verdict");
        assertTrue(nativeApprovalFacts.isEmpty(), "Completed approval investigations retain no unfinished page collection");
        assertEquals(1, filesInstallerSends.get(), "The canonical Agent bootstrap is dispatched once through its original host grant");
        assertEquals(2, readinessSends.get(), "The installed helper is freshly verified after the original bootstrap");
    }

    @Test
    void independentlyIdentifiesThenEvaluatesInapplicableStaticToolAndUsesOwnCommand() throws Exception {
        compareStaticTool = true;
        verifyScenario(ScenarioType.SUCCESS);
        assertTrue(toolObserved);
    }

    @Test
    void missingSourceQueryReturnsFeedbackAndContinuesWithinOriginalBudget() throws Exception {
        missingSourceQuery = true;
        singleUnsupported = true;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void invalidNativeParametersReturnFeedbackAndStopAtOriginalStalledBound() throws Exception {
        verifyScenario(ScenarioType.INVALID_TOOL);
        assertEquals(2, calls.get(), "Two rejected native batches reach the original stalled bound");
        assertEquals(List.of(79, 78), budgets, "Native parameter errors consume the original budget");
    }

    @Test
    void prepareToolsExtraAssessmentIsRejectedBeforeCandidateOrRemoteEffects() throws Exception {
        invalidCapabilityAssessmentTool = "prepare_tools";
        verifyScenario(ScenarioType.NOVEL_READ_PROGRESS);
        assertTrue(invalidCapabilityAssessmentFeedback);
        assertEquals(0, candidateUploads.get());
        assertEquals(preflightSends, boundarySends.size());
        assertEquals(preflightApprovals, approvals.size());
    }

    @Test
    void remoteDatabaseInspectExtraAssessmentIsRejectedBeforeCandidateOrRemoteEffects() throws Exception {
        invalidCapabilityAssessmentTool = "database_inspect";
        verifyScenario(ScenarioType.NOVEL_READ_PROGRESS);
        assertTrue(invalidCapabilityAssessmentFeedback);
        assertEquals(0, candidateUploads.get());
        assertEquals(preflightSends, boundarySends.size());
        assertEquals(preflightApprovals, approvals.size());
    }

    @Test
    void differentParametersWithSameRejectionContinueToSuccessfulDeployment() throws Exception {
        distinctRejectedParameters = true;
        verifyScenario(ScenarioType.SUCCESS);
        assertEquals(List.of(".", "api"), rejectedParameterPaths);
        assertEquals(List.of("TOOL_REJECTED: Missing parameter: offset", "TOOL_REJECTED: Missing parameter: offset"),
            sameTextRejectionFeedback);
        assertEquals(List.of(79, 78, 77), budgets.subList(0, 3), "Both rejected attempts consume the original budget");
    }

    @Test
    void identicalParametersWithSameRejectionStillStopAfterTwoAttempts() throws Exception {
        distinctRejectedParameters = false;
        verifyScenario(ScenarioType.INVALID_TOOL);
        assertEquals(List.of(".", "."), rejectedParameterPaths);
        assertEquals(List.of("TOOL_REJECTED: Missing parameter: offset"), sameTextRejectionFeedback);
        assertEquals(2, calls.get(), "The original session stops before a third model request");
        assertEquals(List.of(79, 78), budgets);
    }

    @ParameterizedTest
    @EnumSource(gold.debug.windowstolinux.app.ai.model.ModelProtocol.class)
    void staticDenialReturnsBoundNativeFeedbackBeforeModelChoosesLegalCommand(gold.debug.windowstolinux.app.ai.model.ModelProtocol protocol) throws Exception {
        nativeProtocol = protocol;
        staticDenialJourney = true;
        verifyScenario(ScenarioType.SUCCESS);
        assertEquals(1, staticDenialFeedbackRequests);
        assertEquals(1, staticDenialWireRequests);
        assertEquals(3, independentReviews.get(), "Only the original three actual legal build commands reach complete independent review");
        assertTrue(boundarySends.stream().noneMatch(script -> script.contains("fixture-sensitive-static-body")));
    }

    @ParameterizedTest
    @EnumSource(gold.debug.windowstolinux.app.ai.model.ModelProtocol.class)
    void identicalStaticDenialsStopWithinOriginalNativeAttemptBound(gold.debug.windowstolinux.app.ai.model.ModelProtocol protocol) throws Exception {
        nativeProtocol = protocol;
        staticDenialJourney = staticDenialRepeat = true;
        verifyScenario(ScenarioType.STATIC_DENIAL_REPEAT);
        assertEquals(5, calls.get(), "READ, identification and plan precede two identical rejected commands");
        assertEquals(2, staticDeniedNativeCalls, "The original bound stops before a third identical command");
        assertEquals(1, staticDenialFeedbackRequests);
        assertEquals(1, staticDenialWireRequests);
        assertEquals(0, independentReviews.get());
        assertEquals(0, candidateUploads.get());
        assertEquals(staticDenialBeforeSends, boundarySends.size());
        assertEquals(staticDenialBeforeApprovals, approvals.size());
    }

    @Test
    void goalBudgetPreflightRejectionAllowsIndependentTimeoutCorrectionAndNormalCommit() throws Exception {
        nativeProtocol = gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS;
        budgetPreflightJourney = sharedPublication = true;
        controlledWorkspaceBytes = 1073741824L;
        verifyScenario(ScenarioType.SUCCESS);
        assertEquals(1, budgetFeedbackRequests);
        assertEquals(1, budgetWireRequests);
        assertEquals(0, budgetCorrectionFacts.path("firstAgentRunSends").asInt());
        assertEquals(0, budgetCorrectionFacts.path("firstIndependentReviews").asInt());
        assertFalse(budgetCorrectionFacts.path("unknownAtCorrection").asBoolean());
        assertTrue(budgetCorrectionFacts.path("innerEffectsSettledBeforeFinish").asBoolean());
        assertEquals(1, budgetCorrectionFacts.path("formalAppCount").asInt());
        assertEquals(1, budgetCorrectionFacts.path("formalReleaseCount").asInt());
    }

    @Test
    void hostEnvelopeStaticDenialReturnsBoundFeedbackBeforeIndependentLegalCommandAndCommit() throws Exception {
        nativeProtocol = gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS;
        hostEnvelopeDenialJourney = sharedPublication = true;
        assertDoesNotThrow(() -> gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate
            .requireFixedAllowed(HOST_ENVELOPE_DENIED_SCRIPT));
        verifyScenario(ScenarioType.SUCCESS);
        assertEquals(1, hostEnvelopeFeedbackRequests);
        assertEquals(1, hostEnvelopeWireRequests);
        assertEquals(0, hostEnvelopeRejectedSends);
        assertEquals(1, hostEnvelopeIndependentReviews);
        assertEquals(1, hostEnvelopeLegalSends);
        assertEquals(1, hostEnvelopeReceiptQueries);
        assertEquals(1, hostEnvelopeFacts.path("formalAppCount").asInt());
        assertEquals(1, hostEnvelopeFacts.path("formalReleaseCount").asInt());
    }

    @Test
    void evictedLatestRejectionRemainsActualCorrectionDataWithoutCreatingQueryableEvidence() throws Exception {
        evictedRejectionFeedback = true;
        verifyScenario(ScenarioType.INVALID_TOOL);
        assertEquals(2, calls.get(), "The actual same rejection still reaches the original stalled bound");
        assertEquals(List.of(79, 78), budgets);
        assertTrue(commandReviews.isEmpty());
        assertTrue(boundarySends.isEmpty());
    }

    @Test
    void exhaustedReasoningOutputStopsWithoutAnyNativeActionOrPaidRetry() throws Exception {
        exhaustedNativeOutput = true;
        verifyScenario(ScenarioType.INVALID_TOOL);
        assertEquals(1, calls.get());
        assertTrue(commandReviews.isEmpty());
        assertTrue(boundarySends.isEmpty());
    }

    @Test
    void unchangedSourcePagesReturnTheirActualIdAndStopWithoutEffects() throws Exception {
        verifyScenario(ScenarioType.REPEATED_BROWSE);
        assertEquals(3, calls.get(), "One actual page and two unchanged attempts reach the original stalled bound");
        assertEquals(List.of(79, 78, 77), budgets);
    }

    @Test
    void unchangedRetainedFeedbackAlsoStopsAtTheOriginalProgressBound() throws Exception {
        verifyScenario(ScenarioType.REPEATED_FEEDBACK);
        assertEquals(4, calls.get(), "The first retained page is readable; two repeats cannot refresh progress");
        assertEquals(List.of(79, 78, 77, 76), budgets);
    }

    @Test
    void evictedFeedbackRecoversOriginalIdentityWithoutResettingProgressOrBudget() throws Exception {
        evictedFeedbackRecovery = true;
        verifyScenario(ScenarioType.REPEATED_FEEDBACK);
        assertEquals(7, calls.get(), "A dropped body can recover; the next visible repeat reaches the existing two-repeat bound");
        assertEquals(List.of(79, 78, 77, 76, 75, 74, 73), budgets, "Recovery consumes the original total budget");
    }

    @Test
    void successfulBodyRecoveryAllowsTheNextProposalItsOwnCorrectionOpportunity() throws Exception {
        verifyScenario(ScenarioType.FEEDBACK_AFTER_REJECTION);
        assertEquals(5, calls.get(), "READ, rejected reread, successful original-body recovery, then two consecutive identify rejections");
        assertEquals(List.of(79, 78, 77, 76, 75), budgets);
    }

    @Test
    void differentPlanValidationFailuresAllowCorrectionsBeforeExecution() throws Exception {
        differentPlanRejections = true;
        verifyScenario(ScenarioType.SUCCESS);
        assertEquals(2, planRejectionTurns, "A corrected assessment exposes a distinct READ gate before the valid plan executes");
    }

    @Test
    void newActualSourceEvidenceCountsAsProgressWithoutResettingTheTotalBudget() throws Exception {
        verifyScenario(ScenarioType.NOVEL_READ_PROGRESS);
        assertEquals(5, calls.get(), "Rejected findings separated by genuinely new reads can continue investigating");
        assertEquals(List.of(79, 78, 77, 76, 75), budgets);
    }

    @Test
    void sourceReadPagesExposeUnreadTailWhileObservationKeepsOriginalPageIdentity() throws Exception {
        sourceReadPagination = true;
        verifyScenario(ScenarioType.NOVEL_READ_PROGRESS);
        assertEquals(5, calls.get());
        assertEquals(List.of(79, 78, 77, 76, 75), budgets);
    }

    @Test
    void emptySourceReadMetadataDoesNotResetRejectedProposalBound() throws Exception {
        emptyProgressPage = true;
        verifyScenario(ScenarioType.NOVEL_READ_PROGRESS);
        assertEquals(3, calls.get(), "A real EOF page with metadata but no source lines is not read progress");
        assertEquals(List.of(79, 78, 77), budgets);
    }

    @Test
    void checkedHostCannotReplaceAnalysisOrPrepareAnyCandidateBeforeRecipeAdmission() throws Exception {
        missingRecipeAnalysis = true;
        verifyScenario(ScenarioType.NOVEL_READ_PROGRESS);
        assertEquals(6, calls.get(), "READ, identify, plan and real check precede two local recipe preflight refusals");
        assertEquals(List.of(79, 78, 77, 76, 75, 74), budgets);
        assertEquals(preflightSends, boundarySends.size());
        assertEquals(preflightApprovals, approvals.size());
        assertTrue(commandReviews.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "search"})
    void newSourceDiscoveryCountsAsProgressWithoutResettingTheTotalBudget(String tool) throws Exception {
        progressBrowseTool = tool;
        verifyScenario(ScenarioType.NOVEL_READ_PROGRESS);
        assertEquals(5, calls.get(), "Actual new directory or search evidence permits further investigation");
        assertEquals(List.of(79, 78, 77, 76, 75), budgets);
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "search"})
    void emptySourceDiscoveryCannotResetTheStalledBound(String tool) throws Exception {
        progressBrowseTool = tool;
        emptyProgressPage = true;
        verifyScenario(ScenarioType.NOVEL_READ_PROGRESS);
        assertEquals(3, calls.get(), "An empty new page cannot turn rejected findings into progress");
        assertEquals(List.of(79, 78, 77), budgets);
    }

    @Test
    void currentPublicationNeedsIndependentFinishAndLegacyRecoveryDoesNotSendAgain() throws Exception {
        verifyScenario(ScenarioType.PUBLISH_UNKNOWN);
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
     * @param rememberAuthorization original rememberAuthorization repetition / 原授权重复策略
     * @throws Exception on actual workflow or fixture failure / 实际流程或夹具失败时 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void automaticOnceOrGeneralRefusesExactHighThenIndependentlyReviewsAndPublishesDifferentLow(boolean rememberAuthorization) throws Exception {
        riskJourney = RiskJourney.REPLACE;
        rememberAuthorization = rememberAuthorization;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void automaticRememberedModeRespectsActualRefusalAndPublishesAlternative() throws Exception {
        riskJourney = RiskJourney.REPLACE;
        rememberAuthorization = true;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void automaticRetainsPreparedCandidateUntilOriginalRecoveryWhenRefusedScriptRepeats() throws Exception {
        riskJourney = RiskJourney.REPEAT;
        rememberAuthorization = true;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void independentDenialReturnsRedactedReasonThenReviewsAndPublishesDifferentScript() throws Exception {
        independentCommandDenial = true;
        riskJourney = RiskJourney.REPLACE;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL"})
    void independentDenialCannotReviewOrSendTheSameScriptAgain(String policy) throws Exception {
        independentCommandDenial = true;
        selectedApprovalPolicy = policy;
        riskJourney = RiskJourney.REPEAT;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void invalidCompleteCommandReviewNeverSendsAgentRunAndRetainsOriginalRecovery() throws Exception {
        invalidCompleteCommandReview = true;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void approvalConfigurationChangeDuringCompleteDenialRemainsHardStop() throws Exception {
        completeDenialApprovalDrift = true;
        independentCommandDenial = true;
        riskJourney = RiskJourney.REPEAT;
        verifyScenario(ScenarioType.SUCCESS);
    }

    /** Refuses the exact first installer without any remote effect or repeated review shopping. / 拒绝精确首次安装器，不产生远端效果或重复审核寻优。
     * @param rememberAuthorization original explicit repetition strategy / 原明确重复策略
     * @throws Exception on actual root or strict evidence failure / 实际根或严格依据失败时 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstHighInstallerStopsAfterReadOnlyReadinessWithoutInstallationOrRepeatedApproval(boolean rememberAuthorization) throws Exception {
        highInitialInstaller = true;
        rememberAuthorization = rememberAuthorization;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @Test
    void highOpenAfterCandidateCreationKeepsUnknownPreparationAndOriginalResources() throws Exception {
        highPreparedOpen = true;
        rememberAuthorization = true;
        var busy = assertThrows(ApiException.class, () -> verifyScenario(ScenarioType.SUCCESS));
        assertEquals(ProblemCode.BUSY, busy.problem().code());
        assertEquals("MOD_STOP_BLOCKED", busy.problem().eventCode());
        assertNull(fixtureFailure.get());
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
                var handle = page.submitLocal(new ServerId("server"), new DeploymentAgentInputs.Request(
                    Map.of("goal", "Read the selected original source"), "MANUAL_REVIEW", true, 65_536, 100, 65_536), location);
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
                var handle = workflow.submitLocal(new ServerId("server"), new DeploymentAgentInputs.Request(
                    Map.of("goal", "Build the observed api and web"), "MANUAL_REVIEW", true, 65536, 100, 65536));
                boundaryHandle.set(handle);
                waitReturned(handle);
                assertNotNull(frozenDirectory.get());
                assertNotEquals(selectedSourceDirectory().toRealPath(),
                    frozenDirectory.get().toAbsolutePath().normalize());
                if (Boolean.TRUE.equals(editFrozenSource)) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertTrue(handle.snapshot().outcome().isEmpty());
                    assertEquals(0, commandReviews.size(),
                        "Changed frozen bytes stop the actual agent-run before its complete command review");
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
                        assertEquals(2, peer.modelPublications.size());
                        assertEquals(sharedPublication, boundarySends.stream().anyMatch(script -> script.contains("'publish-deployment'")));
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

    private DeploymentAgentWorkflow successorWorkflow(ModContext bound) {
        successorDiagnostic = new DeploymentAgentMainOnlyFixture(bound, new ServerId("server"));
        return new DeploymentAgentWorkflow(bound, (owner, task, server, source, configuration) -> {
            successorReviewBindings.add(task.id());
            var original = successorDiagnostic.bind(owner, task, server, source, configuration);
            return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReviewSource.Bound() {
                public void verify(ModContext actual, TaskContext supplied, ServerId target, String revision, String model) {
                    original.verify(actual, supplied, target, revision, model);
                }

                public gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel.Result review(
                    String instructions, Map<String, ?> facts, ActionIntent intent, CommandRequest request, String revision, TaskContext supplied)
                    throws ApiException, java.io.IOException, gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException {
                    // Existing strict fixture independently reconstructs exact script/body/nonce/task before the real host grant.
                    var json = new ObjectMapper();
                    fixedCommands.review(json.readTree(json.writeValueAsString(facts)), supplied);
                    return original.review(instructions, facts, intent, request, revision, supplied);
                }

                public gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision action(ModContext actual, String type,
                                                                                                          String action, Map<String, ?> facts, ServerId target, String revision, TaskContext supplied) throws Exception {
                    return original.action(actual, type, action, facts, target, revision, supplied);
                }
            };
        });
    }

    private DeploymentRemoteWorkspace fixtureWorkspace(TaskContext task, String unit, String archive) {
        try {
            var journal = new DeploymentAgentActionLog(fixedCommands.actual.db());
            return DeploymentAgentWorkspaceBinding.workspace(task.id(), journal.find(task).orElseThrow(), journal.events(task), unit, archive,
                gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
        } catch (Exception invalid) {
            throw new AssertionError("Fixture cannot reconstruct the exact persisted workspace binding", invalid);
        }
    }

    private void verifySuccessorGoal(PlatformRuntime runtime, DeploymentAgentPageService page, TaskService tasks,
                                     DeploymentAgentWorkflow workflow, TaskHandle<?> original, DeploymentAgentTestPeer peer) throws Exception {
        assertEquals(Optional.of(TaskResult.FAILED), original.snapshot().outcome(), diagnostic(runtime, original));
        var goal = workflow.goal(original.id()).orElseThrow();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (!goal.completion().toCompletableFuture().isDone() && System.nanoTime() < until) {
            var observed = goal.observation();
            if (observed.state().equals("BLOCKED"))
                fail(observed + "\n" + diagnostic(runtime, goal.currentHandle().orElseThrow()));
            Thread.sleep(25);
        }
        var complete = goal.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(TaskResult.SUCCEEDED, complete.result().outcome(), complete.toString());
        assertEquals(original.id(), complete.root());
        assertEquals(List.of(original.id()), complete.predecessors());
        assertNotEquals(original.id(), complete.finalTask());
        var child = goal.currentHandle().orElseThrow();
        assertEquals(complete.finalTask(), child.id());
        assertEquals(Optional.of(TaskResult.SUCCEEDED), child.snapshot().outcome());
        assertEquals(TaskState.ENDED, child.snapshot().state());
        assertEquals(Optional.of(TaskResult.FAILED), original.snapshot().outcome(), "Goal success never rewrites the original handle");
        assertEquals(List.of(original.id(), child.id()), goal.observation().tasks());
        assertEquals(List.of(original.id(), child.id()), successorReviewBindings, "The same diagnostic factory binds a fresh independent task conversation");
        assertTrue(childFailureFactsObserved.get());
        assertEquals(0, successorDiagnostic.approvalAttempts());
        assertTrue(successorDiagnostic.decisions() > 0);
        assertEquals(1, modScalar(runtime, "SELECT COUNT(*) FROM managed_application"));
        assertEquals(1, modScalar(runtime, "SELECT COUNT(*) FROM deployment_execution WHERE platform_task_id='" + original.id()
            + "' AND phase='FINISHED' AND outcome='FAILED' AND business_committed=FALSE AND cleanup_complete=TRUE"));
        assertEquals(1, modScalar(runtime, "SELECT COUNT(*) FROM deployment_operation_receipt WHERE platform_task_id='" + child.id() + "'"));
        assertEquals(0, modScalar(runtime, "SELECT COUNT(*) FROM deployment_execution WHERE platform_task_id='" + child.id() + "'"));
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        assertTrue(peer.present.values().stream().noneMatch(Boolean::booleanValue));
        assertEquals(Set.of("demo-api", "demo-web"), peer.current.keySet());
        assertEquals(List.of("demo-api"), peer.rollbacks);
        assertEquals(1, failureRemoteClosed.get());
        assertTrue(failureSourceDirectories.stream().noneMatch(Files::exists));
        assertTrue(capturedSnapshotPaths.values().stream().noneMatch(Files::exists));
        assertEquals(4, capturedSnapshotPaths.size(), "Original and child each own a new material and archive snapshot");
        var history = page.continuationHistory(original.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow();
        assertEquals(1, history.size());
        assertEquals("CLAIMED", history.getFirst().phase());
        assertEquals(child.id(), history.getFirst().child());
        assertEquals(original.id(), history.getFirst().intent().predecessor());
        var events = page.events(original.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
        assertEquals(1, events.stream().filter(event -> event.type().equals("CONTINUATION_RESERVED")).count());
        assertEquals(1, events.stream().filter(event -> event.type().equals("CONTINUATION_CLAIMED") && event.detail().equals(child.id().toString())).count());
        assertTrue(events.stream().noneMatch(event -> event.type().equals("AI_PUBLICATION_FINISHED")));
        var refusedClear = page.clearSettledHistory(original.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(TaskResult.FAILED, refusedClear.outcome(), "The existing archive requires actual SUCCEEDED; failed predecessors remain immutable");
        assertEquals(events, page.events(original.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events());
        assertEquals(TaskResult.SUCCEEDED, page.clearSettledHistory(child.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        assertEquals(1, modScalar(runtime, "SELECT COUNT(*) FROM managed_application"));
        assertEquals(1, modScalar(runtime, "SELECT COUNT(*) FROM deployment_operation_receipt WHERE platform_task_id='" + child.id() + "'"));
        var allowance = goal.observation().allowance();
        assertEquals(successorLanguageCalls.values().stream().mapToInt(Integer::intValue).sum(), allowance.mainResponses() + allowance.nestedResponses());
        DeploymentAgentContinuation.requireCarry(history.getFirst().settledAllowance(), allowance);
        var archived = tasks.submit(new TaskRequest("goal-archive-query", "Read both actual goal conclusions", List.of(), true), currentTask -> {
            assertEquals(Optional.of(TaskResult.FAILED), tasks.findArchived(original.id(), currentTask).orElseThrow().outcome());
            assertEquals(Optional.of(TaskResult.SUCCEEDED), tasks.findArchived(child.id(), currentTask).orElseThrow().outcome());
            return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
        });
        assertEquals(TaskResult.SUCCEEDED, archived.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        assertNull(fixtureFailure.get());
    }

    private void verifyScenario(ScenarioType selected) throws Exception {
        scenario = selected;
        Files.createDirectories(selectedSourceDirectory());
        String sourceText = fourCallAttemptBudget
            ? java.util.stream.IntStream.range(0, 2048).mapToObj(index -> "Actual distinct source fact " + index)
            .collect(java.util.stream.Collectors.joining("\n")) + "\n"
            : sourceReadPagination
            ? java.util.stream.IntStream.rangeClosed(1, 134).mapToObj(line -> "actual source line " + line)
            .collect(java.util.stream.Collectors.joining("\n")) + "\nTAIL_ENTRYPOINT_FACT\n"
            : singleUnsupported
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
            var workflow = continueNativeFailure ? successorWorkflow(bound) : new DeploymentAgentWorkflow(bound);
            liveWorkflow.set(workflow);
            try (var registration = tasks.registerRecovery(DeploymentAgentWorkflow.TASK_TYPE, workflow);
                 var queries = tasks.registerRecovery(DeploymentAgentPageService.SERVER_LIST_TASK,
                     new DeploymentAgentPageService(bound, workflow))) {
                var page = new DeploymentAgentPageService(bound, workflow);
                var location = new gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService.LocalLocation(
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
                var handle = page.submitLocal(new ServerId("server"), new DeploymentAgentInputs.Request(
                    Map.of("goal",
                        singleUnsupported
                            ? "Build the observed unsupported api"
                            : "Build the observed api and web"),
                    selectedApprovalPolicy != null ? selectedApprovalPolicy : riskJourney != null || highInitialInstaller || highPreparedOpen ? "AUTOMATIC" : singleUnsupported
                        ? "FULL_CONTROL"
                        : selected == ScenarioType.AI_APPROVAL_DRIFT
                        || selected == ScenarioType.APPROVAL_MISSING ? "AUTOMATIC" : "MANUAL_REVIEW",
                    selected != ScenarioType.APPROVAL_DRIFT && rememberAuthorization,
                    fourCallAttemptBudget ? 1048576 : 65536, 100,
                    fourCallAttemptBudget ? 4194304 : 65536), location);
                try {
                    waitReturned(handle, finalizationFault != null || sharedPublication ? 120 : fourCallAttemptBudget ? 180 : 40);
                } catch (AssertionError timeout) {
                    throw new AssertionError(diagnostic(runtime, handle), timeout);
                }
                if (finalizationFault != null) {
                    assertNull(fixtureFailure.get());
                    assertTrue(finalizationFaultInjected.get(), diagnostic(runtime, handle) + "\n"
                        + new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(bound).read(handle.id()));
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                    assertEquals(2, peer.current.size(), "Both actual controlled publications completed before the local finalizer failed");
                    assertEquals(1, modScalar(runtime, "SELECT COUNT(*) FROM deployment_execution WHERE outcome='SUCCEEDED' AND business_committed=TRUE"));
                    assertEquals(0, modScalar(runtime, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                    var before = new ObjectMapper().createObjectNode().put("task", handle.id().toString())
                        .put("fault", finalizationFault.name()).put("agentRows", modScalar(runtime, "SELECT COUNT(*) FROM deployment_agent_task"));
                    Files.writeString(root.resolve("finalization.json"), before.toString());
                    System.out.println("FINALIZATION_PRODUCE_VERIFIED " + before);
                    System.exit(0);
                }
                if (planCapacityBreakdownJourney) {
                    assertTrue(planCapacityLegalPlanObserved);
                    assertNull(fixtureFailure.get());
                    assertEquals(2, candidateCapacitySends.get());
                    assertEquals(0, reviewedCandidateCreates.get());
                    assertEquals(0, candidateUploads.get());
                    assertTrue(peer.present.isEmpty());
                    assertTrue(peer.current.isEmpty());
                    assertEquals(0, modScalar(runtime, "SELECT COUNT(*) FROM managed_application"));
                    var events = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    var fixtureTask = handle.id();
                    var fixtureRoot = root.toRealPath();
                    System.out.println("PLAN_CAPACITY_BREAKDOWN_CONTROLLED_STOP task=" + fixtureTask + " state=" + handle.snapshot().state()
                        + " legalPlanObserved=true publication=0 creates=0 uploads=0");
                    try {
                        assertPlanCapacityBreakdown();
                    } finally {
                        assertTrue(fixtureRoot.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()));
                        assertTrue(root.resolve("data").toRealPath().startsWith(fixtureRoot));
                        if (handle.snapshot().state() == TaskState.UNKNOWN) {
                            registration.close();
                            try (var teardown = tasks.registerRecovery(DeploymentAgentWorkflow.TASK_TYPE, (previous, originalTask) -> {
                                assertTrue(planCapacityBreakdownJourney);
                                assertEquals(fixtureRoot, root.toRealPath());
                                assertEquals(fixtureTask, previous.id());
                                assertEquals(fixtureTask, originalTask.id());
                                assertEquals(TaskState.UNKNOWN, previous.state());
                                bound.file().closeTaskResources(originalTask);
                                assertTrue(tasks.resourcesReleased(originalTask));
                                return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(
                                    new Problem(ProblemCode.IO_FAILURE, "CONTROLLED_LOCAL_TEARDOWN", "Only this local plan-feedback fixture is ended", Map.of(), Optional.empty())));
                            })) {
                                assertEquals(TaskState.ENDED, reconcileReady(runtime, handle).state());
                            }
                            assertEquals(events, page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events());
                        }
                        assertEquals(TaskState.ENDED, handle.snapshot().state());
                        assertEquals(Optional.of(TaskResult.FAILED), handle.snapshot().outcome());
                        assertEquals(2, candidateCapacitySends.get());
                        assertEquals(0, candidateUploads.get());
                        System.out.println("PLAN_CAPACITY_BREAKDOWN_FIXTURE_TEARDOWN task=" + fixtureTask + " state=ENDED outcome=FAILED remoteReplay=0");
                    }
                    return;
                }

                if (planCapacityApiFailure) {
                    assertNull(fixtureFailure.get(), "The controlled fault must follow a real original fixed review");
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                    assertEquals(3, calls.get(), "Capacity uncertainty stops after read, identify and plan without another model request");
                    assertEquals(1, candidateCapacitySends.get());
                    assertEquals(handle.id(), capacityApiTask.get());
                    assertEquals(0, candidateUploads.get());
                    assertEquals(0, peer.commands.get());
                    assertTrue(peer.present.isEmpty());
                    assertFalse(handle.completion().toCompletableFuture().isDone(), "UNKNOWN preserves the original occupied task rather than completing it");
                    assertEquals(1, modScalar(runtime, "SELECT COUNT(*) FROM deployment_agent_task WHERE task_id='" + handle.id() + "' AND task_state='UNKNOWN'"));
                    assertEquals(0, modScalar(runtime, "SELECT COUNT(*) FROM managed_application"));
                    var events = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    assertTrue(events.stream().noneMatch(event -> Set.of("PREPARE_EXECUTING", "CANDIDATE", "COMMAND_EXECUTING", "COMMAND_NOT_SENT", "AI_PUBLICATION_NATIVE_RESULT").contains(event.type())),
                        "Original UNKNOWN is neither a build dispatch nor a NOT_SENT or publication proof");
                    var diagnostics = events.stream().filter(event -> event.type().equals("AI_CANDIDATE_COMMAND_API_FAILURE")).toList();
                    var safe = new ObjectMapper().createObjectNode().put("task", handle.id().toString()).put("state", handle.snapshot().state().name())
                        .put("languageRequests", calls.get()).put("capacityExecutions", candidateCapacitySends.get()).put("diagnosticCount", diagnostics.size())
                        .put("requestSha256", capacityApiRequestSha.get()).put("originalCode", capacityApiOriginalFailure.get().problem().code().name())
                        .put("originalEventSha256", evidenceDigestBytes(CAPACITY_API_EVENT)).put("originalMessageSha256", evidenceDigestBytes(CAPACITY_API_MESSAGE));
                    System.out.println("PLAN_CAPACITY_API_DIAGNOSTIC " + safe);
                    assertEquals(1, diagnostics.size(), "Persist the execute API cause before the shared channel replaces it with UNKNOWN");
                    var diagnostic = diagnostics.getFirst();
                    assertEquals(capacityApiRequestSha.get(), diagnostic.action());
                    var metadata = new ObjectMapper().readTree(diagnostic.detail());
                    var fields = new HashSet<String>();
                    metadata.fieldNames().forEachRemaining(fields::add);
                    assertEquals(Set.of("category", "exceptionClass", "problemCode", "problemEventCodeSha256", "messageSha256", "exactRequestSha256", "task"), fields);
                    assertEquals("CANDIDATE_EXECUTE", metadata.path("category").asText());
                    assertEquals("ApiException", metadata.path("exceptionClass").asText());
                    assertEquals("IO_FAILURE", metadata.path("problemCode").asText());
                    assertEquals(evidenceDigestBytes(CAPACITY_API_EVENT), metadata.path("problemEventCodeSha256").asText());
                    assertEquals(evidenceDigestBytes(CAPACITY_API_MESSAGE), metadata.path("messageSha256").asText());
                    assertEquals(capacityApiRequestSha.get(), metadata.path("exactRequestSha256").asText());
                    assertEquals(handle.id().toString(), metadata.path("task").asText());
                    assertFalse(diagnostic.detail().contains(CAPACITY_API_MESSAGE));
                    assertFalse(diagnostic.detail().contains(CAPACITY_API_EVENT));
                    assertEquals(CAPACITY_API_MESSAGE, capacityApiOriginalFailure.get().getMessage());
                    System.out.println("PLAN_CAPACITY_API_UNKNOWN_VERIFIED " + safe.set("metadata", metadata));
                    // Explicit local fixture teardown follows the retained UNKNOWN assertions; it is no product recovery proof.
                    var fixtureRoot = root.toRealPath();
                    var fixtureTask = handle.id();
                    assertTrue(fixtureRoot.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()));
                    assertTrue(root.resolve("data").toRealPath().startsWith(fixtureRoot));
                    assertEquals(fixtureTask, capacityApiTask.get());
                    registration.close();
                    try (var teardown = tasks.registerRecovery(DeploymentAgentWorkflow.TASK_TYPE, (previous, originalTask) -> {
                        assertTrue(planCapacityApiFailure);
                        assertEquals(fixtureRoot, root.toRealPath());
                        assertEquals(fixtureTask, previous.id());
                        assertEquals(fixtureTask, originalTask.id());
                        assertEquals(TaskState.UNKNOWN, previous.state());
                        bound.file().closeTaskResources(originalTask);
                        assertTrue(tasks.resourcesReleased(originalTask));
                        return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(
                            new Problem(ProblemCode.IO_FAILURE, "CONTROLLED_LOCAL_TEARDOWN", "Only this local fixture is ended after its UNKNOWN assertions", Map.of(), Optional.empty())));
                    })) {
                        assertEquals(TaskState.ENDED, reconcileReady(runtime, handle).state());
                    }
                    assertEquals(Optional.of(TaskResult.FAILED), handle.snapshot().outcome());
                    assertEquals(events, page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events());
                    assertEquals(1, candidateCapacitySends.get());
                    assertEquals(3, calls.get());
                    System.out.println("PLAN_CAPACITY_API_FIXTURE_TEARDOWN task=" + fixtureTask + " state=ENDED outcome=FAILED remoteReplay=0");
                    return;
                }
                if (hostEnvelopeDenialJourney) {
                    hostEnvelopeFacts.put("finalTaskState", handle.snapshot().state().name())
                        .put("languageRequests", calls.get()).put("feedbackRequests", hostEnvelopeFeedbackRequests)
                        .put("rejectedSends", hostEnvelopeRejectedSends).put("legalSends", hostEnvelopeLegalSends)
                        .put("independentHostReviews", hostEnvelopeIndependentReviews).put("receiptQueries", hostEnvelopeReceiptQueries)
                        .put("formalAppCount", modScalar(runtime, "SELECT COUNT(*) FROM managed_application"))
                        .put("formalReleaseCount", modScalar(runtime, "SELECT COUNT(*) FROM deployment_release"));
                    var result = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    hostEnvelopeFacts.put("outcome", result.outcome().name());
                    result.problem().ifPresent(problem -> hostEnvelopeFacts.put("problemCode", problem.code().name())
                        .put("eventCodeSha256", evidenceDigestBytes(problem.eventCode())));
                    var snapshots = hostEnvelopeFacts.putArray("checkpointSnapshots");
                    hostEnvelopeCheckpointSnapshots.forEach(snapshots::add);
                    var rejectedCheckpoint = hostEnvelopeCheckpointSnapshots.stream()
                        .filter(entry -> entry.path("body").asText().equals(evidenceDigestBytes(HOST_ENVELOPE_DENIED_SCRIPT)))
                        .findFirst();
                    if (rejectedCheckpoint.isPresent()) {
                        var entry = rejectedCheckpoint.orElseThrow();
                        String executable = DeploymentModelCommandIdentity.envelope(handle.id(), UUID.fromString(entry.path("operation").asText()),
                            entry.path("body").asText(), HOST_ENVELOPE_DENIED_SCRIPT,
                            "Host prerequisite only; no application publication: " + HOST_ENVELOPE_REASON);
                        var rejected = assertThrows(SecurityException.class, () -> gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate
                            .requireFixedAllowed(executable));
                        assertEquals("DEPLOYMENT_COMMAND_STATIC_DENIED", rejected.getMessage());
                        hostEnvelopeFacts.put("actualOperationEnvelopeGateCode", rejected.getMessage())
                            .put("actualOperationEnvelopeSha256", evidenceDigestBytes(executable));
                    }
                    var diagnostics = hostEnvelopeFacts.putArray("safeDiagnostics");
                    for (var record : new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(bound).read(handle.id()).records()) {
                        var event = record.event();
                        if (!Set.of("DEPLOYMENT_AGENT_FAILURE", "DEPLOYMENT_AGENT_TOOL_REJECTED", "DEPLOYMENT_AGENT_SESSION_STOPPED").contains(event.eventCode()))
                            continue;
                        diagnostics.addObject().put("eventCode", event.eventCode()).put("messageSha256", evidenceDigestBytes(event.message()))
                            .put("apiException", event.message().startsWith("ApiException:"))
                            .put("staticDenied", event.message().contains("DEPLOYMENT_COMMAND_STATIC_DENIED"));
                    }
                    System.out.println("HOST_ENVELOPE_DENIAL_BASELINE " + hostEnvelopeFacts);
                    assertEquals(1, hostEnvelopeFeedbackRequests,
                        "Known static refusal of the original host envelope before dispatch must reach its original call and permit a new model decision");
                }
                if (budgetPreflightJourney) {
                    try (var access = scope(runtime, true); var connection = access.openConnection();
                         var query = connection.prepareStatement("SELECT event_id,event_type,action_id,event_detail FROM deployment_agent_event WHERE task_id=? ORDER BY event_id")) {
                        query.setString(1, handle.id().toString());
                        var retained = budgetCorrectionFacts.putArray("terminalEvents");
                        try (var rows = query.executeQuery()) {
                            while (rows.next()) {
                                var event = retained.addObject().put("id", rows.getLong(1)).put("type", rows.getString(2))
                                    .put("actionSha256", evidenceDigestBytes(rows.getString(3))).put("detailSha256", evidenceDigestBytes(rows.getString(4)));
                                if (rows.getString(2).equals("AI_PUBLICATION_ERROR_METADATA"))
                                    event.set("safeErrorMetadata", new ObjectMapper().readTree(rows.getString(4)));
                            }
                        }
                    }
                    budgetCorrectionFacts.put("finalTaskState", handle.snapshot().state().name())
                        .put("formalAppCount", modScalar(runtime, "SELECT COUNT(*) FROM managed_application"))
                        .put("formalReleaseCount", modScalar(runtime, "SELECT COUNT(*) FROM deployment_release"));
                    if (fixtureFailure.get() != null) {
                        var failure = fixtureFailure.get();
                        budgetCorrectionFacts.put("fixtureFailureClass", failure.getClass().getSimpleName())
                            .put("fixtureFailureMessageSha256", evidenceDigestBytes(String.valueOf(failure.getMessage())));
                        for (var fixtureLocation : failure.getStackTrace())
                            if (fixtureLocation.getClassName().equals(DeploymentAgentWorkflowTest.class.getName() + "$FixedCommandFixture")) {
                                budgetCorrectionFacts.put("fixtureFailureSourceMethod", fixtureLocation.getMethodName()).put("fixtureFailureSourceLine", fixtureLocation.getLineNumber());
                                break;
                            }
                    }
                    var diagnostics = budgetCorrectionFacts.putArray("safeDiagnostics");
                    for (var record : new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(bound).read(handle.id()).records()) {
                        var event = record.event();
                        if (Set.of("DEPLOYMENT_AI_USAGE", "DEPLOYMENT_AGENT_OBSERVATION", "DEPLOYMENT_STAGE").contains(event.eventCode()))
                            continue;
                        diagnostics.addObject().put("eventCode", event.eventCode().matches("DEPLOYMENT_[A-Z_]{1,120}") ? event.eventCode() : "NON_DEPLOYMENT_CODE")
                            .put("messageSha256", evidenceDigestBytes(String.valueOf(event.message())));
                    }
                    System.out.println("BUDGET_CORRECTION_BASELINE " + budgetCorrectionFacts);
                    assertFalse(budgetCorrectionFacts.path("unknownAtCorrection").asBoolean(),
                        "A known budget rejection before command send must stay correctable without recording UNKNOWN");
                }
                if (continueNativeFailure) {
                    verifySuccessorGoal(runtime, page, tasks, workflow, handle, peer);
                    return;
                }
                if (acceptNativeFailure) {
                    if (failureCleanupMode != null) {
                        assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), diagnostic(runtime, handle));
                        assertTrue(handle.snapshot().outcome().isEmpty());
                        assertEquals(0, modScalar(runtime, "SELECT COUNT(*) FROM managed_application"));
                        assertEquals(1, modScalar(runtime, "SELECT COUNT(*) FROM deployment_execution WHERE platform_task_id='" + handle.id()
                            + "' AND phase='EXECUTING' AND outcome='FAILED' AND business_committed=FALSE AND cleanup_complete=FALSE"));
                        var retained = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                        assertEquals(1, retained.stream().filter(event -> event.type().equals("AI_PUBLICATION_FAILED_ACCEPTED")).count());
                        assertFalse(retained.stream().anyMatch(event -> Set.of("AI_PUBLICATION_FINISHED", "AI_PUBLICATION_FAILED_CLEANED").contains(event.type())));
                        long deletions = boundarySends.stream().filter(script -> script.contains("'candidate-cleanup-task'")).count();
                        assertEquals(failureCleanupMode.equals("absence-unknown") ? 1 : 0, deletions);
                        assertEquals(failureCleanupMode.equals("absence-unknown") ? 1 : 2, peer.present.values().stream().filter(Boolean::booleanValue).count());
                        assertTrue(scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                        assertTrue(peer.current.isEmpty());
                        assertEquals(List.of("demo-api"), peer.rollbacks);
                        assertNull(fixtureFailure.get());
                        var sent = List.copyOf(boundarySends);
                        registration.close();
                        try (var teardown = tasks.registerRecovery(DeploymentAgentWorkflow.TASK_TYPE, (previous, originalTask) -> {
                            if (failureCleanupMode.equals("reference-unknown")) {
                                var store = new DeploymentExecutionStore(actual.db());
                                var record = store.findForTask(originalTask).orElseThrow();
                                for (var entry : store.references(record).entrySet())
                                    actual.servers().resolve(entry.getValue().orElseThrow(), gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult.NOT_REFERENCED, originalTask);
                            }
                            bound.file().closeTaskResources(originalTask);
                            return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                                "CONTROLLED_LOCAL_TEARDOWN", "Only isolated local fixture leases and references were released", Map.of(), Optional.empty())));
                        })) {
                            assertEquals(TaskState.UNKNOWN, reconcileReady(runtime, handle).state());
                        }
                        assertEquals(sent, boundarySends);
                        assertEquals(retained, page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events());
                        return;
                    }
                    assertEquals(TaskState.ENDED, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(Optional.of(TaskResult.FAILED), handle.snapshot().outcome());
                    assertEquals(TaskResult.FAILED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                    assertEquals(0, modScalar(runtime, "SELECT COUNT(*) FROM managed_application"));
                    assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                    assertEquals(1, modScalar(runtime, "SELECT COUNT(*) FROM deployment_execution WHERE platform_task_id='" + handle.id()
                        + "' AND phase='FINISHED' AND outcome='FAILED' AND business_committed=FALSE AND cleanup_complete=TRUE"));
                    var events = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    assertEquals(1, events.stream().filter(event -> event.type().equals("AI_PUBLICATION_FAILED_ACCEPTED")).count());
                    assertEquals(1, events.stream().filter(event -> event.type().equals("AI_PUBLICATION_FAILED_CLEANED")).count());
                    assertTrue(events.stream().anyMatch(event -> event.type().equals("DELIVERY_RESULT") && event.detail().equals("FAILED")));
                    assertFalse(events.stream().anyMatch(event -> event.type().equals("AI_PUBLICATION_FINISHED")));
                    assertTrue(peer.present.values().stream().noneMatch(Boolean::booleanValue));
                    assertTrue(peer.current.isEmpty());
                    assertEquals(List.of("demo-api"), peer.rollbacks);
                    assertEquals(1, failureRemoteClosed.get());
                    assertFalse(failureSourceDirectories.isEmpty());
                    assertTrue(failureSourceDirectories.stream().noneMatch(Files::exists));
                    assertNull(fixtureFailure.get());
                    var archived = tasks.submit(new TaskRequest("failed-original-archive-query", "Read the closed original failed task", List.of(), true), currentTask -> {
                        var original = tasks.findArchived(handle.id(), currentTask).orElseThrow();
                        assertEquals(handle.id(), original.id());
                        assertEquals(TaskState.ENDED, original.state());
                        assertEquals(Optional.of(TaskResult.FAILED), original.outcome());
                        return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                    });
                    assertEquals(TaskResult.SUCCEEDED, archived.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                    return;
                }
                if (preselectedSource) {
                    assertNotEquals(pickerId, handle.id());
                    assertEquals(handle.id(), sourceTask.get());
                    assertEquals(1, sourceSnapshots.get(), "Only the new original root freezes source");
                    assertEquals(1, sourceArchives.get(), "The actual archive derives from that same root freeze");
                }
                if (modelRefresh.get() != null)
                    modelRefresh.get().get(10, TimeUnit.SECONDS);
                if (planCapacityBreakdownJourney && handle.snapshot().state() == TaskState.UNKNOWN) {
                    var stopped = new ObjectMapper().createObjectNode().put("task", handle.id().toString()).put("state", "UNKNOWN");
                    if (fixtureFailure.get() != null) {
                        stopped.put("fixtureClass", fixtureFailure.get().getClass().getSimpleName())
                            .put("fixtureMessageSha256", evidenceDigestBytes(String.valueOf(fixtureFailure.get().getMessage())));
                        var sites = stopped.putArray("fixtureSourceLines");
                        for (var site : fixtureFailure.get().getStackTrace())
                            if (site.getFileName() != null && site.getFileName().equals("DeploymentAgentWorkflowTest.java"))
                                sites.add(site.getLineNumber());
                    }
                    var failures = stopped.putArray("fixedDiagnostics");
                    for (var record : new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(bound).read(handle.id()).records()) {
                        var event = record.event();
                        if (Set.of("DEPLOYMENT_AGENT_FAILURE", "DEPLOYMENT_AGENT_TOOL_REJECTED", "DEPLOYMENT_AGENT_SESSION_STOPPED").contains(event.eventCode()))
                            failures.addObject().put("eventCode", event.eventCode()).put("messageSha256", evidenceDigestBytes(event.message()));
                    }
                    System.out.println("PLAN_CAPACITY_BREAKDOWN_FIXTURE_STATE " + stopped);
                }
                if (candidateAvailableBytes != null && !planCapacityBreakdownJourney) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(0, peer.commands.get());
                    assertTrue(peer.present.isEmpty());
                    assertTrue(peer.current.isEmpty());
                    assertEquals(0, independentReviews.get());
                    assertEquals(1, readinessSends.get());
                    assertEquals(0, filesInstallerSends.get());
                    var evidence = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    assertEquals(1, evidence.stream().filter(event -> event.type().equals("PREPARE_EXECUTING")).count());
                    assertTrue(evidence.stream().noneMatch(event -> Set.of("PREPARE_RESULT", "CANDIDATE", "COMMAND_EXECUTING").contains(event.type())));
                    var sent = List.copyOf(boundarySends);
                    int connections = peer.connections.get();
                    assertEquals(TaskState.UNKNOWN, reconcileReady(runtime, handle).state(), "Production recovery retains an unconfirmed preparation");
                    assertEquals(sent, boundarySends);
                    assertEquals(connections, peer.connections.get());
                    registration.close();
                    // Only fixture teardown releases local leases; it supplies no preparation receipt or deployment outcome.
                    try (var teardown = tasks.registerRecovery(DeploymentAgentWorkflow.TASK_TYPE, (previous, originalTask) -> {
                        assertEquals(handle.id(), previous.id());
                        assertEquals(TaskState.UNKNOWN, previous.state());
                        bound.file().closeTaskResources(originalTask);
                        assertTrue(tasks.resourcesReleased(originalTask));
                        return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(
                            new Problem(ProblemCode.UNKNOWN, "CONTROLLED_LOCAL_TEARDOWN", "Only local fixture leases were released", Map.of(), Optional.empty())));
                    })) {
                        assertEquals(TaskState.UNKNOWN, reconcileReady(runtime, handle).state());
                    }
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                    try (var connection = runtime.persistence().database().openConnection();
                         var query = connection.prepareStatement("SELECT task_id,resource_kind,resource_id,owner_id,access_mode,recovery_json FROM platform_task_resource WHERE task_id=? AND released_at IS NULL ORDER BY resource_kind")) {
                        query.setObject(1, handle.id());
                        try (var rows = query.executeQuery()) {
                            var kinds = new ArrayList<String>();
                            while (rows.next()) {
                                assertEquals(handle.id(), rows.getObject(1, UUID.class));
                                String kind = rows.getString(2);
                                kinds.add(kind);
                                assertEquals(kind.equals("SERVER") ? "EXCLUSIVE" : "SHARED", rows.getString(5));
                                assertEquals("{\"release\":\"TASK_END\"}", rows.getString(6), "No explicit file/session fixture lease may remain");
                                assertEquals(OWNER, rows.getString(4));
                                if (kind.equals("MOD")) assertEquals(OWNER, rows.getString(3));
                                else if (kind.equals("SERVER")) assertEquals("server", rows.getString(3));
                                else assertDoesNotThrow(() -> UUID.fromString(rows.getString(3)));
                            }
                            assertEquals(List.of("MOD", "SCHEMA", "SERVER"), kinds, "UNKNOWN retains only its original task-end admission claims");
                        }
                    }
                    assertEquals(sent, boundarySends);
                    assertEquals(evidence, page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events(),
                        "Local teardown cannot forge a preparation result or alter the product journal");
                    return;
                }
                if (highInitialInstaller) {
                    assertEquals(TaskState.ENDED, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(TaskResult.FAILED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                    assertEquals(6, calls.get(), "The original refusal and first same-script refusal differ; the next identical refusal reaches the existing stalled bound");
                    assertEquals(0, independentReviews.get(), "No command can reach complete execution review before refused preparation");
                    assertEquals(1, installerReviews.get(), "The exact immutable refused installer is independently reviewed only once");
                    assertEquals(1, installerQuestions.get());
                    assertEquals(1, lowerRiskFeedback.get());
                    assertEquals(1, fixedCommands.sends.get());
                    assertEquals(0, peer.commands.get());
                    assertEquals(1, boundarySends.size(), "Only the freshly reviewed read-only readiness is sent");
                    assertEquals(1, readinessSends.get());
                    assertEquals(0, filesInstallerSends.get());
                    assertTrue(peer.present.isEmpty());
                    assertTrue(peer.current.isEmpty());
                    assertFalse(handle.snapshot().outcome().isEmpty());
                    assertTrue(page.tasks().isEmpty());
                    assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                    var evidence = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    assertEquals(3, evidence.stream().filter(event -> event.type().equals("PREPARE_NOT_SENT")
                        && event.detail().equals("ALTERNATIVE_REQUIRED")).count());
                    assertTrue(evidence.stream().noneMatch(event -> Set.of("CANDIDATE", "COMMAND_EXECUTING", "GRAPH_PROPOSAL").contains(event.type())));
                    assertEquals(sourceText, Files.readString(selectedSourceDirectory().resolve("README.md")));
                    return;
                }
                if (completeDenialApprovalDrift) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(4, calls.get(), "Configuration drift cannot return a correction request");
                    assertEquals(1, independentReviews.get());
                    assertEquals(0, denialToolFeedback.get());
                    assertEquals(Map.of("demo-api", true), peer.present);
                    assertTrue(commandIntents.stream().noneMatch(intent -> intent.exactAction().contains("'agent-run'")));
                    assertTrue(boundarySends.stream().noneMatch(script -> script.contains("'agent-run'")));
                    var events = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    assertTrue(events.stream().noneMatch(event -> event.type().equals("AGENT_OBSERVATION") && event.action().equals("ALTERNATIVE_REQUIRED")));
                    completeDenialApprovalDrift = false;
                    var recovered = reconcileReady(runtime, handle);
                    assertEquals(handle.id(), recovered.id());
                    assertEquals(TaskState.ENDED, recovered.state(), diagnostic(runtime, handle));
                    assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
                    assertEquals(0, peer.commands.get());
                    return;
                }
                if (riskJourney == RiskJourney.REPEAT) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(Map.of("demo-api", true), peer.present);
                    assertTrue(scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL") > 0);
                    var recovered = reconcileReady(runtime, handle);
                    assertEquals(handle.id(), recovered.id());
                    assertEquals(TaskState.ENDED, recovered.state(), diagnostic(runtime, handle));
                    assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
                    assertEquals(Map.of("demo-api", false), peer.present);
                    assertEquals(1, boundarySends.stream().filter(script -> script.contains("candidate-create-task")).count());
                    assertEquals(1, boundarySends.stream().filter(script -> script.contains("candidate-cleanup-task")).count());
                    assertTrue(boundarySends.stream().noneMatch(script -> script.contains("'agent-run'")));
                    verifyRiskJourney(runtime, handle, peer);
                    return;
                }
                if (invalidCompleteCommandReview) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(Map.of("demo-api", true), peer.present);
                    assertEquals(1, independentReviews.get(), "Only the actual complete agent-run is independently reviewed");
                    assertEquals(0, peer.commands.get());
                    assertTrue(boundarySends.stream().noneMatch(script -> script.contains("'agent-run'")));
                    assertTrue(commandIntents.stream().noneMatch(intent -> intent.exactAction().contains("'agent-run'")),
                        "Malformed model review cannot request a host command grant");
                    var stoppedEvents = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    var executing = stoppedEvents.stream().filter(event -> event.type().equals("COMMAND_EXECUTING")).findFirst().orElseThrow();
                    assertEquals(1, stoppedEvents.stream().filter(event -> event.type().equals("COMMAND_NOT_SENT")
                        && event.action().equals(executing.action()) && event.detail().equals("REVIEW_STOPPED_NOT_SENT")).count());
                    var proof = stoppedEvents.stream().filter(event -> event.type().equals("COMMAND_DISPATCH_NOT_SENT")
                        && event.action().equals(executing.action())).findFirst().orElseThrow().detail();
                    assertTrue(proof.startsWith("agent-run-review-stop-v1 task=" + handle.id()));
                    assertTrue(proof.contains("sourceSha256=" + "c".repeat(64)));
                    assertTrue(proof.contains("scriptSha256=" + fixedCommands.sha("printf artifact > $WTL_OUTPUT/run.sh".getBytes(StandardCharsets.UTF_8))));
                    assertTrue(proof.length() < 600);
                    assertFalse(proof.contains("printf artifact"));
                    assertTrue(DeploymentAgentActionLog.effectsSettled(stoppedEvents));
                    assertTrue(stoppedEvents.stream().noneMatch(event -> event.type().equals("COMMAND_RESULT")
                        || event.type().equals("AGENT_OBSERVATION") && event.action().equals("ALTERNATIVE_REQUIRED")));
                    var recovered = reconcileReady(runtime, handle);
                    assertEquals(handle.id(), recovered.id());
                    assertEquals(TaskState.ENDED, recovered.state(), diagnostic(runtime, handle));
                    assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
                    assertEquals(Map.of("demo-api", false), peer.present);
                    assertEquals(1, independentReviews.get(), "Recovery never replays the rejected executable");
                    assertTrue(boundarySends.stream().noneMatch(script -> script.contains("'agent-run'")));
                    return;
                }
                if (highPreparedOpen) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(4, calls.get(), "READ, identification, plan and refused candidate open");
                    assertEquals(0, independentReviews.get(), "The candidate open precedes any complete agent-run review");
                    assertEquals(0, installerQuestions.get());
                    assertEquals(Map.of("demo-api", true), peer.present, diagnostic(runtime, handle));
                    assertEquals(0, peer.commands.get());
                    assertTrue(boundarySends.stream().anyMatch(script -> script.contains("candidate-create-task")));
                    assertTrue(boundarySends.stream().noneMatch(script -> script.contains("'agent-open'") || script.contains("'agent-run'")));
                    assertEquals(fixedCommands.sends.get() + 1, fixedCommands.reviews.get(), "The actual later refused open has no send");
                    assertTrue(scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                    var evidence = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    assertEquals(1, evidence.stream().filter(event -> event.type().equals("PREPARE_EXECUTING")).count());
                    assertTrue(evidence.stream().noneMatch(event -> Set.of("PREPARE_NOT_SENT", "PREPARE_RESULT", "COMMAND_EXECUTING").contains(event.type())));
                    assertFalse(DeploymentAgentActionLog.effectsSettled(evidence));
                    var sendsBeforeRecovery = List.copyOf(boundarySends);
                    int reviewsBeforeRecovery = fixedCommands.reviews.get();
                    int connectionsBeforeRecovery = peer.connections.get();
                    long retainedFiles = scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL");
                    var recovered = reconcileReady(runtime, handle);
                    assertEquals(handle.id(), recovered.id());
                    assertEquals(TaskState.UNKNOWN, recovered.state(), diagnostic(runtime, handle));
                    assertEquals(4, calls.get(), "Original recovery does not replan or replay preparation");
                    assertEquals(0, peer.commands.get());
                    assertEquals(Map.of("demo-api", true), peer.present);
                    assertEquals(1, boundarySends.stream().filter(script -> script.contains("candidate-create-task")).count());
                    assertEquals(0, boundarySends.stream().filter(script -> script.contains("candidate-cleanup-task")).count());
                    assertEquals(sendsBeforeRecovery, boundarySends, "Unconfirmed preparation blocks every recovery probe, query and cleanup");
                    assertEquals(reviewsBeforeRecovery, fixedCommands.reviews.get());
                    assertEquals(connectionsBeforeRecovery, peer.connections.get());
                    assertEquals(evidence, page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events(),
                        "Recovery cannot synthesize a receipt for the refused original open");
                    assertEquals(retainedFiles, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
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
                        assertTrue(DeploymentAgentHistoryReader.pending(detail.events()).isPresent());
                        assertThrows(ApiException.class, () -> page.inspect(detail.entry()),
                            "A new query cannot borrow original UNKNOWN occupancy");
                        var inspected = reconcileReady(runtime, handle);
                        assertEquals(TaskState.UNKNOWN, inspected.state());
                        var after = page.events(handle.id()).completion().toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).value().orElseThrow();
                        assertTrue(DeploymentAgentHistoryReader.pending(after.events()).isEmpty());
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
                    revision.set((selected == ScenarioType.APPROVAL_DRIFT || selected == ScenarioType.AI_APPROVAL_DRIFT ? "b" : "a").repeat(64));
                    if (selected == ScenarioType.PUBLISH_UNKNOWN) {
                        var blocked = reconcileReady(runtime, handle);
                        assertEquals(TaskState.UNKNOWN, blocked.state(), "Semantics 4 requires the original independent finish event");
                        assertEquals(sends, peer.commands.get());
                        assertEquals(decisions, calls.get());
                        // An obsolete execution record is rejected, without changing actual effects.
                        try (var access = scope(runtime, false); var connection = access.openConnection();
                             var statement = connection.prepareStatement("UPDATE deployment_agent_task SET deployment_skill_id='autonomous-deployment-1.0',execution_semantics_version=2 WHERE task_id=?")) {
                            statement.setString(1, handle.id().toString());
                            assertEquals(1, statement.executeUpdate());
                        }
                        assertEquals(TaskState.UNKNOWN, reconcileReady(runtime, handle).state());
                        assertEquals(sends, peer.commands.get());
                        assertEquals(decisions, calls.get());
                        // Supply an explicit current-version finish checkpoint only in this isolated fixture,
                        // after proving both missing-finish and old-version rejection; never force platform results.
                        try (var access = scope(runtime, false); var connection = access.openConnection(); var statement = connection.createStatement()) {
                            statement.executeUpdate("UPDATE deployment_agent_task SET deployment_skill_id='autonomous-deployment-3.0',execution_semantics_version=4 WHERE task_id='" + handle.id() + "'");
                            statement.executeUpdate("INSERT INTO deployment_agent_event(task_id,action_id,event_type,event_detail,created_at) VALUES('" + handle.id() + "','fixture-current-finish','AI_PUBLICATION_FINISHED','verified',1)");
                        }
                    }
                    var recovered = reconcileReady(runtime, handle);
                    assertEquals(TaskState.ENDED, recovered.state(), diagnostic(runtime, handle) + " fixture=" + fixtureFailure.get());
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
                        Optional.of((selected == ScenarioType.SUCCESS && riskJourney != RiskJourney.REPEAT) || selected == ScenarioType.MODEL_REFRESH || selected == ScenarioType.WHOLE_HEALTH_FAILURE
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
                    || selected == ScenarioType.MODEL_REFRESH || selected == ScenarioType.WHOLE_HEALTH_FAILURE ? 1 : 0, committed);
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertTrue(peer.present.values().stream().noneMatch(Boolean::booleanValue));
                if (selected == ScenarioType.WHOLE_HEALTH_FAILURE)
                    assertEquals(List.of("demo-api", "demo-web", "demo-web"), peer.modelPublications,
                        "The model repairs the actual failed health gate with another generated command");
                assertTrue(peer.rollbacks.isEmpty(), "New Agent publication never invokes a static rollback recipe");
                assertEquals(sharedPublication, boundarySends.stream().anyMatch(script -> script.contains("'publish-deployment'")));
                if (selected == ScenarioType.APPROVAL_DRIFT || selected == ScenarioType.AI_APPROVAL_DRIFT
                    || selected == ScenarioType.APPROVAL_MISSING || selected == ScenarioType.DUPLICATE_JSON
                    || selected == ScenarioType.TRAILING_JSON || selected == ScenarioType.INVALID_TOOL || selected == ScenarioType.REPEATED_BROWSE || selected == ScenarioType.REPEATED_FEEDBACK || selected == ScenarioType.FEEDBACK_AFTER_REJECTION || selected == ScenarioType.NOVEL_READ_PROGRESS)
                    assertEquals(0, peer.commands.get());
                if (selected == ScenarioType.BUDGET) {
                    assertEquals(512, calls.get());
                    assertEquals(0, budgets.getLast());
                    if (fourCallAttemptBudget) {
                        var journal = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                        assertEquals(2048, journal.stream().filter(event -> event.type().equals("AGENT_OBSERVATION") && event.action().equals("READ")).count(),
                            "All 2048 actual source pages are durably retained before the decision limit stops execution");
                        assertEquals(512, journal.stream().filter(event -> event.type().equals("DECISION")).count());
                    }
                }
                if (selected == ScenarioType.INVALID_TOOL && exhaustedNativeOutput) {
                    var events = new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(bound)
                        .read(handle.id()).records().stream().map(record -> record.event()).toList();
                    assertTrue(events.stream().noneMatch(event -> event.eventCode().equals("DEPLOYMENT_AGENT_TOOL_REJECTED")));
                    assertTrue(events.stream().anyMatch(event -> event.eventCode().equals("DEPLOYMENT_AGENT_SESSION_STOPPED")
                        && event.message().equals("OUTPUT_BUDGET_EXHAUSTED")));
                    assertTrue(events.stream().anyMatch(event -> event.eventCode().equals("DEPLOYMENT_AGENT_FAILURE")
                        && event.message().contains("exhausted its output budget")));
                }
                if (selected == ScenarioType.INVALID_TOOL && !exhaustedNativeOutput) {
                    var events = new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(bound)
                        .read(handle.id()).records().stream().map(record -> record.event()).toList();
                    var refusals = events.stream().filter(event -> event.eventCode().equals("DEPLOYMENT_AGENT_TOOL_REJECTED")).toList();
                    assertEquals(2, refusals.size(), "The terminal rejection is saved without a third model request");
                    assertTrue(refusals.stream().allMatch(event -> event.message().contains("tool=list")
                        && event.message().contains("Missing parameter: offset")), () -> refusals.toString());
                    assertTrue(refusals.stream().anyMatch(event -> event.message().contains("call=native_2 ")));
                    assertTrue(events.stream().anyMatch(event -> event.eventCode().equals("DEPLOYMENT_AGENT_SESSION_STOPPED")
                        && event.message().equals("NO_PROGRESS")));
                    assertTrue(events.stream().anyMatch(event -> event.eventCode().equals("DEPLOYMENT_AGENT_FAILURE")
                        && event.message().contains("Two consecutive native batches repeated the same rejection")));
                }
                if (evictedFeedbackRecovery) {
                    var events = new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(bound)
                        .read(handle.id()).records().stream().map(record -> record.event()).toList();
                    assertTrue(events.stream().anyMatch(event -> event.eventCode().equals("DEPLOYMENT_AGENT_SESSION_STOPPED")
                        && event.message().equals("REPEATED_EVIDENCE")));
                    var journal = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    assertEquals(2, journal.stream().filter(event -> event.type().equals("AGENT_OBSERVATION")).count());
                    assertEquals(5, journal.stream().filter(event -> event.type().equals("AGENT_QUERY")).count());
                }
                if (missingRecipeAnalysis || invalidCapabilityAssessmentTool != null) {
                    var journal = page.events(handle.id()).completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events();
                    assertTrue(journal.stream().noneMatch(event -> java.util.Set.of("PREPARE_EXECUTING", "CANDIDATE", "CAPABILITY_EXECUTING").contains(event.type())),
                        "Local admission is refused before preparation or capability effects begin");
                    assertTrue(boundarySends.stream().noneMatch(script -> script.contains("'agent-open'") || script.contains("install-helper") || script.contains("'prepare-toolchains'")));
                    assertEquals(preflightSends, boundarySends.size());
                    assertEquals(preflightApprovals, approvals.size());
                    if (invalidCapabilityAssessmentTool != null) {
                        assertEquals(0, readinessSends.get());
                        assertEquals(0, filesInstallerSends.get());
                        assertEquals(0, peer.commands.get());
                        assertEquals(0, candidateUploads.get());
                        assertEquals(6, calls.get(), "The first rejection changes the fixture basis; two identical subsequent malformed calls stop after read, identify and plan");
                    }
                }
                if (staticDenialRepeat) {
                    var rejected = new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(bound)
                        .read(originalAiTask.get()).records().stream().map(record -> record.event())
                        .filter(event -> event.eventCode().equals("DEPLOYMENT_AGENT_TOOL_REJECTED")).toList();
                    assertEquals(2, rejected.size(), "Both identical native inputs produce actual tool rejection records");
                    assertTrue(rejected.stream().allMatch(event -> event.message().contains("DEPLOYMENT_COMMAND_STATIC_DENIED: NOT_SENT")
                            && event.message().contains(evidenceDigestBytes(STATIC_DENIED_SCRIPT))),
                        "Both original assessments remain valid; the second error must be the actual static gate denial");
                }
                if ((selected == ScenarioType.SUCCESS && riskJourney != RiskJourney.REPEAT) || selected == ScenarioType.MODEL_REFRESH)
                    assertEquals(singleUnsupported ? 2 : 3, peer.commands.get(),
                        "One known failed build retries only the same candidate");
                if ((selected == ScenarioType.SUCCESS && riskJourney != RiskJourney.REPEAT) || selected == ScenarioType.MODEL_REFRESH)
                    fixedCommands.completed();
                if ((selected == ScenarioType.SUCCESS && riskJourney != RiskJourney.REPEAT) || selected == ScenarioType.MODEL_REFRESH)
                    assertEquals(riskJourney == RiskJourney.REPLACE ? 4 : singleUnsupported ? 2 : 3,
                        independentReviews.get(), "Every actual agent-run requires its complete independent fixed APPROVAL request");
                if (selected == ScenarioType.APPROVAL_MISSING || selected == ScenarioType.DUPLICATE_JSON
                    || selected == ScenarioType.TRAILING_JSON || selected == ScenarioType.INVALID_TOOL || selected == ScenarioType.REPEATED_BROWSE || selected == ScenarioType.REPEATED_FEEDBACK || selected == ScenarioType.FEEDBACK_AFTER_REJECTION || selected == ScenarioType.NOVEL_READ_PROGRESS || selected == ScenarioType.CANCELLED || selected == ScenarioType.BUDGET)
                    assertEquals(0, independentReviews.get(), "Rejected admission or an invalid non-command proposal cannot obtain a command review");
                if (selected == ScenarioType.APPROVAL_DRIFT || selected == ScenarioType.AI_APPROVAL_DRIFT)
                    assertEquals(1, independentReviews.get(), "The changed original approval must not authorize another candidate");
                if (singleUnsupported) {
                    int expectedDecisions = missingSourceQuery ? 12 : 11;
                    assertEquals(expectedDecisions, calls.get());
                    assertEquals(java.util.stream.IntStream.range(0, expectedDecisions).map(i -> 79 - i).boxed().toList(), budgets);
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
                    assertTrue(commandReviews.stream().allMatch(value -> value.equals("b".repeat(64))),
                        "The draft paused before preparation has no command approval under the old route");
                    assertEquals(15, calls.get());
                    assertEquals(java.util.stream.IntStream.range(0, 15).map(i -> 79 - i).boxed().toList(), budgets);
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
        details.append("\nControlled model rejections: ").append(rejectedModelFeedback);
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
        assertEquals(independentCommandDenial ? 0 : 1, highQuestions.get());
        assertEquals(independentCommandDenial ? 0 : 1, fixedCommands.denied.get());
        if (independentCommandDenial) {
            assertEquals(1, denialToolFeedback.get());
            assertEquals(1, fixedCommands.modelDenied.get());
            assertTrue(commandIntents.stream().noneMatch(intent -> intent.exactAction().contains("'agent-run'")
                    && intent.exactAction().contains(fixedCommands.sha(HIGH_SCRIPT.getBytes(StandardCharsets.UTF_8)))),
                "An independent DENY never reaches the host command-grant request under any policy");
        }
        var events = new ArrayList<DeploymentAgentActionLog.Event>();
        try (var access = scope(runtime, true); var connection = access.openConnection();
             var query = connection.prepareStatement("SELECT event_id,event_type,action_id,event_detail FROM deployment_agent_event WHERE task_id=? ORDER BY event_id")) {
            query.setString(1, handle.id().toString());
            try (var rows = query.executeQuery()) {
                while (rows.next())
                    events.add(new DeploymentAgentActionLog.Event(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4)));
            }
        }
        var observations = events.stream().filter(value -> value.type().equals("AGENT_OBSERVATION") && value.action().equals("ALTERNATIVE_REQUIRED")).toList();
        assertEquals(riskJourney == RiskJourney.REPEAT ? 3 : 1, observations.size());
        assertTrue(observations.stream().allMatch(value -> value.detail().matches("[0-9a-f]{64}")));
        assertTrue(DeploymentAgentActionLog.effectsSettled(events));
        var unsent = events.stream().filter(value -> value.type().equals("COMMAND_NOT_SENT")).toList();
        assertEquals(1, unsent.size());
        if (!unsent.isEmpty()) {
            assertEquals("ALTERNATIVE_REQUIRED", unsent.getFirst().detail());
            assertEquals(fixedCommands.sha(("api:" + "c".repeat(64) + ":" + HIGH_SCRIPT).getBytes(StandardCharsets.UTF_8)), unsent.getFirst().action());
        }
        if (riskJourney == RiskJourney.REPEAT) {
            assertEquals(6, calls.get(), "The initial actual refusal and the first same-script refusal have distinct reasons; only the next identical refusal stalls");
            assertEquals(List.of(79, 78, 77, 76, 75, 74), budgets);
            assertEquals(1, independentReviews.get(), "The repeated rejected script is refused before another independent review");
            assertEquals(0, peer.commands.get());
            assertEquals(Map.of("demo-api", false), peer.present);
            assertTrue(fixedCommands.agentInputDigests.isEmpty());
            assertEquals(1, events.stream().filter(value -> value.type().equals("CANDIDATE")).count());
            assertEquals(1, events.stream().filter(value -> value.type().equals("COMMAND_EXECUTING")).count());
            assertEquals(0, events.stream().filter(value -> value.type().equals("COMMAND_APPROVED")).count(),
                "The incomplete command pre-review is not an execution approval");
            assertEquals("DEPLOYMENT_AGENT_PREFORMAL_RECONCILED",
                handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).problem().orElseThrow().eventCode());
        } else {
            assertEquals(15, calls.get());
            assertEquals(java.util.stream.IntStream.range(0, 15).map(i -> 79 - i).boxed().toList(), budgets);
            assertEquals(4, independentReviews.get());
            assertEquals(3, peer.commands.get());
            assertEquals(Collections.nCopies(3, fixedCommands.sha("printf artifact > $WTL_OUTPUT/run.sh".getBytes(StandardCharsets.UTF_8))), fixedCommands.agentInputDigests);
            assertEquals(Set.of("demo-api", "demo-web"), peer.current.keySet());
            assertTrue(events.stream().anyMatch(value -> value.type().equals("AI_PUBLICATION_VERIFIED") && value.detail().equals("HEALTHY")));
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

    private long modScalar(PlatformRuntime runtime, String query) throws Exception {
        try (var access = scope(runtime, true); var connection = access.openConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void waitReturned(TaskHandle<?> handle) throws Exception {
        waitReturned(handle, fourCallAttemptBudget ? 180 : 40);
    }

    private void waitReturned(TaskHandle<?> handle, int seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (handle.snapshot().state() != TaskState.ENDED && handle.snapshot().state() != TaskState.UNKNOWN
            && System.nanoTime() < end) {
            if (fixtureFailure.get() != null) throw new AssertionError("Controlled peer rejected the actual protocol", fixtureFailure.get());
            Thread.sleep(25);
        }
        assertTrue(handle.snapshot().state() == TaskState.ENDED || handle.snapshot().state() == TaskState.UNKNOWN,
            handle.snapshot().toString());
    }

    private ModContext bind(ModContext actual, TaskService tasks, DeploymentAgentTestPeer peer, AiService ai) {
        fixedCommands = new FixedCommandFixture(actual, tasks);
        var observedTasks = hostEnvelopeDenialJourney ? (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(),
            new Class<?>[]{TaskService.class}, (proxy, method, arguments) -> {
                try {
                    var result = method.invoke(tasks, arguments);
                    if (method.getName().equals("findCheckpoint") && arguments[1].equals(DeploymentModelCommandRunner.KEY)
                        && result instanceof Optional<?> found && found.isPresent()) {
                        var checkpoint = (gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint) found.orElseThrow();
                        var snapshot = checkpoint.snapshot();
                        var payload = new ObjectMapper().readTree(snapshot.payload());
                        assertEquals(((TaskContext) arguments[0]).id().toString(), payload.path("task").asText());
                        for (var command : payload.path("commands"))
                            hostEnvelopeCheckpointSnapshots.add(((com.fasterxml.jackson.databind.node.ObjectNode) command.deepCopy())
                                .put("released", snapshot.released()));
                    }
                    return result;
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }) : tasks;
        var interaction = fixedCommands.observe(actual.interaction());
        var trackedAi = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class},
            (proxy, method, arguments) -> {
                try {
                    return method.invoke(ai, arguments);
                } catch (InvocationTargetException failed) {
                    fixtureFailure.compareAndSet(null, failed.getCause());
                    throw failed.getCause();
                }
            });
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                var original = peer.session(task);
                return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                    new Class<?>[]{RemoteSession.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("upload")) {
                            candidateUploads.incrementAndGet();
                            var upload = (gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest) arguments[0];
                            byte[] bytes = Files.readAllBytes(upload.localPath());
                            uploadedArchiveSizes.put(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), (long) bytes.length);
                        }
                        if (method.getName().equals("execute"))
                            fixedCommands.beforeSend(task, (CommandRequest) arguments[0], (ApprovalGrant) arguments[1]);
                        if (method.getName().equals("execute"))
                            boundarySends.add(((CommandRequest) arguments[0]).script());
                        if (method.getName().equals("execute")) {
                            if (acceptNativeFailure && ((CommandRequest) arguments[0]).script().contains("'application-health'"))
                                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(
                                    Optional.of(64), "HEALTH=failed\n", "Controlled formal health failure", false)), Optional.empty());
                            String originalScript = ((CommandRequest) arguments[0]).script();
                            if (failureCleanupMode != null && originalScript.contains("'publication-original-status'")) {
                                if (failureCleanupMode.equals("status-unknown"))
                                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                                        "CONTROLLED_CURRENT_STATUS_UNKNOWN", "Original current status query is unknown", Map.of(), Optional.of(task.id()))));
                                if (failureCleanupMode.equals("foreign-candidate"))
                                    return new OperationResult<>(TaskResult.FAILED, Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(
                                        Optional.of(64), "", "MANAGED_HELPER_REJECT=publication-candidate-foreign\n", false)), Optional.empty());
                            }
                            if (failureCleanupMode != null && failureCleanupMode.equals("absence-unknown") && originalScript.contains("candidate-query-task")
                                && peer.present.values().stream().anyMatch(value -> !value))
                                return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                                    "CONTROLLED_ABSENCE_UNKNOWN", "Original candidate absence query is unknown", Map.of(), Optional.of(task.id()))));
                            if (((CommandRequest) arguments[0]).script().contains("stat -f -c '%a %S' -- '/var/lib'")) {
                                candidateCapacitySends.incrementAndGet();
                                if (planCapacityApiFailure) {
                                    capacityApiTask.set(task.id());
                                    capacityApiRequestSha.set(evidenceDigestBytes(RemoteActions.command(server, revision.get(), (CommandRequest) arguments[0]).exactAction()));
                                    var failure = new ApiException(new Problem(ProblemCode.IO_FAILURE, CAPACITY_API_EVENT, CAPACITY_API_MESSAGE,
                                        Map.of(), Optional.of(task.id())));
                                    capacityApiOriginalFailure.set(failure);
                                    throw failure;
                                }
                                if (candidateAvailableBytes != null) {
                                    assertEquals(0, candidateAvailableBytes % 4096);
                                    if (planCapacityBreakdownJourney) {
                                        planCapacityRequestSha.set(evidenceDigestBytes(RemoteActions.command(server, revision.get(), (CommandRequest) arguments[0]).exactAction()));
                                        planCapacityResultSha.set(evidenceDigestBytes((candidateAvailableBytes / 4096) + " 4096\n"));
                                    }
                                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(
                                        Optional.of(0), (candidateAvailableBytes / 4096) + " 4096\n", "", false)), Optional.empty());
                                }
                            }
                            byte[] input = ((CommandRequest) arguments[0]).stdin();
                            try {
                                String body = new String(input, StandardCharsets.UTF_8);
                                if (hostEnvelopeDenialJourney && originalScript.equals(DeploymentModelCommandIdentity.LAUNCHER)
                                    && body.startsWith(DeploymentModelCommandIdentity.METADATA)) {
                                    var facts = DeploymentModelCommandIdentity.executableFacts(body);
                                    String executable = facts.get("modelScript").toString();
                                    if (executable.equals(HOST_ENVELOPE_DENIED_SCRIPT)) {
                                        hostEnvelopeRejectedSends++;
                                        fail("The rejected host body must never reach the remote peer");
                                    }
                                    if (executable.equals(HOST_ENVELOPE_LEGAL_SCRIPT)) {
                                        assertEquals(task.id().toString(), facts.get("task"));
                                        assertEquals(evidenceDigestBytes(HOST_ENVELOPE_LEGAL_SCRIPT), facts.get("bodySha256"));
                                        String operation = facts.get("operation").toString();
                                        assertNull(hostEnvelopeReceipts.putIfAbsent(operation, DeploymentModelCommandIdentity.header(task.id(),
                                            UUID.fromString(operation), facts.get("bodySha256").toString()) + "EXIT=0\n"));
                                        hostEnvelopeLegalSends++;
                                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(
                                            Optional.of(0), "WTL_CONTROLLED_HOST_PREREQUISITE_OK\n", "", false)), Optional.empty());
                                    }
                                }
                                if (hostEnvelopeDenialJourney && hostEnvelopeReceipts.entrySet().stream()
                                    .anyMatch(entry -> originalScript.contains(entry.getKey() + ".receipt"))) {
                                    var receipt = hostEnvelopeReceipts.entrySet().stream()
                                        .filter(entry -> originalScript.contains(entry.getKey() + ".receipt")).findFirst().orElseThrow();
                                    hostEnvelopeReceiptQueries++;
                                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(
                                        Optional.of(0), receipt.getValue(), "", false)), Optional.empty());
                                }
                                if (body.equals(DeploymentAgentHelperReadiness.script())) {
                                    readinessSends.incrementAndGet();
                                    boolean ready = bootstrapFilesAvailable.get();
                                    String facts = "FORMAT=WTL_AGENT_HELPER_READINESS_1\nSTATUS=" + (ready ? "READY" : "MISSING")
                                        + "\nREASON=" + (ready ? "CURRENT_TASK_READY" : "HELPER_MISSING")
                                        + "\nPACKAGED_PROTOCOL=16\nHELPER=" + (ready ? "MATCH" : "ABSENT")
                                        + "\nBUILD_ENTRY=" + (ready ? "MATCH" : "ABSENT")
                                        + "\nDEPENDENCIES=MATCH\nROOTS=VALID_OR_ABSENT\nRECEIPTS=ABSENT\n";
                                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(
                                        Optional.of(0), facts, "", false)), Optional.empty());
                                }
                                if (body.equals(DeploymentHelperInstallerScript.renderFiles())
                                    || fixedCommands.isAgentBootstrap(task, (CommandRequest) arguments[0])) {
                                    if (!body.equals(DeploymentHelperInstallerScript.renderFiles())) {
                                        assertEquals(gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript.LAUNCHER,
                                            ((CommandRequest) arguments[0]).script());
                                        String operation = gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript.parseOperation(body);
                                        assertEquals(gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript.render(operation), body);
                                    }
                                    assertTrue(bootstrapFilesAvailable.compareAndSet(false, true), "A matching helper is never reinstalled");
                                    filesInstallerSends.incrementAndGet();
                                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(
                                        Optional.of(0), "HELPER_INSTALL=1\nPROTOCOL=16\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n", "", false)), Optional.empty());
                                }
                            } finally {
                                Arrays.fill(input, (byte) 0);
                            }
                        }
                        if (method.getName().equals("execute") && ((CommandRequest) arguments[0]).script().equals(DeploymentAgentHostProbe.baseline()))
                            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),
                                "FORMAT=WTL_AGENT_BASELINE_1\nDISTRO_ID=ubuntu\nDISTRO_VARIANT=\nVERSION=24.04\nARCH=x86_64\nPACKAGE_MANAGER=apt\nPACKAGE_ARCH=amd64\nSYSTEMD=1\n", "", false)), Optional.empty());
                        if (acceptNativeFailure && method.getName().equals("close"))
                            failureRemoteClosed.incrementAndGet();
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
                            assertEquals(2, peer.modelPublications.size());
                            assertEquals(sharedPublication, boundarySends.stream().anyMatch(script -> script.contains("'publish-deployment'")));
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
                if (finalizationFault == FinalizationFault.FILES && method.getName().equals("closeTaskResources")
                    && finalizerFrame("DeploymentAgentWorkflow", "finishKnown")) {
                    assertEquals(2, peer.current.size());
                    finalizationFaultInjected.set(true);
                    throw new ApiException(new Problem(ProblemCode.IO_FAILURE, "CONTROLLED_SOURCE_RELEASE_FAILED", "Controlled final source release failure", Map.of(), Optional.empty()));
                }
                var value = gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture.invoke(method, actual.file(), arguments);
                if (value instanceof FileSnapshot snapshot) {
                    reviewSnapshots.put(snapshot.id(), snapshot);
                    capturedSnapshotPaths.put(snapshot.id(), snapshot.directory());
                }
                if (acceptNativeFailure && value instanceof FileSnapshot snapshot)
                    failureSourceDirectories.add(snapshot.directory());
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
        var finalizationDb = (gold.debug.windowstolinux.appapi.db.ModDatabase) Proxy.newProxyInstance(
            ModContext.class.getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.db.ModDatabase.class}, (proxy, method, args) -> {
                if (method.getName().equals("openConnection") && (finalizationFault == FinalizationFault.FORMAL
                    && finalizerFrame("DeploymentOriginalPublishCommit", "finishKnown") || finalizationFault == FinalizationFault.ARCHIVE
                    && finalizerFrame("DeploymentAgentHistoryArchive", "archive"))) {
                    assertEquals(2, peer.current.size());
                    finalizationFaultInjected.set(true);
                    throw new java.sql.SQLException("Controlled " + finalizationFault + " persistence failure after actual publication");
                }
                try { return method.invoke(actual.db(), args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "tasks" -> observedTasks;
                case "linux" -> linux;
                case "ai" -> trackedAi;
                case "interaction" -> interaction;
                case "file" -> source;
                case "db" -> finalizationFault == null ? actual.db() : finalizationDb;
                case "servers" -> failureCleanupMode != null && failureCleanupMode.equals("reference-unknown")
                    ? Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.server.ServerService.class.getClassLoader(),
                    new Class<?>[]{gold.debug.windowstolinux.appapi.server.ServerService.class}, (self, call, values) -> {
                        if (call.getName().equals("findReference") && new DeploymentAgentActionLog(actual.db()).events((TaskContext) values[2]).stream()
                            .anyMatch(event -> event.type().equals("AI_PUBLICATION_FAILED_ACCEPTED")))
                            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_REFERENCE_QUERY_UNKNOWN", "Original reference query is unknown", Map.of(), Optional.empty()));
                        try {
                            return call.invoke(actual.servers(), values);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    }) : actual.servers();
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
                if (!originalAiTask.get().equals(task.id()) && !continueNativeFailure) {
                    assertTrue(Set.of(AiPurpose.LANGUAGE, AiPurpose.APPROVAL).contains(purpose), "Terminal inspection verifies both original role revisions without calling the LANGUAGE planner");
                    fixedCommands.requireInspection(task);
                }
                if (purpose == AiPurpose.APPROVAL) {
                    if (scenario == ScenarioType.APPROVAL_MISSING)
                        throw new ApiException(new Problem(ProblemCode.UNAVAILABLE, "CONTROLLED_APPROVAL_MISSING",
                            "No independent reviewer configured", Map.of(), Optional.empty()));
                } else
                    assertEquals(AiPurpose.LANGUAGE, purpose);
                return purpose == AiPurpose.APPROVAL && completeDenialApprovalDrift && highCandidateReviews.get() == 1
                    ? "b".repeat(64) : revision.get();
            }

            public OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException {
                return call(request, revision.get(), task);
            }

            public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext task)
                throws ApiException {
                assertEquals(revision.get(), expected);
                if (hostEnvelopeDenialJourney)
                    hostEnvelopeTask = task;
                if (continueNativeFailure) {
                    successorDiagnostic.beforeRequest(request);
                    assertEquals(AiPurpose.LANGUAGE, request.purpose());
                    successorLanguageCalls.merge(task.id(), 1, Integer::sum);
                    if (successorRoot.get() == null) successorRoot.set(task.id());
                    if (!originalAiTask.get().equals(task.id())) {
                        assertEquals(successorRoot.get(), DeploymentAgentGoal.currentWorker().orElseThrow().predecessor().orElseThrow().predecessor());
                        originalAiTask.set(task.id());
                        calls.set(0);
                        publicationDecisions.set(0);
                        proposalDecisions.set(0);
                        independentIdentification = false;
                        acceptNativeFailure = false;
                    }
                }
                if (riskJourney != null)
                    assertFalse(task.cancelToken().requested(), "Refusing a HIGH action is not cancelling the original workflow");
                if (request.purpose() == AiPurpose.APPROVAL) {
                    return throughProtocol(request, exactIndependentReview(request, task));
                }
                assertEquals(AiPurpose.LANGUAGE, request.purpose());
                assertEquals(originalAiTask.get(), task.id(), "A fresh inspection may verify LANGUAGE configuration but must never send a planner request");
                int turn = calls.getAndIncrement();
                if (planCapacityBreakdownJourney && planCapacityRejectedCall != null && planCapacityFeedbackRequests == 0) {
                    var feedback = request.messages().stream().flatMap(message -> message.toolResult().stream())
                        .filter(result -> result.callId().equals(planCapacityRejectedCall)).findFirst().orElseThrow();
                    assertTrue(feedback.error());
                    assertTrue(feedback.text().contains("TOOL_REJECTED: WORKSPACE_HOST_INSUFFICIENT"));
                    assertEquals(0, reviewedCandidateCreates.get(), "Only this rejected new plan has no candidate creation");
                    assertEquals(0, candidateUploads.get());
                    int dataStart = feedback.text().indexOf("DATA=");
                    try {
                        var journal = new DeploymentAgentActionLog(fixedCommands.actual.db());
                        assertTrue(journal.events(task).stream().noneMatch(event -> event.type().equals("PREPARE_EXECUTING")));
                        planCapacityBindingFacts.put("task", task.id().toString()).put("sourceSha256", journal.events(task).stream()
                                .filter(event -> event.type().equals("SOURCE")).findFirst().orElseThrow().detail())
                            .put("configurationSha256", revision.get()).put("layoutSha256", gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(fixedCommands.tasks, task).digest())
                            .put("capacityRequestSha256", planCapacityRequestSha.get()).put("capacityResultSha256", planCapacityResultSha.get());
                        planCapacityBreakdown.set(dataStart < 0 ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                            : new ObjectMapper().readTree(feedback.text().substring(dataStart + 5)));
                    } catch (Exception unavailable) {
                        throw new AssertionError("Controlled original plan refusal feedback cannot be captured", unavailable);
                    }
                    planCapacityFeedbackRequests++;
                    System.out.println("PLAN_CAPACITY_BREAKDOWN_FEEDBACK call=" + planCapacityRejectedCall + " error=true dataPresent=" + (dataStart >= 0)
                        + " feedbackSha256=" + evidenceDigestBytes(feedback.text()) + " creates=0 uploads=0");
                }
                if (exhaustedNativeOutput)
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "length",
                        new gold.debug.windowstolinux.appapi.ai.model.AiUsage(Optional.of(5L), Optional.of(8192L), Optional.empty(), Optional.empty(), Optional.of(8192L)))), Optional.empty());
                JsonNode data;
                try {
                    data = new ObjectMapper().readTree(request.messages().stream().filter(message -> message.role() == AiRole.USER)
                        .reduce((earlier, latest) -> latest).orElseThrow().text());
                } catch (Exception invalid) {
                    throw new AssertionError(invalid);
                }
                if (continueNativeFailure && !task.id().equals(successorRoot.get()) && turn == 0) {
                    var inherited = data.at("/state/budget/continuation");
                    assertEquals(successorRoot.get().toString(), inherited.path("predecessor").asText());
                    assertEquals(successorRoot.get().toString(), inherited.path("root").asText());
                    assertTrue(inherited.path("nativeFailureObservation").asText().contains("\"outcome\":\"FAILED\""));
                    assertTrue(inherited.path("nativeFailureObservation").asText().contains("\"rollbackConfirmed\":true"));
                    assertTrue(inherited.at("/source/manifest").asText().matches("[0-9a-f]{64}"));
                    assertTrue(data.at("/state/readFiles").isEmpty());
                    assertEquals(0, data.at("/state/planRevision").asInt());
                    assertTrue(inherited.path("acceptedFailure").asText().contains("AI") || !inherited.path("acceptedFailure").asText().isBlank());
                    childFailureFactsObserved.set(true);
                }
                if (baselineJourney && data.path("state").has("planRevision")) {
                    var budget = data.at("/state/budget");
                    assertTrue(budget.isObject(), "The actual coordinator request must expose its distinct tool allowance");
                    assertEquals(data.path("remaining").asInt(), budget.path("remainingModelResponsesAfterThisRequest").asInt());
                    assertEquals(2048, budget.path("maximumMainToolAttempts").asInt());
                    assertEquals(4096, budget.path("maximumSharedToolAttempts").asInt());
                    assertEquals(512, budget.path("maximumModelResponses").asInt());
                    assertEquals(4, budget.path("maximumCallsPerResponse").asInt());
                    assertEquals(Math.min(budget.path("remainingMainToolAttempts").asInt(), budget.path("remainingSharedToolAttempts").asInt()),
                        budget.path("remainingExecutableToolAttempts").asInt());
                    assertTrue(budget.path("publicationUsesSharedAttempts").asBoolean());
                }
                if (planCapacityBreakdownJourney && planCapacityAcceptedCall != null && !planCapacityLegalPlanObserved) {
                    var accepted = request.messages().stream().flatMap(message -> message.toolResult().stream())
                        .filter(result -> result.callId().equals(planCapacityAcceptedCall)).findFirst().orElseThrow();
                    assertFalse(accepted.error());
                    assertEquals(1, data.at("/state/planRevision").asInt());
                    assertEquals(2, data.at("/state/components").size());
                    assertEquals(2, candidateCapacitySends.get());
                    assertEquals(2, fixedCommands.reviews.get(), "Both capacity executions use independent fixed review and exact original grants");
                    assertEquals(0, reviewedCandidateCreates.get());
                    assertEquals(0, candidateUploads.get());
                    planCapacityLegalPlanObserved = true;
                    System.out.println("PLAN_CAPACITY_LEGAL_PLAN_ACCEPTED task=" + task.id() + " call=" + planCapacityAcceptedCall
                        + " planRevision=1 capacityExecutions=2 independentFixedReviews=2 creates=0 uploads=0 feedbackSha256=" + evidenceDigestBytes(accepted.text()));
                }

                if (data.path("state").path("stage").asText().equals("INVESTIGATING"))
                    assertEquals(data.path("state").path("readFiles").isEmpty() ? "read" : "identify",
                        data.path("state").path("nextStep").path("suggestedTool").asText());
                if (data.path("state").path("stage").asText().equals("IDENTIFIED"))
                    assertEquals("plan", data.path("state").path("nextStep").path("suggestedTool").asText());
                var observations = ((com.fasterxml.jackson.databind.node.ObjectNode) data).putArray("observations");
                for (var entry : data.path("state").path("evidenceIndex").path("entries")) {
                    var fact = (com.fasterxml.jackson.databind.node.ObjectNode) entry.deepCopy();
                    fact.put("tool", entry.path("kind").asText());
                    observations.add(fact);
                }
                var callNames = new HashMap<String, String>();
                for (var message : request.messages())
                    for (var call : message.toolCalls()) callNames.put(call.id(), call.name());
                for (var message : request.messages())
                    if (message.toolResult().isPresent()) {
                        var result = message.toolResult().orElseThrow();
                        String value = result.text();
                        if (result.error() && !rejectedModelFeedback.contains(value)) rejectedModelFeedback.add(value);
                        String first = value.lines().findFirst().orElse("");
                        String tool = result.error() ? (value.contains("APPROVAL_DENIED:") ? "ALTERNATIVE_REQUIRED" : "PROPOSAL_REJECTED")
                            : callNames.getOrDefault(result.callId(), "RESULT").toUpperCase(Locale.ROOT);
                        var fact = observations.addObject().put("tool", tool).put("output", value);
                        fact.put("id", first.matches("o[1-9][0-9]*") ? first : "o1");
                    }
                if (independentCommandDenial && highCandidateReviews.get() == 1 && lowerRiskFeedback.get() == 0) {
                    var refusal = request.messages().stream().filter(message -> message.role() == AiRole.TOOL)
                        .flatMap(message -> message.toolResult().stream()).filter(result -> result.text().contains("APPROVAL_DENIED:")).findFirst().orElseThrow();
                    assertTrue(refusal.error());
                    assertTrue(refusal.text().contains("Retain snapshots.db and choose a different output path."));
                    assertTrue(refusal.text().contains("[credential content excluded]"));
                    assertFalse(refusal.text().contains("fixture-private-review-value"));
                    assertTrue(refusal.text().length() <= 527);
                    assertEquals(1, denialToolFeedback.incrementAndGet(), "The denial reason reaches the deployment model as native tool-result data");
                }
                budgets.add(data.path("remaining").intValue());
                if (fourCallAttemptBudget) {
                    var budget = data.at("/state/budget");
                    assertEquals(2048, budget.path("maximumMainToolAttempts").asInt());
                    assertEquals(4096, budget.path("maximumSharedToolAttempts").asInt());
                    assertEquals(512, budget.path("maximumModelResponses").asInt());
                    assertEquals(turn * 4, budget.path("mainToolAttempts").asInt());
                    assertEquals(2048 - turn * 4, budget.path("remainingMainToolAttempts").asInt());
                    assertEquals(4096 - turn * 4, budget.path("remainingSharedToolAttempts").asInt());
                    assertEquals(2048 - turn * 4, budget.path("remainingExecutableToolAttempts").asInt());
                    assertEquals(turn == 0 ? 0 : 1, data.at("/state/readFiles").size(), "Source pages retain the actual frozen file READ basis");
                    var batch = new ArrayList<gold.debug.windowstolinux.appapi.ai.model.AiToolCall>();
                    for (int index = 0; index < 4; index++) {
                        batch.add(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("native_" + (++nativeIds), "read",
                            "{\"path\":\"README.md\",\"offset\":" + (turn * 4 + index) + ",\"limit\":1}"));
                    }
                    return throughProtocol(request, new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "tool_calls",
                        new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                        batch, Optional.empty())), Optional.empty()));
                }
                if (distinctRejectedParameters != null && turn > 0 && turn <= 2) {
                    var latest = request.messages().stream().flatMap(message -> message.toolResult().stream())
                        .reduce((earlier, last) -> last).orElseThrow();
                    assertTrue(latest.error());
                    sameTextRejectionFeedback.add(latest.text());
                    assertTrue(data.at("/state/evidenceIndex/entries").isEmpty(), "Rejected parameters create no source evidence");
                    assertTrue(boundarySends.isEmpty(), "Rejected parameters dispatch no remote work");
                }
                if (distinctRejectedParameters != null && turn < 2) {
                    String path = distinctRejectedParameters && turn == 1 ? "api" : ".";
                    rejectedParameterPaths.add(path);
                    String rejected = "{\"tool\":\"list\",\"arguments\":{\"path\":\"" + path + "\",\"limit\":100}}";
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(nativeResponse(request, rejected)), Optional.empty());
                }
                if (evictedRejectionFeedback && turn == 1) {
                    assertEquals(2, request.messages().size(), "The latest large completed batch leaves as one whole batch");
                    assertTrue(request.messages().stream().noneMatch(message -> message.toolResult().isPresent() || !message.toolCalls().isEmpty()));
                    var rejected = data.at("/state/recentRejections");
                    assertEquals(1, rejected.size());
                    assertEquals("native_1", rejected.get(0).path("callId").asText());
                    assertEquals("list", rejected.get(0).path("tool").asText());
                    assertEquals("TOOL_REJECTED: Missing parameter: offset", rejected.get(0).path("reason").asText());
                    assertTrue(rejected.get(0).path("reason").asText().length() <= 512);
                    assertTrue(rejected.get(0).path("evidenceId").isNull());
                    assertTrue(data.at("/state/evidenceIndex/entries").isEmpty(), "Rejected syntax creates no READ or queryable observation");
                    assertTrue(data.at("/state/remoteRevisions").isEmpty());
                }
                if (sourceReadPagination) verifyReadPagination(request, data, turn);
                if (invalidCapabilityAssessmentTool != null && turn >= 3) {
                    if (turn == 3) {
                        preflightSends = boundarySends.size();
                        preflightApprovals = approvals.size();
                    } else {
                        var latest = request.messages().stream().flatMap(message -> message.toolResult().stream()).reduce((earlier, last) -> last).orElseThrow();
                        assertTrue(latest.error());
                        assertTrue(latest.text().contains("/assessment"));
                        assertTrue(latest.text().contains("[basis, decision, differences, resolution, suitability]"));
                        assertFalse(latest.text().contains("private-arbitrary-field"));
                        assertFalse(latest.text().contains("fixture-sensitive-body"));
                        assertTrue(latest.text().length() <= 512);
                        assertEquals(preflightSends, boundarySends.size());
                        assertEquals(preflightApprovals, approvals.size());
                        assertEquals(0, candidateUploads.get());
                        invalidCapabilityAssessmentFeedback = true;
                    }
                }
                if (staticDenialJourney && staticDeniedCallId != null && staticDenialFeedbackRequests == 0) {
                    var rejected = request.messages().stream().flatMap(message -> message.toolResult().stream())
                        .filter(result -> result.callId().equals(staticDeniedCallId)).findFirst().orElseThrow();
                    assertTrue(rejected.error());
                    assertEquals("TOOL_REJECTED: DEPLOYMENT_COMMAND_STATIC_DENIED: NOT_SENT task=" + task.id()
                        + " component=api scriptSha256=" + evidenceDigestBytes(STATIC_DENIED_SCRIPT), rejected.text());
                    assertTrue(rejected.text().length() <= 512);
                    assertFalse(rejected.text().contains("fixture-sensitive-static-body"));
                    assertEquals(staticDenialBeforeSends, boundarySends.size());
                    assertEquals(staticDenialBeforeApprovals, approvals.size());
                    assertEquals(0, candidateUploads.get());
                    try {
                        var events = new DeploymentAgentActionLog(fixedCommands.actual.db()).events(task);
                        assertTrue(events.stream().noneMatch(event -> Set.of("PREPARE_EXECUTING", "CANDIDATE", "COMMAND_EXECUTING",
                            "COMMAND_DISPATCH_NOT_SENT", "COMMAND_NOT_SENT").contains(event.type())));
                    } catch (Exception unavailable) {
                        throw new AssertionError("Static refusal journal must remain before execution", unavailable);
                    }
                    staticDenialFeedbackRequests++;
                }
                if (hostEnvelopeDenialJourney && hostEnvelopeRejectedCallId != null && hostEnvelopeFeedbackRequests == 0) {
                    var rejected = request.messages().stream().flatMap(message -> message.toolResult().stream())
                        .filter(result -> result.callId().equals(hostEnvelopeRejectedCallId)).findFirst().orElseThrow();
                    assertTrue(rejected.error());
                    assertTrue(rejected.text().contains("DEPLOYMENT_COMMAND_STATIC_DENIED"));
                    assertTrue(rejected.text().contains("NOT_SENT"));
                    assertTrue(rejected.text().contains(task.id().toString()));
                    assertTrue(rejected.text().contains(evidenceDigestBytes(HOST_ENVELOPE_DENIED_SCRIPT)));
                    assertTrue(rejected.text().length() <= 512);
                    assertFalse(rejected.text().contains("fixture-private-host-marker"));
                    assertFalse(rejected.text().contains("rm -rf ."));
                    assertEquals(0, hostEnvelopeRejectedSends);
                    assertEquals(0, hostEnvelopeIndependentReviews);
                    assertEquals(0, hostEnvelopeLegalSends);
                    hostEnvelopeFacts.put("originalFeedbackCallIdSha256", evidenceDigestBytes(rejected.callId()))
                        .put("originalFeedbackSha256", evidenceDigestBytes(rejected.text()));
                    hostEnvelopeFeedbackRequests++;
                }
                if (budgetPreflightJourney && budgetRejectedCallId != null && budgetFeedbackRequests == 0) {
                    var rejected = request.messages().stream().flatMap(message -> message.toolResult().stream())
                        .filter(result -> result.callId().equals(budgetRejectedCallId)).findFirst().orElseThrow();
                    assertTrue(rejected.error());
                    assertTrue(rejected.text().contains("Original goal build allowance cannot fund this exact candidate command"));
                    assertTrue(rejected.text().length() <= 512);
                    try {
                        var events = new DeploymentAgentActionLog(fixedCommands.actual.db()).events(task);
                        budgetCorrectionFacts.put("unknownAtCorrection", events.stream().anyMatch(event -> event.type().equals("STATE") && event.detail().equals("UNKNOWN")))
                            .put("firstAgentRunSends", boundarySends.stream().filter(script -> script.contains("'agent-run'")).count())
                            .put("firstIndependentReviews", independentReviews.get())
                            .put("firstCommandExecuting", events.stream().filter(event -> event.type().equals("COMMAND_EXECUTING")).count())
                            .put("firstCommandNotSent", events.stream().filter(event -> event.type().equals("COMMAND_NOT_SENT")).count())
                            .put("originalFeedbackCallIdSha256", evidenceDigestBytes(rejected.callId()))
                            .put("originalFeedbackSha256", evidenceDigestBytes(rejected.text()));
                    } catch (Exception failure) {
                        throw new AssertionError("Actual budget correction evidence unavailable", failure);
                    }
                    budgetFeedbackRequests++;
                }
                if (evictedFeedbackRecovery) verifyEvictedFeedback(request, data, turn);
                if (missingRecipeAnalysis && turn >= 4) {
                    assertEquals("PLANNED", data.at("/state/stage").asText());
                    assertTrue(data.at("/state/remoteRevisions").isEmpty());
                    var latest = request.messages().stream().flatMap(message -> message.toolResult().stream()).reduce((earlier, last) -> last).orElseThrow();
                    if (turn == 4) {
                        assertFalse(latest.error());
                        assertTrue(latest.text().contains("ENVIRONMENT "));
                        assertTrue(latest.text().contains("assessmentCoverage="));
                        assertTrue(latest.text().contains("NOT_PERFORMED_BY_THIS_OBSERVATION"));
                        assertTrue(latest.text().contains("tools=UNKNOWN"));
                        assertFalse(latest.text().contains("javaMajorVersions"));
                        preflightSends = boundarySends.size();
                        preflightApprovals = approvals.size();
                    } else {
                        assertTrue(latest.error());
                        assertTrue(latest.text().contains("current admissible analysis required"));
                        assertTrue(latest.text().contains("check supplies host facts only"));
                        assertEquals(preflightSends, boundarySends.size());
                        assertEquals(preflightApprovals, approvals.size());
                    }
                }
                if (scenario == ScenarioType.INVALID_TOOL && turn == 2) {
                    var rejected = data.path("observations").get(data.path("observations").size() - 1);
                    assertEquals("PROPOSAL_REJECTED", rejected.path("tool").asText());
                    assertTrue(rejected.path("output").asText().contains("missing [limit, offset]"));
                }
                if (scenario == ScenarioType.REPEATED_BROWSE && turn == 2) {
                    var rejected = data.path("observations").get(data.path("observations").size() - 1);
                    assertEquals("PROPOSAL_REJECTED", rejected.path("tool").asText());
                    assertTrue(rejected.path("output").asText().contains("already retained as o1"));
                    assertTrue(rejected.path("output").asText().contains("use observation"));
                }
                if (scenario == ScenarioType.REPEATED_FEEDBACK && turn == 3) {
                    var recovered = data.path("observations").get(data.path("observations").size() - 1);
                    assertEquals("OBSERVATION", recovered.path("tool").asText());
                    assertTrue(recovered.path("output").asText().startsWith("o1 component="));
                }
                if (scenario == ScenarioType.FEEDBACK_AFTER_REJECTION && turn == 3) {
                    var recovered = data.path("observations").get(data.path("observations").size() - 1);
                    assertEquals("OBSERVATION", recovered.path("tool").asText());
                    assertTrue(recovered.path("output").asText().contains("kind=READ"));
                    assertTrue(recovered.path("output").asText().startsWith("o1 component="));
                    assertEquals(1, data.path("state").path("evidenceIndex").path("entries").size());
                    assertFalse(data.path("state").path("evidenceIndex").path("entries").toString().contains("OBSERVATION"));
                }
                if (data.path("state").has("publication")) {
                    if (nativeFailureRouteJourney && data.at("/state/nativeFailureWithRollback").asBoolean()) {
                        nativeFailureNextStep.compareAndSet(null, data.at("/state/nextStep").deepCopy());
                        nativeFailureContinuationOffered.set(request.tools().stream().anyMatch(tool -> tool.name().equals("continue_deployment")));
                        try (var connection = fixedCommands.actual.db().openConnection(task);
                             var query = connection.prepareStatement("SELECT event_id,event_type,action_id,event_detail FROM deployment_agent_event WHERE task_id=? AND event_type IN ('GRAPH_PROPOSAL','GRAPH_ROLLBACK_RESULT','AI_PUBLICATION_NATIVE_RESULT') ORDER BY event_id")) {
                            query.setString(1, task.id().toString());
                            var captured = new ObjectMapper().createObjectNode().put("task", task.id().toString());
                            var events = captured.putArray("events");
                            try (var rows = query.executeQuery()) {
                                while (rows.next()) {
                                    String type = rows.getString("event_type");
                                    var event = events.addObject().put("id", rows.getLong("event_id")).put("type", type).put("action", rows.getString("action_id"));
                                    if (type.equals("AI_PUBLICATION_NATIVE_RESULT"))
                                        event.set("metadata", new ObjectMapper().readTree(rows.getString("event_detail")));
                                    else if (type.equals("GRAPH_ROLLBACK_RESULT"))
                                        event.put("restored", "RESTORED".equals(rows.getString("event_detail")));
                                }
                            }
                            nativeFailureResultEvidence.compareAndSet(null, captured);
                        } catch (Exception failure) {
                            throw new AssertionError("Actual original native failure evidence read unavailable", failure);
                        }
                    }
                    if (sharedPublication) {
                        var command = request.tools().stream().filter(tool -> tool.name().equals("command")).findFirst().orElseThrow();
                        try {
                            var schema = new ObjectMapper().readTree(command.inputSchema());
                            assertEquals(1, schema.at("/properties/seconds/minimum").asInt());
                            assertEquals(7200, schema.at("/properties/seconds/maximum").asInt());
                        } catch (java.io.IOException invalid) {
                            throw new AssertionError("Actual publication command schema is invalid", invalid);
                        }
                    }
                    int step = publicationDecisions.getAndIncrement() - 1;
                    var layout = data.path("state").path("publication");
                    String output;
                    boolean verified = java.util.stream.StreamSupport.stream(data.path("observations").spliterator(), false)
                        .anyMatch(v -> v.path("tool").asText().equalsIgnoreCase("verify") && v.path("output").asText().contains("VERIFIED_CURRENT_GRAPH_AND_APPLICATION_HEALTH"));
                    if (budgetPreflightJourney && verified) {
                        try {
                            var events = new DeploymentAgentActionLog(fixedCommands.actual.db()).events(task);
                            budgetCorrectionFacts.put("innerEffectsSettledBeforeFinish", DeploymentAgentActionLog.effectsSettled(events.stream()
                                    .filter(event -> !event.type().startsWith("DELIVERY_")).toList()))
                                .put("commandExecutingBeforeFinish", events.stream().filter(event -> event.type().equals("COMMAND_EXECUTING")).count())
                                .put("commandResultsBeforeFinish", events.stream().filter(event -> event.type().equals("COMMAND_RESULT")).count());
                        } catch (Exception failure) {
                            throw new AssertionError("Actual pre-finish evidence unavailable", failure);
                        }
                    }
                    if (acceptNativeFailure && step >= 0) {
                        assertTrue(java.util.stream.StreamSupport.stream(data.path("observations").spliterator(), false)
                                .anyMatch(value -> value.path("tool").asText().equalsIgnoreCase("publish")
                                    && value.path("output").asText().contains("\"outcome\":\"FAILED\"")
                                    && value.path("output").asText().contains("\"rollbackConfirmed\":true")),
                            "A new actual AiRequest must expose the native failed publication and confirmed rollback before the fake model chooses finish_failure");
                    }
                    if (sharedPublication)
                        output = acceptNativeFailure && step >= 0
                            ? "{\"tool\":\"" + (continueNativeFailure ? "continue_deployment" : "finish_failure") + "\",\"arguments\":{\"reason\":\"Accept this actual failed native attempt with confirmed rollback\"}}"
                            : "{\"tool\":\"" + (step < 0 ? "publish" : verified ? "finish" : "verify") + "\",\"arguments\":{}}";
                    else if (step < 0) output = "{\"tool\":\"stage\",\"arguments\":{}}";
                    else if (verified) output = "{\"tool\":\"finish\",\"arguments\":{}}";
                    else if (step < layout.size() || scenario == ScenarioType.WHOLE_HEALTH_FAILURE && step == layout.size() + 1) {
                        var part = layout.get(step < layout.size() ? step : layout.size() - 1);
                        String script = "printf 'WTL_CONTROLLED_PUBLISH=" + part.path("unit").asText() + "|" + part.path("release").asText() + "\\n'";
                        modelScripts.add(script);
                        output = "{\"tool\":\"command\",\"arguments\":{\"script\":" + new ObjectMapper().valueToTree(script)
                            + ",\"seconds\":10,\"reason\":\"" + (step < layout.size() ? "Publish exact AI plan" : "Repair actual failed health gate") + "\"}}";
                    } else output = "{\"tool\":\"verify\",\"arguments\":{}}";
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(nativeResponse(request, assess(output, data))), Optional.empty());
                }
                proposedSourceRevision.set(data.path("state").path("sourceRevision").asText());
                var remoteRevisions = new LinkedHashMap<String, String>();
                data.path("state").path("remoteRevisions").fields().forEachRemaining(entry -> {
                    assertTrue(entry.getValue().isTextual());
                    assertEquals("c".repeat(64), entry.getValue().textValue());
                    remoteRevisions.put(entry.getKey(), entry.getValue().textValue());
                });
                proposedRemoteRevisions.set(Map.copyOf(remoteRevisions));
                if (editFrozenSource != null)
                    assertEquals(frozenMaterial.get().sha256(), data.path("state").path("sourceRevision").asText(),
                        "The captured material must be the source browser's actual approved revision");
                if (scenario == ScenarioType.CANCELLED)
                    return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
                String output = switch (scenario) {
                    case DUPLICATE_JSON -> "{\"tool\":\"deliver\",\"tool\":\"inspect\",\"arguments\":{}}";
                    case TRAILING_JSON -> "{\"tool\":\"inspect\",\"arguments\":{}} {}";
                    case INVALID_TOOL -> "{\"tool\":\"list\",\"arguments\":{\"path\":\".\"}}";
                    case REPEATED_BROWSE ->
                        "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":" + (turn == 1 ? 99 : 100) + "}}";
                    case REPEATED_FEEDBACK -> evictedFeedbackRecovery ? switch (turn) {
                        case 0 ->
                            "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":100}}";
                        case 1 -> "{\"tool\":\"list\",\"arguments\":{\"path\":\".\",\"offset\":0,\"limit\":100}}";
                        default ->
                            "{\"tool\":\"observation\",\"arguments\":{\"id\":\"o1\",\"offset\":" + (turn == 4 ? 1 : 0) + ",\"limit\":6000}}";
                    } : turn == 0
                        ? "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":100}}"
                        : "{\"tool\":\"observation\",\"arguments\":{\"id\":\"o1\",\"offset\":0,\"limit\":6000}}";
                    case FEEDBACK_AFTER_REJECTION -> switch (turn) {
                        case 0, 1 ->
                            "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":100}}";
                        case 2 ->
                            "{\"tool\":\"observation\",\"arguments\":{\"id\":\"o1\",\"offset\":0,\"limit\":6000}}";
                        default ->
                            "{\"tool\":\"identify\",\"arguments\":{\"components\":[{\"component\":\"api\",\"root\":\"api\",\"technology\":\"Unconfirmed\",\"runtime\":\"Unconfirmed\",\"resources\":\"Unconfirmed\",\"uncertainties\":\"Read actual code\",\"evidence\":[\"missing\"]}]}}";
                    };
                    case NOVEL_READ_PROGRESS -> invalidCapabilityAssessmentTool != null ? switch (turn) {
                        case 0 ->
                            "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":100}}";
                        case 1 -> identify(data);
                        case 2 -> plan();
                        default -> invalidCapabilityAssessmentTool.equals("prepare_tools")
                            ? "{\"tool\":\"prepare_tools\",\"arguments\":{\"component\":\"api\",\"recipe\":{\"version\":\"\",\"primary\":\"public\",\"secondary\":\"\",\"jvmTarget\":\"\",\"servicePort\":0}}}"
                            : "{\"tool\":\"database_inspect\",\"arguments\":{\"component\":\"api\",\"scope\":\"REMOTE\"}}";
                    } : missingRecipeAnalysis ? switch (turn) {
                        case 0 ->
                            "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":100}}";
                        case 1 -> identify(data);
                        case 2 -> plan();
                        case 3 -> "{\"tool\":\"check\",\"arguments\":{\"component\":\"api\"}}";
                        default ->
                            "{\"tool\":\"prepare_tools\",\"arguments\":{\"component\":\"api\",\"recipe\":{\"version\":\"\",\"primary\":\"public\",\"secondary\":\"\",\"jvmTarget\":\"\",\"servicePort\":0}}}";
                    } : sourceReadPagination ? switch (turn) {
                        case 0 ->
                            "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":100}}";
                        case 1 ->
                            "{\"tool\":\"observation\",\"arguments\":{\"id\":\"o1\",\"offset\":0,\"limit\":6000}}";
                        case 2 ->
                            "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":100,\"limit\":100}}";
                        case 3 ->
                            "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":100,\"limit\":99}}";
                        default ->
                            "{\"tool\":\"unable\",\"arguments\":{\"reason\":\"Controlled end after checking actual source continuation\"}}";
                    } : switch (turn) {
                        case 0, 2 ->
                            "{\"tool\":\"identify\",\"arguments\":{\"components\":[{\"component\":\"api\",\"root\":\"api\",\"technology\":\"Unconfirmed\",\"runtime\":\"Unconfirmed\",\"resources\":\"Unconfirmed\",\"uncertainties\":\"Read actual code\",\"evidence\":[\"missing\"]}]}}";
                        case 1, 3 -> {
                            String field = progressBrowseTool.equals("search") ? "query" : "path";
                            String value = progressBrowseTool.equals("search")
                                ? emptyProgressPage ? "NoSuchLiteralForProgress" : turn == 1 ? "Build" : "protected"
                                : progressBrowseTool.equals("list") ? turn == 1 ? "." : "api"
                                : turn == 1 ? "README.md" : "api/main.py";
                            int offset = emptyProgressPage && !progressBrowseTool.equals("search") ? 1000 : 0;
                            yield "{\"tool\":\"" + progressBrowseTool + "\",\"arguments\":{\"" + field + "\":\"" + value
                                + "\",\"offset\":" + offset + ",\"limit\":100}}";
                        }
                        default ->
                            "{\"tool\":\"unable\",\"arguments\":{\"reason\":\"Controlled end after new actual source evidence\"}}";
                    };
                    case BUDGET ->
                        "{\"tool\":\"list\",\"arguments\":{\"path\":\".\",\"offset\":" + turn + ",\"limit\":1}}";
                    default -> {
                        if (baselineJourney && !baselineInspectProposed) {
                            baselineInspectProposed = true;
                            yield "{\"tool\":\"inspect\",\"arguments\":{}}";
                        }
                        if (baselineJourney && !baselineCheckProposed && !data.path("state").path("components").isEmpty()) {
                            baselineCheckProposed = true;
                            yield "{\"tool\":\"check\",\"arguments\":{\"component\":\"api\"}}";
                        }
                        boolean readObserved = java.util.stream.StreamSupport.stream(data.path("observations").spliterator(), false)
                            .anyMatch(v -> v.path("tool").asText().equals("READ"));
                        if (!independentIdentification && readObserved) {
                            independentIdentification = true;
                            yield identify(data);
                        }
                        if (compareStaticTool && !toolObserved && !data.path("state").path("components").isEmpty()) {
                            toolObserved = true;
                            assertFalse(data.path("state").path("judgement").path("identification").isEmpty());
                            yield "{\"tool\":\"analyze\",\"arguments\":{\"component\":\"api\",\"type\":\"PYTHON_SERVICE\"}}";
                        }
                        yield proposal(proposalDecisions.getAndIncrement(), data);
                    }
                };
                String submitted = assess(output, data);
                if (invalidCapabilityAssessmentTool != null && turn >= 3) {
                    try {
                        var altered = new ObjectMapper().readTree(submitted);
                        ((com.fasterxml.jackson.databind.node.ObjectNode) altered.path("assessment")).put("private-arbitrary-field", "fixture-sensitive-body");
                        submitted = altered.toString();
                    } catch (Exception invalid) {
                        throw new AssertionError(invalid);
                    }
                }
                if (differentPlanRejections && planRejectionTurns < 2 && output.contains("\"tool\":\"plan\"")) {
                    try {
                        var altered = new ObjectMapper().readTree(submitted);
                        var args = altered.path("arguments");
                        if (planRejectionTurns++ == 0)
                            ((com.fasterxml.jackson.databind.node.ObjectNode) altered.path("assessment")).put("resolution", "");
                        else
                            ((com.fasterxml.jackson.databind.node.ObjectNode) args.path("components").get(0)).putArray("evidence").add("o999");
                        submitted = altered.toString();
                        proposalDecisions.decrementAndGet();
                    } catch (Exception invalid) {
                        throw new AssertionError(invalid);
                    }
                }
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(
                    nativeResponse(request, submitted)),
                    Optional.empty());
            }
        };
    }

    private void verifyEvictedFeedback(AiRequest request, JsonNode state, int turn) {
        if (turn < 2) return;
        var entries = state.at("/state/evidenceIndex/entries");
        assertEquals(2, entries.size(), "Character queries never create new evidence, including recovery after eviction");
        assertEquals(List.of("o1", "o2"), java.util.stream.StreamSupport.stream(entries.spliterator(), false)
            .map(entry -> entry.path("id").asText()).toList());
        assertEquals("READ", entries.get(0).path("kind").asText());
        assertEquals("LIST", entries.get(1).path("kind").asText());
        assertEquals(0, entries.get(0).path("planRevision").asInt());
        assertEquals("INVESTIGATING", state.at("/state/stage").asText());
        assertEquals(1, state.at("/state/readFiles").size());
        var results = request.messages().stream().flatMap(message -> message.toolResult().stream()).toList();
        if (turn == 3) {
            String body = results.getLast().text();
            assertTrue(body.startsWith("o1 component="));
            previouslyQueriedBody.set(body);
        }
        if (turn >= 4) {
            boolean visible = results.stream().anyMatch(result -> result.text().equals(previouslyQueriedBody.get()));
            assertEquals(turn != 5, visible, "Visibility is taken from the actual trimmed request, including opaque provider payload bounds");
        }
        if (turn == 6) {
            assertEquals(previouslyQueriedBody.get(), results.getLast().text());
            assertFalse(results.getLast().error());
            assertEquals(73, state.path("remaining").asInt());
        }
    }

    private void verifyReadPagination(AiRequest request, JsonNode state, int turn) {
        if (turn == 0) return;
        String output = request.messages().stream().flatMap(message -> message.toolResult().stream())
            .reduce((earlier, latest) -> latest).orElseThrow().text();
        var entries = state.at("/state/evidenceIndex/entries");
        assertEquals(turn < 3 ? 1 : 2, entries.size(), "Querying or repeating a page never creates READ evidence");
        var first = entries.get(0);
        assertEquals("o1", first.path("id").asText());
        assertEquals(135, first.path("totalLines").asInt());
        assertEquals(100, first.path("nextLine").asInt());
        assertTrue(first.path("hasMore").asBoolean());
        assertFalse(first.path("truncated").asBoolean(), "Source continuation is independent of character truncation");
        if (turn == 1) {
            assertTrue(output.startsWith("o1\nREADME.md\nsourceSha256="));
            assertTrue(output.contains("totalLines=135 offset=0 nextLine=100 hasMore=true"));
            assertFalse(output.contains("TAIL_ENTRYPOINT_FACT"));
            firstReadPageDigest.set(output.lines().filter(line -> line.startsWith("sourceSha256=")).findFirst().orElseThrow());
        } else if (turn == 2) {
            assertTrue(output.startsWith("o1 component="));
            assertTrue(output.contains("lineOffset=0 totalLines=135 nextLine=100 hasMore=true"));
            assertTrue(output.contains("characters of this retained READ body only"));
            assertFalse(output.contains("TAIL_ENTRYPOINT_FACT"));
        } else {
            var last = entries.get(1);
            assertEquals("o2", last.path("id").asText());
            assertEquals(100, last.path("lineOffset").asInt());
            assertEquals(135, last.path("nextLine").asInt());
            assertFalse(last.path("hasMore").asBoolean());
            if (turn == 3) {
                assertTrue(output.startsWith("o2\nREADME.md\nsourceSha256="));
                assertTrue(output.contains(firstReadPageDigest.get()));
                assertTrue(output.contains("135: TAIL_ENTRYPOINT_FACT"));
                assertTrue(output.contains("totalLines=135 offset=100 nextLine=135 hasMore=false"));
            } else assertTrue(output.contains("Unchanged evidence page already retained as o2"));
        }
    }

    /** Accepts exact original candidates or independently reconstructed fixed requests, never host rememberAuthorization. / 仅接纳精确原候选或独立重建的固定请求，绝不冒充宿主授权。 */
    private OperationResult<AiResponse> exactIndependentReview(AiRequest request, TaskContext task) {
        try {
            var envelope = new ObjectMapper().readTree(request.messages().getLast().text());
            if (envelope.path("currentEvidence").asText().equals("FIRST_USER_MESSAGE_AFTER_BASE_SYSTEM")) {
                assertEquals(AiRole.USER, request.messages().get(1).role());
                var original = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(request.messages().get(1).text());
                assertEquals(original.path("reviewBinding"), envelope.path("reviewBinding"));
                original.setAll((com.fasterxml.jackson.databind.node.ObjectNode) envelope);
                return nativeIndependentReview(request, task, original);
            }
            if (envelope.has("currentRequest")) return nativeIndependentReview(request, task, envelope);
            return exactIndependentReviewUnchecked(request, task);
        } catch (java.io.IOException invalid) {
            fixtureFailure.compareAndSet(null, invalid);
            throw new AssertionError("Independent fixture received malformed native evidence", invalid);
        } catch (RuntimeException | AssertionError failure) {
            fixtureFailure.compareAndSet(null, failure);
            if (finalizationFault != null) failure.printStackTrace();
            throw failure;
        }
    }

    private OperationResult<AiResponse> nativeIndependentReview(AiRequest request, TaskContext task, JsonNode envelope) throws java.io.IOException {
        assertEquals(originalAiTask.get(), task.id());
        assertFalse(request.jsonObjectResponse());
        assertEquals(AiRole.SYSTEM, request.messages().getFirst().role());
        assertEquals(AiRole.USER, request.messages().getLast().role());
        assertEquals(0, (request.messages().size() - 3) % 3, "Fixed evidence and dynamic tail retain every complete native investigation batch");
        for (int index = 2; index < request.messages().size() - 1; index += 3) {
            assertEquals(AiRole.USER, request.messages().get(index).role());
            assertTrue(request.messages().get(index).text().startsWith("Previous tool interaction batch "));
            var decision = request.messages().get(index + 1);
            assertEquals(AiRole.ASSISTANT, decision.role());
            assertEquals(1, decision.toolCalls().size());
            assertTrue(Set.of("request", "evidence").contains(decision.toolCalls().getFirst().name()));
            assertTrue(decision.continuation().isPresent());
            var receipt = request.messages().get(index + 2);
            assertEquals(AiRole.TOOL, receipt.role());
            assertEquals(decision.toolCalls().getFirst().id(), receipt.toolResult().orElseThrow().callId());
            assertFalse(receipt.toolResult().orElseThrow().error());
        }
        assertEquals(Set.of("task", "binding", "reviewBinding", "currentRequest", "evidencePages", "sourceRevisions",
            "remainingTaskTools", "receiptSha256", "discussion", "skills", "identityRoles", "executableInput", "reviewMemory",
            "maxCallsPerBatch", "maxCallsThisBatch", "currentEvidence", "submitResultMustBeAlone"), keys(envelope));
        assertEquals(4, envelope.path("maxCallsPerBatch").asInt());
        assertTrue(envelope.path("submitResultMustBeAlone").asBoolean());
        assertTrue(envelope.path("identityRoles").isObject());
        assertTrue(envelope.path("executableInput").isObject());
        assertEquals(envelope.path("reviewBinding").asText(), envelope.path("reviewMemory").path("reviewBinding").asText());
        assertEquals(task.id().toString(), envelope.path("task").asText());
        assertTrue(envelope.path("currentRequest").isObject());
        assertTrue(envelope.path("evidencePages").isObject());
        assertFalse(envelope.has("reviewSession"));
        var binding = envelope.path("binding");
        assertEquals(Set.of("server", "target", "sourceRevision"), keys(binding));
        assertEquals("server", binding.path("server").asText());
        try {
            var journal = new DeploymentAgentActionLog(fixedCommands.actual.db());
            assertEquals(journal.find(task).orElseThrow().identity().targetDigest(), binding.path("target").asText());
            assertEquals(journal.events(task).stream().filter(event -> event.type().equals("SOURCE")).findFirst().orElseThrow().detail(),
                binding.path("sourceRevision").asText());
        } catch (Exception unavailable) {
            throw new AssertionError("Native approval binding must match the authentic original target and source", unavailable);
        }
        String reviewBinding = envelope.path("reviewBinding").asText();
        assertTrue(reviewBinding.matches("[0-9a-f]{64}"));
        var json = new ObjectMapper();
        var schema = json.readTree(request.tools().stream().filter(tool -> tool.name().equals("evidence")).findFirst().orElseThrow().inputSchema());
        assertEquals(8000, schema.at("/properties/limit/maximum").asInt());
        assertTrue(request.tools().stream().noneMatch(tool -> tool.name().equals("host_command")));
        var collected = nativeApprovalFacts.computeIfAbsent(reviewBinding, ignored -> new StringBuilder());
        var receipts = request.messages().stream().flatMap(message -> message.toolResult().stream()).toList();
        if (!receipts.isEmpty()) {
            var receipt = receipts.getLast();
            assertTrue(nativeApprovalPageReceipts.add(receipt.callId()), "Each real page receipt advances the original investigation once");
            assertFalse(receipt.error());
            var call = request.messages().stream().flatMap(message -> message.toolCalls().stream())
                .filter(value -> value.id().equals(receipt.callId())).findFirst().orElseThrow();
            var args = json.readTree(call.arguments());
            assertEquals(8000, args.path("limit").asInt());
            var response = json.readTree(receipt.text());
            assertEquals("UNTRUSTED_DATA", response.path("trust").asText());
            assertEquals(reviewBinding, response.path("reviewBinding").asText());
            var page = response.path("value");
            assertTrue(page.path("text").isTextual());
            StringBuilder delivered;
            if (call.name().equals("request")) {
                assertEquals(Set.of("part", "offset", "limit"), keys(args));
                String part = args.path("part").asText();
                assertTrue(Set.of("script", "stdin").contains(part));
                var descriptor = envelope.path("executableInput").path(part);
                assertEquals(descriptor.path("sha256").asText(), page.path("sha256").asText());
                assertEquals(descriptor.path("total").asInt(), page.path("total").asInt());
                delivered = nativeApprovalBodies.computeIfAbsent(reviewBinding + ":" + part, ignored -> new StringBuilder());
            } else {
                assertEquals("evidence", call.name());
                assertEquals(Set.of("pointer", "offset", "limit"), keys(args));
                assertEquals("", args.path("pointer").asText());
                delivered = collected;
                nativeApprovalEvidencePages++;
            }
            assertEquals(delivered.length(), args.path("offset").asInt());
            assertEquals(delivered.length(), page.path("offset").asInt());
            delivered.append(page.path("text").asText());
            assertEquals(delivered.length(), page.path("next").asInt());
            assertEquals(delivered.length() < page.path("total").asInt(), page.path("hasMore").asBoolean());
            if (!page.path("hasMore").asBoolean())
                assertEquals(DeploymentAgentInputs.hash(delivered.toString()), page.path("sha256").asText());
            if (call.name().equals("evidence") && !page.path("hasMore").asBoolean()) {
                assertEquals(page.path("total").asInt(), collected.length());
                assertEquals(DeploymentAgentInputs.hash(collected.toString()), page.path("sha256").asText());
                var facts = json.readTree(collected.toString());
                assertFalse(facts.has("reviewSession"));
                assertFalse(facts.has("currentReviewContract"));
                var decision = exactIndependentReviewFacts(request, task, facts);
                var verdict = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(decision.value().orElseThrow().text());
                verdict.putArray("missingEvidence");
                verdict.putArray("adjustments");
                verdict.putArray("candidateCommands");
                assertEquals(Set.of("decision", "requiresUserConfirmation", "reason", "missingEvidence", "adjustments", "candidateCommands"), keys(verdict));
                nativeApprovalPagedReviews++;
                if (collected.length() > 8000) nativeApprovalMultiPageReviews++;
                nativeApprovalVerdicts++;
                nativeApprovalFacts.remove(reviewBinding);
                nativeApprovalBodies.keySet().removeIf(key -> key.startsWith(reviewBinding + ":"));
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse(verdict.toString(), "independent", "stop",
                    new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))), Optional.empty());
            }
        }
        for (String part : List.of("script", "stdin")) {
            var descriptor = envelope.path("executableInput").path(part);
            if (descriptor.isMissingNode() || descriptor.has("text")) continue;
            var body = nativeApprovalBodies.computeIfAbsent(reviewBinding + ":" + part, ignored -> new StringBuilder());
            if (body.length() < descriptor.path("total").asInt()) {
                var args = json.createObjectNode().put("part", part).put("offset", body.length()).put("limit", 8000);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "independent", "tool_calls",
                    new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                    List.of(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("native_" + (++nativeIds), "request", args.toString())), Optional.empty())), Optional.empty());
            }
        }
        var args = json.createObjectNode().put("pointer", "").put("offset", collected.length()).put("limit", 8000);
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "independent", "tool_calls",
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("native_" + (++nativeIds), "evidence", args.toString())), Optional.empty())), Optional.empty());
    }

    private OperationResult<AiResponse> exactIndependentReviewUnchecked(AiRequest request, TaskContext task) {
        if (!originalAiTask.get().equals(task.id())) fixedCommands.requireInspection(task);
        assertTrue(request.messages().size() >= 2);
        assertEquals(0, (request.messages().size() - 2) % 3, "Prior approval history retains complete native request, decision and receipt groups");
        assertEquals(AiRole.SYSTEM, request.messages().getFirst().role());
        assertEquals(AiRole.USER, request.messages().get(1).role());
        assertEquals(AiRole.USER, request.messages().getLast().role());
        for (int index = 1; index < request.messages().size() - 1; index += 3) {
            var prior = request.messages().get(index);
            var decision = request.messages().get(index + 1);
            var receipt = request.messages().get(index + 2);
            assertEquals(AiRole.USER, prior.role());
            assertTrue(prior.text().startsWith("Previous tool interaction batch "));
            assertEquals(AiRole.ASSISTANT, decision.role());
            assertEquals(1, decision.toolCalls().size());
            assertEquals("submit_result", decision.toolCalls().getFirst().name());
            assertTrue(decision.continuation().isPresent(), "The actual provider continuation remains attached to its original decision");
            assertEquals(AiRole.TOOL, receipt.role());
            assertEquals(decision.toolCalls().getFirst().id(), receipt.toolResult().orElseThrow().callId());
            assertFalse(receipt.toolResult().orElseThrow().error());
            assertTrue(receipt.toolResult().orElseThrow().text().startsWith("REVIEW_RECORDED:"));
        }
        assertFalse(request.jsonObjectResponse());
        final JsonNode envelope;
        try {
            envelope = new ObjectMapper().readTree(request.messages().getLast().text());
        } catch (Exception invalid) {
            throw new AssertionError("Independent fixture received malformed action evidence", invalid);
        }
        if (envelope.has("reviewSession")) {
            var binding = envelope.path("reviewSession");
            assertEquals(Set.of("task", "server", "fingerprint", "originalSourceRevision", "configurationRevision"), keys(binding));
            assertEquals(task.id().toString(), binding.path("task").asText());
            assertEquals("server", binding.path("server").asText());
            assertEquals("SHA256:" + "A".repeat(43), binding.path("fingerprint").asText());
            assertEquals(revision.get(), binding.path("configurationRevision").asText());
            try {
                var events = new DeploymentAgentActionLog(fixedCommands.actual.db()).events(task);
                assertEquals(events.stream().filter(event -> event.type().equals("SOURCE")).findFirst().orElseThrow().detail(),
                    binding.path("originalSourceRevision").asText());
            } catch (Exception unavailable) {
                throw new AssertionError("Current approval binding must match the authentic original journal", unavailable);
            }
            assertTrue(envelope.path("currentReviewContract").isTextual());
            assertFalse(envelope.path("currentReviewContract").asText().isBlank());
        } else
            assertEquals(2, request.messages().size(), "Only unbound fresh inspection reviews lack a continuing transcript");
        return exactIndependentReviewFacts(request, task, envelope);
    }

    private OperationResult<AiResponse> exactIndependentReviewFacts(AiRequest request, TaskContext task, JsonNode envelope) {
        if (!envelope.has("type")) {
            assertTrue(envelope.path("serverTargets").isArray());
            assertTrue(envelope.path("operation").isTextual());
            assertTrue(request.messages().getFirst().text().contains(
                gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources.text(
                    gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval.class, "command-review.md")),
                "The complete current fixed-command review contract must reach the independent reviewer");
            boolean high = fixedCommands.review(envelope, task);
            if (envelope.path("operation").asText().equals("deployment.agent.helper.v1")
                && envelope.path("installedBuildEntryPath").isTextual()) {
                assertEquals("EXECUTABLE_SCRIPT", envelope.path("inputKind").asText());
                assertTrue(envelope.path("exactAction").asText().contains("'agent-run'"));
                String script = envelope.path("completeExecutableInput").asText();
                independentReviews.incrementAndGet();
                commandReviews.add(revision.get());
                if (high)
                    assertEquals(1, highCandidateReviews.incrementAndGet(), "The same refused script cannot reach full APP review again");
                else assertEquals("printf artifact > $WTL_OUTPUT/run.sh", script);
                if (invalidCompleteCommandReview)
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse(
                        "{\"decision\":\"DENY\",\"requiresUserConfirmation\":true}", "independent", "stop",
                        new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))), Optional.empty());
                if (high && independentCommandDenial) {
                    fixedCommands.modelDenied.incrementAndGet();
                    var denial = new ObjectMapper().createObjectNode().put("decision", "DENY")
                        .put("requiresUserConfirmation", true).put("reason", INDEPENDENT_DENIAL_REASON);
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse(denial.toString(), "independent", "stop",
                        new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))), Optional.empty());
                }
            }
            return approved("Exact original fixed command", high);
        }
        assertEquals("PATCH", envelope.path("type").asText(), "Agent COMMAND review must use the actual complete fixed request");
        assertEquals("server", envelope.path("target").asText());
        assertEquals(Set.of("candidateRoot", "path", "sourceRevision", "beforeDigest", "edits"), keys(envelope.path("facts")));
        return approved("Exact structured patch", false);
    }

    /** Pauses a proposed command at its original safe checkpoint before preparation or complete command approval. */
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
            Optional.of(new AiResponse("{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":" + high + ",\"reason\":\"" + reason + "\"}",
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

        private final Set<List<String>> sentRequests = ConcurrentHashMap.newKeySet();

        private final AtomicInteger reviews = new AtomicInteger(), sends = new AtomicInteger(), denied = new AtomicInteger();

        private final AtomicInteger modelDenied = new AtomicInteger();

        private final List<String> agentInputDigests = new CopyOnWriteArrayList<>();

        private record Proof(UUID task, String operation, String revision, String exact,
                             String inputKind, Optional<String> executable, Optional<String> installed,
                             String script, String inputBase64, Duration timeout, long outputLimit) {
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
                assertFalse(facts.has("policy"), "Human policy cannot change independent review criteria");
                assertEquals(scenario != ScenarioType.APPROVAL_DRIFT && rememberAuthorization, identity.rememberAuthorization());
                assertEquals(List.of("server"), new ObjectMapper().convertValue(facts.path("serverTargets"), List.class));
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
                if (facts.path("operation").asText().equals("deployment.helper.install.files.custom.v1")) {
                    assertTrue(routes.contains(business));
                    String reconstructed = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.reconstruct(facts.path("completeExecutableInput"));
                    String operation = gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript.parseOperation(reconstructed);
                    String canonical = gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript.render(operation);
                    assertEquals(canonical, reconstructed, "Every observed installer byte matches the current canonical Agent bootstrap");
                    var targets = new HashSet<gold.debug.windowstolinux.appapi.task.action.ActionTarget>();
                    for (var target : facts.path("resourceTargets")) {
                        assertEquals(Set.of("serverId", "kind", "id"), keys(target));
                        assertTrue(targets.add(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(target.path("serverId").asText(),
                            target.path("kind").asText(), target.path("id").asText())));
                    }
                    assertEquals(gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentHelperBootstrapEvidence.targets(new ServerId("server")), targets);
                    add(expected, task, "deployment.helper.install.files.custom.v1", business,
                        gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript.LAUNCHER,
                        canonical.getBytes(StandardCharsets.UTF_8), Duration.ofHours(2), 131072, Optional.of(canonical),
                        Optional.of(DeploymentManagedHelperBundle.renderScript()));
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
                if (facts.path("operation").asText().startsWith("deployment.model.command.")) {
                    var commands = new ObjectMapper().readTree(tasks.findCheckpoint(task, DeploymentModelCommandRunner.KEY).orElseThrow().snapshot().payload());
                    assertEquals("server", commands.path("server").asText());
                    assertEquals(business, commands.path("modelRevision").asText());
                    for (var item : commands.path("commands")) {
                        UUID operation = UUID.fromString(item.path("operation").asText());
                        if (facts.path("operation").asText().equals("deployment.model.command.v1")) {
                            if (!item.path("phase").asText().equals("INTENT")) continue;
                            assertEquals("INTENT", item.path("phase").asText());
                            String script = modelScripts.stream().filter(value -> sha(value.getBytes(StandardCharsets.UTF_8)).equals(item.path("body").asText())).findFirst().orElseThrow();
                            var commandFacts = facts.path("modelCommand");
                            assertEquals(script, commandFacts.path("modelScript").asText());
                            String rationale = commandFacts.path("rationale").asText();
                            boolean hostPrerequisite = hostEnvelopeDenialJourney && script.equals(HOST_ENVELOPE_LEGAL_SCRIPT);
                            if (hostPrerequisite)
                                assertEquals("Host prerequisite only; no application publication: " + HOST_ENVELOPE_REASON, rationale);
                            else
                                assertTrue(Set.of("Publish exact AI plan", "Repair actual failed health gate").contains(rationale));
                            var renderer = DeploymentModelCommandIdentity.class.getDeclaredMethod("envelope", UUID.class, UUID.class, String.class, String.class, String.class);
                            renderer.setAccessible(true);
                            String executable = (String) renderer.invoke(null, task.id(), operation, item.path("body").asText(), script, rationale);
                            assertTrue(executable.contains("set -C"));
                            assertTrue(executable.contains("--noprofile --norc -se"));
                            add(expected, task, "deployment.model.command.v1", business, DeploymentModelCommandIdentity.LAUNCHER,
                                executable.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(item.path("seconds").asInt()), 65536,
                                Optional.of(executable), Optional.empty());
                        } else {
                            empty(expected, task, "deployment.model.command.status.v1", business,
                                DeploymentModelCommandIdentity.receiptStatus(task.id(), operation, item.path("body").asText(),
                                    gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()), 30, 1024);
                        }
                    }
                }
                var matches = expected.stream().filter(value -> value.operation().equals(facts.path("operation").textValue())
                    && value.revision().equals(business) && value.exact().equals(facts.path("exactAction").textValue())).toList();
                assertEquals(1, matches.size(), () -> "Fixed evidence must match one independently reconstructed original request: operation="
                    + facts.path("operation").asText() + " revision=" + business + " requestSha256="
                    + sha(facts.path("exactAction").asText().getBytes(StandardCharsets.UTF_8)) + " expected="
                    + expected.stream().filter(value -> value.operation().equals(facts.path("operation").asText()))
                    .map(value -> value.revision() + ":" + sha(value.exact().getBytes(StandardCharsets.UTF_8))).toList());
                var proof = matches.getFirst();
                var fields = new HashSet<>(Set.of("operation", "serverTargets", "resourceTargets", "businessRevision", "businessRevisionMeaning", "presentationLocale", "exactAction", "inputKind", "authorizationPolicy", "effectSummary"));
                if (facts.has("reviewSession")) fields.addAll(Set.of("reviewSession", "currentReviewContract"));
                proof.executable().ifPresent(value -> fields.add("completeExecutableInput"));
                if (gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandProtocol.registeredOperation(proof.operation())) {
                    var targets = new HashSet<gold.debug.windowstolinux.appapi.task.action.ActionTarget>();
                    for (var target : facts.path("resourceTargets")) targets.add(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(
                        target.path("serverId").asText(), target.path("kind").asText(), target.path("id").asText()));
                    try (var reconstructed = new CommandRequest(proof.script(), Base64.getDecoder().decode(proof.inputBase64()), proof.timeout(), proof.outputLimit())) {
                        var intent = RemoteActions.command(new ServerId("server"), business, reconstructed).withSemantics(
                            new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(proof.operation(), targets));
                        var currentContext = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                            (proxy, method, arguments) -> {
                                if (method.getName().equals("tasks")) return tasks;
                                try { return method.invoke(actual, arguments); }
                                catch (InvocationTargetException failure) { throw failure.getCause(); }
                            });
                        var commonJson = new ObjectMapper();
                        var common = commonJson.readTree(commonJson.writeValueAsBytes(gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandEvidence.facts(
                            intent, reconstructed, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original(), currentContext, task)));
                        // Decoder contracts have separate tests; this workflow fixture binds their facts to its independently rebuilt request.
                        for (String field : Set.of("managedApplication", "workloadInput", "publicationSnapshot", "originalPublicationStatus",
                                "publicationMaintenance", "stagedConfiguration", "snapshotTaskMatchesOriginalContext", "modelCommandReceiptStatus")) {
                            if (common.has(field)) {
                                fields.add(field);
                                assertEquals(common.path(field), facts.path(field), "Complete common facts must describe the original reconstructed request: " + field);
                            }
                        }
                    }
                }
                if (proof.operation().equals("deployment.candidate.capacity.v1")) {
                    fields.add("capacityProbe");
                    var capacity = facts.path("capacityProbe");
                    assertEquals(Set.of("comparison", "target", "scriptBytes", "meaning"), keys(capacity));
                    var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                    String capacityScript = gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel.capacityScript(layout);
                    String capacityTarget = gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel.capacityTarget(layout);
                    assertEquals("EXACT_PRODUCT_CAPACITY_SCRIPT_EMPTY_STDIN_AND_DECLARED_TARGET", capacity.path("comparison").asText());
                    assertEquals(capacityTarget, capacity.path("target").asText());
                    assertEquals(capacityScript.getBytes(StandardCharsets.UTF_8).length, capacity.path("scriptBytes").asInt());
                    assertTrue(capacity.path("meaning").asText().startsWith("Fixed OS test/stat filesystem-capacity observation"));
                    try (var capacityRequest = new CommandRequest(capacityScript, new byte[0], Duration.ofSeconds(20), 1024)) {
                        assertEquals(RemoteActions.command(new ServerId("server"), business, capacityRequest).exactAction(), proof.exact());
                    }
                    assertEquals(1, facts.path("resourceTargets").size());
                    var target = facts.path("resourceTargets").get(0);
                    assertEquals(Set.of("serverId", "kind", "id"), keys(target));
                    assertEquals("server", target.path("serverId").asText());
                    assertEquals("deployment-workspace", target.path("kind").asText());
                    assertEquals(capacityTarget, target.path("id").asText());
                }
                if (proof.operation().equals("deployment.model.command.v1")) {
                    fields.addAll(Set.of("commandOrigin", "executionScope", "modelCommand"));
                    assertEquals("AI_GENERATED_BODY_WITH_PRODUCT_RECEIPT_WRAPPER", facts.path("commandOrigin").asText());
                    assertEquals(Set.of("task", "operation", "bodySha256", "rationale", "modelScript", "productReceiptDirectory"),
                        keys(facts.path("modelCommand")));
                    assertEquals(task.id().toString(), facts.at("/modelCommand/task").asText());
                    assertTrue(facts.path("executionScope").asText().startsWith("Selected SSH host identity"));
                    if (hostEnvelopeDenialJourney && facts.at("/modelCommand/modelScript").asText().equals(HOST_ENVELOPE_LEGAL_SCRIPT))
                        hostEnvelopeIndependentReviews++;
                }
                if (proof.operation().equals("deployment.helper.install.v1") || proof.operation().equals("deployment.helper.install.tracked.v1")
                    || proof.operation().equals("deployment.helper.install.files.v1") || proof.operation().equals("deployment.helper.install.files.custom.v1")) {
                    fields.addAll(Set.of("expectedHelperPath", "expectedHelperSha256"));
                    if (facts.has("reviewSession")) {
                        fields.add("helperInstallationSourceEvidence");
                        assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256,
                            facts.at("/helperInstallationSourceEvidence/helperSha256").asText());
                        assertEquals(22, facts.at("/helperInstallationSourceEvidence/locations").size());
                    }
                    assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.EXPECTED_SHA256,
                        facts.path("expectedHelperSha256").asText());
                }
                if (facts.has("installedHelperPath")) {
                    fields.addAll(Set.of("platformContractRevision", "installedHelperPath", "installedHelperSha256", "ownershipIdentityMeaning"));
                    assertTrue(facts.path("ownershipIdentityMeaning").asText().startsWith(
                        "ownershipManifestSha256 is an opaque ownership marker identity, not a protocol requirement to hash a supplied content manifest."));
                    assertEquals("managed-helper-review.v2", facts.path("platformContractRevision").asText());
                    assertFalse(facts.has("installedHelperScript"));
                    assertEquals(DeploymentManagedHelperBundle.PATH, facts.path("installedHelperPath").asText());
                    assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.EXPECTED_SHA256,
                        facts.path("installedHelperSha256").asText());
                }
                if (facts.has("installedBuildEntryPath")) {
                    assertTrue(proof.operation().equals("deployment.build.run.v1")
                        || proof.operation().equals("deployment.agent.helper.v1") && proof.exact().contains("'agent-run'"));
                    fields.addAll(Set.of("installedBuildEntryPath", "installedBuildEntrySha256"));
                    assertFalse(facts.has("installedBuildEntryScript"));
                    assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.BUILD_ENTRY_PATH,
                        facts.path("installedBuildEntryPath").asText());
                    assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.buildEntrySha256(),
                        facts.path("installedBuildEntrySha256").asText());
                }
                if (proof.operation().equals("deployment.agent.helper.v1") && proof.exact().contains("'agent-open'")) {
                    fields.add("agentOpenPhaseEvidence");
                    var opening = facts.path("agentOpenPhaseEvidence");
                    assertEquals(Set.of("formatVersion", "authority", "phase", "remoteObserved", "phaseMeaning", "implementation",
                        "arguments", "paths", "request", "resourceBinding", "checksAtExecution", "effectsAtExecution", "postconditions", "remoteState"), keys(opening));
                    assertEquals(1, opening.path("formatVersion").asInt());
                    assertEquals("NONE", opening.path("authority").asText());
                    assertEquals("BEFORE_PROTECTED_SOURCE_CREATION", opening.path("phase").asText());
                    assertFalse(opening.path("remoteObserved").asBoolean(true));
                    var frame = java.util.regex.Pattern.compile("'agent-open' '([^']+)' '([^']+)' '([^']+)' '([0-9a-f]{64})'").matcher(proof.exact());
                    assertTrue(frame.find());
                    var workspace = new DeploymentRemoteWorkspace(frame.group(1), frame.group(4));
                    assertEquals(workspace.candidateId(), frame.group(2));
                    assertEquals(task.id().toString(), frame.group(3));
                    assertEquals(Set.of("application", "candidate", "task", "compressedArchiveSha256"), keys(opening.path("arguments")));
                    assertEquals(frame.group(1), opening.at("/arguments/application").asText());
                    assertEquals(frame.group(2), opening.at("/arguments/candidate").asText());
                    assertEquals(frame.group(3), opening.at("/arguments/task").asText());
                    assertEquals(frame.group(4), opening.at("/arguments/compressedArchiveSha256").asText());
                    assertEquals(workspace.candidateRoot(), opening.at("/paths/candidateRoot").asText());
                    assertEquals(workspace.candidateRoot() + "/mutable/source.tar.gz", opening.at("/paths/archive").asText());
                    assertEquals(workspace.candidateRoot() + "/mutable/input-source", opening.at("/paths/protectedSource").asText());
                    assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, opening.at("/implementation/renderedHelperSha256").asText());
                    assertEquals(sha(proof.exact().getBytes(StandardCharsets.UTF_8)), opening.at("/request/exactActionSha256").asText());
                    assertEquals(0, opening.at("/request/stdinBytes").asInt(-1));
                    assertEquals(sha(new byte[0]), opening.at("/request/stdinSha256").asText());
                    assertEquals(120, opening.at("/request/timeoutSeconds").asInt());
                    assertEquals(4096, opening.at("/request/outputLimitBytes").asInt());
                    assertEquals(facts.path("resourceTargets").get(0).path("id").asText(), opening.at("/resourceBinding/originalTargetId").asText());
                    assertTrue(opening.path("checksAtExecution").size() > 0);
                    assertTrue(opening.path("effectsAtExecution").size() > 0);
                    assertFalse(opening.at("/postconditions/projectBodyExecuted").asBoolean(true));
                    assertTrue(opening.path("remoteState").properties().stream().allMatch(entry -> entry.getValue().asText().equals("UNKNOWN")));
                }
                if (facts.has("currentCommandEvidence")) {
                    if (proof.operation().equals("deployment.publication.candidate-cleanup.v1"))
                        requireCleanupEvidence(facts, proof, task, events);
                    else {
                        assertEquals("deployment.candidate.verify.v1", proof.operation());
                        requireArchiveEvidence(facts.path("currentCommandEvidence"), proof, task, source);
                    }
                    fields.add("currentCommandEvidence");
                }
                if (proof.operation().equals("deployment.agent.helper.v1") && proof.exact().contains("'agent-run'")) {
                    fields.add("agentExecutableInputBinding");
                    var binding = facts.path("agentExecutableInputBinding");
                    assertEquals(Set.of("schema", "helperVerb", "digestArgumentIndex", "stdinBytes", "stdinSha256",
                        "argumentSha256", "argumentDigestMatchesInput", "displayedUtf8MatchesInput"), keys(binding));
                    byte[] input = proof.executable().orElseThrow().getBytes(StandardCharsets.UTF_8);
                    String digest = sha(input);
                    assertEquals("agent-executable-input-binding.v1", binding.path("schema").asText());
                    assertEquals("agent-run", binding.path("helperVerb").asText());
                    assertEquals(8, binding.path("digestArgumentIndex").asInt());
                    assertEquals(input.length, binding.path("stdinBytes").asInt());
                    assertEquals(digest, binding.path("stdinSha256").asText());
                    assertEquals(digest, binding.path("argumentSha256").asText());
                    assertTrue(binding.path("argumentDigestMatchesInput").asBoolean());
                    assertTrue(binding.path("displayedUtf8MatchesInput").asBoolean());
                    assertTrue(proof.exact().contains("'ordinary' '" + digest + "'"));
                }
                assertEquals(fields, keys(facts));
                assertEquals(proof.inputKind(), facts.path("inputKind").textValue());
                proof.executable().ifPresent(value -> {
                    if (facts.path("completeExecutableInput").isObject())
                        gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.verify(facts.path("completeExecutableInput"), value);
                    else assertEquals(value, facts.path("completeExecutableInput").textValue());
                });
                if (proof.operation().equals("deployment.agent.helper.v1") && proof.exact().contains("'agent-run'")) {
                    assertEquals(1, facts.path("resourceTargets").size());
                    var target = facts.path("resourceTargets").get(0);
                    assertEquals(Set.of("serverId", "kind", "id"), keys(target));
                    assertEquals("server", target.path("serverId").asText());
                    String body = proof.executable().orElseThrow();
                    if (body.equals(HIGH_SCRIPT)) {
                        assertEquals("deployment-agent-directory-delete", target.path("kind").asText());
                        assertEquals(new DeploymentRemoteWorkspace("demo-api", source.action()).candidateId() + ":.", target.path("id").asText());
                    } else {
                        assertEquals("deployment-agent", target.path("kind").asText());
                        String marker = "\nscript:\n";
                        assertTrue(proof.exact().contains(marker));
                        String guarded = proof.exact().substring(proof.exact().indexOf(marker) + marker.length());
                        assertEquals(sha((guarded + "\n" + sha(body.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8)), target.path("id").asText());
                    }
                    boolean correctedBudget = budgetPreflightJourney && proof.exact().contains("'1600' '1048576' 'ordinary'");
                    assertTrue(proof.exact().contains("timeout=" + Duration.ofSeconds(correctedBudget ? 1620 : 30) + "\noutputLimitBytes=1048576\n"));
                    assertTrue(proof.exact().contains("stdinSha256=" + sha(body.getBytes(StandardCharsets.UTF_8))));
                    assertTrue(proof.exact().contains((correctedBudget ? "'1600'" : "'10'") + " '1048576' 'ordinary'"));
                }
                proof.installed().ifPresent(value -> assertEquals(value,
                    facts.at("/completeExecutableInput/sources/managedHelper").textValue(), "Actual installer includes the entire helper source"));
                reviewed.put(key(task.id(), proof.revision(), proof.exact()), proof);
                reviews.incrementAndGet();
                if (scenario == ScenarioType.AI_APPROVAL_DRIFT && proof.operation().equals("deployment.agent.helper.v1")
                    && proof.exact().contains("'agent-run'")) {
                    assertEquals(originalAiTask.get(), task.id());
                    assertTrue(events.stream().anyMatch(event -> event.type().equals("CANDIDATE")), "The fault follows an actual original candidate, rather than the earlier unsent free-script review");
                    assertEquals(1, aiApprovalDrifts.incrementAndGet());
                    revision.set("b".repeat(64));
                }
                if (highInitialInstaller && proof.operation().equals("deployment.helper.install.files.v1")) {
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
                    if (method.getName().equals("openReviewedWorkflow")
                        && !continueNativeFailure && !originalAiTask.get().equals(((TaskContext) arguments[4]).id())) {
                        requireInspection((TaskContext) arguments[4]);
                        var intent = (ActionIntent) arguments[0];
                        assertEquals(List.of("server"), intent.serverIds());
                        assertEquals(((TaskContext) arguments[4]).id().toString(), intent.target());
                        assertTrue(intent.exactAction().contains("inspect-original=" + terminalInspection.get()));
                    }
                    if (Set.of("approve", "approveRequired", "approveReviewedAction").contains(method.getName())) {
                        var intent = (ActionIntent) arguments[0];
                        var task = (TaskContext) arguments[method.getName().equals("approveReviewedAction") ? 4 : 1];
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
                        if (highInitialInstaller) assertEquals("deployment.helper.install.files.v1", proof.operation());
                        else assertTrue(highPreparedOpen || riskJourney != null);
                        assertNotNull(rememberAuthorization);
                        if (!highInitialInstaller && !highPreparedOpen)
                            assertTrue(proof.executable().filter(HIGH_SCRIPT::equals).isPresent());
                        assertEquals("approveReviewedAction", method.getName());
                        assertTrue(result.value().isEmpty());
                        assertFalse(((TaskContext) arguments[4]).cancelToken().requested());
                        assertEquals(1, denied.incrementAndGet(), "Only one real original HIGH refusal is admitted");
                    }
                    return value;
                });
        }

        void beforeSend(TaskContext task, CommandRequest request, ApprovalGrant grant) {
            var proof = granted.remove(grant.id());
            if (isAgentBootstrap(task, request)) {
                assertNotNull(proof, "Canonical Agent bootstrap must consume its single independently reviewed original host grant");
                assertEquals("deployment.helper.install.files.custom.v1", proof.operation());
            }
            if (request.script().contains(" candidate-create-task ")) {
                assertNotNull(proof, "Candidate allocation must use the exact independently reconstructed configured-capacity request and its original host grant");
                assertEquals("deployment.candidate.create.v1", proof.operation());
                reviewedCandidateCreates.incrementAndGet();
            }
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
            sentRequests.add(key(proof.task(), proof.revision(), proof.exact()));
        }

        private void requireArchiveEvidence(JsonNode evidence, Proof proof, TaskContext task,
                                            DeploymentAgentActionLog.Event source) throws Exception {
            assertEquals(Set.of("formatVersion", "contractId", "authority", "meaning", "phase", "task", "server", "businessRevision",
                "layoutSha256", "workspace", "capturedArchive", "request", "consumer"), keys(evidence));
            assertEquals(1, evidence.path("formatVersion").asInt());
            assertEquals("deployment.candidate.verification.consumer.v1", evidence.path("contractId").asText());
            assertEquals("NONE", evidence.path("authority").asText());
            assertEquals("UPLOADED_ARCHIVE_BEFORE_AGENT_OPEN", evidence.path("phase").asText());
            assertEquals(task.id().toString(), evidence.path("task").asText());
            assertEquals("server", evidence.path("server").asText());
            assertEquals(proof.revision(), evidence.path("businessRevision").asText());
            assertEquals(gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original().digest(), evidence.path("layoutSha256").asText());
            var archive = evidence.path("capturedArchive");
            assertEquals(Set.of("bytes", "sha256", "sourceManifestSha256", "originSnapshotId"), keys(archive));
            assertEquals(source.action(), archive.path("sha256").asText());
            assertEquals(source.detail(), archive.path("sourceManifestSha256").asText());
            assertEquals(uploadedArchiveSizes.get(source.action()).longValue(), archive.path("bytes").asLong());
            assertEquals(source.detail(), reviewSnapshots.get(UUID.fromString(archive.path("originSnapshotId").asText())).sha256());
            var workspace = evidence.path("workspace");
            assertEquals(Set.of("application", "candidate", "root"), keys(workspace));
            var expected = fixtureWorkspace(task, workspace.path("application").asText(), source.action());
            assertEquals(expected.candidateId(), workspace.path("candidate").asText());
            assertEquals(expected.candidateRoot(), workspace.path("root").asText());
            var request = evidence.path("request");
            assertEquals(Set.of("exactActionSha256", "scriptSha256", "stdinBytes", "timeoutSeconds", "outputLimitBytes"), keys(request));
            assertEquals(DeploymentAgentInputs.hash(proof.exact()), request.path("exactActionSha256").asText());
            assertEquals(0, request.path("stdinBytes").asInt());
            assertEquals(30, request.path("timeoutSeconds").asInt());
            assertEquals(4096, request.path("outputLimitBytes").asInt());
            var consumer = evidence.path("consumer");
            assertEquals(Set.of("outcome", "valuePresent", "exitCode", "truncated", "stdoutComparison", "expectedStdout", "mismatchOutcome", "successMeaning"), keys(consumer));
            assertEquals("SUCCEEDED", consumer.path("outcome").asText());
            assertTrue(consumer.path("valuePresent").asBoolean());
            assertEquals(0, consumer.path("exitCode").asInt());
            assertFalse(consumer.path("truncated").asBoolean());
            assertEquals("FULL_EXACT_NO_TRIM", consumer.path("stdoutComparison").asText());
            assertEquals("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=" + archive.path("bytes").asLong()
                + "\nSHA256=" + source.action() + "\nOWNER=0\n", consumer.path("expectedStdout").asText());
            assertEquals("UNKNOWN", consumer.path("mismatchOutcome").asText());
        }

        private void requireCleanupEvidence(JsonNode facts, Proof proof, TaskContext task,
                                            List<DeploymentAgentActionLog.Event> events) throws Exception {
            var evidence = facts.path("currentCommandEvidence");
            assertEquals(Set.of("schema", "scope", "currentTask", "originalTask", "cleanupExactActionSha256", "server",
                "targetProfileSha256", "authenticatedTargetSha256", "originalConfigurationRevision", "currentReviewConfigurationRevision",
                "component", "unit", "releaseRoot", "ownershipManifestSha256", "candidate", "candidateRoot", "sourceArchiveSha256",
                "originalSourceRevision", "sealedSourceRevision", "sealedArtifactSha256", "releaseSha256", "runtimeConfigurationSha256",
                "proposalSha256", "originalExecution", "originalReviewedRevision", "originalRemoteLayout", "helperSha256", "helperProtocol",
                "publicationVerified", "outerDeliveryOutcome", "observationLimits", "queryEvent", "queryRequest", "queryResult", "parsedStatus",
                "originalEventReferences", "evidenceSha256", "reviewRequestSha256"), keys(evidence));
            var identity = new DeploymentAgentActionLog(actual.db()).find(task).orElseThrow().identity();
            var executions = new DeploymentExecutionStore(actual.db());
            var record = executions.findForTask(task).orElseThrow();
            var proposal = executions.publicationTemplate(record).orElseThrow();
            var part = proposal.graph().components().stream().filter(value -> value.componentId().equals(evidence.path("component").asText())).findFirst().orElseThrow();
            var commit = proposal.units().stream().filter(value -> value.unit().equals(part.unit())).findFirst().orElseThrow();
            var source = events.stream().filter(value -> value.type().equals("SOURCE")).findFirst().orElseThrow();
            var workspace = fixtureWorkspace(task, part.unit().id(), source.action());
            var expected = Map.ofEntries(Map.entry("schema", "agent-current-cleanup-evidence.v1"), Map.entry("currentTask", task.id().toString()),
                Map.entry("originalTask", task.id().toString()), Map.entry("cleanupExactActionSha256", DeploymentAgentInputs.hash(proof.exact())),
                Map.entry("server", "server"), Map.entry("authenticatedTargetSha256", identity.targetDigest()),
                Map.entry("originalConfigurationRevision", identity.configurationDigest()), Map.entry("currentReviewConfigurationRevision", revision.get()),
                Map.entry("unit", part.unit().id()), Map.entry("releaseRoot", part.unit().releaseRoot()),
                Map.entry("ownershipManifestSha256", part.unit().ownershipManifestSha256()), Map.entry("candidate", workspace.candidateId()),
                Map.entry("candidateRoot", workspace.candidateRoot()), Map.entry("sourceArchiveSha256", source.action()),
                Map.entry("originalSourceRevision", source.detail()), Map.entry("releaseSha256", commit.release().sha256()),
                Map.entry("runtimeConfigurationSha256", commit.configuration().sha256()), Map.entry("originalExecution", record.identity().executionId().toString()),
                Map.entry("originalReviewedRevision", record.identity().reviewedRevision()), Map.entry("helperSha256", DeploymentManagedHelperBundle.EXPECTED_SHA256),
                Map.entry("proposalSha256", DeploymentAgentInputs.hash(new gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult(proposal.graph(), proposal.units()).toString())),
                Map.entry("targetProfileSha256", events.stream().filter(value -> value.type().equals("TARGET_PROFILE")).findFirst().orElseThrow().detail()));
            expected.forEach((field, value) -> assertEquals(value, evidence.path(field).asText(), field));
            assertEquals("CURRENT_APPROVAL_CALL_ONLY; historical events are references, not current observations or permissions", evidence.path("scope").asText());
            assertEquals(acceptNativeFailure ? "SETTLED" : "UNCONFIRMED_WRAPPER_RESULT; no delivery result is synthesized", evidence.path("outerDeliveryOutcome").asText());
            assertFalse(evidence.path("observationLimits").asText().isBlank());
            var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
            var json = new ObjectMapper();
            assertEquals(json.valueToTree(Map.of("canonical", layout.canonical(), "sha256", layout.digest())), evidence.path("originalRemoteLayout"));
            assertEquals(DeploymentManagedHelperBundle.PROTOCOL_VERSION, evidence.path("helperProtocol").asInt());
            assertEquals(!acceptNativeFailure, evidence.path("publicationVerified").asBoolean());
            assertTrue(events.stream().anyMatch(value -> value.type().equals(acceptNativeFailure ? "AI_PUBLICATION_FAILED_ACCEPTED" : "AI_PUBLICATION_VERIFIED")
                && value.action().equals(proposal.graph().applicationId())));
            for (var entry : Map.of("sealedSourceRevision", "CURRENT_SOURCE", "sealedArtifactSha256", "SEALED_ARTIFACT", "candidate", "CANDIDATE").entrySet())
                assertEquals(events.stream().filter(value -> value.type().equals(entry.getValue()) && value.action().equals(part.componentId())).findFirst().orElseThrow().detail(),
                    evidence.path(entry.getKey()).asText());
            assertEquals(proof.revision(), evidence.path("sealedArtifactSha256").asText());
            var targets = json.createArrayNode()
                .add(json.valueToTree(Map.of("serverId", "server", "kind", "deployment-candidate", "id", workspace.candidateRoot())))
                .add(json.valueToTree(Map.of("serverId", "server", "kind", "deployment-release", "id", part.unit().releaseRoot())));
            assertEquals(targets, facts.path("resourceTargets"));
            var queries = new ArrayList<Proof>();
            publication(queries, task, proof.revision(), "original-status", "publication-original-status", List.of(part.unit().id(),
                part.unit().ownershipManifestSha256(), task.id().toString(), workspace.candidateId(), commit.release().sha256(), "native", "none"), 30);
            var query = queries.getFirst();
            assertTrue(sentRequests.contains(key(task.id(), query.revision(), query.exact())), "Current status evidence has an actual independently reviewed host send");
            var queryTargets = json.createArrayNode()
                .add(json.valueToTree(Map.of("serverId", "server", "kind", "deployment-application-locks", "id", layout.applicationLockRoot(part.unit().id()))))
                .add(json.valueToTree(Map.of("serverId", "server", "kind", "deployment-candidate", "id", workspace.candidateRoot())))
                .add(json.valueToTree(Map.of("serverId", "server", "kind", "deployment-release", "id", part.unit().releaseRoot())))
                .add(json.valueToTree(Map.of("serverId", "server", "kind", "deployment-snapshot", "id", layout.snapshotRoot(part.unit().id(), task.id()))));
            var queryRequest = json.valueToTree(Map.of("exactAction", query.exact(), "operation", query.operation(), "serverTargets", List.of("server"),
                "resourceTargets", queryTargets, "businessRevision", query.revision(), "timeout", "PT30S", "outputLimitBytes", 4096, "stdinBytes", 0));
            assertEquals(queryRequest, evidence.path("queryRequest"));
            var result = evidence.path("queryResult");
            assertEquals(Set.of("outcome", "exitCode", "stdout", "stderr", "truncated"), keys(result));
            assertEquals("SUCCEEDED", result.path("outcome").asText());
            assertEquals(0, result.path("exitCode").asInt());
            assertFalse(result.path("truncated").asBoolean());
            assertEquals("", result.path("stderr").asText());
            assertEquals("PUBLICATION_STATUS=1\nCURRENT=" + (acceptNativeFailure ? "none" : commit.release().sha256())
                + "\nCURRENT_KIND=" + (acceptNativeFailure ? "none" : "deployment") + "\nCURRENT_MODE=" + (acceptNativeFailure ? "none" : "DAEMON")
                + "\nSNAPSHOT=none\nSNAPSHOT_OLD=none\nSNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE=none\nCANDIDATE=owned\nBUILD_ACTIVE=no\nUNIT_PRESENT="
                + (acceptNativeFailure ? "no" : "yes") + "\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=" + (acceptNativeFailure ? 0 : 1) + "\n", result.path("stdout").asText());
            assertEquals(json.valueToTree(Map.ofEntries(Map.entry("current", acceptNativeFailure ? "none" : commit.release().sha256()), Map.entry("currentKind", acceptNativeFailure ? "none" : "deployment"),
                Map.entry("currentMode", acceptNativeFailure ? "none" : "DAEMON"), Map.entry("snapshot", false), Map.entry("previousDigest", "none"), Map.entry("previousKind", "none"),
                Map.entry("previousRunning", "none"), Map.entry("maintenance", false), Map.entry("candidate", "owned"), Map.entry("buildActive", false),
                Map.entry("unitPresent", !acceptNativeFailure), Map.entry("containerPresent", "not-applicable"), Map.entry("retainedCount", acceptNativeFailure ? 0 : 1))), evidence.path("parsedStatus"));
            long queryEvent = evidence.path("queryEvent").asLong();
            var captured = events.stream().filter(value -> value.id() == queryEvent).findFirst().orElseThrow();
            assertEquals("CLEANUP_STATUS_QUERY_RESULT", captured.type());
            assertEquals(DeploymentAgentInputs.hash(query.exact()), captured.action());
            assertEquals(evidenceDigest(result), captured.detail());
            assertTrue(evidence.path("originalEventReferences").isArray());
            assertFalse(evidence.path("originalEventReferences").isEmpty());
            for (var reference : evidence.path("originalEventReferences")) {
                var event = events.stream().filter(value -> value.id() == reference.path("id").asLong()).findFirst().orElseThrow();
                assertTrue(event.id() < queryEvent);
                assertEquals(json.readTree(json.writeValueAsString(Map.of("id", event.id(), "type", event.type(), "action", event.action(), "detail", event.detail()))), reference);
            }
            var raw = (com.fasterxml.jackson.databind.node.ObjectNode) evidence.deepCopy();
            raw.remove(List.of("evidenceSha256", "reviewRequestSha256"));
            assertEquals(evidenceDigest(raw), evidence.path("evidenceSha256").asText());
            assertEquals(evidenceDigest(json.valueToTree(Map.of("command", Map.of("exactAction", proof.exact(), "operation", proof.operation(),
                "serverTargets", List.of("server"), "resourceTargets", targets, "businessRevision", proof.revision(), "timeout", "PT1M30S",
                "outputLimitBytes", 4096, "stdinBytes", 0), "currentEvidenceSha256", evidence.path("evidenceSha256").asText()))), evidence.path("reviewRequestSha256").asText());
        }

        private String evidenceDigest(JsonNode value) throws java.io.IOException {
            return DeploymentAgentInputs.hash(gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.facts(new ObjectMapper().convertValue(value, Map.class), 1048576));
        }

        void completed() {
            assertTrue(sends.get() > 0, "The actual workflow sent independently reviewed fixed requests");
            assertEquals(reviews.get(), sends.get() + denied.get() + modelDenied.get(), "Each complete review has its actual send or explicit model/human refusal");
            assertTrue(granted.isEmpty());
        }

        private List<String> key(UUID task, String revision, String exact) {
            return List.of(task.toString(), revision, exact);
        }

        boolean isHigh(ActionIntent intent, UUID task) {
            var proof = reviewed.get(key(task, intent.revision(), intent.exactAction()));
            return proof != null && (proof.executable().filter(HIGH_SCRIPT::equals).isPresent()
                || highPreparedOpen && proof.operation().equals("deployment.agent.helper.v1") && proof.exact().contains("'agent-open'"));
        }

        boolean isInitialInstaller(ActionIntent intent, UUID task) {
            var proof = reviewed.get(key(task, intent.revision(), intent.exactAction()));
            return proof != null && proof.operation().equals("deployment.helper.install.files.v1");
        }

        boolean isAgentBootstrap(TaskContext task, CommandRequest request) {
            return reviewed.values().stream().anyMatch(proof -> proof.task().equals(task.id())
                && proof.operation().equals("deployment.helper.install.files.custom.v1")
                && proof.exact().equals(RemoteActions.command(new ServerId("server"), proof.revision(), request).exactAction()));
        }

        private void originalCommands(List<Proof> expected, TaskContext task, String business, String archive,
                                      List<DeploymentAgentActionLog.Event> events) {
            String helper = DeploymentManagedHelperBundle.PATH;
            String body = DeploymentHelperInstallerScript.render();
            String installed = DeploymentManagedHelperBundle.renderScript();
            assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, sha(installed.getBytes(StandardCharsets.UTF_8)));
            add(expected, task, "deployment.helper.install.v1", business, "exec /bin/bash --noprofile --norc -s",
                body.getBytes(StandardCharsets.UTF_8), Duration.ofHours(2), 131072, Optional.of(body), Optional.of(installed));
            String filesBody = DeploymentHelperInstallerScript.renderFiles();
            add(expected, task, "deployment.helper.install.files.v1", business, "exec /bin/bash --noprofile --norc -s",
                filesBody.getBytes(StandardCharsets.UTF_8), Duration.ofHours(2), 131072, Optional.of(filesBody), Optional.of(installed));
            String readiness = DeploymentAgentHelperReadiness.script();
            add(expected, task, DeploymentAgentHelperReadiness.OPERATION, business,
                gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript.LAUNCHER,
                readiness.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30), 4096, Optional.of(readiness), Optional.empty());
            String probe = "'" + helper + "' probe\n" + "printf 'SHA256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\\n'\n";
            empty(expected, task, "deployment.helper.probe.v1", business, probe, 30, 4096);
            empty(expected, task, "deployment.helper.probe.v1", business,
                "if [ ! -f /usr/bin/sqlite3 ] || [ ! -x /usr/bin/sqlite3 ]; then exit 3; fi\n" + probe, 30, 4096);
            empty(expected, task, "deployment.candidate.host-probe.v1", business, DeploymentAgentHostProbe.baseline(), 120, 4096);
            empty(expected, task, "deployment.candidate.capacity.v1", business,
                "set -euo pipefail\ntest -d '/var/lib' && test ! -L '/var/lib'\nstat -f -c '%a %S' -- '/var/lib'\n", 20, 1024);
            for (String unit : singleUnsupported ? List.of("demo-api") : List.of("demo-api", "demo-web")) {
                var workspace = fixtureWorkspace(task, unit, archive);
                var ids = List.of(unit, workspace.candidateId(), task.id().toString());
                String prefix = quote(helper) + " candidate-query-task " + quoted(ids);
                empty(expected, task, "deployment.candidate.create.v1", business,
                    quote(helper) + " candidate-create-task " + quoted(ids.subList(0, 2)) + " "
                        + controlledWorkspaceBytes
                        + " " + quote(task.id().toString()), 300, 4096);
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
                    agent(expected, task, business, "agent-run", ids, List.of(source, "10", "1048576", "ordinary", sha(script.getBytes(StandardCharsets.UTF_8)), "-"),
                        30, script.getBytes(StandardCharsets.UTF_8), Optional.of(script));
                    if (budgetPreflightJourney && component.equals("api"))
                        agent(expected, task, business, "agent-run", ids, List.of(source, "1600", "1048576", "ordinary", sha(script.getBytes(StandardCharsets.UTF_8)), "-"),
                            1620, script.getBytes(StandardCharsets.UTF_8), Optional.of(script));
                    if (riskJourney != null && component.equals("api"))
                        agent(expected, task, business, "agent-run", ids, List.of(source, "10", "1048576", "ordinary", sha(HIGH_SCRIPT.getBytes(StandardCharsets.UTF_8)), "-"),
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
            String probe = "'" + helper + "' probe\n" + "printf 'SHA256="
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
                var workspace = fixtureWorkspace(task, unit.id(), archive);
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
            if (script.contains(DeploymentManagedHelperBundle.PATH))
                script = DeploymentManagedHelperBundle.verifiedCommand(script);
            try (var request = new CommandRequest(script, input, timeout, limit)) {
                expected.add(new Proof(task.id(), operation, business, RemoteActions.command(new ServerId("server"), business, request).exactAction(),
                    executable.isPresent() ? "EXECUTABLE_SCRIPT" : input.length == 0 ? "EMPTY" : "OPAQUE_BOUND_DATA", executable, installed,
                    script, Base64.getEncoder().encodeToString(input), timeout, limit));
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

    private boolean sharedPublication;

    private boolean compareStaticTool;

    private boolean toolObserved;

    private String identify(JsonNode data) {
        var mapper = new ObjectMapper();
        var reply = mapper.createObjectNode();
        reply.put("tool", "identify");
        var components = reply.putObject("arguments").putArray("components");
        String read = java.util.stream.StreamSupport.stream(data.path("observations").spliterator(), false)
            .filter(v -> v.path("tool").asText().equals("READ")).findFirst().orElseThrow().path("id").asText();
        for (String id : singleUnsupported ? List.of("api") : List.of("api", "web")) {
            var c = components.addObject();
            c.put("component", id);
            c.put("root", ".");
            c.put("technology", "README declares custom workload");
            c.put("runtime", "managed daemon run.sh");
            c.put("resources", "no database declared");
            c.put("uncertainties", "actual build and health still require verification");
            c.putArray("evidence").add(read);
        }
        return reply.toString();
    }

    private String assess(String output, JsonNode data) {
        try {
            var mapper = new ObjectMapper();
            var reply = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(output);
            if (!Set.of("plan", "command", "host_command", "patch", "seal", "deliver", "stage", "publish", "verify", "finish", "finish_failure", "continue_deployment",
                "prepare_tools", "build", "database_inspect", "databases", "secret_select", "secret_create").contains(reply.path("tool").asText()))
                return output;
            var ids = new LinkedHashSet<String>();
            data.path("state").path("judgement").path("pending").forEach(v -> ids.add(v.asText()));
            if (ids.isEmpty()) {
                var observations = missingRecipeAnalysis ? data.at("/state/evidenceIndex/entries") : data.path("observations");
                if (!observations.isEmpty()) ids.add(observations.get(observations.size() - 1).path("id").asText());
                else ids.add("o1");
            }
            var assessment = reply.putObject("assessment");
            var basis = assessment.putArray("basis");
            ids.forEach(basis::add);
            assessment.put("suitability", "Selected scoped action fits actual task evidence");
            assessment.put("differences", "Actual failed build or health is repaired; success is independently checked");
            assessment.put("resolution", "RESOLVED");
            assessment.put("decision", "Perform current action then inspect actual result");
            if (reply.path("tool").asText().equals("plan")) {
                String read = java.util.stream.StreamSupport.stream(data.path("observations").spliterator(), false)
                    .filter(v -> v.path("tool").asText().equals("READ")).findFirst().orElseThrow().path("id").asText();
                reply.path("arguments").path("components").forEach(node -> {
                    var c = (com.fasterxml.jackson.databind.node.ObjectNode) node;
                    c.put("sourceRoot", ".");
                    c.putArray("evidence").add(read);
                    c.putArray("databases");
                    c.putArray("secretReferences");
                    c.set("workspace", mapper.valueToTree(gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.explicit(
                        planCapacityBreakdownJourney && planCapacityRejectedCall == null ? 2 * controlledWorkspaceBytes : controlledWorkspaceBytes)));
                });
            }
            return reply.toString();
        } catch (Exception malformed) {
            return output;
        }
    }

    private static String evidenceDigestBytes(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }

    private String proposal(int turn, JsonNode data) {
        if (planCapacityBreakdownJourney) {
            if (turn >= 3)
                return "{\"tool\":\"unable\",\"arguments\":{\"reason\":\"Controlled stop after independently admitted corrected plan\"}}";
            if (turn == 2) turn--;
        }
        if (hostEnvelopeDenialJourney && turn >= 2) {
            if (turn == 2 || turn == 3) {
                String script = turn == 2 ? HOST_ENVELOPE_DENIED_SCRIPT : HOST_ENVELOPE_LEGAL_SCRIPT;
                modelScripts.add(script);
                return "{\"tool\":\"host_command\",\"arguments\":{\"script\":" + new ObjectMapper().valueToTree(script)
                    + ",\"seconds\":10,\"reason\":\"" + HOST_ENVELOPE_REASON + "\"}}";
            }
            turn -= 2;
        }
        if (budgetPreflightJourney && turn >= 2) {
            if (turn == 2 || turn == 3)
                return command("api").replace("\"seconds\":10", "\"seconds\":" + (turn == 2 ? 1800 : 1600));
            turn--;
        }
        if (staticDenialJourney && turn >= 2) {
            if (turn == 2) {
                staticDenialBeforeSends = boundarySends.size();
                staticDenialBeforeApprovals = approvals.size();
            }
            if (turn == 2 || staticDenialRepeat)
                return command("api", STATIC_DENIED_SCRIPT.replace("\n", "\\n"));
            turn--;
        }
        if (continueNativeFailure && !originalAiTask.get().equals(successorRoot.get()) && turn >= 3) {
            if (turn == 3) assertTrue(data.path("observations").toString().contains("exit=0"));
            turn++; // The actual child command succeeds; the new model does not repeat the root's controlled failed build.
        }
        if (missingSourceQuery) {
            if (turn == 0)
                return "{\"tool\":\"read\",\"arguments\":{\"path\":\"absent.txt\",\"offset\":0,\"limit\":10}}";
            if (turn == 1) {
                assertTrue(data.path("observations").toString().contains("FILE_NOT_FOUND"));
                assertTrue(data.path("state").path("remoteRevisions").isEmpty());
            }
            turn--;
        }
        if (highInitialInstaller && turn >= 3) {
            if (turn == 3) {
                assertTrue(java.util.stream.StreamSupport.stream(data.path("observations").spliterator(), false)
                    .anyMatch(value -> value.path("tool").asText().equals("ALTERNATIVE_REQUIRED")
                        && value.path("output").asText().contains("was not sent")));
                assertTrue(proposedRemoteRevisions.get().isEmpty());
                assertEquals(1, lowerRiskFeedback.incrementAndGet());
            }
            return command("api", "printf artifact > $WTL_OUTPUT/run2.sh");
        }
        if (riskJourney != null) {
            if (turn == 2) return command("api", HIGH_SCRIPT);
            if (turn == 3) {
                var observations = data.path("observations");
                assertTrue(observations.isArray());
                assertTrue(java.util.stream.StreamSupport.stream(observations.spliterator(), false)
                    .anyMatch(value -> value.path("tool").asText().equals("ALTERNATIVE_REQUIRED") && value.path("output").asText().contains("was not sent")));
                assertEquals(1, lowerRiskFeedback.incrementAndGet());
                assertEquals(independentCommandDenial ? 0 : 1, fixedCommands.denied.get());
                assertFalse(data.path("state").path("remoteRevisions").has("web"));
                assertEquals(Map.of("api", "c".repeat(64)), proposedRemoteRevisions.get());
                if (riskJourney == RiskJourney.REPEAT) return command("api", HIGH_SCRIPT);
            }
            if (riskJourney == RiskJourney.REPEAT && turn == 4) {
                assertTrue(java.util.stream.StreamSupport.stream(data.path("observations").spliterator(), false)
                    .anyMatch(value -> value.path("tool").asText().equals("ALTERNATIVE_REQUIRED")
                        && value.path("output").asText().contains("The same refused script cannot be submitted again")));
                return command("api", HIGH_SCRIPT);
            }
            if (riskJourney == RiskJourney.REPEAT && turn > 4)
                throw new AssertionError("The refused script must stop after two identical same-script refusal results");
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

    private gold.debug.windowstolinux.app.ai.model.ModelProtocol nativeProtocol = gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS;

    private boolean exhaustedNativeOutput;

    private final Map<UUID, String> providerStates = new HashMap<>();

    private int nativeIds;

    private boolean duplicateReadBatch;

    private boolean duplicateReadEmitted;

    private boolean duplicateReadVerified;

    @ParameterizedTest
    @EnumSource(gold.debug.windowstolinux.app.ai.model.ModelProtocol.class)
    void nativeBatchDuplicateReadReturnsRealExistingObservationId(gold.debug.windowstolinux.app.ai.model.ModelProtocol protocol) throws Exception {
        nativeProtocol = protocol;
        duplicateReadBatch = true;
        verifyScenario(ScenarioType.REPEATED_BROWSE);
        assertTrue(duplicateReadVerified);
        assertEquals(3, calls.get());
    }

    @ParameterizedTest
    @EnumSource(gold.debug.windowstolinux.app.ai.model.ModelProtocol.class)
    void threeNativeProtocolsReachReceiptHealthAndFinish(gold.debug.windowstolinux.app.ai.model.ModelProtocol protocol) throws Exception {
        nativeProtocol = protocol;
        verifyScenario(ScenarioType.SUCCESS);
    }

    @ParameterizedTest
    @EnumSource(gold.debug.windowstolinux.app.ai.model.ModelProtocol.class)
    void nativeProtocolsBlockUnknownCommands(gold.debug.windowstolinux.app.ai.model.ModelProtocol protocol) throws Exception {
        nativeProtocol = protocol;
        verifyScenario(ScenarioType.COMMAND_UNKNOWN);
    }

    @ParameterizedTest
    @EnumSource(gold.debug.windowstolinux.app.ai.model.ModelProtocol.class)
    void nativeProtocolsRejectFailedApplicationHealth(gold.debug.windowstolinux.app.ai.model.ModelProtocol protocol) throws Exception {
        nativeProtocol = protocol;
        verifyScenario(ScenarioType.WHOLE_HEALTH_FAILURE);
    }

    @ParameterizedTest
    @EnumSource(gold.debug.windowstolinux.app.ai.model.ModelProtocol.class)
    void nativeProtocolsCannotFinishWithoutExplicitGate(gold.debug.windowstolinux.app.ai.model.ModelProtocol protocol) throws Exception {
        nativeProtocol = protocol;
        verifyScenario(ScenarioType.PUBLISH_UNKNOWN);
    }

    private AiResponse nativeResponse(AiRequest request, String scripted) {
        try {
            if (duplicateReadEmitted && !duplicateReadVerified) {
                var results = request.messages().stream().flatMap(message -> message.toolResult().stream()).toList();
                assertEquals(2, results.size());
                assertFalse(results.get(0).error());
                assertTrue(results.get(1).error());
                String id = results.get(0).text().lines().findFirst().orElseThrow();
                assertTrue(id.matches("o[0-9]+"));
                assertTrue(results.get(1).text().contains(id));
                duplicateReadVerified = true;
            }
            var node = new ObjectMapper().readTree(scripted);
            String name = node.has("tool") ? node.path("tool").asText() : "submit_result";
            var args = (com.fasterxml.jackson.databind.node.ObjectNode) (node.has("arguments") ? node.get("arguments") : node).deepCopy();
            if (node.has("assessment")) args.set("assessment", node.get("assessment"));
            if (staticDenialRepeat && name.equals("command") && args.path("script").asText().equals(STATIC_DENIED_SCRIPT)) {
                if (staticDeniedArguments == null) staticDeniedArguments = args.deepCopy();
                else args = staticDeniedArguments.deepCopy();
            }
            var selectedCalls = new ArrayList<gold.debug.windowstolinux.appapi.ai.model.AiToolCall>();
            selectedCalls.add(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("native_" + (++nativeIds), name, args.toString()));
            if (planCapacityBreakdownJourney && name.equals("plan")) {
                if (planCapacityRejectedCall == null) planCapacityRejectedCall = selectedCalls.getFirst().id();
                else planCapacityAcceptedCall = selectedCalls.getFirst().id();
            }
            if (hostEnvelopeDenialJourney && name.equals("host_command")) {
                assertEquals(gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original(),
                    gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(fixedCommands.tasks,
                        hostEnvelopeTask));
                if (args.path("script").asText().equals(HOST_ENVELOPE_DENIED_SCRIPT))
                    hostEnvelopeRejectedCallId = selectedCalls.getFirst().id();
            }
            if (budgetPreflightJourney && name.equals("command") && args.path("seconds").asInt() == 1800) {
                var originalBudget = DeploymentAgentGoalBudget.current().orElseThrow();
                assertEquals(Duration.ofSeconds(1800).toNanos(), originalBudget.build().snapshot().limitNanos());
                originalBudget.build().charge(1_000_000_000L);
                budgetCorrectionFacts.put("startingRemainingSeconds", originalBudget.build().remainingSeconds());
                budgetRejectedCallId = selectedCalls.getFirst().id();
            }
            if (staticDenialJourney && name.equals("command") && args.path("script").asText().equals(STATIC_DENIED_SCRIPT)) {
                staticDeniedNativeCalls++;
                if (staticDeniedCallId == null) staticDeniedCallId = selectedCalls.getFirst().id();
            }
            if (duplicateReadBatch && !duplicateReadEmitted && name.equals("read")) {
                selectedCalls.add(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("native_" + (++nativeIds), name, args.toString()));
                duplicateReadEmitted = true;
            }
            return throughProtocol(request, new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "tool_calls",
                new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                selectedCalls, Optional.empty())), Optional.empty())).value().orElseThrow();
        } catch (Exception invalid) {
            throw new AssertionError(invalid);
        }
    }

    private OperationResult<AiResponse> throughProtocol(AiRequest request, OperationResult<AiResponse> response) {
        if (response.outcome() != TaskResult.SUCCEEDED) return response;
        try {
            var codec = gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec.forProtocol(nativeProtocol);
            var encoded = codec.encode("controlled", request, 65536, gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel.DEFAULT, providerStates);
            if (hostEnvelopeDenialJourney && request.purpose() == AiPurpose.LANGUAGE && hostEnvelopeFeedbackRequests == 1 && hostEnvelopeWireRequests == 0) {
                String wire = new String(encoded, StandardCharsets.UTF_8);
                assertTrue(wire.contains(hostEnvelopeRejectedCallId));
                assertTrue(wire.contains("TOOL_REJECTED:"));
                assertTrue(wire.contains("DEPLOYMENT_COMMAND_STATIC_DENIED"));
                assertTrue(wire.contains("NOT_SENT"));
                hostEnvelopeWireRequests++;
            }
            if (budgetPreflightJourney && request.purpose() == AiPurpose.LANGUAGE && budgetFeedbackRequests == 1 && budgetWireRequests == 0) {
                String wire = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(wire.contains(budgetRejectedCallId));
                assertTrue(wire.contains("TOOL_REJECTED:"));
                assertTrue(wire.contains("Original goal build allowance cannot fund this exact candidate command"));
                budgetWireRequests++;
            }
            if (staticDenialJourney && request.purpose() == AiPurpose.LANGUAGE && staticDenialFeedbackRequests == 1 && staticDenialWireRequests == 0) {
                String wire = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(wire.contains(staticDeniedCallId));
                assertTrue(wire.contains("DEPLOYMENT_COMMAND_STATIC_DENIED: NOT_SENT"));
                assertTrue(wire.contains(evidenceDigestBytes(STATIC_DENIED_SCRIPT)));
                staticDenialWireRequests++;
            }
            var original = response.value().orElseThrow();
            var calls = original.toolCalls();
            if (calls.isEmpty()) {
                var args = new ObjectMapper().readTree(original.text());
                calls = List.of(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("native_" + (++nativeIds), "submit_result", args.toString()));
            }
            var json = new ObjectMapper();
            var root = json.createObjectNode();
            switch (nativeProtocol) {
                case OPENAI_CHAT_COMPLETIONS -> {
                    var choice = root.putArray("choices").addObject().put("finish_reason", "tool_calls");
                    var message = choice.putObject("message").put("role", "assistant").putNull("content").put("reasoning_content",
                        evictedRejectionFeedback && request.purpose() == AiPurpose.LANGUAGE ? "x".repeat(61000)
                            : evictedFeedbackRecovery && request.purpose() == AiPurpose.LANGUAGE && (this.calls.get() == 4 || this.calls.get() == 5)
                            ? "x".repeat(33000) : "retained private provider state");
                    var array = message.putArray("tool_calls");
                    for (var call : calls)
                        array.addObject().put("type", "function").put("id", call.id()).putObject("function").put("name", call.name()).put("arguments", call.arguments());
                }
                case OPENAI_RESPONSES -> {
                    root.put("status", "completed");
                    var array = root.putArray("output");
                    array.addObject().put("type", "reasoning").put("id", "reasoning_" + nativeIds).put("encrypted_content", "retained private provider state").putArray("summary");
                    for (var call : calls)
                        array.addObject().put("type", "function_call").put("id", "fc_" + call.id()).put("call_id", call.id()).put("name", call.name()).put("arguments", call.arguments());
                }
                case ANTHROPIC_MESSAGES -> {
                    root.put("stop_reason", "tool_use");
                    var array = root.putArray("content");
                    array.addObject().put("type", "thinking").put("thinking", "retained private provider state").put("signature", "signed");
                    for (var call : calls)
                        array.addObject().put("type", "tool_use").put("id", call.id()).put("name", call.name()).set("input", json.readTree(call.arguments()));
                }
            }
            var decoded = codec.decode(json.writeValueAsBytes(root), "controlled", false);
            var handle = new gold.debug.windowstolinux.appapi.ai.model.AiContinuation(UUID.randomUUID(), decoded.continuation().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            providerStates.put(handle.id(), decoded.continuation());
            var value = decoded.response();
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse(value.text(), value.modelId(), value.finishReason(), value.usage(), value.toolCalls(), Optional.of(handle))), Optional.empty());
        } catch (Exception invalid) {
            throw new AssertionError(invalid);
        }
    }
}
