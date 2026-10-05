package gold.debug.windowstolinux.mod.deployment.service.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.recover.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;

/** Returns local position metadata only after the original picker grant closes. / 仅在原选择器授权关闭后返回本地位置元数据。 */
public final class DeploymentSourceSelectionService implements TaskRecovery {
    /** One original host-picker metadata task. / 一项原宿主选择器元数据任务。 */
    public static final String PICKER_TASK = "deployment-source-selection";

    /** Original owner capabilities. / 原所属能力。 */
    private final ModContext context;

    /** Canonical position metadata, never a file grant or frozen snapshot. / 规范位置元数据，不是文件授权或冻结快照。
     * @param location actual local URI / 实际本地URI
     * @param displayName original readable name / 原可读名称 */
    public record LocalLocation(URI location, String displayName) {
        /** Checks explicit local position rather than interpreting a display name. / 校验明确本地位置，不解释展示名称。
         * @param location canonical position / 规范位置
         * @param displayName readable name / 可读名称 */
        public LocalLocation {
            Objects.requireNonNull(displayName);
            if (!"file".equals(Objects.requireNonNull(location).getScheme()) || !Path.of(location).isAbsolute())
                throw new IllegalArgumentException("Local selection requires an absolute file URI");
        }
    }

    /** Binds the common picker to the actual Mod activation. / 将共用选择器绑定实际Mod启用。
     * @param context original context / 原上下文 */
    public DeploymentSourceSelectionService(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Selects or revalidates a user position and closes the short task's actual file resources. / 选择或重验用户位置，并关闭短任务实际文件资源。
     * @param title original host picker title / 原宿主选择器标题
     * @param explicit explicit imported position, absent for host chooser / 明确导入位置，宿主选择时为空
     * @return original small-value task / 原小值任务
     * @throws ApiException if original admission fails / 原准入失败时 */
    public TaskHandle<LocalLocation> select(String title, Optional<Path> explicit) throws ApiException {
        Objects.requireNonNull(explicit);
        return context.tasks().submit(new TaskRequest(PICKER_TASK, title, List.of(), true), task -> {
            try {
                var selected = explicit.isPresent() ? context.file().sources().selectDirectory(explicit.orElseThrow(), task)
                    : context.file().sources().selectDirectory(title, task);
                var location = new LocalLocation(context.file().sources().location(selected, task), selected.displayName());
                if (task.cancelToken().requested())
                    return failure(TaskResult.CANCELLED, "DEPLOYMENT_SOURCE_SELECTION_CANCELLED");
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(location), Optional.empty());
            } catch (ApiException rejected) {
                var code = rejected.problem().code();
                return new OperationResult<>(code == ProblemCode.UNKNOWN ? TaskResult.UNKNOWN
                    : code == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED,
                    Optional.empty(), Optional.of(rejected.problem()));
            } finally {
                context.file().closeTaskResources(task);
            }
        });
    }

    /** Reconciles only the original interrupted picker, without reopening it or minting a location. / 仅核对原中断选择器，不重开或生成位置。
     * @param previous original persisted task / 原持久任务
     * @param task authentic original recovery / 真实原恢复
     * @return confirmed interruption or retained uncertainty / 已确认中断或保留未知
     * @throws ApiException if original cleanup is unconfirmed / 原清理未确认时 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) throws ApiException {
        if (!previous.id().equals(task.id()) || !PICKER_TASK.equals(previous.type()))
            return failure(TaskResult.UNKNOWN, "DEPLOYMENT_SOURCE_RECOVERY_UNKNOWN");
        context.file().closeTaskResources(task);
        return context.tasks().resourcesReleased(task)
            ? failure(TaskResult.CANCELLED, "DEPLOYMENT_SOURCE_TASK_INTERRUPTED")
            : failure(TaskResult.UNKNOWN, "DEPLOYMENT_SOURCE_CLEANUP_UNKNOWN");
    }

    /** Preserves original cleanup uncertainty without returning a replacement grant. / 保留原清理未知，不返回替代授权。
     * @param <T> small result type / 小结果类型
     * @param outcome exact outcome / 精确结果
     * @param event stable diagnostic / 稳定诊断
     * @return original failure / 原失败 */
    private static <T> OperationResult<T> failure(TaskResult outcome, String event) {
        return new OperationResult<>(outcome, Optional.empty(), Optional.of(new Problem(outcome == TaskResult.UNKNOWN
            ? ProblemCode.UNKNOWN : ProblemCode.CANCELLED, event,
            "Original source selection could not complete", Map.of(), Optional.empty())));
    }
}
