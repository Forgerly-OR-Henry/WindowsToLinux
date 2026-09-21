package gold.debug.windowstolinux.app.db.persistence.repository;
import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.shared.model.agent.AgentTaskState;
import gold.debug.windowstolinux.shared.model.deployment.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Stores only bounded audit summaries and digests, never raw prompts or credentials. / 仅存储有界审计摘要及摘要值，不保存原始提示词或凭据。 */
public final class AgentTaskRepository {
    /** Scoped database connection factory. / 限定作用域数据库连接工厂。 */
    private final DesktopConnectionFactory connections;
    /** Binds the existing App database. / 绑定既有 App 数据库。
     * @param connections connection factory / 连接工厂
     */
    public AgentTaskRepository(DesktopConnectionFactory connections){this.connections=connections;}
    /** Creates an immutable task identity before any model or remote operation. / 在模型或远端操作前创建不可变任务身份。
     * @param id task identifier / 任务标识
     * @param server fixed server identifier / 固定服务器标识
     * @param target target identity digest / 目标身份摘要
     * @param mode deployment mode / 部署模式
     * @param approval human policy / 人工策略
     * @param configuration frozen model configuration digest / 冻结模型配置摘要
     * @param deploymentSkill decision skill version / 决策 Skill 版本
     * @param approvalSkill review skill version / 审批 Skill 版本
     * @throws SQLException on persistence failure / 持久化失败时
     */
    public void create(String id,String server,String target,DeploymentAutomationMode mode,AgentApprovalMode approval,String configuration,String deploymentSkill,String approvalSkill)throws SQLException{
        if(!target.matches("[a-f0-9]{64}")||!configuration.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid task digest");
        try(var c=connections.open();var s=c.prepareStatement("INSERT INTO deployment_agent_task VALUES(?,?,?,?,?,?,?,?,?,?,?)")){
            String now=Instant.now().toString();String[] values={id,server,target,mode.name(),approval.name(),configuration,deploymentSkill,approvalSkill,AgentTaskState.RUNNING.name(),now,now};
            for(int i=0;i<values.length;i++)s.setString(i+1,values[i]);s.executeUpdate();
        }
    }
    /** Appends an audit event; failed persistence must stop dispatch. / 追加审计事件，持久化失败必须停止派发。
     * @param taskId task identifier / 任务标识
     * @param type controlled event code / 受控事件代码
     * @param action operation identifier / 操作标识
     * @param detail sanitized summary or digest / 脱敏摘要或摘要值
     * @throws SQLException on persistence failure / 持久化失败时
     */
    public void event(String taskId,String type,String action,String detail)throws SQLException{
        if(!type.matches("[A-Z_]{1,48}")||action.length()>128||detail.length()>4096||detail.indexOf('\0')>=0)throw new IllegalArgumentException("invalid task event");
        try(var c=connections.open();var s=c.prepareStatement("INSERT INTO deployment_agent_event(task_id,event_type,action_id,detail,created_at) VALUES(?,?,?,?,?)")){
            s.setString(1,taskId);s.setString(2,type);s.setString(3,action);s.setString(4,detail);s.setString(5,Instant.now().toString());s.executeUpdate();
        }
    }
    /** Atomically records lifecycle transition and evidence. / 原子记录生命周期转换及证据。
     * @param id task identity / 任务身份
     * @param state new lifecycle state / 新生命周期状态
     * @throws SQLException on persistence failure / 持久化失败时
     */
    public void state(String id,AgentTaskState state)throws SQLException{
        try(var c=connections.open()){
            RepositoryTransactionExecutor.execute(c,()->{
                String now=Instant.now().toString();
                try(var s=c.prepareStatement("UPDATE deployment_agent_task SET state=?,updated_at=? WHERE id=?")){
                    s.setString(1,state.name());s.setString(2,now);s.setString(3,id);if(s.executeUpdate()!=1)throw new SQLException("task missing");
                }
                try(var s=c.prepareStatement("INSERT INTO deployment_agent_event(task_id,event_type,action_id,detail,created_at) VALUES(?,'STATE','',?,?)")){
                    s.setString(1,id);s.setString(2,state.name());s.setString(3,now);s.executeUpdate();
                }
            });
        }
    }
    /** Marks old process-owned runs interrupted; never replays an operation. / 标记旧进程任务中断，绝不重放操作。
     * @throws SQLException on persistence failure / 持久化失败时
     */
    public void interruptUnfinished()throws SQLException{
        try(var c=connections.open();var s=c.prepareStatement("UPDATE deployment_agent_task SET state='INTERRUPTED',updated_at=? WHERE state IN ('RUNNING','PAUSE_REQUESTED','PAUSED','CANCEL_REQUESTED')")){
            s.setString(1,Instant.now().toString());s.executeUpdate();
        }
    }
    /** Reads bounded recent records for App history. / 为 App 历史读取有界最近记录。
     * @return nonsecret task rows / 非秘密任务记录
     * @throws SQLException on read failure / 读取失败时
     */
    public List<Map<String,String>> recent()throws SQLException{
        var result=new ArrayList<Map<String,String>>();
        try(var c=connections.open();var s=c.prepareStatement("SELECT id,server_id,automation_mode,approval_mode,state,started_at,updated_at FROM deployment_agent_task ORDER BY started_at DESC LIMIT 100");var rows=s.executeQuery()){
            while(rows.next()){var row=new LinkedHashMap<String,String>();for(String key:List.of("id","server_id","automation_mode","approval_mode","state","started_at","updated_at"))row.put(key,rows.getString(key));result.add(Map.copyOf(row));}
        }return List.copyOf(result);
    }
    /** Reads bounded chronological evidence for one task. / 读取一个任务的有界时间顺序证据。
     * @param id task identity / 任务身份
     * @return nonsecret audit rows / 非秘密审计记录
     * @throws SQLException on read failure / 读取失败时
     */
    public List<Map<String,String>> events(String id)throws SQLException{
        var result=new ArrayList<Map<String,String>>();
        try(var c=connections.open();var s=c.prepareStatement("SELECT sequence,event_type,action_id,detail,created_at FROM deployment_agent_event WHERE task_id=? ORDER BY sequence LIMIT 1000")){
            s.setString(1,id);try(var rows=s.executeQuery()){while(rows.next()){var row=new LinkedHashMap<String,String>();for(String key:List.of("sequence","event_type","action_id","detail","created_at"))row.put(key,rows.getString(key));result.add(Map.copyOf(row));}}
        }return List.copyOf(result);
    }
}
