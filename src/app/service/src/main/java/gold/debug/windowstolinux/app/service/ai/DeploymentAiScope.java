package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentAutomationMode;
import java.util.*;

/** Worker-owned immutable routing scope; nested helpers cannot bypass static mode. / 工作线程持有的不可变路由作用域，嵌套辅助不能绕过静态模式。 */
public final class DeploymentAiScope implements AutoCloseable {
    /** Scope belonging to the current deployment worker. / 当前部署工作线程的作用域。 */
    private static final ThreadLocal<DeploymentAiScope> CURRENT=new ThreadLocal<>();
    /** Selected deployment mode. / 所选部署模式。 */
    private final DeploymentAutomationMode mode;
    /** Exact profiles and credential revisions by purpose. / 按用途保存的精确模型及凭据修订。 */
    private Map<AiPurposeType,List<AiProviderProfile>> providers;
    /** Models already consumed by technical failure or explicit handoff. / 已因技术失败或显式交接消耗的模型。 */
    private final Map<AiPurposeType,Set<AiProviderProfile>> consumed=new EnumMap<>(AiPurposeType.class);
    /** Explicit configuration snapshot revision. / 显式配置快照修订。 */
    private long revision=1;
    /** Forward-only model positions for the frozen purpose lists. / 冻结用途列表中仅向后移动的模型位置。 */
    private final Map<AiPurposeType,Integer> positions=new EnumMap<>(AiPurposeType.class);
    /** Actual provider calls including failed requests. / 包括失败请求的实际提供者调用次数。 */
    private long calls;
    /** Sum of provider-reported token counts; unreported calls remain unknown. / 提供者返回 Token 数总和，未返回的调用仍未知。 */
    private long reportedTokens;
    /** Number of calls with reported token usage. / 返回 Token 用量的调用数。 */
    private long measuredCalls;
    /** Metadata-only audit sink. / 仅元数据审计端口。 */
    private java.util.function.Consumer<Map<String,String>> events=event->{};
    /** Worker checkpoint before a new provider request. / 新提供者请求前的工作线程检查点。 */
    private Runnable requestCheckpoint=()->{};
    /** Installs task pause and cancellation without interrupting an executing server transaction. / 安装任务暂停及取消，不打断正在执行的服务器事务。
     * @param checkpoint safe request boundary / 安全请求边界
     */
    public void beforeRequest(Runnable checkpoint){requestCheckpoint=Objects.requireNonNull(checkpoint);}
    /** Stops before selecting the next model when pause or cancel is pending. / 暂停或取消待处理时在选择下一模型前停止。 */
    public void checkpoint(){requestCheckpoint.run();}
    /** Actual human callbacks. / 实际人工回调次数。 */
    private long humanInterventions;
    /** Installs a durable sink before model calls. / 模型调用前安装持久化端口。
     * @param events metadata-only events / 仅元数据事件
     */
    public void audit(java.util.function.Consumer<Map<String,String>> events){this.events=Objects.requireNonNull(events);recordSnapshot();}
    /** Counts an actual human interaction without retaining its answer. / 记录实际人工交互，不保留回答。 */
    public void human(){humanInterventions++;}
    /** Records the actual purpose and profile without prompt or endpoint data. / 记录实际用途及模型身份，不记录提示词或端点。
     * @param purpose route / 路由
     * @param profile model identity / 模型身份
     * @param status protocol outcome / 协议结果
     */
    public void attempted(AiPurposeType purpose,String profile,String status){events.accept(Map.of("type","MODEL_ATTEMPT","action","","detail",purpose.name()+":"+profile+":"+status));}
    /** Returns remaining models without resetting prior failures. / 返回剩余模型，不重置历史失败。
     * @param purpose independent route / 独立路由
     * @return immutable suffix / 不可变后缀
     */
    public List<AiProviderProfile> remaining(AiPurposeType purpose){var all=providers(purpose);return all.subList(Math.min(positions.getOrDefault(purpose,0),all.size()),all.size());}
    /** Advances one purpose without cycling or borrowing models. / 推进一个用途，不循环或借用模型。
     * @param purpose independent route / 独立路由
     * @return whether another model remains / 是否还有后续模型
     */
    public boolean advance(AiPurposeType purpose){var remaining=remaining(purpose);if(!remaining.isEmpty())consumed.computeIfAbsent(purpose,p->new HashSet<>()).add(remaining.getFirst());
        positions.put(purpose,positions.getOrDefault(purpose,0)+1);
        events.accept(Map.of("type","MODEL_HANDOFF","action","","detail",purpose.name()+":"+(remaining.isEmpty()?"exhausted":remaining.getFirst().id())+"->"+(remaining(purpose).isEmpty()?"exhausted":remaining(purpose).getFirst().id())));return !remaining(purpose).isEmpty();}
    /** Counts an attempted provider request. / 记录一次提供者请求尝试。 */
    public void called(){calls++;}
    /** Records usage only when returned by the provider. / 仅在提供者返回用量时记录。
     * @param tokens optional measured tokens / 可选实际 Token 数
     */
    public void usage(OptionalLong tokens){if(tokens.isPresent()){measuredCalls++;reportedTokens=Math.addExact(reportedTokens,tokens.getAsLong());}}
    /** Captures nonsecret routing and usage evidence. / 捕获非秘密路由及用量证据。
     * @return bounded metrics / 有界指标
     */
    public Map<String,String> metrics(){return Map.of("modelCalls",Long.toString(calls),"reportedTokens",Long.toString(reportedTokens),"callsWithTokenUsage",Long.toString(measuredCalls),"humanInterventions",Long.toString(humanInterventions));}
    /** Opening thread, used to prevent accidental cross-worker cleanup. / 打开线程，用于防止跨线程清理。 */
    private final Thread owner=Thread.currentThread();
    /** Opens one non-nested deployment scope. / 打开一个不可嵌套部署作用域。
     * @param mode selected deployment mode / 所选部署模式
     * @param providers frozen purpose profiles / 冻结用途模型
     */
    public DeploymentAiScope(DeploymentAutomationMode mode,Map<AiPurposeType,List<AiProviderProfile>> providers){
        if(CURRENT.get()!=null)throw new IllegalStateException("deployment AI scope already active");
        this.mode=Objects.requireNonNull(mode);var frozen=new EnumMap<AiPurposeType,List<AiProviderProfile>>(AiPurposeType.class);
        for(var purpose:AiPurposeType.values())frozen.put(purpose,List.copyOf(providers.getOrDefault(purpose,List.of())));
        this.providers=Map.copyOf(frozen);CURRENT.set(this);
    }
    /** Captures the active worker scope. / 获取活动工作线程作用域。
     * @return scope if running a deployment / 部署运行时的作用域
     */
    public static Optional<DeploymentAiScope> current(){return Optional.ofNullable(CURRENT.get());}
    /** Reports whether this task permits model requests. / 判断任务是否允许模型请求。
     * @return whether AI is enabled / 是否启用 AI
     */
    public boolean allowsAi(){return mode!=DeploymentAutomationMode.STATIC;}
    /** Returns the immutable purpose list. / 返回不可变用途列表。
     * @param purpose selected purpose / 所选用途
     * @return selected profiles, empty for static mode / 所选模型，静态模式为空
     */
    public List<AiProviderProfile> providers(AiPurposeType purpose){return allowsAi()?providers.get(purpose):List.of();}
    /** Replaces models only on the owning paused task's explicit resume path. / 仅在所属暂停任务显式恢复路径替换模型。
     * @param snapshot newly validated purpose snapshot / 新验证用途快照
     */
    public void replace(Map<AiPurposeType,List<AiProviderProfile>> snapshot){
        if(Thread.currentThread()!=owner)throw new IllegalStateException("wrong model snapshot owner");
        var next=new EnumMap<AiPurposeType,List<AiProviderProfile>>(AiPurposeType.class);
        for(var purpose:AiPurposeType.values())next.put(purpose,snapshot.getOrDefault(purpose,List.of()).stream()
            .filter(profile->!consumed.getOrDefault(purpose,Set.of()).contains(profile)).toList());
        providers=Map.copyOf(next);positions.clear();revision++;recordSnapshot();
    }
    /** Returns a nonsecret binding for all frozen model identities and exact credential references. / 返回全部冻结模型身份及精确凭据引用的非秘密绑定。
     * @return revision and digest / 修订及摘要
     */
    public String binding(){return revision+":"+gold.debug.windowstolinux.shared.model.agent.AgentAction.digest(new TreeMap<>(providers).toString());}
    /** Clears the worker scope. / 清理工作线程作用域。 */
    @Override public void close(){if(Thread.currentThread()!=owner||CURRENT.get()!=this)throw new IllegalStateException("wrong deployment scope owner");CURRENT.remove();}
    /** Records selected model names and configuration identities without endpoints or credentials. / 记录所选模型名称及配置身份，不记录端点或凭据。
     */
    private void recordSnapshot(){
        for(var purpose:AiPurposeType.values()){
            int order=0;for(var profile:providers(purpose))events.accept(Map.of("type","MODEL_SNAPSHOT","action","","detail",revision+":"+purpose.name()+":"+(++order)+":"+profile.id()+":"+profile.model()+":"
                +gold.debug.windowstolinux.shared.model.agent.AgentAction.digest(profile.toString())));
        }
    }
}
