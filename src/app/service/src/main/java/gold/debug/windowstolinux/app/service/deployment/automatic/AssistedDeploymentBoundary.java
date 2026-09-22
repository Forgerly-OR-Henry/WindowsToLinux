package gold.debug.windowstolinux.app.service.deployment.automatic;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.*;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.deploy.approval.DeploymentApprovalGate;
import gold.debug.windowstolinux.shared.deploy.contract.*;
import gold.debug.windowstolinux.shared.deploy.task.AgentTaskControl;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.source.snapshot.SourceSnapshotIdentity;
import gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedActionModelPort;
import gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedActionSession;
import gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedExecutionPort;

/** Approves standard steps and bounds assisted recovery to registered tools. / 审批标准步骤，并将辅助恢复限制于登记工具。 */
public final class AssistedDeploymentBoundary {
    /** Frozen task identifier. / 冻结任务标识。 */
    private final String task;

    /** Exact server and execution account, retained independently of display labels. / 精确服务器及执行账户，独立于展示标签保存。 */
    private final String target;

    /** Serial approval and execution coordinator. / 串行审批及执行协调器。 */
    private final AssistedActionSession session;

    /** Safe boundary lifecycle. / 安全边界生命周期。 */
    private final AgentTaskControl control;

    /** Synchronous nonsecret audit sink. / 同步非秘密审计端口。 */
    private final Consumer<Map<String, String>> journal;

    /** Rechecks the selected server identity against current inventory. / 对照当前清单重新检查所选服务器身份。 */
    private final BooleanSupplier serverUnchanged;

    /** Private source root. / 私有源码根目录。 */
    private Path source;

    /** Frozen member/content digest. / 冻结成员及内容摘要。 */
    private String sourceDigest;

    /** Current immutable plan version. / 当前不可变计划版本。 */
    private long revision;

    /** Known nonsecret facts accumulated from actual operations. / 根据实际操作积累的已知非秘密事实。 */
    private final Map<String, String> facts = new LinkedHashMap<>();

    /** Fixed callbacks for supported owned-resource tools. / 受支持且有归属资源工具的固定回调。 */
    private final Map<String, ExtraOperation> extras = new LinkedHashMap<>();

    /** Binds one task before any model or remote call. / 在任何模型或远端调用前绑定一个任务。
     * @param task task identifier / 任务标识
     * @param server frozen server profile / 冻结服务器资料
     * @param policy human confirmation policy / 人工确认策略
     * @param models independent decision/review ports / 独立决策及审批端口
     * @param control task lifecycle / 任务生命周期
     * @param interaction human callback / 人工回调
     * @param journal durable evidence sink / 持久化证据端口
     * @param serverUnchanged selected server identity check / 所选服务器身份检查
     */
    public AssistedDeploymentBoundary(String task, ServerProfile server, AgentApprovalMode policy,
            AssistedActionModelPort models, AgentTaskControl control, AutomaticDeploymentInteraction interaction,
            Consumer<Map<String, String>> journal, BooleanSupplier serverUnchanged) {
        this.task = task;
        this.target = target(server);
        this.control = control;
        this.journal = journal;
        this.serverUnchanged = serverUnchanged;
        session = new AssistedActionSession(task, target,
                "Deploy the frozen selected source to " + target + " using only registered managed resources.", policy,
                models, control,
                (action, review) -> interaction.confirm("deployment.agent.review", Map.of("tool", action.tool().name(),
                        "target", target, "parameters", new TreeMap<>(action.parameters()).toString(), "evidence",
                        new TreeMap<>(action.evidence()).toString(), "risk",
                        AgentRiskLevel.values()[Math.max(action.risk().ordinal(), review.risk().ordinal())].name(),
                        "reason", review.reason(), "binding", action.binding())),
                journal);
        facts.put("authorization",
                "User requested deployment to this exact selected server; no unrelated resources are authorized.");
        facts.put("executionIdentity", target);
        gold.debug.windowstolinux.app.service.ai.DeploymentAiScope.current()
                .ifPresent(scope -> facts.put("modelConfiguration", scope.binding()));
    }

