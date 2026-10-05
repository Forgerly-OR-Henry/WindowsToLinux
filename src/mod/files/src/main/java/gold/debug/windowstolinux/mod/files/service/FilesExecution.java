package gold.debug.windowstolinux.mod.files.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.action.FileOperation;
import gold.debug.windowstolinux.appapi.file.action.FileReport;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.files.file.model.FilesObservation;
import gold.debug.windowstolinux.mod.files.file.model.FilesOutcome;

/** Keeps actual partial results visible before an uncertain task can finish. / 在不确定任务可结束之前保留实际部分结果。 */
final class FilesExecution {
    /** Original capability and authentic callback. / 原能力及真实回调。 */
    private final FileService file;

    /** Original task, never reused outside this callback. / 原任务，不在此回调外复用。 */
    private final TaskContext task;

    /** Original observation sink. / 原观测接收方。 */
    private final Consumer<FilesObservation> observer;

    /** Actual reports accumulated in execution order. / 按执行顺序累积的实际报告。 */
    private final List<FileReport> reports = new ArrayList<>();

    /** Last successfully read original journal. / 最近成功读取的原日志。 */
    private List<FileOperation> operations = List.of();

    /** Actual evidence-access problem, never converted into empty evidence. / 实际依据访问问题，不转换为空依据。 */
    private Optional<Problem> problem = Optional.empty();

    /** Binds one authentic execution. / 绑定一次真实执行。
     * @param file original capability / 原能力
     * @param task authentic callback / 真实回调
     * @param observer immutable evidence sink / 不可变依据接收方 */
    FilesExecution(FileService file, TaskContext task, Consumer<FilesObservation> observer) {
        this.file = file;
        this.task = task;
        this.observer = observer;
        publish(false);
    }

    /** Preserves an actual returned report and refreshes its original journal. / 保留实际已返回报告并刷新原日志。
     * @param report actual platform report / 实际平台报告
     * @throws ApiException if original journal cannot be observed / 无法观测原日志时 */
    void record(FileReport report) throws ApiException {
        reports.add(report);
        refresh();
        publish(false);
    }

    /** Reads only this authentic original task's durable operations. / 仅读取此真实原任务的持久操作。
     * @throws ApiException if evidence access fails / 依据访问失败时 */
    void refresh() throws ApiException {
        try {
            operations = file.actions().operations(task);
        } catch (ApiException failure) {
            problem = Optional.of(failure.problem());
            publish(false);
            throw failure;
        }
    }

    /** Stops new items after actual failure, cancellation or uncertainty. / 实际失败、取消或不确定后停止新项。
     * @return true only while every actual report succeeded / 仅全部实际报告成功时为真 */
    boolean proceed() {
        return reports.stream().allMatch(report -> report.outcome() == TaskResult.SUCCEEDED);
    }

    /** Retains an actual checked failure after any earlier successful items. / 在先前成功项后保留实际受检失败。
     * @param failure actual problem / 实际问题 */
    void failed(Problem failure) {
        problem = Optional.of(failure);
        try {
            refresh();
        } catch (ApiException inaccessible) {
            problem = Optional.of(FilesProblems.problem(ProblemCode.UNKNOWN, "FILES_EVIDENCE_UNAVAILABLE",
                failure.message() + "; original operation evidence could not be read"));
        }
    }

    /** Returns actual accumulated facts and marks only the callback returned. / 返回实际累积事实，仅标记回调已返回。
     * @return actual business outcome and immutable evidence / 实际业务结果及不可变依据 */
    OperationResult<FilesOutcome> finish() {
        TaskResult result = reports.stream().anyMatch(report -> report.outcome() == TaskResult.UNKNOWN)
            ? TaskResult.UNKNOWN : reports.stream().anyMatch(report -> report.outcome() == TaskResult.CANCELLED)
            ? TaskResult.CANCELLED : reports.stream().anyMatch(report -> report.outcome() == TaskResult.FAILED)
            ? TaskResult.FAILED : TaskResult.SUCCEEDED;
        if (problem.isPresent()) {
            TaskResult failure = FilesProblems.outcome(problem.orElseThrow());
            result = result == TaskResult.UNKNOWN || failure == TaskResult.UNKNOWN ? TaskResult.UNKNOWN : failure;
        }
        if ((result == TaskResult.FAILED || result == TaskResult.UNKNOWN) && problem.isEmpty())
            problem = Optional.of(FilesProblems.problem(result == TaskResult.UNKNOWN ? ProblemCode.UNKNOWN : ProblemCode.IO_FAILURE,
                "FILES_ACTUAL_RESULT", "One or more platform file effects did not succeed; inspect the actual per-item results"));
        publish(true);
        return new OperationResult<>(result, Optional.of(new FilesOutcome(reports, operations)), problem);
    }

    /** Publishes immutable facts independently of final cleanup. / 独立于最终清理发布不可变事实。
     * @param returned actual author callback state / 实际作者回调状态 */
    private void publish(boolean returned) {
        observer.accept(new FilesObservation(new FilesOutcome(reports, operations), problem, returned));
    }
}
