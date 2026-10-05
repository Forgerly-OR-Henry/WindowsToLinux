package gold.debug.windowstolinux.mod.files.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.recover.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.files.file.model.FilesObservation;
import gold.debug.windowstolinux.mod.files.file.model.FilesOutcome;

/** Reconciles the authentic original task without repeating its file operations. / 核对真实原任务，不重复文件操作。 */
public final class FilesRecovery implements TaskRecovery {
    /** Original activation capabilities. / 原启用能力。 */
    private final ModContext context;

    /** Original actual-observation sink. / 原实际观测接收方。 */
    private final BiConsumer<UUID, FilesObservation> observer;

    /** Binds original capabilities and observation ownership. / 绑定原能力及观测归属。
     * @param context original Mod context / 原Mod上下文
     * @param observer original observation sink / 原观测接收方 */
    FilesRecovery(ModContext context, BiConsumer<UUID, FilesObservation> observer) {
        this.context = context;
        this.observer = observer;
    }

    /** Lists every submitted task type requiring original recovery registration. / 列出每个需原恢复登记的提交任务类型。
     * @return exact owner-local type list / 精确所有者内部类型列表 */
    public static List<String> taskTypes() {
        return List.of("files-servers", "files-select", "files-browse", "files-mutation", "files-continue");
    }

    /** Checks journals and required cleanup, preserving unproved effects. / 核对日志及必要清理，保留未证实副作用。
     * @param previous original retained state / 原保留状态
     * @param originalTask authentic original recovery callback / 真实原恢复回调
     * @return confirmed aggregate or explicit UNKNOWN / 已确认汇总或明确UNKNOWN
     * @throws Exception if original evidence access or cleanup is unavailable / 原依据访问或清理不可用时 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext originalTask) throws Exception {
        if (!taskTypes().contains(previous.type()) || !previous.id().equals(originalTask.id()))
            throw FilesProblems.error(ProblemCode.ACCESS_DENIED, "FILES_RECOVERY_IDENTITY", "Original file task identity differs");
        var file = context.file();
        var checkpoint = context.tasks().findCheckpoint(originalTask, FilesPlan.KEY);
        var operations = file.actions().operations(originalTask);
        var reports = new ArrayList<gold.debug.windowstolinux.appapi.file.action.FileReport>();
        for (var operation : operations) {
            if (operation.type().equals("REGISTER_LINK") || operation.type().equals("UNREGISTER_LINK")) continue;
            reports.add(file.actions().checkOperation(operation.id(), originalTask));
        }
        TaskResult result = TaskResult.CANCELLED;
        Optional<Problem> problem = Optional.empty();
        if (previous.type().equals("files-mutation") || previous.type().equals("files-continue")) {
            result = reports.stream().anyMatch(report -> report.outcome() == TaskResult.UNKNOWN) ? TaskResult.UNKNOWN
                : reports.stream().anyMatch(report -> report.outcome() == TaskResult.FAILED) ? TaskResult.FAILED : TaskResult.SUCCEEDED;
            if (checkpoint.isEmpty()) result = TaskResult.FAILED;
            else {
                var plan = FilesPlan.read(checkpoint.orElseThrow().snapshot().payload());
                long effects = operations.stream().filter(operation -> !operation.type().equals("REGISTER_LINK")
                    && !operation.type().equals("UNREGISTER_LINK") && !operation.type().equals("DELETE_WAIT")).count();
                if (result == TaskResult.SUCCEEDED && effects != plan.items()) result = TaskResult.FAILED;
                if (plan.returned() && plan.outcome() != TaskResult.SUCCEEDED && plan.outcome() != TaskResult.UNKNOWN && result != TaskResult.UNKNOWN)
                    result = plan.outcome();
            }
        }
        if (checkpoint.isPresent() && !checkpoint.orElseThrow().snapshot().released()) checkpoint.orElseThrow().close();
        file.closeTaskResources(originalTask);
        if (!context.tasks().resourcesReleased(originalTask)) result = TaskResult.UNKNOWN;
        if (result == TaskResult.FAILED || result == TaskResult.UNKNOWN)
            problem = Optional.of(FilesProblems.problem(result == TaskResult.UNKNOWN ? ProblemCode.UNKNOWN : ProblemCode.IO_FAILURE,
                "FILES_ORIGINAL_RECONCILIATION", result == TaskResult.UNKNOWN
                    ? "Original file effects or resource cleanup remain unproved; no operation was replayed"
                    : "Original requested file batch was not completely successful; confirmed effects are preserved"));
        operations = file.actions().operations(originalTask);
        observer.accept(originalTask.id(), new FilesObservation(new FilesOutcome(reports, operations), problem, true));
        return new OperationResult<>(result, Optional.empty(), problem);
    }
}