    /** Binds the current source, plan and observed facts for actual command approval. / 为实际命令审批绑定当前源码、方案及观察事实。
     * @return current evidence digest / 当前证据摘要
     */
    public String binding() {
        return AgentAction.digest(String.valueOf(sourceDigest) + ":" + revision + ":" + new TreeMap<>(facts));
    }

    /** Encodes server and account identity without credentials. / 编码服务器及账户身份，不包含凭据。
     * @param server selected server / 所选服务器
     * @return exact readable target / 精确可读目标
     */
    public static String target(ServerProfile server) {
        return server.id() + "|" + server.username() + "@" + server.host() + ":" + server.sshPort();
    }

    /** Freezes the private source before assisted analysis. / 在辅助分析前冻结私有源码。
     * @param root private snapshot root / 私有快照根目录
     * @throws Exception when snapshot identity cannot be verified / 无法验证快照身份时
     */
    public void freeze(Path root) throws Exception {
        if (source != null)
            throw new IllegalStateException("source already frozen");
        source = root;
        sourceDigest = SourceSnapshotIdentity.digest(root);
        facts.put("sourceSnapshot", sourceDigest);
    }

    /** Adds a bounded nonsecret verified fact. / 增加有界非秘密已验证事实。
     * @param key controlled evidence key / 受控证据键
     * @param value bounded safe content / 有界安全内容
     */
    public void fact(String key, String value) {
        if (value.length() > 8192)
            throw new IllegalArgumentException("fact too large");
        facts.put(key, value);
    }

    /** Registers an owned-resource action supplied by code, never by model text. / 登记由代码而非模型文本提供的有归属资源动作。
     * @param key stable catalog identity / 稳定目录身份
     * @param type registered capability / 已登记能力
     * @param parameters exact nonsecret parameters / 精确非秘密参数
     * @param valid ownership check / 归属检查
     * @param operation narrow implementation / 窄实现
     */
    public void register(String key, AgentToolType type, Map<String, String> parameters, BooleanSupplier valid,
            Callable<AgentObservation> operation) {
        extras.put(key, new ExtraOperation(type, Map.copyOf(parameters), valid, operation));
    }

    /** Approves one standard operation without model step selection. / 审批一个标准操作，不让模型选择步骤。
     * @param type registered capability / 已登记能力
     * @param parameters exact nonsecret parameters / 精确非秘密参数
     * @param operation existing operation / 既有操作
     * @param <T> existing typed result / 既有类型化结果
     * @return actual existing result / 实际既有结果
     * @throws Exception on denial, failed execution or unknown outcome / 拒绝、执行失败或结果未知时
     */
    public <T> T execute(AgentToolType type, Map<String, String> parameters, Callable<T> operation) throws Exception {
        return attempt(type, parameters, operation);
    }

    /** Shares the task budget with independent source analysis. / 与独立源码分析共享任务预算。
     * @return shared task allowance / 共享任务额度
     */
    public gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedDecisionBudget budget() {
        return session.budget();
    }

