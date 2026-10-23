package gold.debug.windowstolinux.mod.deployment.service.environment;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Advances one original environment operation, closing each physical session before subsequent authentication. / 推进一项原环境操作，后续认证前关闭每个物理会话。 */
final class DeploymentEnvironmentRunner {
    /** Original bound capabilities. / 原绑定能力。 */
    private final ModContext context;

    /** Actual complete-command approval callback. / 实际完整命令审核回调。 */
    private final DeploymentEnvironmentCommandReview review;

    /** Original authentic task. / 原真实任务。 */
    private final TaskContext task;

    /** Original CAS journal. / 原 CAS 日志。 */
    private final DeploymentEnvironmentJournal journal;

    /** Monotonic observation budget; not an occupancy lifetime. / 单调观察预算，并非占用存活期。 */
    private final Duration rebootBudget;

    /** Poll spacing, injected only by bounded local tests. / 轮询间隔，仅有界本机测试注入。 */
    private final Duration pollInterval;

    /** Original captured command timing, independent of current resource settings. / 原捕获命令时序，独立于当前资源配置。 */
    private final gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.Environment timing;

    /** Only true inside the host's explicit original recovery callback. / 仅在宿主明确原恢复回调中为真。 */
    private boolean recovering;

    /** Uses the original historical observation budget. / 使用原历史观察预算。
     * @param context original capabilities / 原能力
     * @param review complete command review / 完整命令审核
     * @param task original task / 原任务
     * @param journal original journal / 原日志
     * @throws ApiException if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    DeploymentEnvironmentRunner(ModContext context, DeploymentEnvironmentCommandReview review, TaskContext task,
                                DeploymentEnvironmentJournal journal) throws ApiException {
        this(context, review, task, journal,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.environment(context.tasks(), task));
    }

    /** Uses the same original task's captured observation and command timing. / 使用同一原任务捕获的观察及命令时序。
     * @param context original capabilities / 原能力
     * @param review original review / 原审核
     * @param task authentic task / 真实任务
     * @param journal original journal / 原日志
     * @param timing captured timing / 捕获时序 */
    private DeploymentEnvironmentRunner(ModContext context, DeploymentEnvironmentCommandReview review, TaskContext task,
                                        DeploymentEnvironmentJournal journal,
                                        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.Environment timing) {
        this.context = context;
        this.review = review;
        this.task = task;
        this.journal = journal;
        this.timing = timing;
        this.rebootBudget = Duration.ofSeconds(timing.rebootSeconds());
        this.pollInterval = Duration.ofSeconds(timing.pollSeconds());
    }

    /** Injects observation timing without changing production rememberAuthorization. / 注入观察时序，不改变生产授权。
     * @param context original capabilities / 原能力
     * @param review exact command review / 精确命令审核
     * @param task original task / 原任务
     * @param journal original journal / 原日志
     * @param rebootBudget bounded observation budget / 有界观察预算
     * @param pollInterval bounded poll spacing / 有界轮询间隔 */
    DeploymentEnvironmentRunner(ModContext context, DeploymentEnvironmentCommandReview review, TaskContext task,
                                DeploymentEnvironmentJournal journal, Duration rebootBudget, Duration pollInterval) {
        this.context = context;
        this.review = review;
        this.task = task;
        this.journal = journal;
        this.rebootBudget = rebootBudget;
        this.pollInterval = pollInterval;
        this.timing = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.Environment.original();
    }

