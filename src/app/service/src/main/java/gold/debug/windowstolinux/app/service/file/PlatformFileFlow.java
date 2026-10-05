package gold.debug.windowstolinux.app.service.file;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.file.FileStore;
import gold.debug.windowstolinux.app.file.*;
import gold.debug.windowstolinux.app.file.FileEngine;
import gold.debug.windowstolinux.app.file.LocalFileBackend;
import gold.debug.windowstolinux.app.file.model.FileActionLog;
import gold.debug.windowstolinux.app.file.model.FileBackend;
import gold.debug.windowstolinux.app.service.file.check.FileBackendAccess;
import gold.debug.windowstolinux.app.service.file.check.FileInteraction;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import gold.debug.windowstolinux.app.service.task.TaskCapabilityScope;
import gold.debug.windowstolinux.appapi.file.action.DeleteEffect;
import gold.debug.windowstolinux.appapi.file.action.FileActions;
import gold.debug.windowstolinux.appapi.file.action.FileChoice;
import gold.debug.windowstolinux.appapi.file.action.FileOperation;
import gold.debug.windowstolinux.appapi.file.action.FileReport;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Task-authorized common file service with actual approval, persistence and managed transports. / 实际审批、持久化及受管传输接通的任务授权通用文件服务。 */
public final class PlatformFileFlow implements AutoCloseable {
    /** genuine task admission authority / 真实任务准入授权方.
     */
    private final PlatformTaskRuntime tasks;

    /** current unlocked account boundary / 当前已解锁账户边界.
     */
    private final DesktopSecurityService security;

    /** genuine host approval and waiting service / 真实宿主审批及等待服务.
     */
    private final FileInteraction interaction;

    /** authenticated backend scope access / 已认证后端作用域访问.
     */
    private final FileBackendAccess backends;

    /** original resumable transfer capability / 原可恢复传输能力.
     */
    private final FileTransfers transfers;

    /** Existing platform-owned file evidence repository / 既有平台所属文件依据仓库.
     */
    private final FileStore repository;

    /** Genuine activation and task admission scope / 真实启用及任务准入作用域.
     */
    private final TaskCapabilityScope scope;

    /** exact activation owner / 精确启用所有者.
     */
    private final String owner, version;

    /** Binds one genuine platform capability activation. / 绑定一次真实平台能力启用。
     * @param database existing platform database owner / 既有平台数据库所有者
     * @param tasks genuine task admission authority / 真实任务准入授权方
     * @param security current unlocked account boundary / 当前已解锁账户边界
     * @param owner exact activation owner / 精确启用所有者
     * @param version actual loaded release / 实际加载发布版本
     * @param interaction genuine host approval and waiting service / 真实宿主审批及等待服务
     * @param backends authenticated backend scope access / 已认证后端作用域访问
     * @param transfers original resumable transfer capability / 原可恢复传输能力
     */
    public PlatformFileFlow(PlatformDatabase database, PlatformTaskRuntime tasks, DesktopSecurityService security,
                            String owner, String version, FileInteraction interaction, FileBackendAccess backends,
                            FileTransfers transfers) {
        this.tasks = Objects.requireNonNull(tasks);
        this.security = Objects.requireNonNull(security);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
        this.interaction = Objects.requireNonNull(interaction);
        this.backends = Objects.requireNonNull(backends);
        this.transfers = Objects.requireNonNull(transfers);
        repository = new FileStore(database, owner, version);
        scope = new TaskCapabilityScope(database, tasks, owner, version, Optional.empty(), "file-operation-");
    }

