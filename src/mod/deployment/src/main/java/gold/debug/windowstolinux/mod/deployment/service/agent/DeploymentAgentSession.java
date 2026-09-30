package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.ai.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAgentProtocol;
import gold.debug.windowstolinux.mod.deployment.source.DeploymentSourceBrowser;

/** Serial evidence-driven deployment loop, independent of standard analysis. / 独立于标准分析的串行证据驱动部署循环。 */
public final class DeploymentAgentSession {
    /** Original platform and task. / 原平台与任务。 */
    private final ModContext context;

    /** Authentic original platform task context. / 真实原平台任务上下文。 */
    private final TaskContext task;

    /** Exact admitted server. / 精确已准入服务器。 */
    private final ServerRef server;

    /** Frozen nonsecret constraints and source. / 冻结的不含秘密约束与来源。 */
    private final Map<String, String> requirements;

    /** Read-only original frozen source browser. / 原冻结来源只读浏览器。 */
    private final DeploymentSourceBrowser source;

    /** Strict model, real tools, durable own state. / 严格模型、真实工具与持久专有状态。 */
    private final DeploymentAgentModels models;

    /** Original task-scoped candidate and publication operations. / 原任务范围内候选及发布操作。 */
    private final DeploymentAgentDeliveryPort tools;

    /** Dedicated original task identity and action evidence ledger. / 专有原任务身份及动作依据账本。 */
    private final DeploymentAgentJournal journal;

    /** Original cooperative safe-boundary control. / 原协作安全边界控制。 */
    private final DeploymentAgentControl control;

    /** Actual recent observations. / 最近真实观察。 */
    private final List<Map<String, String>> history = new ArrayList<>();

    /** Files actually read by the model. / 模型实际读过的文件。 */
    private final Set<String> read = new HashSet<>();

    /** Exact prepared candidate, source and output revisions. / 精确已准备候选、来源及输出修订。 */
    private final Map<String, DeploymentAgentDeliveryPort.Prepared> candidates = new LinkedHashMap<>();

    /** Verified protected source revision for each original candidate. / 每个原候选经核实的受保护源码修订。 */
    private final Map<String, String> revisions = new LinkedHashMap<>();

    /** Sealed output digests invalidated by edits or new builds. / 修改或新构建使之失效的封存输出摘要。 */
    private final Map<String, String> artifacts = new LinkedHashMap<>();

    /** Latest validated graph. / 最近已校验图。 */
    private DeploymentAgentPlan plan;

    /** Monotonic plan and decision budget. / 单调计划与决策额度。 */
    private long planRevision;

    /** Monotonic decision allowance shared by all model turns. / 所有模型轮次共用的单调决策额度。 */
    private int remaining = 80;

    /** Consecutive rejected proposals without validated progress. / 无已校验进展时连续被拒提案数。 */
    private int stalled;

    /** Binds real task capabilities without starting work. / 绑定真实任务能力而不启动工作。
     * @param context active Mod / 活动 Mod
     * @param task original task / 原任务
     * @param server exact admitted server / 精确已准入服务器
     * @param requirements bounded nonsecret user requirements / 有界且不含秘密的用户要求
     * @param source frozen read-only source / 冻结只读来源
     * @param tools real scoped delivery tools / 真实限定交付工具
     * @param journal dedicated durable ledger / 专有持久账本
     * @param approvalMode selected task review policy / 任务所选审核策略
     * @param configurationRevision exact frozen model configuration / 精确冻结模型配置
     * @param currentConfigurationRevision live task-authorized revision / 实时任务授权修订 */
    public DeploymentAgentSession(ModContext context, TaskContext task, ServerRef server,
            Map<String, String> requirements, DeploymentSourceBrowser source, DeploymentAgentDeliveryPort tools,
            DeploymentAgentJournal journal, String approvalMode, String configurationRevision,
            Supplier<String> currentConfigurationRevision) {
        this(context, task, server, requirements, source, tools, journal, approvalMode, configurationRevision,
                currentConfigurationRevision, new DeploymentAgentControl());
    }

