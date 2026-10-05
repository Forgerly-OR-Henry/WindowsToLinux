package gold.debug.windowstolinux.mod.files.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.action.FileActions;
import gold.debug.windowstolinux.appapi.file.action.FileReport;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.files.file.model.FilesClipboard;
import gold.debug.windowstolinux.mod.files.file.model.FilesObservation;
import gold.debug.windowstolinux.mod.files.file.model.FilesOutcome;

/** Executes existing File and Link actions only in the authentic callback. / 仅在真实回调执行既有File及Link操作。 */
final class FilesMutation {
    /** Original activation-bound public capability. / 原启用绑定公开能力。 */
    private final FileService file;

    /** Captures the original activation capability. / 捕获原启用能力。
     * @param file original file capability / 原文件能力 */
    FilesMutation(FileService file) {
        this.file = file;
    }

    /** Publishes partial facts even when the final task remains unknown. / 即使最终任务仍不确定也发布部分事实。
     * @param task authentic callback / 真实回调
     * @param observer actual observation sink / 实际观测接收方
     * @param work actual platform operation sequence / 实际平台操作序列
     * @return actual report aggregate / 实际报告汇总 */
    OperationResult<FilesOutcome> run(TaskContext task, Consumer<FilesObservation> observer, Work work) {
        var execution = new FilesExecution(file, task, observer);
        try {
            work.run(execution, task);
            execution.refresh();
        } catch (ApiException failure) {
            execution.failed(failure.problem());
        } catch (RuntimeException unexpected) {
            execution.failed(FilesProblems.problem(ProblemCode.UNKNOWN, "FILES_CALLBACK_UNCERTAIN",
                "File callback could not confirm its final effects; review the original task"));
        }
        return execution.finish();
    }

    /** Creates only a directory or an ordinary empty file. / 仅创建目录或普通空文件。
     * @param directory original browsing position / 原浏览位置
     * @param name exact user-entered complete name / 用户输入的精确完整名称
     * @param folder whether to create a directory / 是否创建目录
     * @param execution current facts / 当前事实
     * @param task authentic callback / 真实回调
     * @throws ApiException if actual authorization or effect fails / 实际授权或副作用失败时 */
    void create(FilePath directory, String name, boolean folder, FilesExecution execution, TaskContext task) throws ApiException {
        check(task);
        directory(directory, task);
        var target = directory.child(name);
        execution.record(folder ? file.actions().createDirectory(target, task) : file.actions().createFile(target, task));
    }

    /** Renames the exact selected object using its own semantics. / 按自身语义重命名精确选中对象。
     * @param expected original observation / 原观测
     * @param name complete new name / 完整新名称
     * @param execution current facts / 当前事实
     * @param task authentic callback / 真实回调
     * @throws ApiException if observation or actual effect differs / 观测或实际副作用不符时 */
    void rename(FileInfo expected, String name, FilesExecution execution, TaskContext task) throws ApiException {
        observed(expected, task);
        execution.record(expected.kind() == FileInfo.Kind.LINK
            ? file.actions().renameLink(expected.requested(), name, task)
            : file.actions().renameFile(expected.requested(), name, task));
    }

    /** Deletes selected objects through actual platform confirmation and recycle rules. / 经实际平台确认及回收规则删除选中对象。
     * @param expected original observations / 原观测
     * @param execution current facts / 当前事实
     * @param task authentic callback / 真实回调
     * @throws ApiException if validation, authorization or effect fails / 核验、授权或副作用失败时 */
    void delete(List<FileInfo> expected, FilesExecution execution, TaskContext task) throws ApiException {
        for (var source : expected) observed(source, task);
        for (var source : expected) {
            if (!execution.proceed()) break;
            observed(source, task);
            task.progress("Deleting " + source.requested().name());
            execution.record(source.kind() == FileInfo.Kind.LINK
                ? file.actions().deleteLink(source.requested(), task)
                : file.actions().deleteFile(source.requested(), task));
        }
    }

    /** Shares one move/copy sequence for clipboard and both drag directions. / 剪贴及双向拖拽共用一个移动复制序列。
     * @param clipboard explicit intent and original sources / 明确意图及原来源
     * @param directory original target directory / 原目标目录
     * @param includedLinks explicitly included observations / 明确纳入的观测
     * @param execution current facts / 当前事实
     * @param task authentic callback / 真实回调
     * @throws ApiException if preflight or actual platform effects fail / 前置核验或实际平台副作用失败时 */
    void transfer(FilesClipboard clipboard, FilePath directory, List<FileInfo> includedLinks,
                  FilesExecution execution, TaskContext task) throws ApiException {
        directory(directory, task);
        for (var source : clipboard.sources()) {
            observed(source, task);
            if (source.kind() == FileInfo.Kind.LINK && !source.actual().server().equals(directory.server()))
                throw FilesProblems.error(ProblemCode.INCOMPATIBLE, "FILES_LINK_CROSS_HOST",
                    "The Link itself cannot retain its original target on a different host");
        }
        for (var link : includedLinks) {
            if (link.kind() != FileInfo.Kind.LINK)
                throw FilesProblems.error(ProblemCode.INVALID_INPUT, "FILES_LINK_SELECTION", "Only explicitly observed links can be included");
            observed(link, task);
        }
        for (var link : includedLinks) {
            check(task);
            file.actions().registerLink(link.requested(), task);
            execution.refresh();
        }
        for (var source : clipboard.sources()) {
            if (!execution.proceed()) break;
            observed(source, task);
            task.progress((clipboard.mode() == FilesClipboard.Mode.MOVE ? "Moving " : "Copying ") + source.requested().name());
            execution.record(transferOne(source, directory.child(source.requested().name()), clipboard.mode(), task));
        }
    }

