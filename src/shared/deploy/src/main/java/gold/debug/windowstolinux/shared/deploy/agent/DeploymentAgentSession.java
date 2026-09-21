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
            AgentDecision decision;
            try{decision=models.decide(goal,List.copyOf(menu.values()),List.copyOf(history),--remaining);}
            catch(Exception failure){if(Thread.currentThread().isInterrupted())throw failure;control.awaitUser("deployment-models-unavailable");continue;}
            if(decision.decision()==AgentDecisionType.NEED_INPUT){event("INPUT_REQUIRED","",decision.reason());control.awaitUser("input-required");continue;}
            if(decision.decision()==AgentDecisionType.UNABLE){handoff("model-unable");continue;}
            AgentAction action=menu.get(decision.actionId());
            if(decision.decision()!=AgentDecisionType.EXECUTE||action==null||!action.binding().equals(decision.binding())){
                event("PROPOSAL_REJECTED","", "unsupported-action-or-binding");stalled();continue;
            }
            if(executed.contains(action.id())){event("REPLAY_REJECTED",action.id(),action.binding());control.awaitUser("replay-rejected");continue;}
            AgentRiskLevel local=executor.validate(action);
            event("LOCAL_VALIDATION",action.id(),local.name());
            if(local==AgentRiskLevel.FORBIDDEN||action.risk()==AgentRiskLevel.FORBIDDEN){
                event("DENIED",action.id(),"local-forbidden");control.awaitUser("local-rejected");continue;
            }
            AgentReview review;
            try{review=models.review(goal,action,local);}
            catch(Exception failure){if(Thread.currentThread().isInterrupted())throw failure;control.awaitUser("approval-service-unavailable");continue;}
            event("AI_REVIEW",action.id(),review.decision().name()+":"+review.risk().name()+":"+review.binding());
            if(review.decision()!=AgentReviewDecision.ALLOW){
                history.add("action "+action.id()+" "+review.decision().name());
                control.awaitUser(review.decision()==AgentReviewDecision.DENY?"approval-rejected":"approval-needs-evidence");continue;
            }
            AgentRiskLevel risk;
            try{risk=AgentApprovalGate.admit(action,local,review);}
            catch(SecurityException invalid){control.awaitUser("approval-binding-invalid");continue;}
            if(AgentApprovalGate.needsHuman(mode,risk)){
                boolean accepted=human.test(action,review);event("HUMAN_REVIEW",action.id(),Boolean.toString(accepted));
                if(!accepted){history.add("user rejected "+action.id());control.awaitUser("user-rejected");continue;}
            }
            control.checkpoint();
            AgentRiskLevel rechecked=executor.validate(action);
            if(rechecked!=local){event("APPROVAL_INVALIDATED",action.id(),"precondition-changed");control.awaitUser("precondition-changed");continue;}
            AgentApprovalGate.admit(action,rechecked,review);
            // Persist intent before dispatch; journal failure prevents remote effects. / 先记录意图，记录失败时不产生远端影响。
            event("EXECUTING",action.id(),action.binding());executed.add(action.id());
            AgentObservation observed;
            try{observed=executor.execute(action);}
            catch(Exception failure){event("UNKNOWN",action.id(),action.binding());control.finish(AgentTaskState.UNKNOWN);throw failure;}
            event(observed.known()?"RESULT":"UNKNOWN",action.id(),observed.known()+":"+observed.succeeded()+":"+AgentAction.digest(new TreeMap<>(observed.facts()).toString()));
            history.add(action.tool().name()+" "+action.id()+" known="+observed.known()+" success="+observed.succeeded()+" facts="+new TreeMap<>(observed.facts()));
            if(!observed.known()){control.finish(AgentTaskState.UNKNOWN);throw new IllegalStateException("execution outcome unknown; reconcile before any replay");}
            if(action.id().equals(required))return observed;
            menu.remove(action.id());if(observed.succeeded())noProgress=0;else stalled();
        }
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
