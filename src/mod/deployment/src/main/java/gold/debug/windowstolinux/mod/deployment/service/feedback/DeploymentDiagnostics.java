package gold.debug.windowstolinux.mod.deployment.service.feedback;

import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.log.LogCategory;
import gold.debug.windowstolinux.appapi.task.log.LogEvent;
import gold.debug.windowstolinux.appapi.task.log.LogLevel;
import gold.debug.windowstolinux.appapi.task.log.LogReadResult;
import gold.debug.windowstolinux.appapi.task.log.LogTarget;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Uses the existing owned diagnostic category for actual task stages and results. / 使用既有所属诊断类别记录实际任务阶段及结果。 */
public final class DeploymentDiagnostics {
    /** Original activation, including its opaque logger authority. / 原启用及其不透明日志授权。 */
    private final ModContext context;

    /** Retains capabilities without performing storage on the EDT. / 保留能力，不在EDT执行存储。
     * @param context original activation / 原启用 */
    public DeploymentDiagnostics(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Resolves fixed workflow copy in the original host language without changing protocol identities. / 按原宿主语言解析固定流程文案，不改变协议身份。
     * @param context original activation / 原启用
     * @param key owned workflow message key / 所属流程文案键
     * @param values actual displayed facts / 实际展示事实
     * @return localized fixed workflow copy / 本地化固定流程文案 */
    public static String text(ModContext context, String key, Object... values) {
        var locale = context.ui().environment().locale();
        var messages = ResourceBundle.getBundle(
            "gold.debug.windowstolinux.mod.deployment.service.feedback.workflow-messages", locale,
            DeploymentDiagnostics.class.getClassLoader(),
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        return new java.text.MessageFormat(messages.getString(key), locale).format(values);
    }

    /** Publishes a real stage, keeping diagnostic storage independent of business success. / 发布真实阶段，诊断存储独立于业务成功。
     * @param context original activation / 原启用
     * @param task original admitted task / 原准入任务
     * @param stage fixed non-secret stage text, never process output / 固定非秘密阶段文字，不能是进程输出
     * @throws ApiException if authoritative task progress cannot be saved / 权威任务进度无法保存时 */
    public static void progress(ModContext context, TaskContext task, String stage) throws ApiException {
        Objects.requireNonNull(stage);
        task.progress(stage);
        new DeploymentDiagnostics(context).event(task.id(), LogLevel.INFO, "DEPLOYMENT_STAGE", stage, Optional.empty());
    }

    /** Reads one actual task's retained redacted diagnostic window on a worker. / 在工作线程读取一项实际任务的保留脱敏诊断窗口。
     * @param taskId selected original task / 所选原任务
     * @return actual bounded records and storage issues / 实际有界记录及存储问题
     * @throws ApiException if original access fails / 原访问失败时 */
    public LogReadResult read(UUID taskId) throws ApiException {
        return context.logs().read(target(), Optional.of(Objects.requireNonNull(taskId)), 100);
    }

    /** Records an observed completion without storing result payloads or problem details. / 记录已观测完成，不存结果载荷或问题详情。
     * @param taskId exact completed task / 精确已完成任务
     * @param result actual completion after host cleanup / 宿主收尾后的实际完成 */
    public void completed(UUID taskId, OperationResult<?> result) {
        if (result == null) return;
        String code = result.problem().map(value -> value.eventCode()).orElse("DEPLOYMENT_RESULT");
        event(taskId, result.outcome() == TaskResult.SUCCEEDED ? LogLevel.INFO : LogLevel.ERROR,
            "DEPLOYMENT_RESULT", code, Optional.of(result.outcome()));
    }

    /** Records actual uncertain or unsuccessful author effects without claiming host cleanup is complete. / 记录实际未知或未成功作者效果，不声称宿主收尾完成。
     * @param taskId original effect task / 原效果任务
     * @param result actual observed author result / 实际观测作者结果 */
    public void effectResult(UUID taskId, OperationResult<?> result) {
        if (result.outcome() == TaskResult.SUCCEEDED) return;
        event(taskId, LogLevel.ERROR, "DEPLOYMENT_EFFECT_OUTCOME",
            result.problem().map(value -> value.eventCode()).orElse("DEPLOYMENT_EFFECT_UNCONFIRMED"),
            Optional.of(result.outcome()));
    }

    /** Records a bounded tool identity while raw observations remain in the existing live view only. / 记录有界工具身份，原观察正文仍仅在既有实时视图。
     * @param context original activation / 原启用
     * @param task original agent task / 原自主任务
     * @param tool bounded original tool identity / 有界原工具身份 */
    public static void agentObservation(ModContext context, TaskContext task, String tool) {
        if (tool == null || !tool.matches("[A-Za-z0-9_.:-]{1,80}")) return;
        new DeploymentDiagnostics(context).event(task.id(), LogLevel.INFO, "DEPLOYMENT_AGENT_OBSERVATION",
            tool, Optional.empty());
    }

    /** Retains a rejected call before another model request or terminal failure can hide it. / 在下一模型请求或终止之前保留被拒调用。
     * @param context original activation / 原启用
     * @param task original task / 原任务
     * @param call original provider identity, never its arguments / 原提供方身份，不记录参数
     * @param result actual rejected result / 实际拒绝结果 */
    public static void agentToolRejected(ModContext context, TaskContext task,
                                         gold.debug.windowstolinux.appapi.ai.model.AiToolCall call,
                                         gold.debug.windowstolinux.appapi.ai.model.AiToolResult result) {
        if (!result.error() || !call.id().equals(result.callId())) return;
        new DeploymentDiagnostics(context).event(task.id(), LogLevel.WARN, "DEPLOYMENT_AGENT_TOOL_REJECTED",
            "call=" + call.id() + " tool=" + call.name() + " " + agentReason(result.text()), Optional.empty());
    }

    /** Records a fixed session stop category without storing model or command bodies. / 记录固定会话停止类别，不保存模型或命令正文。
     * @param context original activation / 原启用
     * @param task original task / 原任务
     * @param reason fixed stop identity / 固定停止身份 */
    public static void agentStopped(ModContext context, TaskContext task, String reason) {
        if (!java.util.Set.of("NO_NATIVE_CALLS", "NO_PROGRESS", "BUDGET_EXHAUSTED", "OUTPUT_BUDGET_EXHAUSTED", "REPEATED_EVIDENCE", "REPEATED_PROPOSAL").contains(reason))
            return;
        new DeploymentDiagnostics(context).event(task.id(), LogLevel.ERROR, "DEPLOYMENT_AGENT_SESSION_STOPPED",
            reason, Optional.empty());
    }

    /** Keeps the caught failure visible even when the public outcome uses a generic stop problem. / 即使公共结果使用通用停止问题也保留捕获失败。
     * @param context original activation / 原启用
     * @param task original task / 原任务
     * @param failure actual caught exception / 实际捕获异常 */
    public static void agentFailure(ModContext context, TaskContext task, Exception failure) {
        String reason = String.valueOf(failure.getMessage());
        if (failure instanceof gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException capability)
            reason += capability.problem().map(problem -> " [" + problem.code() + "/" + problem.eventCode() + "]").orElse("");
        if (failure instanceof ApiException api) {
            reason += " [" + api.problem().code() + "/" + api.problem().eventCode() + "]";
            if (api.problem().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_DENIED"))
                reason += " " + api.problem().context().getOrDefault("reviewReason", "");
            if (api.problem().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_INVALID"))
                reason = "Complete independent command review was invalid [" + api.problem().code()
                    + "/DEPLOYMENT_COMMAND_REVIEW_INVALID]" + reviewValidationDetails(api.problem().context());
        }
        new DeploymentDiagnostics(context).event(task.id(), LogLevel.ERROR, "DEPLOYMENT_AGENT_FAILURE",
            failure.getClass().getSimpleName() + ": " + agentReason(reason), Optional.empty());
        if (failure instanceof ApiException api && api.problem().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_DENIED")) {
            String review = api.problem().context().get("reviewReason");
            if (review != null && !review.isBlank())
                new DeploymentDiagnostics(context).event(task.id(), LogLevel.WARN, "DEPLOYMENT_AGENT_COMMAND_REVIEW_DENIED",
                    publicReviewReason(review), Optional.empty());
        }
    }

    /** Preserves public independent command refusal in every deployment mode without changing its decision. / 各部署模式保留公开命令拒绝原因，不改变决定。 */
    public static void commandReviewDenied(ModContext context, TaskContext task, String reason) {
        new DeploymentDiagnostics(context).event(task.id(), LogLevel.WARN, "DEPLOYMENT_COMMAND_REVIEW_DENIED",
            publicReviewReason(reason), Optional.empty());
    }

    /** Retains fixed-effect refusal without changing its decision or exposing protected data. */
    public static void assistedReviewDenied(ModContext context, TaskContext task, String reason) {
        new DeploymentDiagnostics(context).event(task.id(), LogLevel.WARN, "DEPLOYMENT_ASSISTED_REVIEW_DENIED",
            publicReviewReason(reason), Optional.empty());
    }

    /** Retains the public cause before a graph conservatively becomes UNKNOWN. / 整图保守转为UNKNOWN前保留公开原因。 */
    public static void operationFailure(ModContext context, TaskContext task, Throwable failure) {
        String reason = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
        if (failure instanceof ApiException api)
            reason += " [" + api.problem().code() + "/" + api.problem().eventCode() + "]";
        if (failure instanceof gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException capability)
            reason += capability.problem().map(problem -> " [" + problem.code() + "/" + problem.eventCode() + "]").orElse("");
        new DeploymentDiagnostics(context).event(task.id(), LogLevel.ERROR, "DEPLOYMENT_OPERATION_FAILURE",
            agentReason(reason), Optional.empty());
    }

    /** Keeps the actual independent denial bounded and redacted without granting permission. */
    public static void agentReviewDenied(ModContext context, TaskContext task, String type, String action, String reason) {
        if (!java.util.Set.of("COMMAND", "PATCH").contains(type) || !action.matches("[0-9a-f]{64}")) return;
        new DeploymentDiagnostics(context).event(task.id(), LogLevel.WARN, "DEPLOYMENT_AGENT_REVIEW_DENIED",
            "type=" + type + " action=" + action + " " + agentReason(reason), Optional.empty());
    }

    private static String agentReason(String text) {
        String safe = text.replaceAll(
            "(?im)^.*(?:password|passwd|secret|token|api[_-]?key|private[_-]?key).*[:=].*$",
            "[credential content excluded]").replaceAll("[\\p{Cntrl}]", " ");
        return safe.substring(0, Math.min(512, safe.length()));
    }

    private static String publicReviewReason(String text) {
        String safe = text.replaceAll("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?(?:-----END [^-]*PRIVATE KEY-----|$)",
                "[credential content excluded]").replaceAll("(?i)\\bBearer\\s+[^\\s,;]+", "Bearer [credential content excluded]")
            .replaceAll("(?im)^.*(?:password|passwd|secret|token|api[_-]?key|private[_-]?key).*[:=].*$",
                "[credential content excluded]").replaceAll("[\\p{Cntrl}]", " ");
        return safe.substring(0, Math.min(4096, safe.length()));
    }

    /** Projects only fixed validation shapes and counts, never submitted field values. / 仅投影固定校验形态和计数，不记录提交的字段值。 */
    private static String reviewValidationDetails(java.util.Map<String, String> details) {
        var values = new java.util.ArrayList<String>();
        String stage = details.get("validationStage");
        if (java.util.Set.of("evidence", "response", "rememberAuthorization").contains(stage == null ? "" : stage))
            values.add("validationStage=" + stage);
        var review = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel.safeFailureDiagnostics(details);
        for (String field : java.util.List.of("reviewStage", "reviewCode", "reviewTool", "reviewFailureType"))
            if (review.containsKey(field)) values.add(field + "=" + review.get(field));
        String exact = details.get("fieldsExact");
        if ("true".equals(exact) || "false".equals(exact)) values.add("fieldsExact=" + exact);
        for (String field : java.util.List.of("decision", "requiresUserConfirmation", "reason")) {
            String form = details.get(field + "Form");
            if (java.util.Set.of("MISSING", "ARRAY", "BINARY", "BOOLEAN", "NULL", "NUMBER", "OBJECT", "POJO", "STRING")
                .contains(form == null ? "" : form)) values.add(field + "Form=" + form);
            String size = details.get(field + "Characters");
            if (size != null && size.matches("[0-9]{1,10}")) values.add(field + "Characters=" + size);
        }
        return values.isEmpty() ? "" : " " + String.join(" ", values);
    }

    /** Writes bounded stage identifiers without changing authoritative task results. / 写入有界阶段标识，不改变权威任务结果。
     * @param taskId original association / 原关联
     * @param level actual severity / 实际严重度
     * @param code fixed diagnostic identity / 固定诊断身份
     * @param message non-secret stage or event identifier / 非秘密阶段或事件标识
     * @param outcome actual optional outcome / 实际可选结果 */
    private void event(UUID taskId, LogLevel level, String code, String message, Optional<TaskResult> outcome) {
        try {
            context.logs().write(target(), new LogEvent(level, code, message, Optional.empty(),
                Optional.of(taskId), Optional.of(taskId), outcome));
        } catch (ApiException unavailable) {
            // The existing logger reports its storage issue; diagnostics do not alter the business result.
        }
    }

    /** Resolves the same original target already registered by the Mod. / 解析Mod已登记的同一原目标。
     * @return original opaque target / 原不透明目标
     * @throws ApiException if registration is unavailable / 登记不可用时 */
    private LogTarget target() throws ApiException {
        return context.logs().register(new LogCategory("deployment", "Deployment", Optional.of("deployment_log")));
    }
}