    /** Observes the actual File or Link. / 观测实际 File 或 Link。
     * @param path exact host-qualified position / 精确带主机位置
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileInfo inspect(FilePath path, boolean link, TaskContext task) throws ApiException {
        return access(task, false, () -> backends.withBackend(path, task, b -> new FileEngine(b).inspect(path, link, cancel(task))));
    }

    /** Reads a bounded content copy. / 读取有界内容副本。
     * @param path exact host-qualified position / 精确带主机位置
     * @param limit maximum accepted immediate entries or bytes / 允许的直接子项或字节上限
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public byte[] read(FilePath path, int limit, TaskContext task) throws ApiException {
        return access(task, false, () -> backends.withBackend(path, task, b -> new FileEngine(b).read(path, limit, cancel(task))));
    }

    /** Lists bounded filtered actual entries. / 列举有界筛选实际子项。
     * @param path exact host-qualified position / 精确带主机位置
     * @param limit maximum accepted immediate entries or bytes / 允许的直接子项或字节上限
     * @param kind optional object type filter / 可选对象类型筛选
     * @param text validated link or filter text / 已校验链接或筛选文本
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public List<FileInfo> list(FilePath path, int limit, Optional<FileInfo.Kind> kind, String text, TaskContext task) throws ApiException {
        Objects.requireNonNull(kind);
        if (text == null || text.length() > 4096)
            throw error(ProblemCode.INVALID_INPUT, "FILE_FILTER", "Invalid directory filter");
        return access(task, false, () -> backends.withBackend(path, task, b -> new FileEngine(b).list(path, limit, kind, text, cancel(task))));
    }

    /** Executes a preplanned copy/move; cross-host content retains the existing transfer journal. / 执行预计划复制/移动，跨主机内容保留既有传输记录。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param move whether source removal is requested / 是否请求移除来源
     * @param choice explicit collision strategy / 明确冲突策略
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileReport copy(FilePath from, FilePath to, boolean link, boolean move, FileChoice choice, TaskContext task) throws ApiException {
        Objects.requireNonNull(choice);
        return access(task, false, () -> copyAdmitted(from, to, link, move, choice, task));
    }

    /** Renames within one admitted call using the original move contract. / 在一次准入调用内按原移动合同重命名。
     * @param from original source position / 原来源位置
     * @param name unqualified sibling name / 不带根或盘符的同级名称
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded move result / 精确有界的移动结果
     * @throws ApiException if the name, admission, identity or effect cannot be verified / 名称、准入、身份或效果无法核实时
     */
    public FileReport rename(FilePath from, String name, boolean link, TaskContext task) throws ApiException {
        return access(task, false, () -> {
            if (from == null)
                throw new IllegalArgumentException("A source position is required");
            FilePath to = from.parent().child(name);
            return copyAdmitted(from, to, link, true, FileChoice.ask(), task);
        });
    }

    /** Executes the shared copy/move body inside the caller's existing admission. / 在调用方既有准入内执行共享复制与移动逻辑。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param move whether source removal is requested / 是否请求移除来源
     * @param choice explicit collision strategy / 明确冲突策略
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded copy or move result / 精确有界的复制或移动结果
     * @throws IOException if object access or durable evidence fails / 对象访问或持久依据失败时
     * @throws ApiException if authorization, cancellation or effect reporting fails / 授权、取消或效果报告失败时
     */
    private FileReport copyAdmitted(FilePath from, FilePath to, boolean link, boolean move, FileChoice choice,
                                    TaskContext task) throws IOException, ApiException {
        if (from.server().isPresent() && to.server().isPresent() && !from.server().equals(to.server()))
            throw new IOException("Cross-server operations are unsupported");
        FilePath host = from.server().isPresent() ? from : to;
        return backends.withBackend(host, task, backend -> {
            FileBackend source = from.server().isPresent() ? backend : new LocalFileBackend();
            FileBackend target = to.server().isPresent() ? backend : new LocalFileBackend();
            if (from.server().isEmpty() && to.server().isEmpty()) {
                source = backend;
                target = backend;
            }
            FileEngine fromEngine = new FileEngine(source), toEngine = new FileEngine(target);
            boolean cross = !from.server().equals(to.server());
            var result = fromEngine.copy(from, to, link, move, choice, toEngine, decisions(task), approval(task), journal(task), cancel(task),
                (completed, total) -> {
                    try {
                        task.progress("Transferring file content", total == 0 ? Optional.empty() : Optional.of((double) completed / total));
                    } catch (ApiException failure) {
                        throw new IOException("Progress could not be recorded", failure);
                    }
                },
                cross ? (s, t, e) -> transfers.transfer(s, t, e, task) : null);
            observe(result, task);
            return result;
        });
    }

