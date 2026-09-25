package gold.debug.windowstolinux.shared.agent.execution;

import java.time.Duration;
import java.util.*;
import java.util.function.*;

import gold.debug.windowstolinux.shared.agent.approval.SourcePatchApproval;
import gold.debug.windowstolinux.shared.agent.execution.protocol.*;
import gold.debug.windowstolinux.shared.agent.tool.AgentDeliveryPort;
import gold.debug.windowstolinux.shared.deploy.delivery.ManagedDelivery;
import gold.debug.windowstolinux.shared.deploy.task.AgentTaskControl;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.source.browse.SourceBrowser;

/** Serial evidence-driven deployment loop with no standard-analysis dependency. / 不依赖标准分析的串行证据驱动部署循环。 */
public final class AutonomousDeploymentSession {
    /** Exact task identity. / 精确任务身份。 */
    private final String task;

    /** Bounded user-supplied nonsecret deployment constraints. / 用户提供的有界非秘密部署约束。 */
    private final Map<String, String> requirements;

    /** Immutable local source browser. / 不可变本地源码浏览器。 */
    private final SourceBrowser source;

    /** Frozen purpose model adapter. / 冻结用途模型适配器。 */
    private final AutonomousModelPort model;

    /** Scoped remote and publication tools. / 限定作用域远端及发布工具。 */
    private final AgentDeliveryPort tools;

    /** Dedicated patch authorization. / 专用补丁授权。 */
    private final SourcePatchApproval patches;

    /** Task lifecycle control. / 任务生命周期控制。 */
    private final AgentTaskControl control;

    /** Missing input callback. / 缺失输入回调。 */
    private final Function<String, Optional<String>> input;

    /** Durable digest journal. / 持久化摘要日志。 */
    private final Consumer<Map<String, String>> journal;

    /** Live nonsecret plan and tool observation sink. / 实时非秘密方案及工具观察端口。 */
    private final Consumer<Map<String, String>> progress;

    /** Bounded actual model observations. / 有界实际模型观察。 */
    private final List<Map<String, String>> history = new ArrayList<>();

    /** Paths actually read, required by plan evidence. / 实际读取路径，作为方案证据要求。 */
    private final Set<String> read = new HashSet<>();

    /** Task-owned candidates. / 任务所属候选。 */
    private final Map<String, AgentDeliveryPort.Prepared> candidates = new LinkedHashMap<>();

    /** Actual source revisions. / 实际源码修订。 */
    private final Map<String, String> revisions = new LinkedHashMap<>();

    /** Successfully sealed output revisions. / 成功封存输出修订。 */
    private final Map<String, String> artifacts = new LinkedHashMap<>();

    /** Latest validated graph. / 最新已校验图。 */
    private ManagedDelivery delivery;

    /** Monotonic plan revision. / 单调方案修订。 */
    private long planRevision;

    /** Global decision budget, unaffected by handoff. / 不受接替影响的全局决策预算。 */
    private int remaining = 80;

    /** Repeated missing progress count. / 重复无进展计数。 */
    private int stalled;

    /** Binds the autonomous task without reading or deploying implicitly. / 绑定自主任务，不隐式读取或部署。
     * @param task task identity / 任务身份
     * @param requirements explicit nonsecret task constraints / 显式非秘密任务约束
     * @param source source browser / 源码浏览器
     * @param model model decision port / 模型决策端口
     * @param tools remote tools / 远端工具
     * @param patches patch authorization / 补丁授权
     * @param control task control / 任务控制
     * @param input user input port / 用户输入端口
     * @param journal durable journal / 持久化日志
     * @param progress live observations / 实时观察
     */
    public AutonomousDeploymentSession(String task, Map<String, String> requirements, SourceBrowser source,
            AutonomousModelPort model, AgentDeliveryPort tools, SourcePatchApproval patches, AgentTaskControl control,
            Function<String, Optional<String>> input, Consumer<Map<String, String>> journal,
            Consumer<Map<String, String>> progress) {
        this.task = Objects.requireNonNull(task);
        this.source = Objects.requireNonNull(source);
        this.model = Objects.requireNonNull(model);
        var safe = new TreeMap<String, String>();
        requirements.forEach((key, value) -> {
            if (!key.matches("(?i).*(password|secret|credential|token|api.?key).*"))
                safe.put(key, gold.debug.windowstolinux.shared.ai.redaction.AgentEvidenceText.redact(value));
        });
        if (safe.size() > 64 || safe.toString().length() > 8000)
            throw new IllegalArgumentException("task constraints exceed context budget");
        this.requirements = Map.copyOf(safe);
        this.tools = Objects.requireNonNull(tools);
        this.patches = Objects.requireNonNull(patches);
        this.control = Objects.requireNonNull(control);
        this.input = Objects.requireNonNull(input);
        this.journal = Objects.requireNonNull(journal);
        this.progress = Objects.requireNonNull(progress);
    }

