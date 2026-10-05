package gold.debug.windowstolinux.mod.deployment.service.feedback;

import java.util.Objects;
import java.util.Optional;
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
