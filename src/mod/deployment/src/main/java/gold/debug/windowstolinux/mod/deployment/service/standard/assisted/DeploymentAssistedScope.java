package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.util.*;

import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.mod.model.ModIdentity;
import gold.debug.windowstolinux.appapi.mod.service.ModServices;
import gold.debug.windowstolinux.appapi.secret.SecretService;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.log.LogService;
import gold.debug.windowstolinux.appapi.task.process.ProcessService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;

/** Freezes one original assisted task and its language-call budget without replacing platform authority. / 冻结一项原辅助任务及语言调用预算，不替换平台权限。 */
public final class DeploymentAssistedScope implements ModContext, gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence {
    /** Actual owner capabilities. / 实际所属能力。 */
    private final ModContext original;

    /** Authentic original task. / 真实原任务。 */
    private final TaskContext task;

    /** Frozen complete model routing revision. / 冻结完整模型路由修订。 */
    private final String revision;

    /** Explicit independent-review policy. / 明确独立审核策略。 */
    private final String approvalMode;

    /** Original immutable rememberAuthorization repetition. / 原不可变授权重复规则。 */
    private final boolean rememberAuthorization;

    private volatile gold.debug.windowstolinux.appapi.task.action.WorkflowGrant workflow;

    private volatile Map<String, Object> sourceSelection;

    private final LinkedHashMap<String, Map<String, Object>> preparedToolchains = new LinkedHashMap<>();

    private final LinkedHashMap<String, Map<String, Object>> sourceArchives = new LinkedHashMap<>();

    /** Records the archiver's original-source association, never source trust or build permission. */
    public synchronized void archiveCaptured(gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material,
                                             gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive archive,
                                             TaskContext supplied) throws ApiException {
        sameTask(supplied);
        verify();
        var selected = sourceSelection;
        if (selected == null || !archive.originSnapshotId().toString().equals(selected.get("snapshotId"))
            || !archive.sourceManifestSha256().equals(selected.get("sourceRevision"))
            || !material.snapshot().id().equals(archive.originSnapshotId())
            || !material.snapshot().sha256().equals(archive.sourceManifestSha256()))
            throw new IllegalArgumentException("Archive differs from the original captured source");
        validateWorkflow(gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.reviewFacts(
            workflow, task, new ServerId(selected.get("server").toString())));
        String prefix = archive.relativeRoot().isEmpty() ? "" : archive.relativeRoot() + "/";
        var names = material.includedFiles().stream().filter(name -> name.startsWith(prefix))
            .map(name -> name.substring(prefix.length())).sorted().toList();
        var displayed = new java.util.ArrayList<String>();
        int chars = 0;
        for (String name : names) {
            if (displayed.size() == 64 || chars + name.length() > 8192) break;
            displayed.add(name);
            chars += name.length();
        }
        Map<String, Object> observation = Map.of("originSnapshotId", archive.originSnapshotId().toString(),
            "sourceManifestSha256", archive.sourceManifestSha256(), "archiveSha256", archive.sha256(),
            "archiveSnapshotId", archive.snapshot().id().toString(), "localArchivePath", archive.path().toString(),
            "archiveBytes", archive.byteCount(), "relativeRoot", archive.relativeRoot(), "fileCount", names.size(),
            "fileNames", List.copyOf(displayed), "fileNamesTruncated", displayed.size() != names.size());
        String key = archive.relativeRoot() + "\n" + archive.sha256();
        var previous = sourceArchives.get(key);
        if (previous != null && !previous.equals(observation))
            throw new IllegalStateException("Original archive association cannot be replaced");
        if (previous == null && sourceArchives.size() == 32)
            sourceArchives.remove(sourceArchives.keySet().iterator().next());
        sourceArchives.put(key, observation);
    }

