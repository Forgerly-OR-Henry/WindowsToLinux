package gold.debug.windowstolinux.app.linux.ssh.recover.ai;

import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.app.linux.ssh.recover.ai.RecoveryModels;
import gold.debug.windowstolinux.app.linux.ssh.recover.ai.RecoveryProtocol;
import gold.debug.windowstolinux.app.linux.ssh.recover.model.TerminalObservation;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiMessage;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiRole;
import gold.debug.windowstolinux.appapi.ai.vision.AiImage;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Uses the original platform task and configured model budgets for rescue advice. / 使用原平台任务及已配置模型预算提供救援建议。 */
public final class PlatformRecoveryModels implements RecoveryModels {
    /** Frozen timeout and token limits for rescue requests. / 救援请求的冻结超时及 token 上限。 */
    private final gold.debug.windowstolinux.app.config.AppConfig.Budget budget;

    /** Task-bound call supplied by the composition root. / 由装配根提供的任务绑定调用。 */
    @FunctionalInterface
    public interface Call {
        /** Calls one bounded original request. / 调用一个有界原请求。
         * @param request original request / 原请求
         * @param task original task / 原任务
         * @return original outcome / 原结果
         * @throws ApiException if authority or cleanup fails / 授权或清理失败时 */
        OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException;
    }

    /** Actual task-bound model capability. / 实际任务绑定模型能力。 */
    private final Call ai;

    /** Original admitted rescue task, never replaced by a second task. / 原已接纳救援任务，不替换为第二个任务。 */
    private final TaskContext task;

    /** Captures the real model capability and original task. / 捕获真实模型能力及原任务。
     * @param ai actual platform model service / 实际平台模型服务
     * @param task original rescue task / 原救援任务 */
    public PlatformRecoveryModels(Call ai, TaskContext task) {
        this(ai, task, gold.debug.windowstolinux.app.config.AppConfig.defaultsConfig().ai().recovery());
    }

    /** Captures the startup configuration. / 捕获启动配置。
     * @param ai supplied startup dependency / 启动依赖
     * @param task supplied startup dependency / 启动依赖
     * @param budget supplied startup dependency / 启动依赖
     */
    public PlatformRecoveryModels(Call ai, TaskContext task, gold.debug.windowstolinux.app.config.AppConfig.Budget budget) {
        this.budget = Objects.requireNonNull(budget);
        this.ai = Objects.requireNonNull(ai);
        this.task = Objects.requireNonNull(task);
    }

    /** Reads text locally or invokes the configured visual model group. / 本地读取文字或调用已配置视觉模型组。
     * @param observation selected terminal evidence / 所选终端证据
     * @return transient strictly validated observation / 临时严格校验观测
     * @throws ApiException if task authority, cancellation or model uncertainty prevents continuation / 任务授权、取消或模型不确定性阻止继续时 */
    @Override
    public Result observe(TerminalObservation observation) throws ApiException {
        Objects.requireNonNull(observation);
        if (!observation.text().isBlank())
            return new Result("ok", observation.text(), Map.of());
        if (observation.image().length == 0)
            return new Result("visionUnsupported", "", Map.of());
        return call(AiPurpose.VISION, RecoveryProtocol.OBSERVATION_INSTRUCTION, new AiMessage(AiRole.USER,
            RecoveryProtocol.OBSERVATION_REQUEST, List.of(new AiImage("image/png", observation.image()))));
    }

    /** Requests a separately approved proposal from the ordinary language group. / 从常规语言模型组请求须另行批准的建议。
     * @param observation original bounded untrusted facts / 原有界不可信事实
     * @return transient strictly validated proposal / 临时严格校验建议
     * @throws ApiException if task authority, cancellation or model uncertainty prevents continuation / 任务授权、取消或模型不确定性阻止继续时 */
    @Override
    public Result decide(String observation) throws ApiException {
        if (Objects.requireNonNull(observation).length() > 40000)
            return new Result("modelInvalid", "", Map.of());
        return call(AiPurpose.LANGUAGE, RecoveryProtocol.ACTION_INSTRUCTION,
            new AiMessage(AiRole.USER, observation, List.of()));
    }

    /** Reuses actual platform routing, credentials, HTTP ownership and uncertain-effect handling. / 复用实际平台路由、凭据、HTTP 归属及不确定副作用处理。
     * @param purpose original model group / 原模型组
     * @param instruction fixed rescue instruction / 固定救援指令
     * @param evidence selected transient evidence / 所选临时证据
     * @return safe classification and optional transient content / 安全分类及可选临时内容
     * @throws ApiException if the original task cannot safely continue / 原任务不能安全继续时 */
    private Result call(AiPurpose purpose, String instruction, AiMessage evidence) throws ApiException {
        var request = new AiRequest(purpose, List.of(new AiMessage(AiRole.SYSTEM, instruction, List.of()), evidence),
            budget.maxTokens(), Duration.ofSeconds(budget.timeoutSeconds()), true);
        OperationResult<AiResponse> result;
        try {
            result = ai.call(request, task);
        } catch (ApiException failure) {
            var problem = failure.problem();
            if (problem.code() != ProblemCode.UNAVAILABLE || !problem.eventCode().equals("AI_PURPOSE_UNAVAILABLE"))
                throw failure;
            return new Result("modelUnavailable", "", Map.of("platform", problem.eventCode()));
        }
        if (result.outcome() == TaskResult.UNKNOWN || result.outcome() == TaskResult.CANCELLED)
            throw new ApiException(result.problem()
                .orElseGet(() -> new Problem(
                    result.outcome() == TaskResult.UNKNOWN ? ProblemCode.UNKNOWN : ProblemCode.CANCELLED,
                    "RESCUE_MODEL_INCOMPLETE", "Rescue model invocation did not complete", Map.of(),
                    Optional.empty())));
        if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty())
            return new Result("modelUnavailable", "",
                result.problem().map(problem -> Map.of("platform", problem.eventCode())).orElse(Map.of()));
        var response = result.value().orElseThrow();
        try {
            if (purpose == AiPurpose.VISION)
                RecoveryProtocol.parseObservation(response.text());
            else
                RecoveryProtocol.parseAction(response.text());
            return new Result("ok", response.text(), Map.of(response.modelId(), "VALID"));
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            return new Result("modelInvalid", "", Map.of(response.modelId(), "INVALID"));
        }
    }
}