    /** Performs admitted original work or only explicitly authorized original reconciliation. / 执行已准入原工作或仅明确授权的原核对。
     * @param recovery whether the host entered original recovery / 宿主是否进入原恢复
     * @return verified terminal outcome or retained original uncertainty / 已核终态结果或保留原未知
     * @throws ApiException if durable original evidence or actual cleanup fails / 持久原依据或实际清理失败时 */
    OperationResult<Void> run(boolean recovery) throws ApiException {
        recovering = recovery;
        try {
            var state = journal.read();
            if (recovery && task.cancelToken().requested()) return finishCancelledRecovery();
            if (recovery && state.stage().equals("INTENT")) {
                journal.close();
                return cancelled();
            }
            if (state.stage().equals("INTENT")) initialize(state);
            while (!journal.read().stage().equals("VERIFIED") && !journal.read().stage().equals("FAILED")) step();
            var result = journal.read().stage().equals("VERIFIED")
                ? new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty())
                : new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new gold.debug.windowstolinux.appapi.task.result.Problem(
                ProblemCode.IO_FAILURE, "ENVIRONMENT_INSTALL_FAILED", "Original installation failed; no rollback is claimed",
                java.util.Map.of(), Optional.empty())));
            journal.close();
            return result;
        } catch (DeploymentEnvironmentPendingException pending) {
            return pending.result();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new DeploymentEnvironmentPendingException("ENVIRONMENT_INTERRUPTED_RETAINED").result();
        } catch (ApiException failure) {
            var state = journal.read();
            if (state.stage().equals("INTENT") || state.stage().equals("OBSERVED")) {
                journal.close();
                return new OperationResult<>(failure.problem().code() == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED,
                    Optional.empty(), Optional.of(failure.problem()));
            }
            return new DeploymentEnvironmentPendingException("ENVIRONMENT_ORIGINAL_CONNECTION_UNCONFIRMED").result();
        } catch (RuntimeException conflict) {
            return new DeploymentEnvironmentPendingException("ENVIRONMENT_ORIGINAL_FACTS_CONFLICT").result();
        }
    }

    /** Reconciles only previously attempted effects after cancellation and sends no subsequent business effect. / 取消后仅核对先前已尝试副作用，不发送后续业务副作用。
     * @return cancellation only when original effects and actual connection closure are known / 仅原副作用与实际连接关闭已知时返回取消
     * @throws ApiException if original rememberAuthorization, receipt persistence or physical closure fails / 原授权、回执持久化或物理关闭失败时
     * @throws DeploymentEnvironmentPendingException if original security or sent effects remain unresolved / 原安全状态或已发送副作用仍未决时 */
    private OperationResult<Void> finishCancelledRecovery() throws ApiException, DeploymentEnvironmentPendingException {
        var state = journal.read();
        if (state.stage().equals("INTENT") || state.stage().equals("OBSERVED")) {
            journal.close();
            return cancelled();
        }
        switch (state.stage()) {
            case "PREPARE_DISPATCHED" -> settleSecurity(state, "prepare", "REBOOT_PENDING");
            case "ENFORCE_DISPATCHED" -> settleSecurity(state, "enforce", "ENFORCEMENT_PENDING");
            case "COMMIT_DISPATCHED" -> settleSecurity(state, "commit", "SELINUX_COMPLETE");
            case "PACKAGES_DISPATCHED" -> settlePackages(state);
            case "HELPER_DISPATCHED" ->
                new DeploymentEnvironmentHelper(context, review, task, journal).reconcile(state);
            default -> {
            }
        }
        var retained = journal.read();
        if (!journal.cancellationSettled())
            throw new DeploymentEnvironmentPendingException("ENVIRONMENT_CANCELLED_ORIGINAL_EFFECT_UNRESOLVED");
        try (var session = context.linux().connect(retained.target(), task)) {
            if (!retained.target().fingerprint().equals(Optional.of(session.facts().fingerprint())))
                throw new DeploymentEnvironmentPendingException("ENVIRONMENT_CANCELLED_REAUTH_UNCONFIRMED");
        }
        var result = cancelled(retained);
        journal.closeCancelled();
        return result;
    }

    /** Pins the actual initial wire key before any reviewed command. / 任何审核命令前固定实际初始 wire 密钥。
     * @param state original unsent intent / 原未发送意图
     * @throws ApiException if authentication, persistence or close fails / 认证、持久化或关闭失败时
     * @throws DeploymentEnvironmentPendingException if original observation is unresolved / 原观测未决时 */
    private void initialize(DeploymentEnvironmentState state) throws ApiException, DeploymentEnvironmentPendingException {
        try (var session = context.linux().connect(state.target(), task)) {
            var original = state.target();
            var pinned = new RemoteTarget(original.server(), original.taskId(), original.host(), original.port(), original.username(),
                Optional.of(session.facts().fingerprint()));
            var next = new DeploymentEnvironmentState(1, pinned, state.operation(), state.bodySha(), "INTENT", "none", Optional.empty());
            journal.advance(state, next);
            var observed = execute(session, next, "probe");
            journal.advance(next, next.next("OBSERVED", "none", observed));
        }
    }

    /** Chooses only the next original phase; sent stages are observation-only. / 仅选择下一原阶段，已发送阶段仅作观察。
     * @throws ApiException if original facts or authentication fail / 原事实或认证失败时
     * @throws DeploymentEnvironmentPendingException if original effects remain uncertain / 原副作用仍未知时
     * @throws InterruptedException if reboot waiting is interrupted / 重启等待中断时 */
    private void step() throws ApiException, DeploymentEnvironmentPendingException, InterruptedException {
        var state = journal.read();
        gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.progress(
            context, task, gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "environment.stage", state.stage()));
        switch (state.stage()) {
            case "OBSERVED" -> beginChanges(state);
            case "PREPARE_DISPATCHED" -> settleSecurity(state, "prepare", "REBOOT_PENDING");
            case "REBOOT_PENDING" -> afterReboot(state);
            case "ENFORCE_DISPATCHED" -> settleSecurity(state, "enforce", "ENFORCEMENT_PENDING");
            case "ENFORCEMENT_PENDING" -> commit(state);
            case "COMMIT_DISPATCHED" -> settleSecurity(state, "commit", "SELINUX_COMPLETE");
            case "SELINUX_COMPLETE" -> {
                confirm(state, "packages");
                dispatch(state, "packages", "PACKAGES_DISPATCHED");
            }
            case "PACKAGES_DISPATCHED" -> settlePackages(state);
            case "PACKAGES_INSTALLED" -> new DeploymentEnvironmentHelper(context, review, task, journal).prepare(state);
            case "HELPER_DISPATCHED" ->
                new DeploymentEnvironmentHelper(context, review, task, journal).reconcile(state);
            case "HELPER_INSTALLED" -> verify(state);
            default -> throw new IllegalArgumentException("Unsupported original environment phase");
        }
    }

    /** Starts only explicitly reviewed changes from the original observation. / 仅从原观测开始明确审阅的变更。
     * @param state original observed state / 原观测状态
     * @throws ApiException if approval fails / 审批失败时
     * @throws DeploymentEnvironmentPendingException if security is unsupported / 安全姿态不受支持时 */
    private void beginChanges(DeploymentEnvironmentState state) throws ApiException, DeploymentEnvironmentPendingException {
        var observed = state.observation().orElseThrow();
        if (observed.packagePhase().equals("ABSENT") && (observed.selinuxPhase().equals("NOT_APPLICABLE")
            || observed.selinuxPhase().equals("COMPLETE"))) {
            if (!observed.profile().startsWith("ubuntu-") && !observed.profile().startsWith("debian-")
                && !observed.security().equals("Enforcing"))
                throw new DeploymentEnvironmentPendingException("ENVIRONMENT_ENFORCING_REQUIRED");
            confirm(state, "packages");
            dispatch(state, "packages", "PACKAGES_DISPATCHED");
        } else if (observed.selinuxPhase().equals("UNPREPARED") && observed.packagePhase().equals("ABSENT")) {
            confirm(state, "prepare");
            dispatch(state, "prepare", "PREPARE_DISPATCHED");
        } else throw new DeploymentEnvironmentPendingException("ENVIRONMENT_FOREIGN_OR_UNEXPECTED_STATE");
    }

    /** Records exact dispatch before sending once; approval denial before dispatch remains unsent. / 发送一次前记录精确发送，发送前审核拒绝仍为未发送。
     * @param state original observed facts / 原观测事实
     * @param stage fixed command / 固定命令
     * @param dispatched retained dispatch stage / 保留发送阶段
     * @throws ApiException if original CAS, approval or connection fails / 原 CAS、审批或连接失败时
     * @throws DeploymentEnvironmentPendingException if a sent result is uncertain / 已发送结果未知时 */
    private void dispatch(DeploymentEnvironmentState state, String stage, String dispatched)
        throws ApiException, DeploymentEnvironmentPendingException {
        checkCancellation();
        try (var session = context.linux().connect(state.target(), task); var request = DeploymentEnvironmentScripts.request(state, stage, timing)) {
            var approval = review.approve(state.target(), revision(state), request, task);
            if (approval.outcome() != TaskResult.SUCCEEDED) {
                if (state.stage().equals("OBSERVED")) {
                    journal.close();
                    throw cancelledFailure();
                }
                throw new DeploymentEnvironmentPendingException("ENVIRONMENT_REVIEW_CANCELLED_AFTER_CHANGE");
            }
            var sent = state.next(dispatched, DeploymentEnvironmentScripts.requestDigest(state, stage), state.observation().orElseThrow());
            journal.advance(state, sent);
            var result = session.execute(request, approval.value().orElseThrow());
            if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()
                || !result.value().orElseThrow().exitCode().equals(Optional.of(0)) || result.value().orElseThrow().truncated())
                throw new DeploymentEnvironmentPendingException("ENVIRONMENT_DISPATCH_UNCONFIRMED");
            var observed = DeploymentEnvironmentObservation.parse(result.value().orElseThrow().stdout(), sent);
            settleObserved(sent, stage, observed);
        }
    }

    /** Observes a sent security stage without executing it again. / 观察已发送安全阶段，不再执行它。
     * @param state original dispatched state / 原已发送状态
     * @param stage original command / 原命令
     * @param next expected next stage / 预期后续阶段
     * @throws ApiException if original connection fails / 原连接失败时
     * @throws DeploymentEnvironmentPendingException if original receipt is unresolved / 原回执未决时 */
    private void settleSecurity(DeploymentEnvironmentState state, String stage, String next)
        throws ApiException, DeploymentEnvironmentPendingException {
        var observed = observe(state, "probe");
        requireReceipt(state, stage, observed);
        requireChange(stage, observed);
        if (stage.equals("prepare") && !List.of("REBOOT_PENDING", "READY_TO_ENFORCE").contains(observed.selinuxPhase())
            || stage.equals("enforce") && !observed.selinuxPhase().equals("ENFORCEMENT_PENDING")
            || stage.equals("commit") && !observed.selinuxPhase().equals("COMPLETE"))
            throw new DeploymentEnvironmentPendingException("ENVIRONMENT_SECURITY_RECEIPT_CONFLICT");
        journal.advance(state, state.next(next, state.requestSha(), observed));
    }

    /** Matches a returned dispatch receipt before phase advancement. / 阶段推进前匹配已返回发送回执。
     * @param state original dispatch / 原发送
     * @param stage fixed command / 固定命令
     * @param observed original response / 原响应
     * @throws ApiException if original CAS fails / 原 CAS 失败时
     * @throws DeploymentEnvironmentPendingException if receipt differs / 回执不符时 */
    private void settleObserved(DeploymentEnvironmentState state, String stage, DeploymentEnvironmentObservation observed)
        throws ApiException, DeploymentEnvironmentPendingException {
        requireReceipt(state, stage, observed);
        requireChange(stage, observed);
        String next = switch (stage) {
            case "prepare" -> "REBOOT_PENDING";
            case "enforce" -> "ENFORCEMENT_PENDING";
            case "commit" -> "SELINUX_COMPLETE";
            case "packages" -> "PACKAGES_INSTALLED";
            default -> throw new IllegalArgumentException("Unknown original dispatch");
        };
        if (stage.equals("packages") && !observed.packagePhase().equals("SUCCEEDED"))
            throw new DeploymentEnvironmentPendingException("ENVIRONMENT_PACKAGES_UNCONFIRMED");
        journal.advance(state, state.next(next, state.requestSha(), observed));
    }

    /** Polls through fresh original-target authentications without repeating reboot. / 经原目标新认证轮询，不重复重启。
     * @param state original reboot evidence / 原重启依据
     * @throws ApiException if key, credentials or target changed / 密钥、凭据或目标改变时
     * @throws DeploymentEnvironmentPendingException if the bounded observation cannot prove reboot / 有界观测无法证明重启时
     * @throws InterruptedException if waiting is interrupted / 等待中断时 */
    private void afterReboot(DeploymentEnvironmentState state)
        throws ApiException, DeploymentEnvironmentPendingException, InterruptedException {
        long deadline = System.nanoTime() + rebootBudget.toNanos();
        while (true) {
            checkCancellation();
            try {
                var observed = observe(state, "probe");
                requireReceipt(state, "prepare", observed);
                if (observed.selinuxPhase().equals("READY_TO_ENFORCE")) {
                    var ready = state.next("REBOOT_PENDING", state.requestSha(), observed);
                    journal.advance(state, ready);
                    confirm(ready, "enforce");
                    dispatch(ready, "enforce", "ENFORCE_DISPATCHED");
                    return;
                }
                if (!observed.selinuxPhase().equals("REBOOT_PENDING"))
                    throw new DeploymentEnvironmentPendingException("ENVIRONMENT_REBOOT_STATE_CONFLICT");
            } catch (ApiException failure) {
                if (!failure.problem().eventCode().equals("SSH_CONNECTION_FAILED")
                    && !failure.problem().eventCode().equals("SSH_CONNECT_TIMEOUT")) throw failure;
            }
            if (System.nanoTime() >= deadline)
                throw new DeploymentEnvironmentPendingException("ENVIRONMENT_REBOOT_NOT_OBSERVED");
            Thread.sleep(pollInterval.toMillis());
        }
    }

    /** Requires a fresh authenticated enforcing observation before persistent commit. / 持久提交前要求一次新认证的强制态观测。
     * @param state original enforcing stage / 原强制态阶段
     * @throws ApiException if original rememberAuthorization fails / 原授权失败时
     * @throws DeploymentEnvironmentPendingException if fresh login or posture is unproved / 新登录或姿态未证时 */
    private void commit(DeploymentEnvironmentState state) throws ApiException, DeploymentEnvironmentPendingException {
        var observed = observe(state, "probe");
        requireReceipt(state, "enforce", observed);
        if (!observed.security().equals("Enforcing") || !observed.selinuxPhase().equals("ENFORCEMENT_PENDING"))
            throw new DeploymentEnvironmentPendingException("ENVIRONMENT_ENFORCEMENT_REAUTH_UNCONFIRMED");
        var verified = state.next("ENFORCEMENT_PENDING", state.requestSha(), observed);
        journal.advance(state, verified);
        confirm(verified, "commit");
        dispatch(verified, "commit", "COMMIT_DISPATCHED");
    }

    /** Reconciles only the original package receipt, never a second installation. / 仅核对原安装回执，绝不进行第二次安装。
     * @param state original package dispatch / 原安装发送
     * @throws ApiException if original CAS or connection fails / 原 CAS 或连接失败时
     * @throws DeploymentEnvironmentPendingException if receipt is absent or started / 回执不存在或仅开始时 */
    private void settlePackages(DeploymentEnvironmentState state) throws ApiException, DeploymentEnvironmentPendingException {
        var observed = observe(state, "probe");
        requireReceipt(state, "packages", observed);
        if (!List.of("SUCCEEDED", "FAILED").contains(observed.packagePhase()))
            throw new DeploymentEnvironmentPendingException("ENVIRONMENT_PACKAGES_RECEIPT_UNCONFIRMED");
        journal.advance(state, state.next(observed.packagePhase().equals("SUCCEEDED") ? "PACKAGES_INSTALLED" : "FAILED",
            state.requestSha(), observed));
    }

    /** Proves installed capabilities after the installation session physically exited. / 安装会话物理退出后证明已安装能力。
     * @param state original installed receipt / 原已安装回执
     * @throws ApiException if original authentication or cleanup fails / 原认证或清理失败时
     * @throws DeploymentEnvironmentPendingException if final original facts are unproved / 最终原事实未证时 */
    private void verify(DeploymentEnvironmentState state) throws ApiException, DeploymentEnvironmentPendingException {
        var observed = observe(state, "verify");
        if (!observed.packageRequest().equals(state.observation().orElseThrow().packageRequest()))
            throw new DeploymentEnvironmentPendingException("ENVIRONMENT_ORIGINAL_PACKAGE_RECEIPT_CONFLICT");
        if (!observed.packagePhase().equals("SUCCEEDED") || !observed.boot().equals(state.observation().orElseThrow().boot())
            || !observed.security().equals(state.observation().orElseThrow().security()))
            throw new DeploymentEnvironmentPendingException("ENVIRONMENT_FINAL_REAUTH_UNCONFIRMED");
        journal.advance(state, state.next("VERIFIED", state.requestSha(), observed));
    }

    /** Verifies the actual security transition before advancing its original receipt. / 推进原回执前核实实际安全转移。
     * @param stage original command / 原命令
     * @param observed returned original facts / 已返回原事实
     * @throws DeploymentEnvironmentPendingException if an expected security transition is absent / 缺少预期安全转移时 */
    private static void requireChange(String stage, DeploymentEnvironmentObservation observed) throws DeploymentEnvironmentPendingException {
        if (stage.equals("prepare") && !List.of("REBOOT_PENDING", "READY_TO_ENFORCE").contains(observed.selinuxPhase())
            || stage.equals("enforce") && (!observed.selinuxPhase().equals("ENFORCEMENT_PENDING") || !observed.security().equals("Enforcing"))
            || stage.equals("commit") && (!observed.selinuxPhase().equals("COMPLETE") || !observed.security().equals("Enforcing")))
            throw new DeploymentEnvironmentPendingException("ENVIRONMENT_SECURITY_TRANSITION_UNCONFIRMED");
    }

    /** Opens and actually closes an independent authenticated observation session. / 打开并实际关闭一个独立认证观察会话。
     * @param state original fixed state / 原固定状态
     * @param stage fixed read-only command / 固定只读命令
     * @return exact observation / 精确观测
     * @throws ApiException if original authentication or cleanup fails / 原认证或清理失败时
     * @throws DeploymentEnvironmentPendingException if command result is unknown / 命令结果未知时 */
    private DeploymentEnvironmentObservation observe(DeploymentEnvironmentState state, String stage)
        throws ApiException, DeploymentEnvironmentPendingException {
        checkCancellation();
        try (var session = context.linux().connect(state.target(), task)) {
            return execute(session, state, stage);
        }
    }

    /** Reviews and executes one exact read-only request. / 审核并执行一条精确只读请求。
     * @param session original owned session / 原所属会话
     * @param state original facts / 原事实
     * @param stage fixed command / 固定命令
     * @return exact observation / 精确观测
     * @throws ApiException if original approval fails / 原审核失败时
     * @throws DeploymentEnvironmentPendingException if the command lacks verified facts / 命令缺少已核事实时 */
    private DeploymentEnvironmentObservation execute(RemoteSession session, DeploymentEnvironmentState state, String stage)
        throws ApiException, DeploymentEnvironmentPendingException {
        try (var request = DeploymentEnvironmentScripts.request(state, stage, timing)) {
            var approval = review.approve(state.target(), revision(state), request, task);
            if (approval.outcome() != TaskResult.SUCCEEDED) throw cancelledFailure();
            var result = session.execute(request, approval.value().orElseThrow());
            if (result.value().isPresent() && !result.value().orElseThrow().truncated()
                && result.value().orElseThrow().exitCode().filter(code -> code > 0 && code < 128).isPresent())
                throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(ProblemCode.INCOMPATIBLE,
                    "ENVIRONMENT_OBSERVATION_REJECTED", "Original fixed environment prerequisites were rejected",
                    java.util.Map.of(), Optional.empty()));
            if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()
                || !result.value().orElseThrow().exitCode().equals(Optional.of(0)) || result.value().orElseThrow().truncated())
                throw new DeploymentEnvironmentPendingException("ENVIRONMENT_OBSERVATION_UNCONFIRMED");
            return DeploymentEnvironmentObservation.parse(result.value().orElseThrow().stdout(), state);
        }
    }

    /** Requires independent exact human approval of system changes or the fixed package list. / 要求对系统变更或固定包集合进行独立精确人工批准。
     * @param state original observed facts / 原观测事实
     * @param stage specific reviewed change / 具体已审阅变更
     * @throws ApiException if original review is unavailable or denied / 原审阅不可用或被拒时 */
    private void confirm(DeploymentEnvironmentState state, String stage) throws ApiException {
        var observed = state.observation().orElseThrow();
        String effect = stage.equals("packages") ? "Fixed packages: " + String.join(" ", DeploymentEnvironmentProfiles.packages(observed.profile()))
            : "SELinux " + stage + ": config.before backup, targeted labels, approved reboot (prepare), enforcing login safety timer (enforce), persistent enforcing (commit).";
        String exact = "task=" + task.id() + "\noperation=" + state.operation() + "\ntarget="
            + DeploymentEnvironmentScripts.targetDigest(state.target()) + "\nbody=" + state.bodySha() + "\n" + observed + "\n" + effect;
        var approval = context.interaction().approve(new ActionIntent("deployment.environment." + stage,
            List.of(state.target().server().id()), state.target().server().id(), revision(state), exact,
            gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "environment.confirmSystem")), task);
        if (approval.outcome() != TaskResult.SUCCEEDED) throw cancelledFailure();
    }

    /** Checks receipt identity without allowing sent effects to be replayed. / 核对回执身份，不允许重放已发送副作用。
     * @param state original dispatch facts / 原发送事实
     * @param stage original command / 原命令
     * @param observed original remote receipts / 原远端回执
     * @throws DeploymentEnvironmentPendingException if exact original digest is absent / 精确原摘要缺失时 */
    private static void requireReceipt(DeploymentEnvironmentState state, String stage, DeploymentEnvironmentObservation observed)
        throws DeploymentEnvironmentPendingException {
        String actual = switch (stage) {
            case "prepare" -> observed.prepareRequest();
            case "enforce" -> observed.enforceRequest();
            case "commit" -> observed.commitRequest();
            case "packages" -> observed.packageRequest();
            default -> throw new IllegalArgumentException("Unknown original receipt");
        };
        if (!state.requestSha().equals(actual))
            throw new DeploymentEnvironmentPendingException("ENVIRONMENT_ORIGINAL_RECEIPT_CONFLICT");
    }

    /** Binds every approval to unchanged resources and latest exact facts. / 将每项审批绑定到未改变资源及最近精确事实。
     * @param state original facts / 原事实
     * @return original review revision / 原审阅修订 */
    private static String revision(DeploymentEnvironmentState state) {
        return state.operation() + ":" + DeploymentEnvironmentScripts.digest(state.bodySha() + state.stage() + state.observation());
    }

    /** Cancels future sends without claiming any remote rollback. / 取消后续发送，不声称任何远端回滚。
     * @throws ApiException if cancellation was requested / 已请求取消时 */
    private void checkCancellation() throws ApiException {
        if (!recovering && task.cancelToken().requested()) throw cancelledFailure();
    }

    /** Creates a safe original cancellation diagnostic. / 创建安全原取消诊断。
     * @return checked cancellation / 受检取消 */
    private static ApiException cancelledFailure() {
        return new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(ProblemCode.CANCELLED,
            "ENVIRONMENT_REVIEW_CANCELLED", "Further environment sends were cancelled", java.util.Map.of(), Optional.empty()));
    }

    /** Reports a proven unsent original operation. / 报告已证明未发送的原操作。
     * @return cancellation result / 取消结果 */
    private static OperationResult<Void> cancelled() {
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
    }

    /** Retains bounded non-secret original effect facts in the host's terminal summary before checkpoint archival. / 检查点归档前在宿主终态摘要保留有界非秘密原副作用事实。
     * @param state original settled partial preparation / 原已核部分准备
     * @return cancellation without claiming reversal of prior effects / 不声称撤回先前副作用的取消结果 */
    private static OperationResult<Void> cancelled(DeploymentEnvironmentState state) {
        var observed = state.observation().orElseThrow();
        var facts = java.util.Map.ofEntries(
            java.util.Map.entry("taskId", state.target().taskId().toString()),
            java.util.Map.entry("serverId", state.target().server().id()),
            java.util.Map.entry("targetSha", DeploymentEnvironmentScripts.targetDigest(state.target())),
            java.util.Map.entry("operation", state.operation().toString()),
            java.util.Map.entry("stage", state.stage()),
            java.util.Map.entry("bodySha", state.bodySha()),
            java.util.Map.entry("requestSha", state.requestSha()),
            java.util.Map.entry("profile", observed.profile()),
            java.util.Map.entry("bootId", observed.boot().toString()),
            java.util.Map.entry("configSha", observed.configSha()),
            java.util.Map.entry("security", observed.security()),
            java.util.Map.entry("selinuxPhase", observed.selinuxPhase()),
            java.util.Map.entry("packagePhase", observed.packagePhase()),
            java.util.Map.entry("packageRequest", observed.packageRequest()),
            java.util.Map.entry("prepareRequest", observed.prepareRequest()),
            java.util.Map.entry("enforceRequest", observed.enforceRequest()),
            java.util.Map.entry("commitRequest", observed.commitRequest()));
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.of(new gold.debug.windowstolinux.appapi.task.result.Problem(
            ProblemCode.CANCELLED, "ENVIRONMENT_CANCELLED_AFTER_KNOWN_EFFECTS",
            "Known original effects remain; further preparation was cancelled",
            facts, Optional.empty())));
    }
}
