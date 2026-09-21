package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.app.service.source.SourceSnapshotIdentity;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.deploy.agent.*;
import gold.debug.windowstolinux.shared.deploy.contract.*;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.*;

/** Adapts existing deterministic operations to the single Agent's checked action catalog. / 将既有确定性操作接入单 Agent 的受检动作目录。 */
public final class AutomaticAgentBoundary {
    /** Frozen task identifier. / 冻结任务标识。 */
    private final String task;
    /** Exact server and execution account, retained independently of display labels. / 精确服务器及执行账户，独立于展示标签保存。 */
    private final String target;
    /** Serial approval and execution coordinator. / 串行审批及执行协调器。 */
    private final DeploymentAgentSession session;
    /** Safe boundary lifecycle. / 安全边界生命周期。 */
    private final AgentTaskControl control;
    /** Synchronous nonsecret audit sink. / 同步非秘密审计端口。 */
    private final Consumer<Map<String,String>> journal;
    /** Rechecks the selected server identity against current inventory. / 对照当前清单重新检查所选服务器身份。 */
    private final BooleanSupplier serverUnchanged;
    /** Private source root. / 私有源码根目录。 */
    private Path source;
    /** Frozen member/content digest. / 冻结成员及内容摘要。 */
    private String sourceDigest;
    /** Current immutable plan version. / 当前不可变计划版本。 */
    private long revision;
    /** Known nonsecret facts accumulated from actual operations. / 根据实际操作积累的已知非秘密事实。 */
    private final Map<String,String> facts=new LinkedHashMap<>();
    /** Fixed callbacks for supported owned-resource tools. / 受支持且有归属资源工具的固定回调。 */
    private final Map<String,ExtraOperation> extras=new LinkedHashMap<>();
    /** Prevents additional remote tool execution inside an atomic existing transaction. / 防止在既有原子事务内执行额外远端工具。 */
    private int depth;
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
    public AutomaticAgentBoundary(String task,ServerProfile server,AgentApprovalMode policy,AgentModelPort models,AgentTaskControl control,
            AutomaticDeploymentInteraction interaction,Consumer<Map<String,String>> journal,BooleanSupplier serverUnchanged){
        this.task=task;this.target=target(server);this.control=control;this.journal=journal;this.serverUnchanged=serverUnchanged;
        session=new DeploymentAgentSession(task,target,"Deploy the frozen selected source to "+target+" using only registered managed resources.",policy,models,control,
            (action,review)->interaction.confirm("deployment.agent.review",Map.of("tool",action.tool().name(),"target",target,
                "parameters",new TreeMap<>(action.parameters()).toString(),"evidence",new TreeMap<>(action.evidence()).toString(),
                "risk",AgentRiskLevel.values()[Math.max(action.risk().ordinal(),review.risk().ordinal())].name(),"reason",review.reason(),"binding",action.binding())),journal);
        facts.put("authorization","User requested deployment to this exact selected server; no unrelated resources are authorized.");
        facts.put("executionIdentity",target);
        gold.debug.windowstolinux.app.service.ai.DeploymentAiScope.current().ifPresent(scope->facts.put("modelConfiguration",scope.binding()));
    }
    /** Encodes server and account identity without credentials. / 编码服务器及账户身份，不包含凭据。
     * @param server selected server / 所选服务器
     * @return exact readable target / 精确可读目标
     */
    public static String target(ServerProfile server){return server.id()+"|"+server.username()+"@"+server.host()+":"+server.sshPort();}
    /** Freezes the private source before the first Agent decision. / 在首次 Agent 决策前冻结私有源码。
     * @param root private snapshot root / 私有快照根目录
     * @throws Exception when snapshot identity cannot be verified / 无法验证快照身份时
     */
    public void freeze(Path root)throws Exception{if(source!=null)throw new IllegalStateException("source already frozen");source=root;sourceDigest=SourceSnapshotIdentity.digest(root);facts.put("sourceSnapshot",sourceDigest);}
    /** Adds a bounded nonsecret verified fact. / 增加有界非秘密已验证事实。
     * @param key controlled evidence key / 受控证据键
     * @param value bounded safe content / 有界安全内容
     */
    public void fact(String key,String value){if(value.length()>8192)throw new IllegalArgumentException("fact too large");facts.put(key,value);}
    /** Registers an owned-resource action supplied by code, never by model text. / 登记由代码而非模型文本提供的有归属资源动作。
     * @param key stable catalog identity / 稳定目录身份
     * @param type registered capability / 已登记能力
     * @param parameters exact nonsecret parameters / 精确非秘密参数
     * @param valid ownership check / 归属检查
     * @param operation narrow implementation / 窄实现
     */
    public void register(String key,AgentToolType type,Map<String,String> parameters,BooleanSupplier valid,Callable<AgentObservation> operation){
        extras.put(key,new ExtraOperation(type,Map.copyOf(parameters),valid,operation));
    }
    /** Submits one existing operation and offers bounded diagnostics before it. / 提交一个既有操作，并在之前提供有界诊断。
     * @param type registered capability / 已登记能力
     * @param parameters exact nonsecret parameters / 精确非秘密参数
     * @param operation existing operation / 既有操作
     * @param <T> existing typed result / 既有类型化结果
     * @return actual existing result / 实际既有结果
     * @throws Exception on denial, failed execution or unknown outcome / 拒绝、执行失败或结果未知时
     */
    public <T> T execute(AgentToolType type,Map<String,String> parameters,Callable<T> operation)throws Exception{
        while(true){T result=attempt(type,parameters,operation);
            if(type!=AgentToolType.DEPLOY_TRANSACTION||observation(result).succeeded())return result;
            // A known failed transaction completed recovery. A retry is a new reviewed action, never a replay. / 已知失败事务已完成恢复，重试是新审批动作而非重放。
            facts.put("previousDeployment",observation(result).facts().toString());
        }
    }
    /** Runs one proposal menu through exact local and independent review. / 将一次提案菜单交给精确本地及独立审批。
     * @param type registered capability / 已登记能力
     * @param parameters nonsecret exact arguments / 非秘密精确参数
     * @param operation existing typed executor / 既有类型化执行器
     * @param <T> existing result type / 既有结果类型
     * @return actual result / 实际结果
     * @throws Exception on unavailable or unknown execution / 执行不可用或未知时
     */
    private <T> T attempt(AgentToolType type,Map<String,String> parameters,Callable<T> operation)throws Exception{
        if(source==null)throw new IllegalStateException("source must be frozen");
        AgentRiskLevel risk=risk(type);var pending=action(type,parameters,risk);var offered=new LinkedHashMap<String,ExtraOperation>();
        var menu=new ArrayList<AgentAction>();menu.add(pending);
        if(depth==0)for(var extra:extras.values()){if(extra.type()==type)continue;var offeredAction=action(extra.type(),extra.parameters(),risk(extra.type()));offered.put(offeredAction.id(),extra);menu.add(offeredAction);}
        var result=new java.util.concurrent.atomic.AtomicReference<T>();
        session.run(menu,pending.id(),new AgentExecutionPort(){
            /** Binds newly observed diagnostics before the next decision. / 在下一决策前绑定新观察诊断。
             * @param action prior proposal / 原提案
             * @return refreshed exact proposal / 刷新的精确提案
             */
            @Override public AgentAction refresh(AgentAction action){
                gold.debug.windowstolinux.app.service.ai.DeploymentAiScope.current().ifPresent(scope->facts.put("modelConfiguration",scope.binding()));
                if(action.evidence().equals(facts))return action;
                return new AgentAction(action.id(),task,target,sourceDigest,++revision,action.tool(),action.parameters(),Map.copyOf(facts),action.risk());}
            /** Rechecks exact task/source/server/tool ownership. / 重新检查精确任务、源码、服务器及工具归属。
             * @param action bound action / 绑定动作
             * @return local risk or rejection / 本地风险或拒绝
             * @throws Exception on failed source verification / 源码验证失败时
             */
            @Override public AgentRiskLevel validate(AgentAction action)throws Exception{
                if(!action.taskId().equals(task)||!action.target().equals(target)||!action.sourceRevision().equals(sourceDigest)||!serverUnchanged.getAsBoolean()
                        ||!SourceSnapshotIdentity.digest(source).equals(sourceDigest))return AgentRiskLevel.FORBIDDEN;
                if(action.id().equals(pending.id()))return action.parameters().equals(pending.parameters())&&action.tool()==pending.tool()&&action.evidence().equals(facts)?risk:AgentRiskLevel.FORBIDDEN;
                var extra=offered.get(action.id());return extra!=null&&extra.valid().getAsBoolean()&&extra.parameters().equals(action.parameters())?risk(extra.type()):AgentRiskLevel.FORBIDDEN;
            }
            /** Dispatches only the already captured callback. / 仅派发已经捕获的回调。
             * @param action checked action / 已检查动作
             * @return actual observation / 实际观察
             * @throws Exception on execution failure / 执行失败时
             */
            @Override public AgentObservation execute(AgentAction action)throws Exception{
                depth++;
                try{
                    if(action.id().equals(pending.id())){T value=operation.call();result.set(value);var observed=observation(value);facts.put("lastOperation",type.name()+":"+observed.facts());return observed;}
                    var observed=offered.get(action.id()).operation().call();observed.facts().forEach((k,v)->facts.put("observed/"+k,v));return observed;
                }finally{depth--;}
            }
        });
        control.checkpoint();return result.get();
    }
    /** Creates an exact action with a monotonically increasing plan version. / 创建计划版本单调增加的精确动作。
     * @param type capability / 能力
     * @param parameters nonsecret parameters / 非秘密参数
     * @param risk deterministic risk / 确定性风险
     * @return frozen action / 冻结动作
     */
    private AgentAction action(AgentToolType type,Map<String,String> parameters,AgentRiskLevel risk){
        var action=new AgentAction(UUID.randomUUID().toString(),task,target,sourceDigest,++revision,type,parameters,Map.copyOf(facts),risk);
        journal.accept(Map.of("type","ACTION_PROPOSED","action",action.id(),"detail",type.name()+":"+revision+":"+action.binding()));return action;
    }
    /** Classifies material server effects conservatively. / 保守分类显著服务器影响。
     * @param type registered capability / 已登记能力
     * @return deterministic severity / 确定性严重程度
     */
    private static AgentRiskLevel risk(AgentToolType type){return switch(type){case ANALYZE_SOURCE,VERIFY_SERVER,SERVICE_STATUS,SERVICE_LOGS->AgentRiskLevel.NORMAL;default->AgentRiskLevel.HIGH;};}
    /** Converts actual typed results without trusting model completion claims. / 转换实际类型化结果，不信任模型完成声明。
     * @param result existing result / 既有结果
     * @return verified status evidence / 已验证状态证据
     */
    private static AgentObservation observation(Object result){
        DeploymentStatus status=result instanceof gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome outcome?outcome.status()
            :result instanceof gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult multiple?multiple.status():null;
        if(status!=null)return new AgentObservation(status!=DeploymentStatus.MANUAL_RECOVERY_REQUIRED,status==DeploymentStatus.SUCCEEDED,Map.of("deploymentStatus",status.name()));
        if(result instanceof gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts facts)return new AgentObservation(true,true,Map.of(
            "os",facts.operatingSystem(),"architecture",facts.architecture(),"helperVersion",Integer.toString(facts.managedHelperProtocolVersion()),"availableBytes",Long.toString(facts.availableBytes())));
        return new AgentObservation(true,true,Map.of("operation","completed"));
    }
    /** Known registered operation and its independent ownership check. / 已知登记操作及其独立归属检查。
     * @param type capability / 能力
     * @param parameters fixed parameters / 固定参数
     * @param valid ownership precondition / 归属前置条件
     * @param operation narrow callback / 窄回调
     */
    private record ExtraOperation(AgentToolType type,Map<String,String> parameters,BooleanSupplier valid,Callable<AgentObservation> operation){}
}