    /** Runs until actual managed health succeeds or a bounded failure stops the task. / 运行直至实际受管健康成功或有界失败停止任务。
     * @return verified publication result / 已验证发布结果
     * @throws Exception when execution is denied, unknown or exhausted / 执行被拒绝、未知或预算耗尽时
     */
    public AgentDeliveryPort.Result run() throws Exception {
        while (remaining > 0) {
            control.checkpoint();
            source.verify();
            AgentProposal proposal;
            try {
                proposal = model.decide(context(), List.copyOf(history), --remaining);
            } catch (Exception failure) {
                if (failure instanceof java.util.concurrent.CancellationException
                        || Thread.currentThread().isInterrupted())
                    throw failure;
                observe("MODEL_UNAVAILABLE", failure.getClass().getSimpleName());
                if (!model.advance())
                    throw new IllegalStateException("deployment models unavailable", failure);
                continue;
            }
            control.checkpoint();
            try {
                switch (proposal) {
                    case AgentProposal.ListSource p ->
                        observe("LIST", String.join("\n", source.list(p.path(), p.offset(), p.limit())));
                    case AgentProposal.ReadSource p -> {
                        var lines = source.read(p.path(), p.offset(), p.limit());
                        read.add(p.path());
                        observe("READ", p.path() + "\n" + String.join("\n", lines));
                    }
                    case AgentProposal.SearchSource p ->
                        observe("SEARCH", String.join("\n", source.search(p.query(), p.offset(), p.limit())));
                    case AgentProposal.InspectServer ignored ->
                        observe("SERVER", new TreeMap<>(tools.inspect()).toString());
                    case AgentProposal.Plan p -> plan(p);
                    case AgentProposal.Command p -> command(p);
                    case AgentProposal.ReadRemote p -> {
                        var candidate = prepare(p.component());
                        observe("REMOTE_READ", tools.projects().read(task, candidate.workspace(),
                                revisions.get(p.component()), p.path(), p.offset(), p.limit()));
                    }
                    case AgentProposal.Patch p -> patch(p);
                    case AgentProposal.Seal p -> seal(p.component());
                    case AgentProposal.NeedInput p -> {
                        if (p.question() == null || p.question().isBlank() || p.question().length() > 2000)
                            throw new IllegalArgumentException("invalid input question");
                        String answer = input.apply(p.question())
                                .orElseThrow(java.util.concurrent.CancellationException::new);
                        observe("USER_INPUT",
                                gold.debug.windowstolinux.shared.ai.redaction.AgentEvidenceText.redact(answer));
                    }
                    case AgentProposal.Unable p -> {
                        observe("HANDOFF", p.reason());
                        if (!model.advance())
                            throw new IllegalStateException("deployment models exhausted");
                    }
                    case AgentProposal.Deliver ignored -> {
                        var result = deliver();
                        if (result.isPresent())
                            return result.orElseThrow();
                    }
                }
            } catch (IllegalArgumentException invalid) {
                observe("PROPOSAL_REJECTED", invalid.getMessage());
                if (++stalled >= 2)
                    handoff();
            }
        }
        throw new IllegalStateException("autonomous task decision budget exhausted");
    }

    /** Submits sealed outputs and distinguishes verified delivery from recoverable failure. / 提交封存输出，区分已验证交付与可恢复失败。
     * @return terminal outcome, or empty to continue diagnosis / 终态结果，继续诊断时为空
     * @throws Exception when publication is unavailable or its evidence is invalid / 发布不可用或证据无效时
     */
    private Optional<AgentDeliveryPort.Result> deliver() throws Exception {
        if (delivery == null || artifacts.size() != delivery.components().size())
            throw new IllegalArgumentException("all declared components require sealed output");
        var result = tools.deliver(delivery, Map.copyOf(candidates), Map.copyOf(revisions), Map.copyOf(artifacts));
        observe("DELIVERY", result.status() + ":" + result.evidence());
        if (result.status() == DeploymentStatus.MANUAL_RECOVERY_REQUIRED) {
            control.finish(AgentTaskState.UNKNOWN);
            return Optional.of(result);
        }
        if (result.status() == DeploymentStatus.SUCCEEDED) {
            if (!result.healthy().equals(artifacts.keySet())) {
                control.finish(AgentTaskState.UNKNOWN);
                throw new IllegalStateException("whole application health evidence missing");
            }
            source.verify();
            control.finish(AgentTaskState.SUCCEEDED);
            return Optional.of(result);
        }
        artifacts.clear();
        if (++stalled >= 2)
            handoff();
        return Optional.empty();
    }