    private synchronized void toolchainsPrepared(ServerId server,
                                                 gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools,
                                                 TaskContext supplied) throws ApiException {
        sameTask(supplied);
        verify();
        if (workflow == null || tools.selections().isEmpty()) return;
        validateWorkflow(gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.reviewFacts(workflow, task, server));
        String binding = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.identity(tools);
        preparedToolchains.remove(binding);
        if (preparedToolchains.size() == 32) preparedToolchains.remove(preparedToolchains.keySet().iterator().next());
        preparedToolchains.put(binding, Map.of("bindingSha256", binding, "selectionCount", tools.selections().size(),
            "recordedAt", java.time.Instant.now().toString(), "server", server.id(), "task", task.id().toString()));
    }

    /** Records the actual post-picker frozen selection, not executable trust or a command grant. */
    public synchronized void sourceCaptured(ServerId server, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material,
                                            TaskContext supplied) throws ApiException {
        sameTask(supplied);
        verify();
        var authorization = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.reviewFacts(workflow, task, server);
        validateWorkflow(authorization);
        var snapshot = material.snapshot();
        String hash = Objects.requireNonNull(snapshot.sha256());
        if (!hash.matches("[a-f0-9]{64}") || material.includedFiles().isEmpty())
            throw new IllegalArgumentException("Actual source snapshot is missing");
        Map<String, Object> selected = Map.of("server", server.id(), "snapshotId", Objects.requireNonNull(snapshot.id()).toString(),
            "sourceRevision", hash, "displayName", material.displayName(), "includedFiles", material.includedFiles().size(),
            "meaning", "Actual frozen source selected after workflow admission (folder picker may leave the initial draft URI empty). Snapshot identity only; not code trust, per-command permission or an installation safety verdict.");
        if (sourceSelection != null && !sourceSelection.equals(selected))
            throw new IllegalStateException("Original assisted source snapshot cannot be replaced");
        sourceSelection = selected;
    }

    @Override
    public synchronized void workflowOpened(gold.debug.windowstolinux.appapi.task.action.WorkflowGrant permission) {
        if (workflow != null) throw new IllegalStateException("Original assisted workflow is already bound");
        workflow = Objects.requireNonNull(permission);
    }

    @Override
    public Map<String, ?> facts(ActionIntent intent, gold.debug.windowstolinux.appapi.linux.command.CommandRequest request,
                                TaskContext supplied) throws ApiException {
        return effectFacts(intent, supplied);
    }

    /** Supplies the same task-bound observations for fixed transfers and business effects. */
    public synchronized Map<String, ?> effectFacts(ActionIntent intent, TaskContext supplied) throws ApiException {
        sameTask(supplied);
        verify();
        if (workflow == null) return Map.of();
        var evidence = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.reviewFacts(workflow, task, intent);
        validateWorkflow(evidence);
        var source = sourceSelection;
        var facts = new LinkedHashMap<String, Object>();
        facts.put("workflowAuthorization", evidence);
        if (source != null) facts.put("sourceSelection", source);
        if (!sourceArchives.isEmpty() && intent.semantics().map(value -> Set.of("deployment.candidate.upload.v1", "deployment.build.run.v1", "deployment.publication.publish.v1").contains(value.operation())).orElse(false))
            facts.put("sourceArchiveObservations", Map.of("archives", List.copyOf(sourceArchives.values()),
                "meaning", "Archiver-produced frozen source/subtree associations captured before final confirmation under this original task/server. Source manifest and compressed archive hashes identify different objects; match the build envelope's expected archive SHA to its association and declared candidate. Listed companion files belong to that archived subtree; file lists and recent history (32 archives) are explicitly bounded, not a complete content/safety review. The deterministic build body is separately reviewed, not required to be a file or signature of the source manifest. It verifies actual compressed archive bytes before extraction; project hooks still require non-root candidate isolation, complete executable input and fresh independent host permission. These observations do not prove live remote state, safe content or authorization."));
        if (!preparedToolchains.isEmpty() && intent.semantics().map(value -> Set.of("deployment.toolchain.bind.v1", "deployment.build.run.v1", "deployment.publication.publish.v1").contains(value.operation())).orElse(false))
            facts.put("toolchainPreparationObservations", Map.of("bindings", List.copyOf(preparedToolchains.values()),
                "meaning", "Earlier complete successful preparation receipts under this original task/server, matched to every typed selection after standard catalog/requirement validation. bindingSha256 identifies that exact canonical set. Recent history is bounded to 32 sets; absence is not proof of missing installation. This is a prior observation, not current live state, a safety verdict or command permission; bind still validates live directories/markers/versions and SYSTEM hashes."));
        return Map.copyOf(facts);
    }

