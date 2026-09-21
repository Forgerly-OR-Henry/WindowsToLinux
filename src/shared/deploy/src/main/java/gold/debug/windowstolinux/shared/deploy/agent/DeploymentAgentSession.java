package gold.debug.windowstolinux.shared.deploy.agent;

import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.AgentApprovalMode;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** One serial deployment agent with an independent reviewer and durable execution boundary. / 具有独立审批者和持久化执行边界的单部署 Agent。 */
public final class DeploymentAgentSession {
    /** Exact task identity. / 精确任务身份。 */
    private final String taskId;
    /** Frozen server/account identity. / 冻结服务器及账户身份。 */
    private final String target;
    /** Fixed user goal. / 固定用户目标。 */
    private final String goal;
    /** Frozen human policy. / 冻结人工策略。 */
    private final AgentApprovalMode mode;
    /** Separate purpose-specific model contexts. / 独立用途模型上下文。 */
    private final AgentModelPort models;
    /** Task boundary controller. / 任务边界控制器。 */
    private final AgentTaskControl control;
    /** Per-action confirmation; no batch approval. / 逐动作确认，不批量批准。 */
    private final BiPredicate<AgentAction,AgentReview> human;
    /** Synchronous durable event sink; failure blocks execution. / 同步持久化事件端口，失败阻断执行。 */
    private final Consumer<Map<String,String>> journal;
    /** Immutable prior operation IDs, including unknown outcomes. / 历史操作标识，包括未知结果。 */
    private final Set<String> executed=new HashSet<>();
    /** Bounded nonsecret handoff history. / 有界非秘密交接历史。 */
    private final List<String> history=new ArrayList<>();
    /** Shared budget, not reset by model handoff. / 不随模型接替重置的共享预算。 */
    private int remaining=30;
    /** Consecutive non-progress decisions. / 连续无进展决策数。 */
    private int noProgress;
    /** Denied exact bindings cannot be retried against a different reviewer. / 被拒绝的精确绑定不能换审批者重试。 */
    private final Set<String> denied=new HashSet<>();
    /** Evidence requests retain a task-wide per-action budget. / 补证据请求保留任务内逐动作预算。 */
    private final Map<String,Integer> evidenceRounds=new HashMap<>();
    /** Actions awaiting a new observation before another review. / 等待新观察后才能再次审批的动作。 */
    private final Map<String,String> awaitingEvidence=new HashMap<>();
    /** Last verifiable facts for each semantic diagnostic operation. / 每个语义诊断操作上次可验证事实。 */
    private final Map<String,String> observedFacts=new HashMap<>();
    /** Binds ports without invoking a model or executor. / 绑定端口，不调用模型或执行器。
     * @param taskId task identity / 任务身份
     * @param target server identity / 服务器身份
     * @param goal authorized goal / 已授权目标
     * @param mode human policy / 人工策略
     * @param models isolated model ports / 独立模型端口
     * @param control safe boundary control / 安全边界控制
     * @param human one-action confirmation / 单动作确认
     * @param journal durable event sink / 持久化事件端口
     */
    public DeploymentAgentSession(String taskId,String target,String goal,AgentApprovalMode mode,AgentModelPort models,
            AgentTaskControl control,BiPredicate<AgentAction,AgentReview> human,Consumer<Map<String,String>> journal){
        this.taskId=Objects.requireNonNull(taskId);this.target=Objects.requireNonNull(target);this.goal=Objects.requireNonNull(goal);
        this.mode=Objects.requireNonNull(mode);this.models=Objects.requireNonNull(models);this.control=Objects.requireNonNull(control);
        this.human=Objects.requireNonNull(human);this.journal=Objects.requireNonNull(journal);
    }
    /** Runs decisions until the required transaction finishes or the user cancels. / 持续决策直至所需事务结束或用户取消。
     * @param actions current allowed menu / 当前允许动作菜单
     * @param required required transaction identifier / 所需事务标识
     * @param executor narrow validation and execution port / 窄验证及执行端口
     * @return actual required action result / 所需动作实际结果
     * @throws Exception when execution failed or cannot be safely established / 执行失败或无法确认安全时
     */
    public AgentObservation run(List<AgentAction> actions,String required,AgentExecutionPort executor)throws Exception{
        var menu=new LinkedHashMap<String,AgentAction>();
        for(var action:actions){
            if(!action.taskId().equals(taskId)||!action.target().equals(target)||menu.put(action.id(),action)!=null)throw new SecurityException("invalid action scope");
        }
        if(!menu.containsKey(required))throw new IllegalArgumentException("missing required action");
        while(true){
            control.checkpoint();
            if(remaining<=0){control.awaitUser("decision-budget-exhausted");continue;}
            menu.replaceAll((id,action)->executor.refresh(action));
            var selectable=menu.values().stream().filter(a->!a.binding().equals(awaitingEvidence.get(a.id()))).toList();
            if(selectable.isEmpty()){control.awaitUser("approval-needs-evidence");continue;}
            AgentDecision decision;
            try{decision=models.decide(goal,selectable,List.copyOf(history),--remaining);}
            catch(Exception failure){if(Thread.currentThread().isInterrupted()||failure instanceof java.util.concurrent.CancellationException)throw failure;control.awaitUser("deployment-models-unavailable");continue;}
            control.checkpoint();
            if(decision.decision()==AgentDecisionType.NEED_INPUT){event("INPUT_REQUIRED","",decision.reason());control.awaitUser("input-required");continue;}
            if(decision.decision()==AgentDecisionType.UNABLE){handoff("model-unable");continue;}
            AgentAction action=menu.get(decision.actionId());
            if(decision.decision()!=AgentDecisionType.EXECUTE||action==null||!action.binding().equals(decision.binding())){
                event("PROPOSAL_REJECTED","", "unsupported-action-or-binding");stalled();continue;
            }
            if(executed.contains(action.id())){event("REPLAY_REJECTED",action.id(),action.binding());control.awaitUser("replay-rejected");continue;}
            if(denied.contains(action.intentBinding())){control.awaitUser("approval-rejected");continue;}
            if(action.binding().equals(awaitingEvidence.get(action.id()))){control.awaitUser("approval-needs-evidence");continue;}
            if(!approve(action,executor,menu.values()))continue;
            AgentObservation observed=dispatch(action,executor);
            if(action.id().equals(required)){if(!observed.succeeded())stalled();else noProgress=0;return observed;}
            menu.remove(action.id());
            String actual=AgentAction.digest(new TreeMap<>(observed.facts()).toString());
            boolean changed=!actual.equals(observedFacts.put(action.intentBinding(),actual));
            if(observed.succeeded()&&changed)noProgress=0;else stalled();
        }
    }
    /** Persists dispatch intent and preserves uncertainty until actual execution returns. / 持久化派发意图，直至实际执行返回前保留不确定状态。
     * @param action approved exact operation / 已批准精确操作
     * @param executor controlled execution port / 受控执行端口
     * @return actual observation / 实际观测
     * @throws Exception when execution or durable evidence fails / 执行或持久化证据失败时
     */
    private AgentObservation dispatch(AgentAction action,AgentExecutionPort executor)throws Exception{
            // Persist intent before dispatch; journal failure prevents remote effects. / 先记录意图，记录失败时不产生远端影响。
            event("EXECUTING",action.id(),action.binding());executed.add(action.id());
            AgentObservation observed;
            try{observed=executor.execute(action);}
            catch(Exception failure){
                if(!action.tool().modifiesServer()){event("RESULT",action.id(),"true:false:read-only-query-failed");control.finish(failure instanceof java.util.concurrent.CancellationException?AgentTaskState.CANCELLED:AgentTaskState.FAILED);}
                else{event("UNKNOWN",action.id(),action.binding());control.finish(AgentTaskState.UNKNOWN);}throw failure;}
            event(observed.known()?"RESULT":"UNKNOWN",action.id(),observed.known()+":"+observed.succeeded()+":"+AgentAction.digest(new TreeMap<>(observed.facts()).toString()));
            String facts=new TreeMap<>(observed.facts()).toString();
            history.add(action.tool().name()+" "+action.id()+" known="+observed.known()+" success="+observed.succeeded()+" facts="
                +(facts.length()>512?facts.substring(0,512)+" [digest="+AgentAction.digest(facts)+"]":facts));
            if(!observed.known()){control.finish(AgentTaskState.UNKNOWN);throw new IllegalStateException("execution outcome unknown; reconcile before any replay");}
        return observed;
    }
    /** Applies mandatory local, independent AI and optional per-action human approval. / 应用强制本地、独立 AI 及按策略逐动作人工审批。
     * @param action exact selected action / 精确所选动作
     * @param executor narrow revalidation port / 窄重新验证端口
     * @param available evidence tools available at this boundary / 此边界可用证据工具
     * @return whether fresh approval admits this action / 新审批是否准入此动作
     * @throws Exception on unavailable validation / 验证不可用时
     */
    private boolean approve(AgentAction action,AgentExecutionPort executor,Collection<AgentAction> available)throws Exception{
            if(evidenceRounds.getOrDefault(action.id(),0)>2){control.awaitUser("approval-needs-evidence");return false;}
            AgentRiskLevel local=executor.validate(action);
            event("LOCAL_VALIDATION",action.id(),local.name());
            if(local==AgentRiskLevel.FORBIDDEN||action.risk()==AgentRiskLevel.FORBIDDEN){
                event("DENIED",action.id(),"local-forbidden");control.awaitUser("local-rejected");return false;
            }
            AgentReview review;
            try{review=models.review(goal,action,local);}
            catch(Exception failure){if(Thread.currentThread().isInterrupted()||failure instanceof java.util.concurrent.CancellationException)throw failure;control.awaitUser("approval-service-unavailable");return false;}
            control.checkpoint();
            event("AI_REVIEW",action.id(),review.decision().name()+":"+review.risk().name()+":"+review.binding()+":"+review.reason());
            if(review.decision()!=AgentReviewDecision.ALLOW||review.risk()==AgentRiskLevel.FORBIDDEN){
                history.add("action "+action.id()+" "+review.decision().name()+" reason="+review.reason());
                if(review.decision()==AgentReviewDecision.DENY||review.risk()==AgentRiskLevel.FORBIDDEN){denied.add(action.intentBinding());control.awaitUser("approval-rejected");}
                else{awaitingEvidence.put(action.id(),action.binding());
                    if(evidenceRounds.merge(action.id(),1,Integer::sum)>2||available.stream().noneMatch(a->a.tool()==AgentToolType.SERVICE_STATUS||a.tool()==AgentToolType.SERVICE_LOGS||a.tool()==AgentToolType.VERIFY_SERVER||a.tool()==AgentToolType.CANDIDATE_STATUS))control.awaitUser("approval-needs-evidence");}
                return false;
            }
            AgentRiskLevel risk;
            try{risk=AgentApprovalGate.admit(action,local,review);}
            catch(SecurityException invalid){control.awaitUser("approval-binding-invalid");return false;}
            if(AgentApprovalGate.needsHuman(mode,risk)){
                boolean accepted=human.test(action,review);event("HUMAN_REVIEW",action.id(),Boolean.toString(accepted));
                if(!accepted){denied.add(action.intentBinding());history.add("user rejected "+action.id());control.awaitUser("user-rejected");return false;}
            }
            control.checkpoint();
            if(!executor.refresh(action).binding().equals(review.binding())){event("APPROVAL_INVALIDATED",action.id(),"configuration-or-evidence-changed");return false;}
            AgentRiskLevel rechecked=executor.validate(action);
            if(rechecked!=local){event("APPROVAL_INVALIDATED",action.id(),"precondition-changed");control.awaitUser("precondition-changed");return false;}
            AgentApprovalGate.admit(action,rechecked,review);
        return true;
    }
    /** Records an event before its corresponding transition. / 在对应转换前记录事件。
     * @param type event kind / 事件种类
     * @param action action identifier / 动作标识
     * @param detail nonsecret bounded detail / 非秘密有界详情
     */
    private void event(String type,String action,String detail){journal.accept(Map.of("type",type,"action",action,"detail",detail));}
    /** Accounts for missing progress without resetting the global budget. / 记录无进展，不重置全局预算。 */
    private void stalled(){if(++noProgress>=2)handoff("two-rounds-without-progress");}
    /** Moves only forward in the deployment role list. / 仅沿部署用途列表向后移动。
     * @param reason nonsecret handoff reason / 非秘密交接原因
     */
    private void handoff(String reason){noProgress=0;event("HANDOFF","",reason);history.add(reason);if(!models.advanceDeployment())control.awaitUser("deployment-models-exhausted");}
    /** Reports remaining decisions for UI and tests. / 向界面及测试提供剩余决策数。
     * @return remaining decision count / 剩余决策数
     */
    public int remaining(){return remaining;}
}
