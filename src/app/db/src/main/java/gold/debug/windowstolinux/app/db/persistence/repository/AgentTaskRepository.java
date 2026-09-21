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
    /** Finds uncertain effects independently of the latest task state or UI history limit. / 独立于末次任务状态及界面历史上限查找不确定影响。
     * @param server exact server identity / 精确服务器身份
     * @return unresolved task identifiers / 未确认任务标识
     * @throws SQLException when durable evidence cannot be read / 无法读取持久化证据时
     */
    public List<String> unresolved(String server)throws SQLException{
        return unresolved(server,"");
    }
    /** Also checks a frozen endpoint identity so a second saved profile cannot replay uncertain effects. / 同时检查冻结端点身份，避免第二份保存资料重放不确定影响。
     * @param server saved server identity / 已保存服务器身份
     * @param endpointDigest digest of normalized host and port / 规范主机及端口摘要
     * @return unresolved task identities / 未确认任务身份
     * @throws SQLException if evidence cannot be read / 无法读取证据时
     */
    public List<String> unresolved(String server,String endpointDigest)throws SQLException{
        var ids=new ArrayList<String>();
        String sql="""
            SELECT t.id FROM deployment_agent_task t WHERE (t.server_id=? OR EXISTS(SELECT 1 FROM deployment_agent_event target
            WHERE target.task_id=t.id AND target.event_type='REMOTE_TARGET' AND target.detail=?)) AND
            (t.state='UNKNOWN' OR EXISTS(SELECT 1 FROM deployment_agent_event e
            WHERE e.task_id=t.id AND e.event_type='EXECUTING' AND NOT EXISTS
            (SELECT 1 FROM deployment_agent_event r WHERE r.task_id=e.task_id AND r.action_id=e.action_id
            AND r.sequence>e.sequence AND r.event_type IN ('RESULT','CANCELLED')))) ORDER BY t.started_at
            """;
        try(var c=connections.open();var s=c.prepareStatement(sql)){s.setString(1,server);s.setString(2,endpointDigest);try(var rows=s.executeQuery()){while(rows.next())ids.add(rows.getString(1));}}
        return List.copyOf(ids);
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
    /** Reads exact persisted task identity without a history limit. / 不依赖历史上限读取精确持久化任务身份。
     * @param id task identity / 任务身份
     * @return persisted identity if present / 存在时返回持久化身份
     * @throws SQLException when the record cannot be read / 无法读取记录时
     */
    public Optional<Map<String,String>> find(String id)throws SQLException{
        try(var c=connections.open();var s=c.prepareStatement("SELECT server_id,target_digest,state FROM deployment_agent_task WHERE id=?")){
            s.setString(1,id);try(var rows=s.executeQuery()){return rows.next()?Optional.of(Map.of("server_id",rows.getString(1),"target_digest",rows.getString(2),"state",rows.getString(3))):Optional.empty();}
        }
    }
}