    /** Binds explicit user control to the same original task and budgets. / 将显式用户控制绑定到同一原任务及预算。
     * @param context active Mod / 活动Mod
     * @param task original task / 原任务
     * @param server exact target / 精确目标
     * @param requirements bounded nonsecret requirements / 有界非秘密要求
     * @param source original frozen source / 原冻结来源
     * @param tools actual scoped tools / 实际限定工具
     * @param journal original evidence / 原依据
     * @param approvalMode immutable policy / 不可变策略
     * @param configurationRevision initial frozen route / 初始冻结路由
     * @param currentConfigurationRevision live route observer / 实时路由观察器
     * @param control original worker control / 原工作线程控制 */
    public DeploymentAgentSession(ModContext context, TaskContext task, ServerRef server,
            Map<String, String> requirements, DeploymentSourceBrowser source, DeploymentAgentDeliveryPort tools,
            DeploymentAgentJournal journal, String approvalMode, String configurationRevision,
            Supplier<String> currentConfigurationRevision, DeploymentAgentControl control) {
        this.context = Objects.requireNonNull(context);
        this.task = Objects.requireNonNull(task);
        this.server = Objects.requireNonNull(server);
        this.source = Objects.requireNonNull(source);
        this.tools = Objects.requireNonNull(tools);
        this.journal = Objects.requireNonNull(journal);
        this.control = Objects.requireNonNull(control);
        if (requirements.size() > 64 || requirements.toString().length() > 8000 || requirements.keySet().stream()
                .anyMatch(k -> k.matches("(?i).*(password|secret|credential|token|key).*")))
            throw new IllegalArgumentException("Agent requirements exceed nonsecret budget");
        this.requirements = Map.copyOf(requirements);
        this.models = new DeploymentAgentModels(context, task, server, approvalMode, configurationRevision,
                currentConfigurationRevision, journal, tools);
    }

    /** Runs until actual whole-application health succeeds or a bounded stop occurs. / 运行至真实整应用健康成功或有界停止。
     * @return actual publication outcome / 真实发布结果
     * @throws Exception on denied, unknown, cancelled or exhausted work / 工作被拒绝、未知、取消或耗尽时 */
    public OperationResult<DeploymentAgentDeliveryPort.Published> run() throws Exception {
        while (remaining > 0) {
            try {
                checkpoint();
                requireConfiguration();
                source.verifyBoundaries();
                journal.event(task, "DECISION", "", Integer.toString(remaining));
                DeploymentAgentProposal proposal = models.model().decide(context(), List.copyOf(history), --remaining, task);
                checkpoint();
                var result = perform(proposal);
                if (result != null)
                    return result;
            } catch (ProposalInvalidatedException changed) {
                observe("MODEL_REFRESH", models.revision());
            } catch (IllegalArgumentException rejected) {
                observe("PROPOSAL_REJECTED", rejected.getMessage());
                if (++stalled >= 2)
                    throw new IllegalStateException("Agent made no further progress", rejected);
            }
        }
        throw new IllegalStateException("Autonomous task decision budget exhausted");
    }

