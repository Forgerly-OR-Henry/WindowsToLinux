package gold.debug.windowstolinux.mod.files.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.log.LogCategory;
import gold.debug.windowstolinux.appapi.task.log.LogEvent;
import gold.debug.windowstolinux.appapi.task.log.LogLevel;
import gold.debug.windowstolinux.appapi.task.log.LogReadResult;
import gold.debug.windowstolinux.appapi.task.log.LogTarget;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind;
import gold.debug.windowstolinux.mod.files.file.model.FilesClipboard;
import gold.debug.windowstolinux.mod.files.file.model.FilesDirectory;
import gold.debug.windowstolinux.mod.files.file.model.FilesObservation;
import gold.debug.windowstolinux.mod.files.file.model.FilesOutcome;

/** Original-task file management and activation-owned actual observations. / 原任务文件管理及启用所属实际观测。 */
public final class FilesService {
    /** Complete bounded listing ceiling; overflow is a failure, never truncation. / 完整有界列举上限；溢出失败，不截断。 */
    private static final int ENTRY_LIMIT = 100000;

    /** Original published activation context. / 原已发布启用上下文。 */
    private final ModContext context;

    /** Existing public File and Link effect dispatcher. / 既有公开File及Link副作用分派方。 */
    private final FilesMutation mutation;

    /** Bounded actual observations independent of task completion. / 独立于任务完成的有界实际观测。 */
    private final FilesObservations observations = new FilesObservations();

    /** Original opaque diagnostic target. / 原不透明诊断目标。 */
    private final LogTarget log;

    /** Original-task reconciliation without replay. / 不重放的原任务核对。 */
    private final FilesRecovery recovery;

    /** Binds a stateless Mod without accessing a business Schema. / 不访问业务Schema地绑定无状态Mod。
     * @param context original authentic Mod context / 原真实Mod上下文
     * @throws ApiException if diagnostic registration is unavailable / 诊断登记不可用时 */
    public FilesService(ModContext context) throws ApiException {
        this.context = Objects.requireNonNull(context);
        mutation = new FilesMutation(context.file());
        log = context.logs().register(new LogCategory("runtime", "Runtime", Optional.empty()));
        recovery = new FilesRecovery(context, this::published);
    }

    /** Discovers saved non-secret servers without admitting their use. / 发现已保存非秘密服务器，不准入其使用。
     * @return original discovery task / 原发现任务
     * @throws ApiException if discovery admission fails / 发现准入失败时 */
    public TaskHandle<List<ServerInfo>> servers() throws ApiException {
        return read("files-servers", "Discover saved file locations", List.of(), task -> {
            var result = new ArrayList<ServerInfo>();
            Optional<String> cursor = Optional.empty();
            do {
                var page = context.servers().listConfigured(cursor, 200, task);
                result.addAll(page.items());
                if (result.size() > ENTRY_LIMIT)
                    throw FilesProblems.error(ProblemCode.INVALID_INPUT, "FILES_SERVER_CAPACITY", "Saved server selection exceeds the supported bound");
                cursor = page.nextCursor();
            } while (cursor.isPresent());
            return success(List.copyOf(result));
        });
    }

    /** Selects a real local directory and observes its URI within the same callback. / 在同一回调选择真实本机目录并观测其URI。
     * @param title readable picker title, not a path / 可读选择器标题，不是路径
     * @return actual directory and complete immediate list / 实际目录及完整直接列表
     * @throws ApiException if original admission fails / 原准入失败时 */
    public TaskHandle<FilesDirectory> chooseLocal(String title) throws ApiException {
        return read("files-select", title, List.of(), task -> {
            var selected = context.file().sources().selectDirectory(title, task);
            var location = FilePath.local(Path.of(context.file().sources().location(selected, task)));
            return success(new FilesDirectory(location, context.file().queries().list(location, ENTRY_LIMIT, task)));
        });
    }

