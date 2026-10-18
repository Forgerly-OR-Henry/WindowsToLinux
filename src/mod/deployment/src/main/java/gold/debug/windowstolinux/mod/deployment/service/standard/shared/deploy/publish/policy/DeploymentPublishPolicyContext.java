package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.mod.model.ModIdentity;
import gold.debug.windowstolinux.appapi.mod.service.ModServices;
import gold.debug.windowstolinux.appapi.secret.SecretService;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.log.LogService;
import gold.debug.windowstolinux.appapi.task.process.ProcessService;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.linux.database.DeploymentNativeDatabaseWire;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.setup.DeploymentProtocolBootstrap;
import gold.debug.windowstolinux.mod.deployment.service.core.setup.DeploymentProtocolBootstrapActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardModeSupport;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardRecoverySupport;

/** Shares one original publication policy across preparation, every connection and explicit recovery. / 在准备、每次连接及明确恢复间共用一份原发布策略。 */
public final class DeploymentPublishPolicyContext implements ModContext {
    /** Actual original owner. / 实际原所有者。 */
    private final ModContext original;

    /** Authentic original task. / 真实原任务。 */
    private final TaskContext task;

    /** Original captured policy checkpoint. / 原捕获策略检查点。 */
    private final Optional<DeploymentPublishPolicyFacts> journal;

    /** Explicit original recovery mode. / 明确原恢复模式。 */
    private final boolean recovery;

    /** Upper assembly route for captured original recovery. / 已捕获原恢复的上层装配路由。 */
    private final DeploymentStandardRecoverySupport recoverySupport;

    /** Same original frozen assisted capabilities or static owner. / 同份原冻结辅助能力或静态所有者。 */
    private ModContext routed;

    /** Fresh close-only host authority; never persisted. / 新仅关闭宿主权限；绝不持久化。 */
    private WorkflowGrant permission;

    /** Whether this recovery already completed its one fresh handoff. / 此恢复是否已完成一次新交接。 */
    private boolean recoveryOpened;

    /** Whether known cleanup released this original policy. / 已知收尾是否已释放此原策略。 */
    private boolean released;

    /** Binds one task without opening any recovery scope or model request. / 绑定一项任务，不打开恢复作用域或模型请求。
     * @param original actual owner / 实际所有者
     * @param task authentic task / 真实任务
     * @param journal retained original facts / 留存原事实
     * @param routed original frozen route / 原冻结路线
     * @param recovery explicit recovery / 明确恢复
     * @param recoverySupport assembled original recovery route / 已装配原恢复路由 */
    private DeploymentPublishPolicyContext(ModContext original, TaskContext task, Optional<DeploymentPublishPolicyFacts> journal,
                                           ModContext routed, boolean recovery, DeploymentStandardRecoverySupport recoverySupport) {
        this.original = Objects.requireNonNull(original);
        this.task = Objects.requireNonNull(task);
        this.journal = journal;
        this.routed = routed;
        this.recovery = recovery;
        this.recoverySupport = Objects.requireNonNull(recoverySupport);
    }