    /** Creates or writes bounded content. / 创建或写入有界内容。
     * @param path exact host-qualified position / 精确带主机位置
     * @param input caller content copied before dispatch / 发出前复制的调用方内容
     * @param directory whether to create a directory / 是否创建目录
     * @param createOnly whether existing objects must be rejected / 是否须拒绝已有对象
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileReport write(FilePath path, byte[] input, boolean directory, boolean createOnly, TaskContext task) throws ApiException {
        Objects.requireNonNull(input);
        if (input.length > 64 * 1024 * 1024)
            throw error(ProblemCode.INVALID_INPUT, "FILE_WRITE_SIZE", "Write capacity exceeded");
        byte[] content = input.clone();
        try {
            return access(task, false, () -> backends.withBackend(path, task, b -> {
                var result = new FileEngine(b).write(path, content, directory, createOnly, decisions(task), approval(task), journal(task), cancel(task));
                observe(result, task);
                return result;
            }));
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    /** Creates and registers an explicitly targeted link. / 创建并登记明确指向链接。
     * @param path exact host-qualified position / 精确带主机位置
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileReport createLink(FilePath path, String target, TaskContext task) throws ApiException {
        if (target == null || target.isBlank() || target.length() > 4096 || target.codePoints().anyMatch(Character::isISOControl))
            throw error(ProblemCode.INVALID_INPUT, "FILE_LINK_TARGET", "Invalid link target");
        return access(task, false, () -> backends.withBackend(path, task, b -> {
            var result = new FileEngine(b).createLink(path, target, approval(task), journal(task), cancel(task));
            observe(result, task);
            return result;
        }));
    }

    /** Registers an observed link without changing it. / 登记观测链接而不修改。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public void registerLink(FilePath path, TaskContext task) throws ApiException {
        access(task, false, () -> backends.withBackend(path, task, b -> {
            var engine = new FileEngine(b);
            FileInfo link = engine.inspect(path, true, cancel(task));
            FileActionLog j = journal(task);
            UUID id = j.begin("REGISTER_LINK", link.actual(), Optional.empty(), FileActions.stamp(link));
            j.saveLink(link, engine.optionalTarget(link, cancel(task)));
            j.record(id, "COMPLETE", link.actual(), Optional.empty(), link.identity());
            return null;
        }));
    }

    /** Removes only caller-owned registry metadata. / 仅解除调用方所属登记元数据。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public void unregisterLink(FilePath path, TaskContext task) throws ApiException {
        access(task, false, () -> {
            FileActionLog j = journal(task);
            UUID id = j.begin("UNREGISTER_LINK", path, Optional.empty(), path.key());
            j.removeLink(path);
            j.record(id, "COMPLETE", path, Optional.empty(), "unregistered");
            return null;
        });
    }

    /** Implements ordinary confirmation/waiting or independently approved single-use deletion. / 实现常规确认等待或独立审批的单次许可删除。
     * @param path exact host-qualified position / 精确带主机位置
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param permit genuine exact single-use deletion permit / 真实精确单次删除许可
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileReport delete(FilePath path, boolean link, Optional<ApprovalGrant> permit, TaskContext task) throws ApiException {
        return access(task, false, () -> {
            while (true) {
                DeleteObservation observation = backends.withBackend(path, task, b -> {
                    var engine = new FileEngine(b);
                    FileInfo info = engine.inspect(path, link, cancel(task));
                    return new DeleteObservation(info, b.canRecycle(info.actual()));
                });
                DeleteEffect effect = observation.recyclable ? DeleteEffect.RECYCLE : DeleteEffect.PERMANENT;
                FileInfo info = observation.info;
                ApprovalGrant grant;
                Optional<ActionSemantics> semantics = Optional.empty();
                if (permit.isPresent()) {
                    grant = permit.orElseThrow();
                    ActionIntent reviewed = interaction.reviewed(grant, task);
                    semantics = reviewed.semantics();
                    ActionIntent permanent = FileActions.delete(info, link, DeleteEffect.PERMANENT), recycle = FileActions.delete(info, link, DeleteEffect.RECYCLE);
                    if (reviewed.equals(semantics.map(permanent::withSemantics).orElse(permanent)))
                        effect = DeleteEffect.PERMANENT;
                    else if (!reviewed.equals(semantics.map(recycle::withSemantics).orElse(recycle)))
                        throw error(ProblemCode.ACCESS_DENIED, "FILE_DELETE_PERMIT", "Deletion permit does not match the actual object or effect");
                    if (effect == DeleteEffect.RECYCLE && !observation.recyclable)
                        throw error(ProblemCode.UNAVAILABLE, "FILE_RECYCLE_UNAVAILABLE", "Recycle permission cannot be upgraded to permanent deletion");
                    if (effect == DeleteEffect.PERMANENT && observation.recyclable)
                        throw error(ProblemCode.ACCESS_DENIED, "FILE_RECYCLE_REQUIRED", "An available recoverable deletion cannot be replaced by a permanent-delete permit");
                } else {
                    String explanation;
                    try {
                        explanation = interaction.explain(info, task);
                    } catch (ApiException failure) {
                        explanation = "AI purpose explanation unavailable: " + failure.problem().eventCode();
                    }
                    var decision = interaction.approve(FileActions.delete(info, link, effect), info, true, explanation, task);
                    if (decision.outcome() != TaskResult.SUCCEEDED || decision.value().isEmpty()) {
                        if (cancel(task).requested()) return stopped(path, "Task cancelled before deletion");
                        FileActionLog j = journal(task);
                        UUID wait = j.begin("DELETE_WAIT", path, Optional.empty(), FileActions.stamp(info));
                        j.record(wait, "WAITING", path, Optional.empty(), "User declined deletion");
                        boolean resume = interaction.awaitContinue("Deletion was not confirmed; the file is retained. Continue to revalidate and ask again, or terminate the task.", task);
                        j.record(wait, resume ? "COMPLETE" : "CANCELLED", path, Optional.empty(), resume ? "Explicitly continue and revalidate" : "Task terminated");
                        if (!resume) return stopped(path, "Task terminated while waiting for deletion");
                        continue;
                    }
                    grant = decision.value().orElseThrow();
                    ActionIntent selected = interaction.reviewed(grant, task);
                    if (selected.equals(FileActions.delete(info, link, DeleteEffect.PERMANENT)))
                        effect = DeleteEffect.PERMANENT;
                    else if (observation.recyclable && selected.equals(FileActions.delete(info, link, DeleteEffect.RECYCLE)))
                        effect = DeleteEffect.RECYCLE;
                    else
                        throw error(ProblemCode.ACCESS_DENIED, "FILE_DELETE_CHOICE", "Confirmed deletion effect does not match the offered object or choices");
                }
                DeleteEffect chosen = effect;
                Optional<ActionSemantics> chosenSemantics = semantics;
                return backends.withBackend(path, task, b -> {
                    var engine = new FileEngine(b);
                    FileInfo current = engine.inspect(path, link, cancel(task));
                    if (!FileActions.stamp(info).equals(FileActions.stamp(current)))
                        throw error(ProblemCode.ACCESS_DENIED, "FILE_DELETE_CHANGED", "Object changed after confirmation");
                    ActionIntent intent = FileActions.delete(current, link, chosen);
                    intent = chosenSemantics.map(intent::withSemantics).orElse(intent);
                    if (permit.isPresent()) interaction.consumePermit(grant, intent, task);
                    else interaction.consume(grant, intent, task);
                    var result = engine.delete(current, link, chosen, journal(task), cancel(task));
                    observe(result, task);
                    return result;
                });
            }
        });
    }

    /** Reads original task-scoped operation evidence. / 读取原任务限定操作依据。
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public List<FileOperation> operations(TaskContext task) throws ApiException {
        return access(task, true, () -> repository.operations(task.id()));
    }

    /** Checks original observed results without replaying effects or reviving permits. / 核对原观测结果，不重放副作用或复活许可。
     * @param operation original durable operation identity / 原持久操作身份
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileReport checkOperation(UUID operation, TaskContext task) throws ApiException {
        return access(task, true, () -> {
            FileOperation original = repository.ownedOperation(operation).orElseThrow(() -> error(ProblemCode.NOT_FOUND, "FILE_OPERATION", "Original operation for this caller and release is unavailable"));
            return new FileOperationRecovery(backends, repository).check(original, task, cancel(task));
        });
    }

    /** Starts new exact authority only for proved unfinished original link maintenance. / 仅为已证明的未完成原链接维护开启新精确授权。
     * @param operation original move operation owned by the same caller and release / 同调用方及发布所属的原移动操作
     * @param task new ordinary task or the current original ordinary callback / 新普通任务或当前原普通回调
     * @return actual new link-only continuation report / 真实新仅链接续作报告
     * @throws ApiException if called from recovery, ownership differs, facts changed or new approval fails / 恢复回调调用、归属不同、事实改变或新审批失败时 */
    public FileReport continueOperation(UUID operation, TaskContext task) throws ApiException {
        return access(task, false, () -> {
            tasks.callbacks().requireOrdinaryExecuting(task, owner, version);
            FileOperation original = repository.ownedOperation(operation).orElseThrow(() -> error(ProblemCode.NOT_FOUND, "FILE_OPERATION", "Original move for this caller and release is unavailable"));
            var recovery = new FileOperationRecovery(backends, repository);
            var pending = recovery.pending(original, task, cancel(task));
            if (pending.isEmpty())
                throw error(ProblemCode.INVALID_INPUT, "FILE_NO_PENDING_LINKS", "No proved unfinished link remains");
            var result = backends.withBackend(pending.getFirst().link(), task, backend -> new FileEngine(backend).maintainLinks(pending, FileOperationRecovery.targets(original), approval(task), journal(task, Optional.of(original)), cancel(task)));
            observe(result, task);
            return result;
        });
    }

