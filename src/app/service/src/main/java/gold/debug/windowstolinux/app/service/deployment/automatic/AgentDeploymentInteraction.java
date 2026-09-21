package gold.debug.windowstolinux.app.service.deployment.automatic;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.model.agent.AgentToolType;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import java.util.*;

/** Routes existing concrete risk decisions through the same mandatory dual approval. / 将既有具体风险决定接入相同的强制双审批。 */
final class AgentDeploymentInteraction implements AutomaticDeploymentInteraction {
    /** Human-only missing input and secret port. / 仅由人工提供缺失输入及秘密的端口。 */
    private final AutomaticDeploymentInteraction human;
    /** Shared exact-action approval boundary. / 共享精确动作审批边界。 */
    private final AutomaticAgentBoundary boundary;
    /** Binds the task's two ports. / 绑定任务的两个端口。
     * @param human user interaction / 用户交互
     * @param boundary mandatory approval boundary / 强制审批边界
     */
    AgentDeploymentInteraction(AutomaticDeploymentInteraction human,AutomaticAgentBoundary boundary){this.human=human;this.boundary=boundary;}
    /** Keeps business input under user control. / 业务输入仍由用户控制。
     * @param fields requested input / 请求输入
     * @return supplied values or cancellation / 提供的值或取消
     */
    @Override public Optional<Map<String,String>> requestInputs(List<DeploymentInputField> fields){return human.requestInputs(fields);}
    /** Never asks a model to supply credentials. / 绝不要求模型提供凭据。
     * @param key secret prompt / 秘密提示
     * @return user-owned secret buffer / 用户提供的秘密缓冲区
     */
    @Override public char[] requestSecret(String key){return human.requestSecret(key);}
    /** Requires fresh approval for actual system-security and initialization effects. / 对实际系统安全及初始化影响要求新审批。
     * @param key existing risk identity / 既有风险身份
     * @param details actual observed effect / 实际观察影响
     * @return accepted concrete risk / 接受具体风险
     */
    @Override public boolean confirm(String key,Map<String,?> details){
        if(!Set.of("environment.system.confirm","db.existingSchema","db.sqliteInitialization").contains(key))return human.confirm(key,details);
        var values=new TreeMap<String,String>();values.put("operation",key);
        details.forEach((name,value)->values.put(name,new gold.debug.windowstolinux.shared.ai.collaboration.role.ErrorExplanationRoleContext("risk-review",String.valueOf(value)).safeDiagnostic()));
        try{return boundary.execute(key.startsWith("environment.")?AgentToolType.PREPARE_ENVIRONMENT:AgentToolType.PREPARE_DATABASE,values,()->Boolean.TRUE);}
        catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("risk approval unavailable",failure);}
    }
    /** Refuses replacement of shared database software outside the application's resource scope. / 拒绝超出应用资源范围的共享数据库软件替换。
     * @param details observed replacement proposal / 观察到的替换提案
     * @return false because this first Agent version has no backup/migration authorization / 首版 Agent 无备份迁移授权，返回 false
     */
    @Override public boolean confirmDatabaseReplacement(Map<String,?> details){return false;}
}