    /** Browses a local or explicitly admitted single-server position. / 浏览本机或明确准入的单服务器位置。
     * @param directory original absolute position / 原绝对位置
     * @return original actual listing task / 原实际列举任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<FilesDirectory> browse(FilePath directory) throws ApiException {
        return read("files-browse", "Browse " + directory.path(), serversFor(List.of(directory)),
            task -> success(new FilesDirectory(directory, context.file().queries().list(directory, ENTRY_LIMIT, task))));
    }

    /** Creates only the requested empty object through the original platform. / 经原平台仅创建请求的空对象。
     * @param directory original current directory / 原当前目录
     * @param name exact complete user name / 用户精确完整名称
     * @param folder whether to create a directory / 是否创建目录
     * @return authentic effect task / 真实副作用任务
     * @throws ApiException if input or admission fails / 输入或准入失败时 */
    public TaskHandle<FilesOutcome> create(FilePath directory, String name, boolean folder) throws ApiException {
        name(directory, name);
        return submit("Create " + name, serversFor(List.of(directory)), 1,
            (execution, task) -> mutation.create(directory, name, folder, execution, task));
    }

    /** Renames a revalidated original File or Link itself. / 重命名已重新核验的原File或Link自身。
     * @param expected original selection / 原选择
     * @param name complete new name / 完整新名称
     * @return authentic effect task / 真实副作用任务
     * @throws ApiException if input or admission fails / 输入或准入失败时 */
    public TaskHandle<FilesOutcome> rename(FileInfo expected, String name) throws ApiException {
        name(expected.requested().parent(), name);
        return submit("Rename " + expected.requested().name(), serversFor(List.of(expected.requested())), 1,
            (execution, task) -> mutation.rename(expected, name, execution, task));
    }

    /** Deletes a bounded original selection using platform confirmation and recovery effects. / 按平台确认及恢复效果删除有界原选择。
     * @param expected original no-follow observations / 原不跟随链接观测
     * @return authentic effect task / 真实副作用任务
     * @throws ApiException if input or admission fails / 输入或准入失败时 */
    public TaskHandle<FilesOutcome> delete(List<FileInfo> expected) throws ApiException {
        var selection = selection(expected);
        return submit("Delete selected file objects", serversFor(selection.stream().map(FileInfo::requested).toList()), selection.size(),
            (execution, task) -> mutation.delete(selection, execution, task));
    }

    /** Executes explicit copy or move; clipboard metadata is never reusable authorization. / 执行明确复制或移动；剪贴元数据不是可复用授权。
     * @param clipboard original selected observations and intent / 原选中观测及意图
     * @param directory exact current destination / 精确当前目标
     * @param includedLinks links explicitly approved for inclusion, never an implicit scan / 明确同意纳入的链接，不做隐式扫描
     * @return authentic shared clipboard/drag effect task / 真实共用剪贴拖拽副作用任务
     * @throws ApiException if input or server combination is unsupported / 输入或服务器组合不受支持时 */
    public TaskHandle<FilesOutcome> transfer(FilesClipboard clipboard, FilePath directory, List<FileInfo> includedLinks) throws ApiException {
        selection(clipboard.sources());
        var links = List.copyOf(includedLinks);
        if (clipboard.mode() != FilesClipboard.Mode.MOVE && !links.isEmpty())
            throw FilesProblems.error(ProblemCode.INVALID_INPUT, "FILES_COPY_LINK_INCLUSION", "Link maintenance inclusion belongs only to an explicit move");
        var paths = new ArrayList<FilePath>();
        paths.add(directory);
        clipboard.sources().forEach(source -> paths.add(source.requested()));
        links.forEach(link -> paths.add(link.requested()));
        return submit("Transfer selected file objects", serversFor(paths), clipboard.sources().size(),
            (execution, task) -> mutation.transfer(clipboard, directory, links, execution, task));
    }

    /** Submits fresh authorization only for unfinished original link maintenance. / 仅为原未完成链接维护提交新授权。
     * @param originalOperation original durable move identity / 原持久移动身份
     * @param host original affected host, not an authorization / 原受影响主机，不是授权
     * @return new ordinary link-only task / 新普通仅链接任务
     * @throws ApiException if new admission fails / 新准入失败时 */
    public TaskHandle<FilesOutcome> continueLinks(UUID originalOperation, FilePath host) throws ApiException {
        Objects.requireNonNull(originalOperation);
        return submit("Continue original link maintenance", serversFor(List.of(host)), 1,
            (execution, task) -> mutation.continueLinks(originalOperation, execution, task), "files-continue");
    }