    /** Publishes a source-backed plan without assuming builds succeeded. / 发布源码支持的方案，不假定构建成功。
     * @param proposal proposed graph / 提议图
     */
    private void plan(AgentProposal.Plan proposal) {
        Objects.requireNonNull(proposal.delivery());
        for (String id : candidates.keySet()) {
            var replacement = proposal.delivery().components().stream().filter(c -> c.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("prepared component cannot be removed"));
            if (!replacement.runtime().getClass().equals(component(id).runtime().getClass()) || replacement
                    .runtime() instanceof gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification.Container next
                    && next.engine() != ((gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification.Container) component(
                            id).runtime()).engine())
                throw new IllegalArgumentException("prepared sandbox backend cannot change");
        }
        for (var component : proposal.delivery().components())
            if (!read.containsAll(component.evidence()))
                throw new IllegalArgumentException("plan must cite files actually read by the model");
        delivery = proposal.delivery();
        planRevision++;
        artifacts.clear();
        observe("PLAN", proposal.explanation() + "\n" + delivery);
        stalled = 0;
    }

    /** Runs a bounded script and exposes its actual failure output to the next decision. / 运行有界脚本并向下一决策提供实际失败输出。
     * @param proposal exact command / 精确命令
     * @throws Exception on denied or unknown execution / 执行被拒绝或未知时
     */
    private void command(AgentProposal.Command proposal) throws Exception {
        if (proposal.script() == null || proposal.script().isBlank() || proposal.script().length() > 24000
                || proposal.script().indexOf('\0') >= 0 || proposal.seconds() < 1 || proposal.seconds() > 7200)
            throw new IllegalArgumentException("invalid command budget");
        var component = component(proposal.component());
        if (!artifacts.keySet().containsAll(component.dependencies()))
            throw new IllegalArgumentException("build dependencies first");
        var candidate = prepare(component.id());
        artifacts.remove(component.id());
        var result = tools.projects().command(task, candidate.workspace(), revisions.get(component.id()),
                proposal.script(), Duration.ofSeconds(proposal.seconds()), 1048576);
        observe("COMMAND_RESULT", "component=" + component.id() + " exit=" + result.exitStatus() + " timeout="
                + result.timedOut() + "\n" + result.evidenceOutput() + "\n" + result.error());
        if (!result.known()) {
            control.finish(AgentTaskState.UNKNOWN);
            throw new IllegalStateException("unknown command result must not be replayed");
        }
        if (result.succeeded())
            stalled = 0;
        else if (++stalled >= 2)
            handoff();
    }

    /** Applies a dedicated patch and invalidates all affected build evidence. / 应用专用补丁并使全部受影响构建证据失效。
     * @param proposal exact source diff / 精确源码差异
     * @throws Exception on rejected, conflicting or unknown patch / 补丁被拒绝、冲突或未知时
     */
    private void patch(AgentProposal.Patch proposal) throws Exception {
        var candidate = prepare(proposal.component());
        String old = revisions.get(proposal.component());
        String approval = patches.approve(proposal.patch(), old);
        journal.accept(Map.of("type", "PATCH_EXECUTING", "action", proposal.patch().binding(), "detail",
                old + ":" + proposal.patch().beforeDigest()));
        String revised;
        try {
            revised = tools.projects().patch(task, candidate.workspace(), proposal.patch(), approval);
        } catch (IllegalArgumentException rejected) {
            journal.accept(Map.of("type", "PATCH_CANCELLED", "action", proposal.patch().binding(), "detail",
                    "known conflict or rejected patch"));
            throw rejected;
        } catch (Exception unknown) {
            control.finish(AgentTaskState.UNKNOWN);
            throw unknown;
        }
        journal.accept(
                Map.of("type", "PATCH_RESULT", "action", proposal.patch().binding(), "detail", old + ":" + revised));
        if (revised == null || !revised.matches("[0-9a-f]{64}") || revised.equals(old)) {
            control.finish(AgentTaskState.UNKNOWN);
            throw new IllegalStateException("source patch did not return a new revision");
        }
        revisions.put(proposal.component(), revised);
        artifacts.clear();
        planRevision++;
        observe("SOURCE_REVISION", proposal.component() + ":" + old + "->" + revised);
        source.verify();
    }

