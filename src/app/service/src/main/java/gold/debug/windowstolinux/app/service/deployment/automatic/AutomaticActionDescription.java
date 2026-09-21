package gold.debug.windowstolinux.app.service.deployment.automatic;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.shared.model.agent.AgentAction;
import java.util.*;

/** Produces bounded reviewable operation effects, excluding configuration values and secrets. / 生成有界可审阅操作影响，排除配置值及秘密。 */
public final class AutomaticActionDescription {
    /** Prevents construction. / 禁止实例化。 */
    private AutomaticActionDescription(){}
    /** Describes one existing atomic deployment transaction. / 描述一个既有原子部署事务。
     * @param request immutable validated request / 不可变已校验请求
     * @return nonsecret exact effects and revision bindings / 非秘密精确影响及修订绑定
     */
    public static Map<String,String> deployment(ReviewedDeploymentRequest request){
        return Map.ofEntries(Map.entry("application",request.facts().applicationId()),Map.entry("sourceRevision",request.archive().contentSha256()),
            Map.entry("configurationRevision",Long.toString(request.configuration().revision())),Map.entry("configurationDigest",request.configuration().sha256()),
            Map.entry("requestDigest",AgentAction.digest(request.toString())),Map.entry("projectType",request.facts().projectType().name()),
            Map.entry("runtimeIdentity",request.runtime().identityPolicy().name()),Map.entry("healthCheckType",request.runtime().healthCheck().getClass().getSimpleName()),
            Map.entry("resourceBindings",request.fileBindings().stream().map(b->b.bindingId()+":"+b.location()).toList().toString()),
            Map.entry("databaseCount",Integer.toString(request.databaseBindings().map(List::size).orElse(0))),
            Map.entry("effect","Upload frozen source; create isolated candidate; restricted non-root build; verify health; publish managed release; recover using existing verified transaction. No arbitrary commands."),
            Map.entry("scope","Only the validated application installation, configuration and data bindings; never unowned directories or symlink traversal"));
    }
    /** Describes the existing multi-component transaction without disassembling it. / 描述既有多组件事务，不拆散事务。
     * @param request immutable component plan / 不可变组件计划
     * @return nonsecret component identities and binding / 非秘密组件身份及绑定
     */
    public static Map<String,String> deployment(ReviewedMultiComponentApplication request){
        var result=new LinkedHashMap<String,String>();result.put("effect","Existing ordered component deployment transaction with application health gate and supported recovery");
        result.put("requestDigest",AgentAction.digest(request.toString()));
        result.put("healthOwner",request.applicationHealth().componentId());
        int index=0;for(var component:request.components()){
            String prefix="component"+(++index)+"/";
            deployment(component.request()).forEach((key,value)->result.put(prefix+key,value));
        }
        if(result.size()>128)throw new IllegalArgumentException("component review exceeds supported context");
        return Map.copyOf(result);
    }
}