    /** Reads already-returned facts without I/O, including an unresolved UNKNOWN callback. / 不执行I/O地读取已返回事实，包含未解决UNKNOWN回调。
     * @param originalTaskId exact original task identity / 精确原任务身份
     * @return retained actual facts / 保留的实际事实 */
    public Optional<FilesObservation> observation(UUID originalTaskId) {
        return observations.read(Objects.requireNonNull(originalTaskId));
    }

    /** Reads the genuine newest hundred-record window; consumers deduplicate record UUIDs. / 读取真实最新百条窗口；调用方按记录UUID去重。
     * @param originalTaskId association filter, without operation authority / 关联筛选，不含操作权
     * @return actual redacted diagnostics and explicit storage problems / 实际脱敏诊断及明确存储问题
     * @throws ApiException if worker, lock or lifetime access fails / 工作线程、锁定或寿命访问失败时 */
    public LogReadResult diagnostics(UUID originalTaskId) throws ApiException {
        return context.logs().read(log, Optional.of(Objects.requireNonNull(originalTaskId)), 100);
    }

    /** Opens existing platform task review on the EDT without replaying effects. / 在EDT打开既有平台任务审阅，不重放副作用。
     * @throws ApiException if the owner is locked or unavailable / 所属者锁定或不可用时 */
    public void openTasks() throws ApiException {
        context.ui().openPlatform(PlatformPageKind.TASKS);
    }

    /** Returns the original recovery implementation for owner registration. / 返回供所属登记的原恢复实现。
     * @return original non-replaying recovery / 原不重放恢复 */
    public FilesRecovery recovery() {
        return recovery;
    }