    private void validateWorkflow(Map<String, Object> evidence) {
        if (!revision.equals(evidence.get("modelRevision")) || !approvalMode.equals(evidence.get("approvalPolicy"))
            || !Boolean.valueOf(rememberAuthorization).equals(evidence.get("rememberAuthorization")))
            throw new IllegalArgumentException("Workflow evidence differs from the frozen assisted route");
    }

    @Override
    public void verify(ActionIntent intent, gold.debug.windowstolinux.appapi.linux.command.CommandRequest request,
                       TaskContext supplied) throws ApiException {
        facts(intent, request, supplied);
    }

    /** Logical language capability calls, shared by analysis and recovery. / 分析和恢复共用的逻辑语言能力调用次数。 */
    private final java.util.concurrent.atomic.AtomicInteger languageCalls = new java.util.concurrent.atomic.AtomicInteger();

    /** Validates the task-local route and policy without replacing host authority. / 验证任务内部路由及策略，不替换宿主权限。
     * @param original actual owner capabilities / 实际所属能力
     * @param task original task / 原任务
     * @param revision frozen complete revision / 冻结完整修订
     * @param approvalMode selected exact policy / 所选精确策略 */
    public DeploymentAssistedScope(ModContext original, TaskContext task, String revision, String approvalMode) {
        this(original, task, revision, approvalMode, true);
    }

    /** Captures both original policies without replacing host authority. / 抓取两项原策略，不替换宿主权限。
     * @param original actual owner capabilities / 实际所属能力
     * @param task authentic original task / 真实原任务
     * @param revision frozen model route / 冻结模型路由
     * @param approvalMode selected approval strategy / 所选审批策略
     * @param rememberAuthorization selected rememberAuthorization repetition / 所选授权重复规则 */
    public DeploymentAssistedScope(ModContext original, TaskContext task, String revision, String approvalMode, boolean rememberAuthorization) {
        this.original = Objects.requireNonNull(original);
        this.task = Objects.requireNonNull(task);
        this.revision = Objects.requireNonNull(revision);
        gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAuthorizationPolicy.requireAllowed(approvalMode, rememberAuthorization);
        this.approvalMode = approvalMode;
        this.rememberAuthorization = rememberAuthorization;
    }

    /** Returns the selected policy without changing it. / 返回所选策略，不改变其值。
     * @return original exact policy / 原精确策略 */
    public String approvalMode() {
        return approvalMode;
    }

    /** Returns the captured original question repetition. / 返回已抓取原问题重复规则。
     * @return original rememberAuthorization strategy / 原授权策略 */
    public boolean rememberAuthorization() {
        return rememberAuthorization;
    }

    /** Returns the original complete frozen model revision without reading preferences. / 不读取偏好地返回原完整冻结模型修订。
     * @return original frozen revision / 原冻结修订 */
    public String modelRevision() {
        return revision;
    }