    /** Dispatches only one strict proposal using actual tool feedback. / 仅根据真实工具反馈调度一项严格提案。
     * @param proposal checked model proposal / 已检查模型提案
     * @return terminal publication or null to continue / 终态发布结果，继续时为空
     * @throws Exception on denied or unknown tool effect / 工具影响被拒绝或未知时 */
    private OperationResult<DeploymentAgentDeliveryPort.Published> perform(DeploymentAgentProposal proposal)
            throws Exception {
        return switch (proposal) {
            case DeploymentAgentProposal.ListSource value -> {
                observe("LIST", String.join("\n",
                        source.list(value.path().equals(".") ? "" : value.path(), value.offset(), value.limit())));
                yield null;
            }
            case DeploymentAgentProposal.ReadSource value -> {
                var lines = source.read(value.path(), value.offset(), value.limit());
                read.add(value.path());
                observe("READ", value.path() + "\n" + String.join("\n", lines));
                yield null;
            }
            case DeploymentAgentProposal.SearchSource value -> {
                observe("SEARCH", String.join("\n", source.search(value.query(), value.offset(), value.limit())));
                yield null;
            }
            case DeploymentAgentProposal.InspectServer ignored -> {
                observe("SERVER", new TreeMap<>(tools.inspect()).toString());
                yield null;
            }
            case DeploymentAgentProposal.Plan value -> {
                plan(value);
                yield null;
            }
            case DeploymentAgentProposal.Command value -> {
                command(value);
                yield null;
            }
            case DeploymentAgentProposal.ReadRemote value -> {
                var candidate = prepare(value.component());
                observe("REMOTE_READ", tools.read(task, candidate, revisions.get(value.component()), value.path(),
                        value.offset(), value.limit()));
                yield null;
            }
            case DeploymentAgentProposal.Patch value -> {
                patch(value);
                yield null;
            }
            case DeploymentAgentProposal.Seal value -> {
                seal(value.component());
                yield null;
            }
            case DeploymentAgentProposal.NeedInput value -> {
                input(value.question());
                yield null;
            }
            case DeploymentAgentProposal.Unable value -> {
                observe("HANDOFF", value.reason());
                throw new IllegalStateException("Deployment model cannot continue");
            }
            case DeploymentAgentProposal.Deliver ignored -> deliver();
        };
    }