    /** Submits read-only work with the complete original server set. / 按完整原服务器集合提交只读工作。
     * @param <T> copied result type / 独立结果类型
     * @param type original task type / 原任务类型
     * @param title readable title / 可读标题
     * @param servers complete resource set / 完整资源集合
     * @param work authentic callback / 真实回调
     * @return admitted task / 已准入任务
     * @throws ApiException if admission fails / 准入失败时 */
    private <T> TaskHandle<T> read(String type, String title, List<String> servers, TaskWork<T> work) throws ApiException {
        return context.tasks().submit(new TaskRequest(type, title, servers, true), task -> {
            try {
                return work.run(task);
            } catch (ApiException failure) {
                return new OperationResult<>(FilesProblems.outcome(failure.problem()), Optional.empty(), Optional.of(failure.problem()));
            } catch (java.util.concurrent.CancellationException cancelled) {
                var problem = FilesProblems.problem(ProblemCode.CANCELLED, "FILES_READ_CANCELLED", "Original file observation was cancelled");
                return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.of(problem));
            }
        });
    }

    /** Submits one cancellable ordinary batch. / 提交一个可取消普通批次。
     * @param title readable operation title / 可读操作标题
     * @param servers complete server set / 完整服务器集合
     * @param count requested effect count / 请求副作用计数
     * @param work actual operation sequence / 实际操作序列
     * @return authentic task / 真实任务
     * @throws ApiException if admission fails / 准入失败时 */
    private TaskHandle<FilesOutcome> submit(String title, List<String> servers, int count, FilesMutation.Work work) throws ApiException {
        return submit(title, servers, count, work, "files-mutation");
    }

    /** Serializes observation-slot admission without serializing file I/O. / 串行准入观测槽，不串行执行文件I/O。
     * @param title readable title / 可读标题
     * @param servers complete server set / 完整服务器集合
     * @param count requested effect count / 请求副作用计数
     * @param work actual operation sequence / 实际操作序列
     * @param type original task type / 原任务类型
     * @return authentic task / 真实任务
     * @throws ApiException if observation or task admission fails / 观测槽或任务准入失败时 */
    private synchronized TaskHandle<FilesOutcome> submit(String title, List<String> servers, int count, FilesMutation.Work work, String type) throws ApiException {
        observations.requireRoom();
        var handle = context.tasks().submit(new TaskRequest(type, title, servers, true), task -> execute(count, work, task));
        observations.track(handle);
        return handle;
    }

    /** Retains original batch size before effects and actual callback outcome before release. / 副作用前保留原批次大小，释放前保留实际回调结果。
     * @param count requested effect count / 请求副作用计数
     * @param work actual operation sequence / 实际操作序列
     * @param task authentic callback / 真实回调
     * @return actual result or explicit evidence uncertainty / 实际结果或明确依据不确定性 */
    private OperationResult<FilesOutcome> execute(int count, FilesMutation.Work work, TaskContext task) {
        OperationResult<FilesOutcome> result = null;
        try {
            var checkpoint = context.tasks().beginCheckpoint(task, FilesPlan.KEY, FilesPlan.encode(count, false, TaskResult.UNKNOWN));
            result = mutation.run(task, value -> published(task.id(), value), work);
            checkpoint.advance(checkpoint.snapshot(), FilesPlan.encode(count, true, result.outcome()));
            checkpoint.close();
            return result;
        } catch (ApiException failure) {
            var actual = result == null ? new FilesOutcome(List.of(), List.of()) : result.value().orElseThrow();
            var problem = result == null ? failure.problem()
                : FilesProblems.problem(ProblemCode.UNKNOWN, "FILES_BATCH_RECORDING", failure.problem().message());
            published(task.id(), new FilesObservation(actual, Optional.of(problem), true));
            return new OperationResult<>(FilesProblems.outcome(problem), Optional.of(actual), Optional.of(problem));
        }
    }

    /** Publishes actual immutable facts and genuine owner diagnostics. / 发布实际不可变事实及真实所属诊断。
     * @param task original task identity / 原任务身份
     * @param value actual callback observation / 实际回调观测 */
    private synchronized void published(UUID task, FilesObservation value) {
        observations.publish(task, value);
        var last = value.outcome().reports().isEmpty() ? Optional.<gold.debug.windowstolinux.appapi.file.action.FileReport>empty()
            : Optional.of(value.outcome().reports().getLast());
        context.logs().write(log, new LogEvent(value.problem().isPresent() ? LogLevel.WARN : LogLevel.INFO,
            "FILES_OBSERVED", value.callbackReturned() ? "Original file callback returned" : "Original file facts observed",
            Optional.of("Actual reports=" + value.outcome().reports().size() + "; actual original operations=" + value.outcome().operations().size()
                + value.problem().map(problem -> "; " + problem.message()).orElse("")), Optional.of(task),
            last.map(report -> report.operationId()), last.map(report -> report.outcome())));
    }

    /** Admits no more than one distinct remote server. / 最多准入一台不同远端服务器。
     * @param paths all actual endpoints and explicitly included links / 全部实际端点及明确纳入链接
     * @return complete distinct server identity list / 完整互不重复服务器身份列表
     * @throws ApiException if different remote hosts are requested / 请求不同远端主机时 */
    private static List<String> serversFor(List<FilePath> paths) throws ApiException {
        var servers = new LinkedHashSet<String>();
        paths.forEach(path -> path.server().ifPresent(server -> servers.add(server.id())));
        if (servers.size() > 1)
            throw FilesProblems.error(ProblemCode.INCOMPATIBLE, "FILES_CROSS_SERVER", "Transfers between different remote servers are unsupported");
        return List.copyOf(servers);
    }

    /** Copies a bounded unique selection without granting access. / 复制有界唯一选择，不授予访问权。
     * @param expected original selected observations / 原选中观测
     * @return independent selection / 独立选择
     * @throws ApiException if selection is empty, duplicated or oversized / 选择为空、重复或超限时 */
    private static List<FileInfo> selection(List<FileInfo> expected) throws ApiException {
        var copied = List.copyOf(expected);
        if (copied.isEmpty() || copied.size() > ENTRY_LIMIT || copied.stream().map(value -> value.requested().key()).distinct().count() != copied.size())
            throw FilesProblems.error(ProblemCode.INVALID_INPUT, "FILES_SELECTION", "A bounded nonempty unique selection is required");
        return copied;
    }

    /** Validates one exact user-entered name without changing it. / 不改写地核验用户输入的精确名称。
     * @param directory original directory / 原目录
     * @param name complete user name / 用户完整名称
     * @throws ApiException if it is not a direct child / 不是直接子项时 */
    private static void name(FilePath directory, String name) throws ApiException {
        try {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Empty name");
            directory.child(name);
        } catch (IllegalArgumentException invalid) {
            throw FilesProblems.error(ProblemCode.INVALID_INPUT, "FILES_NAME", "Enter one complete filename without a path or parent traversal");
        }
    }

    /** Creates a successful copied read result. / 创建成功的独立读取结果。
     * @param <T> copied payload type / 独立载荷类型
     * @param value actual observation / 实际观测
     * @return confirmed read result / 已确认读取结果 */
    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