    /** Counts one logical language send; provider HTTP fallback is outside this counter. / 计算一次逻辑语言发送；提供方 HTTP 回退不属于该计数。
     * @param purpose actual model purpose / 实际模型用途
     * @throws ApiException if the original thirty-call budget is exhausted / 原三十次调用预算耗尽时 */
    private void consume(AiPurpose purpose) throws ApiException {
        if (purpose == AiPurpose.LANGUAGE && languageCalls.updateAndGet(value -> Math.min(321, value + 1)) > 320)
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_ASSISTED_DECISION_BUDGET",
                "Original assisted language-call budget is exhausted", Map.of("task", task.id().toString()), Optional.of(task.id())));
    }

    /** Captures the usable language route once before any assisted work. / 辅助工作前一次捕获可用语言路由。
     * @param original actual capabilities / 实际能力
     * @param task original task / 原任务
     * @return task-bound frozen capabilities / 任务绑定冻结能力
     * @throws ApiException if configuration is unavailable / 配置不可用时 */
    public static DeploymentAssistedScope freeze(ModContext original, TaskContext task) throws ApiException {
        return freeze(original, task, "MANUAL_REVIEW");
    }

    /** Requires both selected model roles before freezing any approval policy. / 冻结任何审批策略前要求两个所选模型角色可用。
     * @param original actual owner capabilities / 实际所属能力
     * @param task original authentic task / 真实原任务
     * @param approvalMode selected policy / 所选策略
     * @return exact frozen route / 精确冻结路由
     * @throws ApiException if a required independent reviewer is absent or changed / 必需独立审核者缺失或变化时 */
    public static DeploymentAssistedScope freeze(ModContext original, TaskContext task, String approvalMode) throws ApiException {
        return freeze(original, task, approvalMode, true);
    }

    /** Requires both original roles before freezing both deployment policies. / 冻结部署两策略前要求两个原角色。
     * @param original actual original owner / 实际原所有者
     * @param task authentic original task / 真实原任务
     * @param approvalMode approval strategy / 审批策略
     * @param rememberAuthorization rememberAuthorization repetition / 授权重复规则
     * @return original bound capabilities / 原绑定能力
     * @throws ApiException if original model route is unavailable / 原模型路由不可用时 */
    public static DeploymentAssistedScope freeze(ModContext original, TaskContext task, String approvalMode, boolean rememberAuthorization) throws ApiException {
        String revision = original.ai().configurationRevision(AiPurpose.LANGUAGE, task);
        if (!revision.equals(original.ai().configurationRevision(AiPurpose.APPROVAL, task)))
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED",
                "Independent assisted review route differs", Map.of("task", task.id().toString()), Optional.of(task.id())));
        return new DeploymentAssistedScope(original, task, revision, approvalMode, rememberAuthorization);
    }

    /** Verifies the original binding at actual dispatch time. / 在真实发送时验证原绑定。
     * @throws ApiException when the binding changed / 绑定变化时 */
    private void verify() throws ApiException {
        if (!revision.equals(original.ai().configurationRevision(AiPurpose.LANGUAGE, task)))
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED",
                "Assisted configuration changed", Map.of(), Optional.empty()));
        if (!revision.equals(original.ai().configurationRevision(AiPurpose.APPROVAL, task)))
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED",
                "Independent assisted review configuration changed", Map.of("task", task.id().toString()), Optional.of(task.id())));
    }

    /** Preserves the exact task at every scoped capability call. / 每次限定能力调用均保留精确任务。
     * @param supplied supplied task / 提供任务 */
    private void sameTask(TaskContext supplied) {
        if (supplied != task)
            throw new IllegalArgumentException("Assisted capability belongs to another task");
    }

    /** Returns real SSH with a guard before each actual send. / 返回每次真实发送前带校验的真实 SSH。
     * @return guarded original Linux access / 带校验原 Linux 访问 */
    @Override
    public LinuxService linux() {
        return new LinuxService() {
            /** Connects only under the frozen original task. / 仅按冻结原任务连接。
             * @param server original target / 原目标
             * @param supplied original task / 原任务
             * @return actual guarded connection / 实际带校验连接
             * @throws ApiException on drift or failed connection / 漂移或连接失败时 */
            public RemoteSession connect(ServerId server, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                return new DeploymentAssistedSession(original.linux().connect(server, supplied),
                    DeploymentAssistedScope.this::verify);
            }

            /** Enforces the original endpoint and wire before authenticated connection. / 认证连接前强制原端点及 wire。
             * @param expected original fixed target / 原固定目标
             * @param supplied original task / 原任务
             * @return actual guarded fixed-target connection / 实际受控固定目标连接
             * @throws ApiException if original route, endpoint or trust differs / 原路由、端点或信任不符时 */
            public RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                return new DeploymentAssistedSession(original.linux().connect(expected, supplied), DeploymentAssistedScope.this::verify);
            }

            /** Reads only original pending transfers. / 仅读取原待结传输。
             * @param supplied original task / 原任务
             * @return actual pending transfers / 实际待结传输
             * @throws ApiException on unavailable evidence / 依据不可用时 */
            public List<TransferCheckpoint> pendingTransfers(TaskContext supplied) throws ApiException {
                sameTask(supplied);
                return original.linux().pendingTransfers(supplied);
            }
        };
    }

    /** Returns AI that cannot silently adopt a later configuration. / 返回不能默默采用后续配置的 AI。
     * @return frozen original AI capability / 冻结原 AI 能力 */
    @Override
    public AiService ai() {
        return new AiService() {
            /** Rejects audio outside the frozen text/vision assisted decision budget. / 拒绝冻结文字与视觉辅助决策预算之外的音频。
             * @param request audio request / 音频请求
             * @param supplied original task / 原任务
             * @return no audio dispatch / 不发送音频
             * @throws ApiException because this workflow did not admit audio / 此流程未准入音频 */
            public OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                throw voiceDenied();
            }

            /** Rejects audio regardless of a caller-supplied revision. / 无论调用方提供何修订均拒绝音频。
             * @param request audio request / 音频请求
             * @param expected caller revision / 调用方修订
             * @param supplied original task / 原任务
             * @return no audio dispatch / 不发送音频
             * @throws ApiException because audio was not admitted / 音频未准入 */
            public OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, String expected, TaskContext supplied) throws ApiException {
                return call(request, supplied);
            }

            /** Rejects realtime resources outside the assisted workflow scope. / 拒绝辅助流程作用域以外的实时资源。
             * @param options audio budgets / 音频预算
             * @param listener audio events / 音频事件
             * @param supplied original task / 原任务
             * @return no realtime session / 不创建实时会话
             * @throws ApiException because audio was not admitted / 音频未准入 */
            public gold.debug.windowstolinux.appapi.ai.voice.VoiceSession openVoice(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceOptions options, gold.debug.windowstolinux.appapi.ai.voice.VoiceListener listener,
                TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                throw voiceDenied();
            }

            /** Explains the exact missing admission without exposing business data. / 不暴露业务数据地说明精确未准入范围。
             * @return denied capability / 拒绝能力 */
            private ApiException voiceDenied() {
                return new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
                    "DEPLOYMENT_ASSISTED_VOICE_SCOPE", "Assisted deployment admits only its frozen text and vision decisions", Map.of(), Optional.empty()));
            }

            /** Verifies the usable purpose and frozen whole route. / 验证可用用途和冻结整路由。
             * @param purpose required purpose / 必需用途
             * @param supplied original task / 原任务
             * @return frozen revision / 冻结修订
             * @throws ApiException on drift or missing purpose / 漂移或用途缺失时 */
            public String configurationRevision(AiPurpose purpose, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                if (!revision.equals(original.ai().configurationRevision(purpose, supplied)))
                    throw new IllegalStateException("Whole AI route differs by purpose");
                return revision;
            }

            /** Calls only the frozen route. / 仅调用冻结路由。
             * @param request bounded request / 有界请求
             * @param supplied original task / 原任务
             * @return actual provider feedback / 实际提供方反馈
             * @throws ApiException on drift or failed invocation / 漂移或调用失败时 */
            public OperationResult<AiResponse> call(AiRequest request, TaskContext supplied) throws ApiException {
                return call(request, revision, supplied);
            }

            /** Rejects any caller revision differing from the task. / 拒绝与任务不同的调用方修订。
             * @param request bounded request / 有界请求
             * @param expected caller binding / 调用方绑定
             * @param supplied original task / 原任务
             * @return actual provider feedback / 实际提供方反馈
             * @throws ApiException on drift or failed invocation / 漂移或调用失败时 */
            public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext supplied)
                throws ApiException {
                sameTask(supplied);
                verify();
                if (!revision.equals(expected))
                    throw new IllegalArgumentException("Assisted caller revision differs");
                consume(request.purpose());
                return original.ai().call(request, revision, supplied);
            }
        };
    }

    /** Returns bound owner identity. / 返回绑定所有者身份。
     * @return bound owner identity / 绑定所有者身份 */
    @Override
    public ModIdentity identity() {
        return original.identity();
    }

    /** Returns original launch mode. / 返回原启动模式。
     * @return original launch mode / 原启动模式 */
    @Override
    public ModLaunchMode launchMode() {
        return original.launchMode();
    }

    /** Returns owner page capability. / 返回所属页面能力。
     * @return owner page capability / 所属页面能力 */
    @Override
    public UiService ui() {
        return original.ui();
    }

    /** Returns original task capability. / 返回原任务能力。
     * @return original task capability / 原任务能力 */
    @Override
    public TaskService tasks() {
        return original.tasks();
    }

    /** Returns host review capability. / 返回宿主审核能力。
     * @return host review capability / 宿主审核能力 */
    @Override
    public InteractionService interaction() {
        return new gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview() {
            @Override
            public void toolchainsPrepared(ServerId server,
                                           gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools,
                                           TaskContext supplied) throws ApiException {
                DeploymentAssistedScope.this.toolchainsPrepared(server, tools, supplied);
            }

            /** Reviews complete fixed command bytes before the exact host grant. / 精确宿主许可前审核完整固定命令字节。
             * @param intent original exact intent / 原精确意图
             * @param request complete actual request / 完整实际请求
             * @param supplied authentic original task / 真实原任务
             * @return actual single-use host grant or refusal / 实际单次宿主许可或拒绝
             * @throws ApiException if original review fails / 原审核失败时 */
            public OperationResult<ApprovalGrant> approveCommand(ActionIntent intent,
                                                                 gold.debug.windowstolinux.appapi.linux.command.CommandRequest request, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                var actual = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval.approve(
                    DeploymentAssistedScope.this, original.interaction(), intent, request, supplied, revision, approvalMode, rememberAuthorization);
                verify();
                return actual;
            }

            /** Applies the task's fixed policy before the original exact host review. / 原精确宿主审阅前应用任务固定策略。
             * @param intent exact fixed intent / 精确固定意图
             * @param supplied authentic original task / 真实原任务
             * @return original host grant or refusal / 原宿主授权或拒绝
             * @throws ApiException if routing or fixed review rejects / 路由或固定审阅拒绝时 */
            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                var actual = DeploymentAssistedApproval.approve(DeploymentAssistedScope.this, original.interaction(), intent, supplied, revision, approvalMode, rememberAuthorization);
                verify();
                return actual;
            }

            /** Preserves original human input authority. / 保留原人工输入权限。
             * @param title original title / 原标题
             * @param prompt original question / 原问题
             * @param secret original secret classification / 原秘密分类
             * @param supplied original authentic task / 原真实任务
             * @return original classified answer / 原分类答案
             * @throws ApiException if the frozen task is no longer admitted / 冻结任务不再准入时 */
            public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                return original.interaction().requestText(title, prompt, secret, supplied);
            }
        };
    }

    /** Returns original server references. / 返回原服务器引用。
     * @return original server references / 原服务器引用 */
    @Override
    public ServerService servers() {
        return original.servers();
    }

    /** Returns original Schema access. / 返回原 Schema 访问。
     * @return original Schema access / 原 Schema 访问 */
    @Override
    public ModDatabase db() {
        return original.db();
    }

    /** Returns original opaque secret access. / 返回原不透明秘密访问。
     * @return original opaque secret access / 原不透明秘密访问 */
    @Override
    public SecretService secrets() {
        return original.secrets();
    }

    /** Returns original source ownership. / 返回原来源归属。
     * @return original source ownership / 原来源归属 */
    @Override
    public FileService file() {
        return original.file();
    }

    /** Returns original structured diagnostics. / 返回原结构化诊断。
     * @return original structured diagnostics / 原结构化诊断 */
    @Override
    public LogService logs() {
        return original.logs();
    }

    /** Returns original process ownership. / 返回原进程归属。
     * @return original process ownership / 原进程归属 */
    @Override
    public ProcessService processes() {
        return original.processes();
    }

    /** Returns declared service capability. / 返回已声明服务能力。
     * @return declared service capability / 已声明服务能力 */
    @Override
    public ModServices services() {
        return original.services();
    }
}