    /** Runs bounded diagnostics and requires an observed corrective change before retry. / 执行有界诊断，重试前要求观察到修正变化。
     * @param failure actual known failed transaction evidence / 实际已知失败事务证据
     * @return whether an admitted recovery operation changed the environment / 准入恢复操作是否改变环境
     * @throws Exception when approval fails or the outcome is unknown / 审批失败或结果未知时
     */
    public boolean recover(Map<String, String> failure) throws Exception {
        failure.forEach((key, value) -> fact("failure/" + key, value));
        var pending = new LinkedHashMap<>(extras);
        for (int round = 0; round < 4 && !pending.isEmpty() && session.remaining() > 0; round++) {
            var offered = new LinkedHashMap<String, ExtraOperation>();
            var keys = new LinkedHashMap<String, String>();
            var menu = new ArrayList<AgentAction>();
            pending.forEach((key, extra) -> {
                if (extra.valid().getAsBoolean()) {
                    var action = action(extra.type(), extra.parameters(), risk(extra.type()));
                    offered.put(action.id(), extra);
                    keys.put(action.id(), key);
                    menu.add(action);
                }
            });
            if (offered.isEmpty())
                return false;
            var selected = new java.util.concurrent.atomic.AtomicReference<AgentToolType>();
            var observed = session.run(menu, "", new AssistedExecutionPort() {
                /** Rebinds current source and model facts. / 重新绑定当前源码及模型事实。
                 * @param action pending action / 待执行动作
                 * @return current action / 当前动作
                 */
                @Override
                public AgentAction refresh(AgentAction action) {
                    return refreshEvidence(action);
                }

                /** Checks exact owned tools and unchanged source. / 检查精确归属工具及未变源码。
                 * @param action selected action / 所选动作
                 * @return local risk / 本地风险
                 * @throws Exception when source identity cannot be checked / 无法检查源码身份时
                 */
                @Override
                public AgentRiskLevel validate(AgentAction action) throws Exception {
                    var extra = offered.get(action.id());
                    return extra != null && extra.valid().getAsBoolean() && serverUnchanged.getAsBoolean()
                            && action.taskId().equals(task) && action.target().equals(target)
                            && action.sourceRevision().equals(sourceDigest) && action.tool() == extra.type()
                            && action.evidence().equals(facts)
                            && SourceSnapshotIdentity.digest(source).equals(sourceDigest)
                            && extra.parameters().equals(action.parameters())
                                    ? risk(extra.type())
                                    : AgentRiskLevel.FORBIDDEN;
                }

                /** Executes only the captured registered callback. / 仅执行已捕获的登记回调。
                 * @param action approved action / 已批准动作
                 * @return actual evidence / 实际证据
                 * @throws Exception when execution fails / 执行失败时
                 */
                @Override
                public AgentObservation execute(AgentAction action) throws Exception {
                    var extra = offered.get(action.id());
                    selected.set(extra.type());
                    pending.remove(keys.get(action.id()));
                    var result = extra.operation().call();
                    result.facts().forEach((key, value) -> fact("observed/" + key, value));
                    return result;
                }
            });
            if (!observed.known())
                return false;
            if (selected.get().modifiesServer())
                return observed.succeeded() && Boolean.parseBoolean(observed.facts().getOrDefault("changed", "false"));
        }
        return false;
    }

    /** Runs one proposal menu through exact local and independent review. / 将一次提案菜单交给精确本地及独立审批。
     * @param type registered capability / 已登记能力
     * @param parameters nonsecret exact arguments / 非秘密精确参数
     * @param operation existing typed executor / 既有类型化执行器
     * @param <T> existing result type / 既有结果类型
     * @return actual result / 实际结果
     * @throws Exception on unavailable or unknown execution / 执行不可用或未知时
     */
    private <T> T attempt(AgentToolType type, Map<String, String> parameters, Callable<T> operation) throws Exception {
        if (source == null)
            throw new IllegalStateException("source must be frozen");
        AgentRiskLevel risk = risk(type);
        var pending = action(type, parameters, risk);
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        session.executeApproved(pending, new AssistedExecutionPort() {
            /** Binds newly observed diagnostics before the next decision. / 在下一决策前绑定新观察诊断。
             * @param action prior proposal / 原提案
             * @return refreshed exact proposal / 刷新的精确提案
             */
            @Override
            public AgentAction refresh(AgentAction action) {
                return refreshEvidence(action);
            }

            /** Rechecks exact task/source/server/tool ownership. / 重新检查精确任务、源码、服务器及工具归属。
             * @param action bound action / 绑定动作
             * @return local risk or rejection / 本地风险或拒绝
             * @throws Exception on failed source verification / 源码验证失败时
             */
            @Override
            public AgentRiskLevel validate(AgentAction action) throws Exception {
                if (!action.taskId().equals(task) || !action.target().equals(target)
                        || !action.sourceRevision().equals(sourceDigest) || !serverUnchanged.getAsBoolean()
                        || !SourceSnapshotIdentity.digest(source).equals(sourceDigest))
                    return AgentRiskLevel.FORBIDDEN;
                if (action.id().equals(pending.id()))
                    return action.parameters().equals(pending.parameters()) && action.tool() == pending.tool()
                            && action.evidence().equals(facts) ? risk : AgentRiskLevel.FORBIDDEN;
                return AgentRiskLevel.FORBIDDEN;
            }

            /** Dispatches only the already captured callback. / 仅派发已经捕获的回调。
             * @param action checked action / 已检查动作
             * @return actual observation / 实际观察
             * @throws Exception on execution failure / 执行失败时
             */
            @Override
            public AgentObservation execute(AgentAction action) throws Exception {
                T value = operation.call();
                result.set(value);
                var observed = observation(value);
                facts.put("lastOperation", type.name() + ":" + observed.facts());
                return observed;
            }
        });
        if (type != AgentToolType.DEPLOY_TRANSACTION || !observation(result.get()).succeeded())
            control.checkpoint();
        return result.get();
    }

