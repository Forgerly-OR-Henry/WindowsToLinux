package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.util.*;
import java.util.function.Supplier;
import gold.debug.windowstolinux.appapi.ai.AiPurpose;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAgentProtocol;

/** Keeps deployment, approval and dispatch bound to one explicitly adopted model revision. / 将部署、审批及发送绑定到一份明确采用的模型修订。 */
final class DeploymentAgentModels implements DeploymentAgentControl.Boundary {
    /** Original task capabilities. / 原任务能力。 */ private final ModContext context;
    /** Authentic original worker. / 真实原工作线程。 */ private final TaskContext task;
    /** Original selected target. / 原所选目标。 */ private final ServerRef server;
    /** Frozen human approval policy. / 冻结人工审批策略。 */ private final String mode;
    /** Original durable action evidence. / 原持久动作依据。 */ private final DeploymentAgentJournal journal;
    /** Actual task-bound tools. / 实际任务绑定工具。 */ private final DeploymentAgentDeliveryPort tools;
    /** Live original route observer. / 实时原路由观察器。 */ private final Supplier<String> current;
    /** Explicitly adopted route. / 明确采用的路由。 */ private String revision;
    /** Model using the adopted route. / 使用已采用路由的模型。 */ private DeploymentAgentProtocol model;
    /** Fresh exact approvals using the adopted route. / 使用已采用路由的全新精确审批。 */ private DeploymentAgentReview reviewer;

    /** Binds the original worker and immutable approval policy. / 绑定原工作线程及不可变审批策略。
     * @param context actual platform / 实际平台
     * @param task original task / 原任务
     * @param server exact target / 精确目标
     * @param mode frozen approval mode / 冻结审批模式
     * @param revision initial route / 初始路由
     * @param current live route observer / 实时路由观察器
     * @param journal original evidence / 原依据
     * @param tools actual scoped tools / 实际限定工具 */
    DeploymentAgentModels(ModContext context, TaskContext task, ServerRef server, String mode, String revision,
            Supplier<String> current, DeploymentAgentJournal journal, DeploymentAgentDeliveryPort tools) {
        this.context = Objects.requireNonNull(context); this.task = Objects.requireNonNull(task);
        this.server = Objects.requireNonNull(server); this.mode = Objects.requireNonNull(mode);
        this.current = Objects.requireNonNull(current); this.journal = Objects.requireNonNull(journal);
        this.tools = Objects.requireNonNull(tools); replace(Objects.requireNonNull(revision));
    }

    /** Returns the current decision model. / 返回当前决策模型。
     * @return exactly bound model / 精确绑定模型 */
    DeploymentAgentProtocol model() { return model; }
    /** Returns fresh action review for the adopted route. / 返回已采用路由的全新动作审核。
     * @return exactly bound reviewer / 精确绑定审核者 */
    DeploymentAgentReview reviewer() { return reviewer; }
    /** Returns the explicitly adopted identity. / 返回明确采用的身份。
     * @return original or explicitly replaced revision / 原或明确替换的修订 */
    String revision() { return revision; }
    /** Rejects silent configuration adoption before any action. / 任一动作前拒绝默默采用配置。 */
    void verify() { if (!revision.equals(current.get())) throw new SecurityException("Agent configuration changed"); }

    /** Records a paused boundary only after every original action is settled. / 仅全部原动作已结清后记录暂停边界。
     * @throws Exception if any action or transfer remains uncertain / 任一动作或传输仍不明时 */
    @Override public void paused() throws Exception {
        settled();
        journal.state(task, "WAITING");
        journal.event(task, "PAUSED", "", revision);
        task.progress("Agent paused at its original safe boundary", Optional.empty());
    }

    /** Adopts models only on this owning paused worker and invalidates every prior approval. / 仅在此所属暂停工作线程上采用模型，并使全部先前审批失效。
     * @return whether the old proposal is invalidated / 旧提案是否失效
     * @throws Exception if original evidence or exact route cannot be confirmed / 原依据或精确路由无法确认时 */
    @Override public boolean refresh() throws Exception {
        settled();
        final String selected;
        try {
            selected = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
            if (!mode.equals("MANUAL_REVIEW") && !selected.equals(
                    context.ai().configurationRevision(AiPurpose.APPROVAL, task)))
                throw new IllegalArgumentException("Agent purposes differ from the selected route");
        } catch (ApiException | IllegalArgumentException rejected) {
            if (rejected instanceof ApiException api && api.problem().code() == ProblemCode.UNKNOWN) throw api;
            journal.event(task, "MODEL_REFRESH_REJECTED", "", revision);
            task.progress("Model refresh rejected; original agent remains paused", Optional.empty());
            throw new DeploymentAgentControl.RefreshRejectedException(rejected instanceof ApiException api ? api.problem()
                    : new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_AGENT_MODEL_REFRESH_REJECTED",
                            "Selected model purposes differ from the original paused route", Map.of("task", task.id().toString()),
                            Optional.of(task.id())), rejected);
        }
        String previous = revision;
        journal.replaceConfiguration(task, previous, selected);
        tools.replaceConfiguration(selected);
        replace(selected);
        return true;
    }

    /** Resumes the same worker without resetting plan or decision budgets. / 继续同一工作线程，不重置计划或决策预算。
     * @throws Exception if the original binding or evidence changed / 原绑定或依据变化时 */
    @Override public void resumed() throws Exception {
        verify(); journal.event(task, "RESUMED", "", revision); journal.state(task, "RUNNING");
    }

    /** Requires no unacknowledged effects or pending transfer before replacing authority. / 替换权限前要求无未确认副作用或待结传输。
     * @throws Exception if the original boundary is unsettled / 原边界未结清时 */
    private void settled() throws Exception {
        if (!DeploymentAgentJournal.effectsSettled(journal.events(task))
                || !context.linux().pendingTransfers(task).isEmpty())
            throw new IllegalStateException("Unsettled original effects forbid pausing or model refresh");
    }

    /** Creates fresh deployment and review adapters while keeping the original policy. / 创建全新部署及审核适配器，同时保留原策略。
     * @param selected explicitly adopted route / 明确采用的路由 */
    private void replace(String selected) {
        revision = selected; model = new DeploymentAgentProtocol(context, selected);
        reviewer = new DeploymentAgentReview(context, server, task, mode, selected, current, journal);
    }
}
