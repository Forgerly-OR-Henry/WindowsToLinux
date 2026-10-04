package gold.debug.windowstolinux.appapi.ai;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;

import gold.debug.windowstolinux.appapi.ai.voice.VoiceListener;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceOptions;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceSession;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/**
 * Caller-bound model access with task authorization and budgets. / 绑定调用方并校验任务授权及预算的模型能力。
 */
public interface AiService {

    /** Transcribes a complete validated WAV through the configured voice-purpose route. / 经已配置语音用途路由转写完整已校验 WAV。
     * @param request complete bounded audio / 完整有界音频
     * @param task authentic executing task / 真实执行任务
     * @return actual provider result / 实际提供方结果
     * @throws ApiException if authorization, route or cleanup fails / 授权、路由或清理失败时 */
    OperationResult<VoiceResponse> call(VoiceRequest request, TaskContext task) throws ApiException;

    /** Uses only a previously frozen routing revision for file transcription. / 文件转写仅使用此前冻结路由修订。
     * @param request complete bounded audio / 完整有界音频
     * @param expectedRevision frozen configuration digest / 冻结配置摘要
     * @param task authentic task / 真实任务
     * @return actual provider result / 实际提供方结果
     * @throws ApiException if revision, authority or cleanup fails / 修订、授权或清理失败时 */
    OperationResult<VoiceResponse> call(VoiceRequest request, String expectedRevision, TaskContext task) throws ApiException;

    /** Opens actual audio input streaming and freezes its selected model and configuration. / 打开实际音频输入流并冻结所选模型和配置。
     * @param options finite session and buffering limits / 有限会话及缓冲限制
     * @param listener serialized transcript observations / 串行转写观测
     * @param task original task, whose callback must stay active until completion / 原任务，其回调须保持活动直至完成
     * @return owned session; caller must finish or cancel and observe completion before returning from the task / 所属会话；调用方须在任务返回前结束或取消并观察完成
     * @throws ApiException if route, authorization or opening fails / 路由、授权或打开失败时 */
    VoiceSession openVoice(VoiceOptions options, VoiceListener listener, TaskContext task) throws ApiException;

    /** Returns an opaque revision of all configured purpose routes before a recoverable AI decision. / 可恢复 AI 决策前返回全部已配置用途路由的不透明修订。
     * @param purpose purpose that must currently have a usable model / 当前必须有可用模型的用途
     * @param task authentic original task / 真实原任务
     * @return exact non-secret routing revision / 精确不含秘密的路由修订
     * @throws ApiException if authorization or configuration is unavailable / 授权或配置不可用时 */
    String configurationRevision(AiPurpose purpose, TaskContext task) throws ApiException;

    /**
     * Performs task-bound synchronous work with validated budgets and ownership. / 校验预算及归属后执行绑定任务的同步工作。
     * @param request immutable capability request / 不可变的能力请求
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException;

    /** Calls only the frozen routing revision, retaining its exact selected candidate list. / 仅按冻结路由修订调用并保持其精确候选列表。
     * @param request bounded model request / 有界模型请求
     * @param expectedRevision previously frozen opaque revision / 此前冻结的不透明修订
     * @param task authentic original task / 真实原任务
     * @return actual result or classified refusal / 实际结果或已分类拒绝
     * @throws ApiException if authorization or configuration cannot be verified / 授权或配置无法核实时 */
    OperationResult<AiResponse> call(AiRequest request, String expectedRevision, TaskContext task) throws ApiException;
}