    /** Rebinds changed evidence and model identity before independent review. / 独立审核前重新绑定变化的证据及模型身份。
     * @param action pending proposal / 待执行提案
     * @return current evidence-bound proposal / 绑定当前证据的提案
     */
    private AgentAction refreshEvidence(AgentAction action) {
        gold.debug.windowstolinux.app.service.ai.DeploymentAiScope.current()
                .ifPresent(scope -> facts.put("modelConfiguration", scope.binding()));
        if (action.evidence().equals(facts))
            return action;
        return new AgentAction(action.id(), task, target, sourceDigest, ++revision, action.tool(), action.parameters(),
                Map.copyOf(facts), action.risk());
    }

    /** Creates an exact action with a monotonically increasing plan version. / 创建计划版本单调增加的精确动作。
     * @param type capability / 能力
     * @param parameters nonsecret parameters / 非秘密参数
     * @param risk deterministic risk / 确定性风险
     * @return frozen action / 冻结动作
     */
    private AgentAction action(AgentToolType type, Map<String, String> parameters, AgentRiskLevel risk) {
        var action = new AgentAction(UUID.randomUUID().toString(), task, target, sourceDigest, ++revision, type,
                parameters, Map.copyOf(facts), risk);
        journal.accept(Map.of("type", "ACTION_PROPOSED", "action", action.id(), "detail",
                type.name() + ":" + revision + ":" + action.binding()));
        return action;
    }

    /** Classifies material server effects conservatively. / 保守分类显著服务器影响。
     * @param type registered capability / 已登记能力
     * @return deterministic severity / 确定性严重程度
     */
    private static AgentRiskLevel risk(AgentToolType type) {
        return switch (type) {
            case ANALYZE_SOURCE, VERIFY_SERVER, SERVICE_STATUS, SERVICE_LOGS, CANDIDATE_STATUS -> AgentRiskLevel.NORMAL;
            default -> AgentRiskLevel.HIGH;
        };
    }

    /** Converts actual typed results without trusting model completion claims. / 转换实际类型化结果，不信任模型完成声明。
     * @param result existing result / 既有结果
     * @return verified status evidence / 已验证状态证据
     */
    private static AgentObservation observation(Object result) {
        DeploymentStatus status = result instanceof gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome outcome
                ? outcome.status()
                : result instanceof gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult multiple
                        ? multiple.status()
                        : null;
        if (status != null)
            return new AgentObservation(status != DeploymentStatus.MANUAL_RECOVERY_REQUIRED,
                    status == DeploymentStatus.SUCCEEDED, DeploymentDiagnosticEvidence.from(result));
        if (result instanceof gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts facts)
            return new AgentObservation(true, true,
                    Map.of("os", facts.operatingSystem(), "architecture", facts.architecture(), "helperVersion",
                            Integer.toString(facts.managedHelperProtocolVersion()), "availableBytes",
                            Long.toString(facts.availableBytes())));
        return new AgentObservation(true, true, Map.of("operation", "completed"));
    }
    /** Known registered operation and its independent ownership check. / 已知登记操作及其独立归属检查。
     * @param type capability / 能力
     * @param parameters fixed parameters / 固定参数
     * @param valid ownership precondition / 归属前置条件
     * @param operation narrow callback / 窄回调
     */
    private record ExtraOperation(AgentToolType type, Map<String, String> parameters, BooleanSupplier valid,
            Callable<AgentObservation> operation) {
    }
}