    /** Requires remote output sealing before publication. / 发布前要求远端输出封存。
     * @param id component identity / 组件身份
     * @throws Exception when output cannot be verified / 无法验证输出时
     */
    private void seal(String id) throws Exception {
        var candidate = prepare(id);
        String digest = tools.projects().seal(task, candidate.workspace(), revisions.get(id));
        if (digest == null || !digest.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("invalid sealed artifact identity");
        artifacts.put(id, digest);
        observe("SEALED", id + ":" + digest);
        stalled = 0;
    }

    /** Creates a candidate once, retaining actual source identity. / 仅创建一次候选并保留实际源码身份。
     * @param id declared component / 已声明组件
     * @return prepared candidate / 已准备候选
     * @throws Exception on unavailable preparation / 准备不可用时
     */
    private AgentDeliveryPort.Prepared prepare(String id) throws Exception {
        var value = candidates.get(id);
        if (value != null)
            return value;
        value = tools.prepare(component(id));
        if (value.revision() == null || !value.revision().matches("[0-9a-f]{64}"))
            throw new IllegalStateException("invalid remote source revision");
        candidates.put(id, value);
        revisions.put(id, value.revision());
        observe("WORKSPACE", "component=" + id + " application=" + value.workspace().applicationId() + " candidate="
                + value.workspace().candidateId() + " revision=" + value.revision());
        return value;
    }

    /** Resolves only an explicitly declared component. / 仅解析显式声明的组件。
     * @param id component identity / 组件身份
     * @return declared component / 已声明组件
     */
    private ManagedDelivery.Component component(String id) {
        if (delivery == null)
            throw new IllegalArgumentException("a source-backed plan is required before execution");
        return delivery.components().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("undeclared component"));
    }

    /** Exposes bounded live state without deployment transcripts to the reviewer. / 提供有界实时状态，不向审批者传递部署推理。
     * @return decision context / 决策上下文
     */
    private Map<String, Object> context() {
        return Map.of("task", task, "requirements", requirements, "sourceRevision", source.revision(), "planRevision",
                planRevision, "components",
                delivery == null
                        ? List.of()
                        : delivery.components().stream().map(ManagedDelivery.Component::id).toList(),
                "remoteRevisions", Map.copyOf(revisions), "sealed", Map.copyOf(artifacts), "readFiles",
                read.stream().sorted().toList());
    }

    /** Exposes sanitized transient observations while journaling digests only. / 提供脱敏临时观察，仅持久化摘要。
     * @param tool actual tool name / 实际工具名称
     * @param output actual observed output / 实际观察输出
     */
    private void observe(String tool, String output) {
        String safe = gold.debug.windowstolinux.shared.ai.redaction.AgentEvidenceText
                .redact(Objects.requireNonNull(output));
        if (safe.length() > 12000)
            safe = safe.substring(0, 12000);
        var item = Map.of("tool", tool, "output", safe);
        history.add(item);
        while (history.size() > 12 || history.stream().mapToInt(v -> v.get("output").length()).sum() > 42000)
            history.removeFirst();
        journal.accept(Map.of("type", "AGENT_OBSERVATION", "action", tool, "detail", AgentAction.digest(safe)));
        progress.accept(item);
    }

    /** Preserves global budgets when handing off a stalled deployment model. / 接替停滞部署模型时保留全局预算。 */
    private void handoff() {
        stalled = 0;
        observe("HANDOFF", "two decisions without progress");
        if (!model.advance())
            throw new IllegalStateException("deployment models exhausted");
    }

    /** Binds pending commands to current source and plan revisions. / 将待执行命令绑定到当前源码及方案修订。
     * @return exact evidence revision / 精确证据修订
     */
    public String revision() {
        return AgentAction.digest(source.revision() + ":" + planRevision + ":" + new TreeMap<>(revisions));
    }
}