    /** Captures original policy and endpoint before preparation and opens only its selected ordinary authority. / 准备前捕获原策略及端点，仅开启所选普通权限。
     * @param original actual owner / 实际所有者
     * @param task authentic original task / 真实原任务
     * @param server exact admitted server / 精确准入服务器
     * @param mode assembled standard mode adapter / 已装配标准模式适配
     * @param approval original review policy / 原审批策略
     * @param rememberAuthorization original repetition / 原重复规则
     * @param request original non-secret frozen choices / 原非秘密冻结选择
     * @param recoverySupport assembled original recovery route / 已装配原恢复路由
     * @return same-task guarded capabilities / 同任务受控能力
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时
     * @throws DeploymentAiJson.CapabilityOutcomeException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static DeploymentPublishPolicyContext begin(ModContext original, TaskContext task, ServerId server, DeploymentStandardModeSupport mode,
                                                       String approval, boolean rememberAuthorization, String request, DeploymentStandardRecoverySupport recoverySupport)
        throws ApiException, DeploymentAiJson.CapabilityOutcomeException {
        boolean assisted = mode.assisted();
        var scope = mode.freeze(original, task, approval, rememberAuthorization);
        ModContext routed = scope.context();
        String revision = scope.revision();
        var journal = DeploymentPublishPolicyFacts.begin(original, task, server, assisted ? "ASSISTED" : "STATIC", approval, rememberAuthorization, revision);
        var guarded = new DeploymentPublishPolicyContext(original, task, Optional.of(journal), routed, false, recoverySupport);
        try {
            if (assisted) {
                guarded.permission = DeploymentWorkflowAuthorization.open(original, task, server, revision, approval, rememberAuthorization, request);
                if (routed instanceof gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence evidence)
                    evidence.workflowOpened(guarded.permission);
            }
            return guarded;
        } catch (ApiException | DeploymentAiJson.CapabilityOutcomeException refused) {
            journal.close();
            throw refused;
        }
    }

    /** Reads original retained facts without current preferences, model calls or a new handoff. / 不读取当前偏好、不调用模型、不新交接地读取原留存事实。
     * @param original actual recovery owner / 实际恢复所有者
     * @param task authentic original recovery / 真实原恢复
     * @param recoverySupport assembled original recovery route / 已装配原恢复路由
     * @return one shared recovery context / 一份共用恢复上下文
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static DeploymentPublishPolicyContext recover(ModContext original, TaskContext task, DeploymentStandardRecoverySupport recoverySupport) throws ApiException {
        var journal = DeploymentPublishPolicyFacts.find(original, task);
        if (journal.isEmpty()) throw new ApiException(new Problem(ProblemCode.CONTRACT_VIOLATION,
            "DEPLOYMENT_PUBLICATION_POLICY_REQUIRED", "Current deployment recovery requires its original policy checkpoint", Map.of(), Optional.of(task.id())));
        try {
            var execution = new DeploymentExecutionStore(original.db()).findForTask(task);
            if (journal.isPresent() && execution.isPresent()) {
                var facts = journal.orElseThrow().facts();
                var identity = execution.orElseThrow().identity();
                if (!identity.server().equals(facts.target().server()) || !identity.mode().name().equals(facts.executionMode()))
                    throw new IllegalArgumentException("Original publication policy conflicts with formal execution");
            }
        } catch (java.sql.SQLException unreadable) {
            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_PUBLICATION_POLICY_IDENTITY_UNAVAILABLE",
                "Original publication identity cannot be confirmed", Map.of(), Optional.of(task.id())));
        }
        return new DeploymentPublishPolicyContext(original, task, journal, original, true, recoverySupport);
    }

    /** Retains unknown facts and releases known policy only after original author cleanup succeeds. / 保留未知事实，仅在原作者清理成功后释放已知策略。
     * @param preparation same-task original preparation / 同任务原准备
     * @return original one-shot publication / 原一次性发布
     * @throws Exception if original preparation or cleanup fails / 原准备或清理失败时 */
    public OperationResult<DeploymentRefFlow.PreparedPublish> prepare(TaskWork<DeploymentRefFlow.PreparedPublish> preparation) throws Exception {
        var outcome = new AtomicReference<>(TaskResult.UNKNOWN);
        boolean transferred = false;
        try {
            var result = preparation.run(task);
            if (result.outcome() != TaskResult.SUCCEEDED) {
                outcome.set(result.outcome());
                return result;
            }
            var ready = result.value().orElseThrow();
            var guarded = new DeploymentRefFlow.PreparedPublish(ready.plan(), supplied -> {
                sameTask(supplied);
                var actual = ready.author().run(supplied);
                outcome.set(actual.outcome());
                return actual;
            }, () -> {
                try {
                    ready.cleanup().close();
                    finish(outcome.get());
                } finally {
                    finish(TaskResult.UNKNOWN);
                }
            });
            transferred = true;
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(guarded), Optional.empty());
        } finally {
            if (!transferred) finish(outcome.get());
        }
    }

    /** Closes fresh authority and releases original policy only for settled known effects. / 关闭新权限，且仅为已结清已知效果释放原策略。
     * @param outcome original known result or uncertainty / 原已知结果或未知
     * @throws ApiException if actual authority or checkpoint cleanup fails / 实际权限或检查点清理失败时 */
    public synchronized void finish(TaskResult outcome) throws ApiException {
        if (released) return;
        if (outcome != TaskResult.UNKNOWN && !recoverySupport.cleanupReady(original, task))
            return;
        if (permission != null) {
            permission.close();
            permission = null;
        }
        if (outcome == TaskResult.UNKNOWN) return;
        if (recovery) original.interaction().closeWorkflowResources(task);
        if (journal.isPresent()) journal.orElseThrow().close();
        released = true;
    }

    /** Releases a shared guarded recovery before file cleanup and host resourcesReleased checks. / 文件清理及宿主 resourcesReleased 检查前释放共用受控恢复。
     * @param context actual original recovery capabilities / 实际原恢复能力
     * @param task authentic original recovery / 真实原恢复
     * @return whether original policy resources are settled / 原策略资源是否已结清
     * @throws ApiException if original policy cleanup fails / 原策略清理失败时 */
    public static boolean releaseKnown(ModContext context, TaskContext task) throws ApiException {
        if (context instanceof DeploymentPublishPolicyContext guarded) {
            guarded.sameTask(task);
            guarded.finish(TaskResult.SUCCEEDED);
            return guarded.released;
        }
        return true;
    }

    /** Shares the already frozen assisted capabilities with the advisor without creating another scope. / 与顾问共用已冻结辅助能力，不另建作用域。
     * @return the same original assisted scope / 同份原辅助作用域 */
    public ModContext assistedCapabilities() {
        if (routed == original)
            throw new IllegalStateException("Original publication has no frozen assisted scope");
        return routed;
    }

    /** Repairs the packaged helper under the original authenticated recovery policy before reading business state. / 读取业务状态前按原认证恢复策略修复打包helper。
     * @param session original fixed-target authenticated session / 原固定目标认证会话
     * @throws ApiException if approval, installation or its exact receipt is unconfirmed / 审批、安装或精确回执未确认时 */
    public void ensureRecoveryHelper(RemoteSession session) throws ApiException {
        if (!recovery || journal.isEmpty()) return;
        var policy = journal.orElseThrow();
        var facts = policy.facts();
        var server = facts.target().server();
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(tasks(), task);
        var bootstrap = new DeploymentProtocolBootstrap(tasks(), task, server, session, interaction(), layout);
        var retained = policy.repair();
        OperationResult<DeploymentManagedHelper.Identity> result;
        if (retained.isPresent()) {
            result = bootstrap.reconcile(retained.orElseThrow());
        } else {
            var helper = new DeploymentManagedHelper(session, interaction(), server, task, "deployment-helper-recovery-v1", layout);
            result = helper.probe();
            if (result.outcome() == TaskResult.FAILED && result.problem()
                .filter(value -> value.code() == ProblemCode.INCOMPATIBLE && value.eventCode().equals("DEPLOYMENT_HELPER_IDENTITY")).isPresent()) {
                var operation = UUID.randomUUID();
                String serverDigest = digest(server.id());
                String bodyDigest = digest(DeploymentHelperInstallerScript.render(layout));
                String requestDigest = digest(DeploymentHelperInstallerScript.renderTracked(task.id(), operation, serverDigest, bodyDigest, layout));
                var repair = policy.beginRepair(new DeploymentProtocolBootstrapActionLog.Facts(server.id(), operation, serverDigest,
                    bodyDigest, requestDigest, DeploymentManagedHelperBundle.helperSha256(layout), "INTENT", layout));
                result = bootstrap.install(repair);
            }
        }
        if (result.outcome() != TaskResult.SUCCEEDED)
            throw new ApiException(result.problem().orElseGet(() -> new Problem(ProblemCode.UNKNOWN,
                "DEPLOYMENT_HELPER_REPAIR_UNCONFIRMED", "Original helper repair must be verified before business recovery", Map.of(), Optional.of(task.id()))));
    }

    /** Digests exact nonsecret installer bindings. / 摘要精确非秘密安装绑定。
     * @param value fixed value / 固定值
     * @return canonical lowercase SHA256 / 规范小写 SHA256 */
    private static String digest(String value) {
        return DeploymentNativeDatabaseWire.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Opens one fresh original scope only when a validated original remote receipt requires connection. / 仅已校验原远端回执需要连接时开启一次新原作用域。
     * @throws ApiException if original facts, model roles or fresh authority are unavailable / 原事实、模型角色或新权限不可用时 */
    private synchronized void openRecovery() throws ApiException {
        if (!recovery || recoveryOpened) return;
        var facts = journal.orElseThrow(() -> unavailable("DEPLOYMENT_PUBLICATION_POLICY_MISSING")).facts();
        if (facts.actualFingerprint().isEmpty()) throw unavailable("DEPLOYMENT_PUBLICATION_WIRE_MISSING");
        if (!recoverySupport.permits(original, task))
            throw unavailable("DEPLOYMENT_PUBLICATION_ORIGINAL_RECOVERY_UNCONFIRMED");
        var current = original.servers().get(facts.target().server(), task);
        if (!current.host().equals(facts.target().host()) || current.port() != facts.target().port()
            || !current.username().equals(facts.target().username())
            || current.trustedFingerprint().filter(value -> !facts.actualFingerprint().orElseThrow().equals(value)).isPresent())
            throw unavailable("DEPLOYMENT_PUBLICATION_TARGET_CHANGED");
        original.interaction().closeWorkflowResources(task);
        if (facts.executionMode().equals("ASSISTED")) {
            var frozen = recoverySupport.mode(facts.executionMode()).recover(original, task, facts.modelRevision(), facts.approvalMode(), facts.rememberAuthorization());
            try {
                permission = DeploymentWorkflowAuthorization.recover(original, task, facts.target().server(), facts.modelRevision(),
                    facts.approvalMode(), facts.rememberAuthorization(), facts.toString());
            } catch (DeploymentAiJson.CapabilityOutcomeException refused) {
                throw unavailable("DEPLOYMENT_PUBLICATION_RECOVERY_AUTHORITY_REFUSED");
            }
            routed = frozen;
            if (routed instanceof gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence evidence)
                evidence.workflowOpened(permission);
        }
        recoveryOpened = true;
    }

    /** Rejects every substituted task capability. / 拒绝每份替代任务能力。
     * @param supplied caller-supplied task / 调用方提供任务 */
    private void sameTask(TaskContext supplied) {
        if (supplied != task) throw new IllegalArgumentException("Publication policy belongs to another task");
    }

    /** Reports retained uncertainty without adopting a fallback route. / 不采用回退路线地报告留存未知。
     * @param event stable missing-fact event / 稳定缺失事实事件
     * @return explicit uncertain capability / 明确未知能力 */
    private ApiException unavailable(String event) {
        return new ApiException(new Problem(ProblemCode.UNKNOWN, event, "Original publication needs retained policy and target evidence",
            Map.of(), Optional.of(task.id())));
    }

    /** Returns fixed original SSH for every preparation and publication connection. / 为每次准备及发布连接返回固定原 SSH。
     * @return same-task guarded SSH / 同任务受控 SSH */
    @Override
    public LinuxService linux() {
        return new LinuxService() {
            /** Connects only to the original captured endpoint. / 仅连接原捕获端点。
             * @param server original server / 原服务器
             * @param supplied original task / 原任务
             * @return actual pinned session / 实际固定会话
             * @throws ApiException if original facts or trust fail / 原事实或信任失败时 */
            public RemoteSession connect(ServerId server, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                var facts = journal.orElseThrow(() -> unavailable("DEPLOYMENT_PUBLICATION_POLICY_MISSING")).facts();
                if (!facts.target().server().equals(server)) throw unavailable("DEPLOYMENT_PUBLICATION_SERVER_CHANGED");
                openRecovery();
                var session = routed.linux().connect(facts.fixedTarget(), supplied);
                try {
                    journal.orElseThrow().authenticated(session.facts().fingerprint());
                    return session;
                } catch (ApiException | RuntimeException unconfirmed) {
                    session.close();
                    throw unconfirmed;
                }
            }

            /** Rejects a caller expectation differing from retained original facts. / 拒绝与留存原事实不同的调用方预期。
             * @param expected caller expectation / 调用方预期
             * @param supplied original task / 原任务
             * @return actual pinned session / 实际固定会话
             * @throws ApiException if original expectation differs / 原预期不符时 */
            public RemoteSession connect(RemoteTarget expected, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                if (!journal.orElseThrow(() -> unavailable("DEPLOYMENT_PUBLICATION_POLICY_MISSING")).facts().fixedTarget().equals(expected))
                    throw unavailable("DEPLOYMENT_PUBLICATION_TARGET_CHANGED");
                return connect(expected.server(), supplied);
            }

            /** Reads original transfer evidence without opening scope or SSH. / 不打开作用域或 SSH 地读取原传输依据。
             * @param supplied original task / 原任务
             * @return original outstanding transfers / 原待结传输
             * @throws ApiException if original evidence cannot be read / 原依据无法读取时 */
            public List<TransferCheckpoint> pendingTransfers(TaskContext supplied) throws ApiException {
                sameTask(supplied);
                return original.linux().pendingTransfers(supplied);
            }
        };
    }

    /** Returns original identity. / 返回原身份。
     * @return original identity / 原身份 */
    @Override
    public ModIdentity identity() {
        return original.identity();
    }

    /** Returns original launch mode. / 返回原启动模式。
     * @return original mode / 原模式 */
    @Override
    public ModLaunchMode launchMode() {
        return original.launchMode();
    }

    /** Returns original page capabilities. / 返回原页面能力。
     * @return original page capabilities / 原页面能力 */
    @Override
    public UiService ui() {
        return original.ui();
    }

    /** Returns original task capabilities. / 返回原任务能力。
     * @return original task capabilities / 原任务能力 */
    @Override
    public TaskService tasks() {
        return original.tasks();
    }

    /** Returns complete command review under the original policy. / 返回原策略下的完整命令审核。
     * @return original bound interaction / 原绑定交互 */
    @Override
    public InteractionService interaction() {
        return routed.interaction();
    }

    /** Returns original server lookup. / 返回原服务器查询。
     * @return original server lookup / 原服务器查询 */
    @Override
    public ServerService servers() {
        return original.servers();
    }

    /** Returns original frozen AI route. / 返回原冻结 AI 路由。
     * @return original route / 原路由 */
    @Override
    public AiService ai() {
        return routed.ai();
    }

    /** Returns original database generation. / 返回原数据库代次。
     * @return original database / 原数据库 */
    @Override
    public ModDatabase db() {
        return original.db();
    }

    /** Returns original secrets. / 返回原秘密能力。
     * @return original secrets / 原秘密能力 */
    @Override
    public SecretService secrets() {
        return original.secrets();
    }

    /** Returns original file ownership. / 返回原文件归属。
     * @return original file ownership / 原文件归属 */
    @Override
    public FileService file() {
        return original.file();
    }

    /** Returns original diagnostic storage. / 返回原诊断存储。
     * @return original diagnostics / 原诊断 */
    @Override
    public LogService logs() {
        return original.logs();
    }

    /** Returns original process ownership. / 返回原进程归属。
     * @return original processes / 原进程 */
    @Override
    public ProcessService processes() {
        return original.processes();
    }

    /** Returns original declared services. / 返回原声明服务。
     * @return original services / 原服务 */
    @Override
    public ModServices services() {
        return original.services();
    }
}