    /** Routes collision choices to the original host presenter. / 将冲突选择交给原宿主呈现器。
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    private FileEngine.Decisions decisions(TaskContext task) {
        return new FileEngine.Decisions() {
            /** Obtains the explicit outer directory collision choice from the host. / 从宿主取得明确外层目录冲突选择。
             * @param source original observed source object / 原观测来源对象
             * @param target exact planned destination or link target / 精确预定位置或链接目标
             * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
             * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
             */
            public FileChoice.Directory directory(FileInfo source, FileInfo target) throws ApiException {
                return interaction.chooseDirectory(source, target, task);
            }

            /** Obtains the explicit inner regular-file or Link collision choice from the host. / 从宿主取得明确内部普通文件或 Link 冲突选择。
             * @param source original observed source object / 原观测来源对象
             * @param target exact planned destination or link target / 精确预定位置或链接目标
             * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
             * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
             */
            public FileChoice.Conflict file(FileInfo source, FileInfo target) throws ApiException {
                return interaction.chooseFile(source, target, task);
            }
        };
    }

    /** Binds exact source identity and plan digest to a genuine host grant. / 将精确来源身份及计划摘要绑定至真实宿主许可。
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    private FileEngine.Approval approval(TaskContext task) {
        return (source, type, plan) -> {
            String exact = FileActions.stamp(source) + "\n" + digest(plan);
            String summary = "File operation plan: " + type + "\n" + plan.substring(0, Math.min(plan.length(), 4000));
            var intent = FileActions.intent(type, source, "file-v1", exact, summary);
            var decision = interaction.approve(intent, source, false, "", task);
            if (decision.outcome() != TaskResult.SUCCEEDED || decision.value().isEmpty())
                throw error(ProblemCode.ACCESS_DENIED, "FILE_OPERATION_DECLINED", "File operation was not approved");
            interaction.consume(decision.value().orElseThrow(), intent, task);
        };
    }

    /** Binds operation evidence and link registry writes to the exact original task. / 将操作依据及链接登记写入绑定至精确原任务。
     * @param task authentic original executing task / 真实原执行任务
     * @return original task-bound durable evidence adapter / 原任务绑定持久依据适配器
     */
    private FileActionLog journal(TaskContext task) {
        return journal(task, Optional.empty());
    }

    /** Binds original durable parent evidence without reusing old authorization. / 绑定原持久父依据，不复用旧授权。
     * @param task current authentic task / 当前真实任务
     * @param original original operation only for a new explicitly authorized continuation / 仅用于新明确授权续作的原操作
     * @return actual current-task durable journal / 实际当前任务持久记录 */
    private FileActionLog journal(TaskContext task, Optional<FileOperation> original) {
        return new FileActionLog() {
            /** Commits the exact original task plan before dispatching any file effect. / 发出任何文件副作用前提交精确原任务计划。
             * @param type exact operation category / 精确操作类别
             * @param source original observed source object / 原观测来源对象
             * @param target exact planned destination or link target / 精确预定位置或链接目标
             * @param plan exact preapproved operation plan / 精确预批准操作计划
             * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
             * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
             */
            public UUID begin(String type, FilePath source, Optional<FilePath> target, String plan) throws IOException {
                try {
                    return original.isPresent() ? repository.beginContinuation(task.id(), original.orElseThrow(), plan) : repository.begin(task.id(), type, source, target, plan);
                } catch (SQLException failure) {
                    throw new IOException("File operation evidence could not be committed", failure);
                }
            }

            /** Commits one original task-owned effect observation and preserves storage failures. / 提交单个原任务所属副作用观测，并保留存储失败。
             * @param operation original durable operation identity / 原持久操作身份
             * @param phase confirmed or pending effect stage / 已确认或待核对副作用阶段
             * @param source original observed source object / 原观测来源对象
             * @param target exact planned destination or link target / 精确预定位置或链接目标
             * @param detail bounded evidence or diagnostic detail / 有界依据或诊断详情
             * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
             */
            public void record(UUID operation, String phase, FilePath source, Optional<FilePath> target, String detail) throws IOException {
                try {
                    repository.record(operation, task.id(), phase, source, target, detail);
                } catch (SQLException failure) {
                    throw new IOException("File phase could not be committed", failure);
                }
            }

            /** Loads only original owner registry links for the same host. / 仅加载原所有者在同主机的登记链接。
             * @param host host-qualified registry scope / 带主机登记作用域
             * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
             * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
             */
            public List<FileInfo> links(FilePath host) throws IOException {
                try {
                    return repository.links(host);
                } catch (SQLException failure) {
                    throw new IOException("Link registry unavailable", failure);
                }
            }

            /** Checks that the registered target path and identity still match the original object. / 核对登记目标位置及身份仍与原对象一致。
             * @param link whether to operate on the link itself / 是否操作链接自身
             * @param target exact planned destination or link target / 精确预定位置或链接目标
             * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
             * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
             */
            public boolean targetMatches(FileInfo link, FileInfo target) throws IOException {
                try {
                    return repository.targetMatches(link, target);
                } catch (SQLException failure) {
                    throw new IOException("Registered target identity unavailable", failure);
                }
            }

            /** Persists confirmed or broken link association evidence for the original owner. / 为原所有者保存已确认或断链的关联依据。
             * @param link whether to operate on the link itself / 是否操作链接自身
             * @param target exact planned destination or link target / 精确预定位置或链接目标
             * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
             */
            public void saveLink(FileInfo link, Optional<FileInfo> target) throws IOException {
                try {
                    repository.saveLink(task.id(), link, target);
                } catch (SQLException failure) {
                    throw new IOException("Link association could not be committed", failure);
                }
            }

            /** Removes only original owner association metadata, without deleting files. / 仅移除原所有者关联元数据，不删除文件。
             * @param link whether to operate on the link itself / 是否操作链接自身
             * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
             */
            public void removeLink(FilePath link) throws IOException {
                try {
                    repository.removeLink(link);
                } catch (SQLException failure) {
                    throw new IOException("Link association could not be removed", failure);
                }
            }
        };
    }

    /** Obtains genuine original task cancellation without accepting caller substitutes. / 取得真实原任务取消，不接受调用方替代。
     * @param task authentic original executing task / 真实原执行任务
     * @return authentic original task cancellation / 真实原任务取消
     */
    private CancelToken cancel(TaskContext task) {
        return tasks.operationCancellation(task);
    }

    /** Admits exact task-owned work and translates host failures into public diagnostics. / 准入精确任务所属工作，并将宿主失败转成公开诊断。
     * @param task authentic original executing task / 真实原执行任务
     * @param recovery whether original recovery admission is permitted / 是否允许原恢复准入
     * @param work bounded admitted host callback / 有界准入宿主回调
     * @param <T> admitted callback result type / 准入回调结果类型
     * @return actual admitted callback result / 实际准入回调结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private <T> T access(TaskContext task, boolean recovery, Work<T> work) throws ApiException {
        try {
            return scope.call(task, recovery, () -> {
                security.requireUnlocked();
                try {
                    return work.run();
                } catch (ApiException failure) {
                    throw failure;
                } catch (java.util.concurrent.CancellationException cancelled) {
                    throw error(ProblemCode.CANCELLED, "FILE_CANCELLED", "File operation was cancelled");
                } catch (IllegalArgumentException invalid) {
                    throw error(ProblemCode.INVALID_INPUT, "FILE_REQUEST", "Invalid file operation request");
                } catch (IOException failure) {
                    throw error(ProblemCode.IO_FAILURE, "FILE_IO", "File object, permission, identity or effect could not be verified");
                }
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "FILE_STORAGE", "File operation storage is unavailable");
        }
    }

    /** Retains genuine task uncertainty when file effects cannot be confirmed. / 文件副作用无法确认时保留真实任务不确定状态。
     * @param result actual per-object result retaining unknown effects / 保留未知副作用的实际逐对象结果
     * @param task authentic original executing task / 真实原执行任务
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private void observe(FileReport result, TaskContext task) throws ApiException {
        if (result.outcome() == TaskResult.UNKNOWN) tasks.reportUncertainEffect(task);
    }

    /** Hashes exact bounded content or plan text for identity comparison. / 散列精确有界内容或计划文本用于身份比较。
     * @param value content whose exact digest is required / 需要精确摘要的内容
     * @return exact bounded SHA-256 content or plan digest / 精确有界 SHA-256 内容或计划摘要
     */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Reports cooperative cancellation without claiming a file effect. / 报告协作取消，不声称已执行文件副作用。
     * @param path exact host-qualified position / 精确带主机位置
     * @param reason safe explicit wait or result reason / 安全明确等待或结果原因
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    private static FileReport stopped(FilePath path, String reason) {
        return new FileReport(UUID.randomUUID(), TaskResult.CANCELLED, List.of(new FileReport.Item(path, Optional.empty(), TaskResult.CANCELLED, reason)));
    }

    /** Builds safe checked diagnostics without source content or secrets. / 构造不含来源内容或秘密的安全受检诊断。
     * @param code public failure category / 公开失败类别
     * @param event stable safe diagnostic event identifier / 稳定安全诊断事件标识
     * @param message safe diagnostic or proven refusal reason / 安全诊断或已证明拒绝原因
     * @return safe public checked failure / 安全公开受检失败
     */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /** Deletion metadata and actual recoverability captured before user review. / 用户审核前捕获的删除元数据及实际可恢复性。
     * @param info exact observed object and link chain / 精确观测对象及链接链
     * @param recyclable whether this exact location supports recoverable trash / 此精确位置是否支持可恢复回收
     */
    private record DeleteObservation(FileInfo info, boolean recyclable) {
    }

    /** Admitted task-owned file callback with explicit checked host failures. / 带明确受检宿主失败的准入任务所属文件回调。
     * @param <T> admitted callback result type / 准入回调结果类型
     */
    @FunctionalInterface
    private interface Work<T> {
        /** Runs one task-admitted file action with explicit host and storage failures. / 执行单个任务准入文件动作，保留明确宿主及存储失败。
         * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
         * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
         * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
         * @throws SQLException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
         */
        T run() throws IOException, ApiException, SQLException;
    }

    /** Seals the activation while preserving unresolved operation evidence. / 封闭启用并保留未核对操作依据。
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    public void close() throws ApiException {
        scope.close();
    }
}