    /** Dispatches only the corresponding public File or Link action. / 仅分派对应公开File或Link操作。
     * @param source original source / 原来源
     * @param target exact requested target / 精确请求目标
     * @param mode explicit intent / 明确意图
     * @param task authentic callback / 真实回调
     * @return actual platform report / 实际平台报告
     * @throws ApiException if authorization or effect fails / 授权或副作用失败时 */
    private FileReport transferOne(FileInfo source, FilePath target, FilesClipboard.Mode mode, TaskContext task) throws ApiException {
        boolean move = mode == FilesClipboard.Mode.MOVE;
        if (source.kind() == FileInfo.Kind.LINK)
            return move ? file.actions().moveLink(source.requested(), target, task) : file.actions().copyLink(source.requested(), target, task);
        return move ? file.actions().moveFile(source.requested(), target, task) : file.actions().copyFile(source.requested(), target, task);
    }

    /** Continues only original unfinished link maintenance under new approval. / 仅在新审批下续作原未完成链接维护。
     * @param operation original move identity / 原移动身份
     * @param execution current facts / 当前事实
     * @param task new authentic ordinary callback / 新真实普通回调
     * @throws ApiException if original evidence or fresh authority fails / 原依据或新授权失败时 */
    void continueLinks(UUID operation, FilesExecution execution, TaskContext task) throws ApiException {
        check(task);
        execution.record(file.actions().continueOperation(operation, task));
    }

    /** Rejects stale identities, kinds, link targets and resolution chains. / 拒绝过期身份、类型、链接指向及解析链。
     * @param expected original user-selected observation / 原用户选中观测
     * @param task authentic callback / 真实回调
     * @return freshly observed same object / 新观测的相同对象
     * @throws ApiException if source changed or is unsupported / 来源变化或不受支持时 */
    private FileInfo observed(FileInfo expected, TaskContext task) throws ApiException {
        check(task);
        if (expected.kind() == FileInfo.Kind.OTHER)
            throw FilesProblems.error(ProblemCode.INCOMPATIBLE, "FILES_UNSUPPORTED_OBJECT", "This filesystem object is not supported");
        var actual = expected.kind() == FileInfo.Kind.LINK
            ? file.queries().readLink(expected.requested(), task) : file.queries().getFile(expected.requested(), task);
        if (!FileActions.stamp(expected).equals(FileActions.stamp(actual)))
            throw FilesProblems.error(ProblemCode.ACCESS_DENIED, "FILES_SOURCE_CHANGED",
                "The selected object's identity, kind, target or resolution chain changed; select it again");
        return actual;
    }

    /** Validates a target directory without obtaining reusable permission. / 校验目标目录，不取得可复用权限。
     * @param directory original target position / 原目标位置
     * @param task authentic callback / 真实回调
     * @throws ApiException if the actual object is not a directory / 实际对象不为目录时 */
    private void directory(FilePath directory, TaskContext task) throws ApiException {
        check(task);
        if (file.queries().getFile(directory, task).kind() != FileInfo.Kind.DIRECTORY)
            throw FilesProblems.error(ProblemCode.INVALID_INPUT, "FILES_TARGET_DIRECTORY", "The current target is not a directory");
    }

    /** Observes cooperative cancellation before beginning another item. / 开始另一项前观察协作取消。
     * @param task authentic callback / 真实回调
     * @throws ApiException if cancellation was requested / 已请求取消时 */
    private static void check(TaskContext task) throws ApiException {
        if (task.cancelToken().requested())
            throw FilesProblems.error(ProblemCode.CANCELLED, "FILES_CANCELLED", "File operation was cancelled before the next item");
    }

    /** One authentic platform-effect sequence. / 一个真实平台副作用序列。 */
    @FunctionalInterface
    interface Work {
        /** Executes under the original task. / 在原任务下执行。
         * @param execution actual fact accumulator / 实际事实累积器
         * @param task authentic callback / 真实回调
         * @throws ApiException if actual work fails / 实际工作失败时 */
        void run(FilesExecution execution, TaskContext task) throws ApiException;
    }
}
