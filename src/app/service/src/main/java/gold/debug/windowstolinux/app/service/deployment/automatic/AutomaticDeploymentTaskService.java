package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.app.db.persistence.repository.AgentTaskRepository;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.service.contract.*;
import gold.debug.windowstolinux.app.service.contract.definition.*;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.source.SourcePreparationUseCase;
import gold.debug.windowstolinux.shared.ai.client.AgentProtocolClient;
import gold.debug.windowstolinux.shared.deploy.agent.AgentTaskControl;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** App-only mode assembly, frozen model scope and durable task lifetime. / 仅 App 使用的模式装配、冻结模型作用域及持久化任务生命周期。 */
public final class AutomaticDeploymentTaskService {
    /** Existing typed deployment facade. / 既有类型化部署门面。 */
    private final AutomaticDeploymentApplicationFacade service;
    /** Model configuration and credential gateway. / 模型配置及凭据网关。 */
    private final AiUseCaseFacade ai;
    /** Existing source preparation. / 既有源码准备。 */
    private final SourcePreparationUseCase source;
    /** Shared same-server exclusion. / 共享同服务器互斥。 */
    private final ServerOperationLockRegistry locks;
    /** Nonsecret durable journal. / 非秘密持久化日志。 */
    private final AgentTaskRepository records;
    /** In-process controls only; restart never reconstructs execution closures. / 仅进程内控制，重启不重建执行闭包。 */
    private final Map<String,AgentTaskControl> active=new ConcurrentHashMap<>();
    /** Explicit requests to replace a paused task's model snapshot. / 显式替换暂停任务模型快照的请求。 */
    private final Set<String> refreshRequested=ConcurrentHashMap.newKeySet();
    /** Binds App dependencies without performing deployment. / 绑定 App 依赖，不执行部署。
     * @param service typed deployment facade / 类型化部署门面
     * @param ai purpose model gateway / 用途模型网关
     * @param source source preparation / 源码准备
     * @param locks server exclusion / 服务器互斥
     * @param records task journal / 任务日志
     */
    public AutomaticDeploymentTaskService(AutomaticDeploymentApplicationFacade service,AiUseCaseFacade ai,SourcePreparationUseCase source,
            ServerOperationLockRegistry locks,AgentTaskRepository records){this.service=service;this.ai=ai;this.source=source;this.locks=locks;this.records=records;}
    /** Dispatches a fully checked task; model preflight precedes all effects. / 派发完整检查的任务，模型预检先于全部外部影响。
     * @param request frozen click-time request / 点击时冻结请求
     * @param master task unlock buffer / 任务解锁缓冲区
     * @param interaction user inputs and exact reviews / 用户输入及精确审阅
     * @param fingerprint first-use host identity confirmation / 首次主机身份确认
     * @param progress structured progress / 结构化进度
     * @return actual verified deployment result / 实际已验证部署结果
     * @throws Exception on failed preflight or deployment / 预检或部署失败时
     */
    public AutomaticDeploymentOutcome deploy(AutomaticDeploymentRequest request,char[] master,AutomaticDeploymentInteraction interaction,
            Predicate<String> fingerprint,Consumer<LocalizedMessage> progress)throws Exception{
        long started=System.nanoTime();
        try(var scope=ai.openDeployment(request.automationMode())){
            if(request.automationMode()==DeploymentAutomationMode.STATIC)return new AutomaticDeploymentUseCase(service,source,locks).deploy(request,master,interaction,fingerprint,progress);
            if(request.automationMode()==DeploymentAutomationMode.ASSISTED){
                try(var advice=new AssistedDeploymentAdvisor(ai,master,interaction,progress)){
                    try{return new AutomaticDeploymentUseCase(service,source,locks,advice,null).deploy(request,master,interaction,fingerprint,progress);}
                    catch(Exception failure){if(!(failure instanceof CancellationException))advice.check("FAILURE",Map.of("exceptionType",failure.getClass().getSimpleName()));throw failure;}
                    finally{progress.accept(LocalizedMessage.of("deployment.ai.metrics",Map.of("metrics",scope.metrics().toString(),"seconds",Long.toString((System.nanoTime()-started)/1_000_000_000))));}
                }
            }
            String id=request.taskId();var frozen=new TreeMap<String,String>();
            for(var purpose:AiPurposeType.values())frozen.put(purpose.name(),scope.providers(purpose).toString());
            records.create(id,request.server().id(),AgentAction.digest(AutomaticAgentBoundary.target(request.server())),request.automationMode(),request.approvalMode(),
                AgentAction.digest(frozen.toString()),AgentProtocolClient.DEPLOYMENT_SKILL,AgentProtocolClient.APPROVAL_SKILL);
            var control=new AgentTaskControl(state->{try{records.state(id,state);}catch(java.sql.SQLException failure){throw new IllegalStateException("cannot persist task state",failure);}
                progress.accept(LocalizedMessage.of("deployment.agent.state","state",state.name()));});
            control.onResume(()->{if(refreshRequested.remove(id)){
                try{scope.replace(ai.deploymentSnapshot(request.automationMode()));records.event(id,"CONFIGURATION_REPLACED","",scope.binding());}
                catch(java.sql.SQLException failure){throw new IllegalStateException("cannot replace model snapshot",failure);}
            }});
            if(active.putIfAbsent(id,control)!=null)throw new IllegalStateException("task already running");
            try(var models=ai.agentModels(master)){
                Consumer<Map<String,String>> journal=event->{try{records.event(id,event.get("type"),event.get("action"),event.get("detail"));}
                    catch(java.sql.SQLException failure){throw new IllegalStateException("cannot persist action intent",failure);}
                    progress.accept(LocalizedMessage.of("deployment.agent.event",Map.of("type",event.get("type"),"action",event.get("action"),"detail",event.get("detail"))));};
                var boundary=new AutomaticAgentBoundary(id,request.server(),request.approvalMode(),models,control,interaction,journal,()->{
                    try{return service.findServerProfile(request.server().id()).map(current->current.equals(request.server())).orElse(false);}
                    catch(java.sql.SQLException failure){return false;}
                });
                var result=new AutomaticDeploymentUseCase(service,source,locks,null,boundary).deploy(request,master,new AgentDeploymentInteraction(interaction,boundary),fingerprint,progress);
                control.finish(result.status()==DeploymentStatus.SUCCEEDED?AgentTaskState.SUCCEEDED:
                    result.status()==DeploymentStatus.MANUAL_RECOVERY_REQUIRED?AgentTaskState.UNKNOWN:AgentTaskState.FAILED);return result;
            }catch(Exception failure){
                if(control.state()!=AgentTaskState.UNKNOWN)control.finish(failure instanceof CancellationException?AgentTaskState.CANCELLED:AgentTaskState.FAILED);
                throw failure;
            }finally{
                active.remove(id);refreshRequested.remove(id);records.event(id,"METRICS","",scope.metrics()+";seconds="+(System.nanoTime()-started)/1_000_000_000);
            }
        }finally{Arrays.fill(master,'\0');}
    }
    /** Changes process-local control without changing frozen task policy. / 改变进程内控制，不改变冻结任务策略。
     * @param id task identifier / 任务标识
     * @param command explicit user action / 显式用户操作
     */
    public void control(String id,AgentTaskCommandAction command){
        var task=active.get(id);if(task==null)throw new IllegalStateException("task not active");
        switch(command){case PAUSE->task.pause();case RESUME->task.resume();case CANCEL->task.cancel();case REFRESH_MODELS->{
            if(task.state()!=AgentTaskState.PAUSED)throw new IllegalStateException("model replacement requires paused task");
            try{ai.deploymentSnapshot(DeploymentAutomationMode.AGENT);}catch(java.sql.SQLException failure){throw new IllegalStateException("invalid model configuration",failure);}
            refreshRequested.add(id);task.resume();}}
    }
    /** Reads a running task's safe state and pause reason. / 读取运行任务的安全状态及暂停原因。
     * @param id task identifier / 任务标识
     * @return nonsecret state fields / 非秘密状态字段
     */
    public Map<String,String> state(String id){var task=active.get(id);return task==null?Map.of():Map.of("state",task.state().name(),"reason",task.reason());}
    /** Reads persisted recent task history. / 读取持久化最近任务历史。
     * @return nonsecret task summaries / 非秘密任务摘要
     * @throws java.sql.SQLException on database failure / 数据库失败时
     */
    public List<Map<String,String>> history()throws java.sql.SQLException{return records.recent();}
    /** Reads one persisted task's event sequence. / 读取一个持久化任务的事件序列。
     * @param id task identifier / 任务标识
     * @return nonsecret chronological events / 非秘密时间顺序事件
     * @throws java.sql.SQLException on database failure / 数据库失败时
     */
    public List<Map<String,String>> events(String id)throws java.sql.SQLException{return records.events(id);}
}
