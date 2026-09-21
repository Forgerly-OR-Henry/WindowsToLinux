package gold.debug.windowstolinux.app.service.deployment.automatic;
import gold.debug.windowstolinux.app.service.ai.AiUseCaseFacade;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Fixed checkpoint assistance; no repair commands or autonomous execution. / 固定节点辅助，不产生修复命令或自主执行。 */
public final class AssistedDeploymentAdvisor implements AutoCloseable {
    /** Explicit deployment-purpose gateway. / 显式部署用途网关。 */
    private final AiUseCaseFacade ai;
    /** Task-owned unlock buffer. / 任务所属解锁缓冲区。 */
    private final char[] master;
    /** Human decisions for uncertainty and provider failure. / 不确定性及提供者失败时的人工决定。 */
    private final AutomaticDeploymentInteraction interaction;
    /** Visible explanatory output. / 可见解释输出。 */
    private final Consumer<LocalizedMessage> progress;
    /** True only after explicit user acceptance of manual continuation. / 仅在用户明确同意人工继续后为真。 */
    private boolean manual;
    /** Binds one task's fixed assistance policy. / 绑定一个任务的固定辅助策略。
     * @param ai purpose gateway / 用途网关
     * @param master unlock buffer / 解锁缓冲区
     * @param interaction human interaction / 人工交互
     * @param progress progress sink / 进度端口
     */
    public AssistedDeploymentAdvisor(AiUseCaseFacade ai,char[] master,AutomaticDeploymentInteraction interaction,Consumer<LocalizedMessage> progress){
        this.ai=ai;this.master=master.clone();this.interaction=interaction;this.progress=progress;
    }
    /** Completes only uniquely evidenced supplied candidates; unknown input stays human. / 仅补齐证据唯一的既有候选，未知输入仍交人工。
     * @param fields requested fields / 请求字段
     * @return checked field values / 已检查字段值
     */
    public Map<String,String> complete(List<DeploymentInputField> fields){
        if(fields.isEmpty())return Map.of();
        var candidates=new LinkedHashMap<String,List<String>>();var evidence=new LinkedHashMap<String,String>();
        for(var field:fields)if(!secret(field.id())&&!field.choices().isEmpty()){candidates.put(field.id(),field.choices());evidence.put("candidate/"+field.id(),String.join(", ",field.choices()));}
        var advice=invoke("ANALYSIS",candidates,evidence);var adopted=new LinkedHashMap<String,String>();
        if(advice!=null&&advice.unresolved().isEmpty())for(var suggestion:advice.suggestions()){
            var choices=candidates.getOrDefault(suggestion.field(),List.of());
            if(choices.size()==1&&choices.contains(suggestion.candidate())&&suggestion.evidence().contains("candidate/"+suggestion.field())
                    &&advice.suggestions().stream().filter(s->s.field().equals(suggestion.field())).count()==1)adopted.put(suggestion.field(),suggestion.candidate());
        }
        var pending=fields.stream().filter(field->!adopted.containsKey(field.id())).toList();
        adopted.putAll(AutomaticInputCompletion.ask(pending,interaction));return Map.copyOf(adopted);
    }
    /** Reviews nonsecret observations before execution or after failure. / 在执行前或失败后审阅非秘密观察。
     * @param phase fixed checkpoint / 固定节点
     * @param evidence bounded nonsecret facts / 有界非秘密事实
     */
    public void check(String phase,Map<String,String> evidence){
        var advice=invoke(phase,Map.of(),evidence);
        if(advice!=null&&!phase.equals("FAILURE")&&!advice.unresolved().isEmpty()
                &&!interaction.confirm("deployment.assisted.unresolved",Map.of("issues",String.join("\n",advice.unresolved()))))throw new CancellationException();
    }
    /** Calls once at the checkpoint, exposing every failure before manual continuation. / 在节点调用一次，人工继续前明确显示失败。
     * @param phase fixed checkpoint / 固定节点
     * @param candidates nonsecret candidates / 非秘密候选
     * @param evidence observed facts / 观察事实
     * @return valid advice or null after explicit manual continuation / 有效建议，明确人工继续后可为空
     */
    private AssistedDeploymentAdvice invoke(String phase,Map<String,List<String>> candidates,Map<String,String> evidence){
        if(manual)return null;
        try{var advice=ai.assist(phase,candidates,evidence,master.clone());
            progress.accept(LocalizedMessage.of("deployment.assisted.advice",Map.of("phase",phase,"summary",advice.summary(),
                "unresolved",String.join("\n",advice.unresolved()))));return advice;}
        catch(Exception unavailable){
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            if(!interaction.confirm("deployment.assisted.manualFallback",Map.of("phase",phase)))throw new CancellationException();
            manual=true;progress.accept(LocalizedMessage.of("deployment.assisted.manual"));return null;
        }
    }
    /** Excludes sensitive and authorization fields from candidate prompts. / 从候选提示中排除敏感及授权字段。
     * @param id field identity / 字段身份
     * @return whether automatic suggestion is forbidden / 是否禁止自动建议
     */
    private static boolean secret(String id){String key=id.toLowerCase(Locale.ROOT);return key.matches(".*(password|secret|credential|token|authorization|risk|permission|databaseDetails).*".toLowerCase(Locale.ROOT));}
    /** Wipes only the task-owned unlock copy. / 仅清零任务所属解锁副本。 */
    @Override public void close(){Arrays.fill(master,'\0');}
}
