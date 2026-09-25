package gold.debug.windowstolinux.shared.deploy.approval;

import java.util.*;
import java.util.function.*;

import gold.debug.windowstolinux.shared.deploy.task.AgentTaskControl;
import gold.debug.windowstolinux.shared.linux.command.*;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.AgentApprovalMode;

/** Applies identical command semantics to standard assistance and autonomous deployment. / 对标准辅助及自主部署应用一致的命令语义。 */
public final class CommandApprovalAudit implements RemoteCommandAudit {
    /** Selected human confirmation policy. / 所选人工确认策略。 */
    private final AgentApprovalMode mode;

    /** Independent approval context. / 独立审批上下文。 */
    private final DeploymentReviewPort reviewer;

    /** Safe boundary lifecycle. / 安全边界生命周期。 */
    private final AgentTaskControl control;

    /** Human sees the entire actual command or script. / 人工查看完整实际命令或脚本。 */
    private final BiPredicate<RemoteCommandRequest, AgentRiskLevel> human;

    /** Deterministic command and scope validation. / 确定性命令及作用域验证。 */
    private final Function<RemoteCommandRequest, AgentRiskLevel> validate;

    /** Durable nonsecret event sink. / 持久化非秘密事件端口。 */
    private final Consumer<Map<String, String>> journal;

    /** Transient command display, excluded from persistent journals. / 临时命令展示，不进入持久化日志。 */
    private final Consumer<RemoteCommandRequest> display;

    /** Dispatched operations cannot be silently replayed. / 已派发操作不能静默重放。 */
    private final Set<String> dispatched = new HashSet<>();

    /** Binds mandatory local, AI and human review ports. / 绑定强制本地、AI 及人工审核端口。
     * @param mode human policy / 人工策略
     * @param reviewer isolated reviewer / 隔离审批者
     * @param control task control / 任务控制
     * @param human exact-command confirmation / 精确命令确认
     * @param validate local validation / 本地验证
     * @param journal durable metadata sink / 持久化元数据端口
     * @param display transient command display / 临时命令展示
     */
    public CommandApprovalAudit(AgentApprovalMode mode, DeploymentReviewPort reviewer, AgentTaskControl control,
            BiPredicate<RemoteCommandRequest, AgentRiskLevel> human,
            Function<RemoteCommandRequest, AgentRiskLevel> validate, Consumer<Map<String, String>> journal,
            Consumer<RemoteCommandRequest> display) {
        this.mode = Objects.requireNonNull(mode);
        this.reviewer = Objects.requireNonNull(reviewer);
        this.control = Objects.requireNonNull(control);
        this.human = Objects.requireNonNull(human);
        this.validate = Objects.requireNonNull(validate);
        this.journal = Objects.requireNonNull(journal);
        this.display = Objects.requireNonNull(display);
    }

    /** Independently reviews every content segment, then confirms the complete command once. / 独立审批每个内容分段，随后对完整命令仅确认一次。
     * @param request exact execution envelope / 精确执行信封
     */
    @Override
    public void before(RemoteCommandRequest request) {
        control.checkpoint();
        if (dispatched.contains(request.operation()))
            throw new SecurityException("command replay rejected");
        AgentRiskLevel local = validate.apply(request);
        if (local == null || local == AgentRiskLevel.FORBIDDEN)
            throw new SecurityException("local command validation rejected");
        display.accept(request);
        event("COMMAND_BOUND", request,
                "revision=" + request.revision() + ";binding=" + request.binding() + ";stdin=" + request.inputDigest());
        AgentRiskLevel risk = local;
        String full = "COMMAND\n" + request.command() + "\nSCRIPT STDIN\n" + request.script();
        int segments = (full.length() + 5999) / 6000;
        for (int i = 0; i < segments; i++) {
            String segment = full.substring(i * 6000, Math.min(full.length(), (i + 1) * 6000));
            var action = new AgentAction(request.operation() + "-" + i, request.task(), request.target(),
                    request.revision(), 1, AgentToolType.EXECUTE_COMMAND,
                    Map.of("commandSegment", segment, "segment", (i + 1) + "/" + segments, "commandBinding",
                            request.binding(), "directory", request.directory(), "identity", request.identity(),
                            "stdinDigest", request.inputDigest(), "timeout", request.timeout().toString(),
                            "outputLimit", Long.toString(request.outputLimit())),
                    Map.of("scope", "Only this exact selected target and task are authorized.", "revision",
                            request.revision()),
                    local);
            try {
                var review = reviewer.review(
                        "Review the actual pending command. Segments belong to one command; request evidence if context is insufficient.",
                        action, local);
                risk = AgentRiskLevel.values()[Math.max(risk.ordinal(),
                        DeploymentApprovalGate.admit(action, local, review).ordinal())];
                event("COMMAND_AI_REVIEW", request, review.decision().name() + ":" + review.risk().name() + ":" + i);
            } catch (Exception failure) {
                event("COMMAND_DENIED", request, "approval-unavailable-or-rejected");
                throw new SecurityException("independent command approval unavailable or rejected", failure);
            }
            control.checkpoint();
        }
        if (DeploymentApprovalGate.needsHuman(mode, risk)) {
            boolean accepted = human.test(request, risk);
            event("COMMAND_HUMAN_REVIEW", request, Boolean.toString(accepted));
            if (!accepted)
                throw new java.util.concurrent.CancellationException("command rejected by user");
        }
        control.checkpoint();
        if (validate.apply(request) != local)
            throw new SecurityException("command validation changed after review");
    }

    /** Records final dispatch intent after evidence revalidation. / 证据复核后记录最终派发意图。
     * @param request approved command / 已审批命令
     */
    @Override
    public void dispatching(RemoteCommandRequest request) {
        if (!dispatched.add(request.operation()))
            throw new SecurityException("command replay rejected");
        event("COMMAND_EXECUTING", request, request.binding());
    }

    /** Persists only outcome metadata, never raw scripts or source. / 仅持久化结果元数据，不保存原始脚本或源码。
     * @param request executed request / 已执行请求
     * @param result observed exit and output / 观察到的退出及输出
     */
    @Override
    public void after(RemoteCommandRequest request, RemoteCommandResult result) {
        if (!result.known()) {
            unknown(request);
            throw new IllegalStateException("command outcome unknown; automatic continuation blocked");
        }
        event("COMMAND_RESULT", request, "exit=" + result.exitStatus() + ";success=" + result.succeeded() + ";output="
                + AgentAction.digest(result.evidenceOutput() + result.error()));
    }

    /** Retains uncertainty as a terminal replay barrier. / 将不确定状态保留为阻止重放的终态。
     * @param request uncertain request / 不确定请求
     */
    @Override
    public void unknown(RemoteCommandRequest request) {
        try {
            event("COMMAND_UNKNOWN", request, request.binding());
        } finally {
            control.finish(AgentTaskState.UNKNOWN);
        }
    }

    /** Writes a bounded metadata event. / 写入有界元数据事件。
     * @param type event kind / 事件类型
     * @param request exact operation / 精确操作
     * @param detail nonsecret result / 非秘密结果
     */
    private void event(String type, RemoteCommandRequest request, String detail) {
        journal.accept(Map.of("type", type, "action", request.operation(), "detail", detail));
    }
}