    /** Retains only an evidence-backed plan with stable prepared backends. / 仅保留有依据且已准备后端稳定的计划。
     * @param proposal graph proposal / 图提案
     * @throws Exception if original evidence cannot be saved / 原依据无法保存时 */
    private void plan(DeploymentAgentProposal.Plan proposal) throws Exception {
        for (var component : proposal.plan().components())
            if (!read.containsAll(component.evidence()))
                throw new IllegalArgumentException("Plan cites unread source");
        if (plan != null)
            for (var candidate : candidates.keySet()) {
                var before = component(candidate);
                var after = proposal.plan().components().stream().filter(c -> c.id().equals(candidate)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Prepared component removed"));
                if (!before.runtime().getClass().equals(after.runtime().getClass()))
                    throw new IllegalArgumentException("Prepared sandbox backend changed");
            }
        plan = proposal.plan();
        planRevision++;
        journal.event(task, "PLAN_APPLICATION", "", plan.applicationId());
        for (String id : artifacts.keySet())
            journal.event(task, "ARTIFACT_INVALIDATED", id, "plan");
        artifacts.clear();
        journal.event(task, "PLAN", "", digest(plan.toString()));
        observe("PLAN", proposal.explanation() + "\n" + plan);
        stalled = 0;
    }

    /** Runs one approved sandbox command and preserves uncertain effects. / 执行一项已审核沙箱命令并保留不确定影响。
     * @param proposal exact command / 精确命令
     * @throws Exception on denied or unknown work / 工作被拒绝或未知时 */
    private void command(DeploymentAgentProposal.Command proposal) throws Exception {
        if (proposal.script().isBlank() || proposal.script().length() > 24000 || proposal.script().indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid agent command");
        var component = component(proposal.component());
        if (!artifacts.keySet().containsAll(component.dependencies()))
            throw new IllegalArgumentException("Build dependencies first");
        var candidate = prepare(component.id());
        if (artifacts.containsKey(component.id()))
            journal.event(task, "ARTIFACT_INVALIDATED", component.id(), "command");
        artifacts.remove(component.id());
        String binding = digest(component.id() + ":" + revisions.get(component.id()) + ":" + proposal.script());
        requireConfiguration();
        models.reviewer().command(component.id(), revisions.get(component.id()), proposal.script());
        checkpoint();
        requireConfiguration();
        source.verify();
        journal.event(task, "COMMAND_EXECUTING", binding, Integer.toString(proposal.seconds()));
        DeploymentAgentDeliveryPort.CommandFeedback result;
        try {
            result = tools.command(task, candidate, revisions.get(component.id()), proposal.script(),
                    Duration.ofSeconds(proposal.seconds()));
        } catch (Exception unknown) {
            journal.state(task, "UNKNOWN");
            throw unknown;
        }
        if (!result.known()) {
            journal.state(task, "UNKNOWN");
            throw new IllegalStateException("Unknown agent command must not be replayed");
        }
        journal.event(task, "COMMAND_RESULT", binding, Integer.toString(result.exitStatus()));
        observe("COMMAND_RESULT",
                "component=" + component.id() + " exit=" + result.exitStatus() + "\n" + result.evidenceOutput());
        stalled = result.succeeded() ? 0 : stalled + 1;
    }

    /** Opens a task-owned protected source candidate exactly once. / 恰好一次开启任务所属受保护来源候选。
     * @param id declared component / 已声明组件
     * @return actual candidate / 真实候选
     * @throws Exception on unavailable or uncertain preparation / 准备不可用或不确定时 */
    private DeploymentAgentDeliveryPort.Prepared prepare(String id) throws Exception {
        var present = candidates.get(id);
        if (present != null)
            return present;
        var component = component(id);
        checkpoint();
        requireConfiguration();
        String binding = digest(task.id() + ":" + id + ":" + source.revision());
        journal.event(task, "PREPARE_EXECUTING", binding, id);
        DeploymentAgentDeliveryPort.Prepared candidate;
        try {
            candidate = tools.prepare(plan.applicationId(), component);
        } catch (Exception unknown) {
            journal.state(task, "UNKNOWN");
            throw unknown;
        }
        if (!candidate.revision().matches("[0-9a-f]{64}")) {
            journal.state(task, "UNKNOWN");
            throw new IllegalStateException("Agent source revision invalid");
        }
        journal.event(task, "PREPARE_RESULT", binding, candidate.revision());
        journal.event(task, "CANDIDATE", id, candidate.workspace().candidateId());
        journal.event(task, "CURRENT_SOURCE", id, candidate.revision());
        candidates.put(id, candidate);
        revisions.put(id, candidate.revision());
        observe("WORKSPACE", id + ":" + candidate.workspace().candidateId() + ":" + candidate.revision());
        return candidate;
    }

    /** Requires a declared component. / 要求已声明组件。
     * @param id component ID / 组件标识
     * @return declared component / 已声明组件 */
    private DeploymentAgentPlan.Component component(String id) {
        if (plan == null)
            throw new IllegalArgumentException("Source-backed plan required");
        return plan.components().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Undeclared agent component"));
    }

    /** Provides current real facts, without inferred success. / 提供当前真实事实，不推断成功。
     * @return bounded model context / 有界模型上下文 */
    private Map<String, Object> context() {
        return Map.of("task", task.id().toString(), "requirements", requirements, "sourceRevision", source.revision(),
                "planRevision", planRevision, "components",
                plan == null ? List.of() : plan.components().stream().map(DeploymentAgentPlan.Component::id).toList(),
                "remoteRevisions", Map.copyOf(revisions), "sealed", Map.copyOf(artifacts), "readFiles",
                read.stream().sorted().toList());
    }

    /** Records only a digest durably while retaining bounded redacted live feedback. / 仅持久化摘要，保留有界脱敏实时反馈。
     * @param tool actual tool / 真实工具
     * @param output actual nonsecret observation / 真实不含秘密观察
     * @throws Exception on failed durable evidence / 持久依据失败时 */
    private void observe(String tool, String output) throws Exception {
        String safe = redact(Objects.requireNonNull(output));
        if (safe.length() > 12000)
            safe = safe.substring(0, 12000);
        history.add(Map.of("tool", tool, "output", safe));
        while (history.size() > 12 || history.stream().mapToInt(item -> item.get("output").length()).sum() > 42000)
            history.removeFirst();
        journal.event(task, "AGENT_OBSERVATION", tool, digest(safe));
        task.progress(tool + ": " + safe.substring(0, Math.min(safe.length(), 160)), Optional.empty());
    }

    /** Waits only at an original settled boundary and discards a proposal after explicit refresh. / 仅在原已结清边界等待，并在明确刷新后丢弃提案。
     * @throws Exception if the original boundary cannot safely continue / 原边界不能安全继续时 */
    private void checkpoint() throws Exception {
        if (control.checkpoint(task, models)) throw new ProposalInvalidatedException(task.id());
    }

    /** Explicit replacement invalidates a decision without resetting its consumed budget. / 明确替换使决策失效且不重置其已消费预算。 */
    private static final class ProposalInvalidatedException extends ApiException {
        /** Creates one original-task-bound invalidation. / 创建一次原任务绑定失效。
         * @param originalTask authentic original identity / 真实原身份 */
        private ProposalInvalidatedException(UUID originalTask) {
            super(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_AGENT_PROPOSAL_INVALIDATED",
                    "Explicit model replacement invalidated the old proposal", Map.of("task", originalTask.toString()),
                    Optional.of(originalTask)));
        }
    }

    /** Redacts likely credentials in provider and tool observations. / 脱敏提供方与工具观察中的疑似凭据。
     * @param value raw bounded observation / 原有界观察
     * @return safe observation / 安全观察 */
    private static String redact(String value) {
        return value.replaceAll("(?im)^.*(?:password|passwd|secret|token|api[_-]?key|private[_-]?key).*[:=].*$",
                "[credential content excluded]");
    }

    /** Hashes a nonsecret action binding. / 散列不含秘密的动作绑定。
     * @param value binding text / 绑定文本
     * @return lowercase SHA-256 / 小写 SHA-256 */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Applies an approved source edit and invalidates build evidence. / 应用经批准源码修改并使构建依据失效。
     * @param proposal source patch / 源码补丁
     * @throws Exception when edit is denied or unknown / 修改被拒绝或未知时 */
    private void patch(DeploymentAgentProposal.Patch proposal) throws Exception {
        var candidate = prepare(proposal.component());
        String old = revisions.get(proposal.component());
        DeploymentAgentPatch patch = proposal.patch();
        if (!old.equals(patch.sourceRevision()))
            throw new IllegalArgumentException("Stale or invalid source patch");
        String path = patch.path().toLowerCase(Locale.ROOT);
        for (String part : path.split("/"))
            if (Set.of(".git", ".ssh", ".env", ".npmrc", ".pypirc", ".w2l").contains(part) || part.startsWith(".env.")
                    || part.endsWith(".pem") || part.endsWith(".key"))
                throw new SecurityException("Protected source path");
        String binding = digest(old + ":" + patch);
        models.reviewer().patch(patch);
        requireConfiguration();
        var intent = new ActionIntent("deployment.agent.patch", List.of(server.id()), patch.path(),
                models.revision(), "source=" + old + "\nbefore=" + patch.beforeDigest() + "\npatch=" + patch,
                "Apply the exact reviewed diff only to the task-owned source candidate");
        var approval = context.interaction().approve(intent, task);
        if (approval.outcome() != Outcome.SUCCEEDED)
            throw new gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException(
                    approval.outcome(), approval.problem());
        checkpoint();
        requireConfiguration();
        journal.event(task, "PATCH_EXECUTING", binding, old + ":" + patch.beforeDigest());
        String revised;
        try {
            revised = tools.patch(task, candidate, old, patch);
        } catch (Exception uncertain) {
            journal.state(task, "UNKNOWN");
            throw uncertain;
        }
        if (!revised.matches("[0-9a-f]{64}") || revised.equals(old)) {
            journal.state(task, "UNKNOWN");
            throw new IllegalStateException("Patch revision was not confirmed");
        }
        journal.event(task, "PATCH_RESULT", binding, revised);
        journal.event(task, "CURRENT_SOURCE", proposal.component(), revised);
        revisions.put(proposal.component(), revised);
        for (String id : artifacts.keySet())
            journal.event(task, "ARTIFACT_INVALIDATED", id, "patch");
        artifacts.clear();
        planRevision++;
        observe("SOURCE_REVISION", proposal.component() + ":" + old + "->" + revised);
        source.verifyBoundaries();
    }

    /** Seals actual output for one current revision. / 为一个当前修订封存真实输出。
     * @param id component / 组件
     * @throws Exception on missing or invalid output / 输出缺失或无效时 */
    private void seal(String id) throws Exception {
        var candidate = prepare(id);
        String revision = revisions.get(id);
        checkpoint();
        requireConfiguration();
        String binding = digest(id + ":" + revision + ":seal");
        journal.event(task, "SEAL_EXECUTING", binding, revision);
        String digest;
        try {
            digest = tools.seal(task, candidate, revision);
        } catch (Exception uncertain) {
            journal.state(task, "UNKNOWN");
            throw uncertain;
        }
        if (!digest.matches("[0-9a-f]{64}")) {
            journal.state(task, "UNKNOWN");
            throw new IllegalStateException("Sealed artifact identity missing");
        }
        artifacts.put(id, digest);
        journal.event(task, "SEAL_RESULT", binding, digest);
        journal.event(task, "SEALED_ARTIFACT", id, digest);
        observe("SEALED", id + ":" + digest);
        stalled = 0;
    }

    /** Obtains explicit user input. / 获取明确用户输入。
     * @param question bounded question / 有界问题
     * @throws Exception if user input is unavailable / 用户输入不可用时 */
    private void input(String question) throws Exception {
        if (question.isBlank() || question.length() > 2000)
            throw new IllegalArgumentException("Invalid agent question");
        var answer = context.interaction().requestText("Autonomous deployment input", question, false, task);
        if (answer.outcome() != Outcome.SUCCEEDED)
            throw new gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException(
                    answer.outcome(), answer.problem());
        observe("USER_INPUT", answer.value().orElseThrow());
    }

    /** Publishes only after full artifact and health evidence. / 仅在完整制品及健康依据后发布。
     * @return actual result or continuation / 真实结果或继续
     * @throws Exception on uncertain delivery / 交付不确定时 */
    private OperationResult<DeploymentAgentDeliveryPort.Published> deliver() throws Exception {
        if (plan == null || artifacts.size() != plan.components().size())
            throw new IllegalArgumentException("Every declared component needs sealed output");
        checkpoint();
        requireConfiguration();
        source.verify();
        String binding = digest(plan + ":" + new TreeMap<>(artifacts));
        journal.event(task, "DELIVERY_EXECUTING", binding, Long.toString(planRevision));
        OperationResult<DeploymentAgentDeliveryPort.Published> result;
        try {
            result = tools.deliver(task, plan, Map.copyOf(candidates), Map.copyOf(revisions), Map.copyOf(artifacts));
        } catch (Exception uncertain) {
            journal.state(task, "UNKNOWN");
            throw uncertain;
        }
        if (result.outcome() == Outcome.UNKNOWN) {
            journal.state(task, "UNKNOWN");
            return result;
        }
        journal.event(task, "DELIVERY_RESULT", binding, result.outcome().name());
        if (result.outcome() == Outcome.SUCCEEDED) {
            var published = result.value().orElseThrow();
            if (!published.healthy().equals(artifacts.keySet())) {
                journal.state(task, "UNKNOWN");
                throw new IllegalStateException("Whole application health evidence missing");
            }
            source.verify();
            journal.state(task, "SUCCEEDED");
            return result;
        }
        observe("DELIVERY", result.problem().map(Problem::eventCode).orElse(result.outcome().name()));
        journal.state(task, result.outcome() == Outcome.CANCELLED ? "CANCELLED" : "FAILED");
        return result;
    }

    /** Invalidates approvals when any purpose-specific model binding changed. / 任一专用模型绑定变化时使审批失效。
     * @throws SecurityException when the frozen revision changed / 冻结修订改变时 */
    private void requireConfiguration() {
        models.verify();
    }
}
