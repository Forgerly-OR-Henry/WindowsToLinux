package gold.debug.windowstolinux.app.service.source;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Callable;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.app.source.boundary.SelectedDirectory;
import gold.debug.windowstolinux.app.source.snapshot.FrozenSourceWork;
import gold.debug.windowstolinux.app.source.snapshot.SourceWorkDirectory;
import gold.debug.windowstolinux.appapi.source.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Binds user-selected files and owned snapshots to the original Mod task. / 将用户所选文件及所属快照绑定到原 Mod 任务。 */
public final class PlatformSourceAccess implements SourceService, AutoCloseable {
    /** Durable identity prefix for owned source work. / 所属来源工作的持久身份前缀。 */
    private static final String PREFIX = "source-snapshot-v1:";

    /** Durable identity prefix for writable task files. / 可写任务文件的持久身份前缀。 */
    private static final String WORKSPACE_PREFIX = "source-workspace-v1:";

    /** Current exclusive storage owner. / 当前独占存储所有者。 */
    private final PlatformDatabase database;

    /** Authentic task and resource authority. / 真实任务及资源授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Current desktop privacy gate. / 当前桌面隐私门禁。 */
    private final DesktopSecurityService security;

    /** Actual directory selection presenter. / 实际目录选择呈现器。 */
    private final DirectoryPicker picker;

    /** Exact activating owner and release. / 精确启用所有者及发布版本。 */
    private final String owner, version;

    /** Synchronous operation ownership. / 同步操作归属。 */
    private final TaskCapabilityScope scope;

    /** Non-forgeable original-object selection grants. / 不可伪造的原对象选择授权。 */
    private final Map<SourceSelection, Selection> selections = new IdentityHashMap<>();

    /** Actual snapshots retained until physical and durable cleanup. / 保留至物理及持久清理完成的实际快照。 */
    private final Set<Snapshot> snapshots = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Writable handles retained until physical and durable cleanup. / 保留至物理及持久清理完成的可写句柄。 */
    private final Set<Workspace> workspaces = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Activation admission state. / 启用准入状态。 */
    private volatile boolean closed;

    /** Reads original user-selection location without granting access from its URI. / 读取原用户选择位置，不允许凭URI获取访问权。
     * @param selection original user-approved object / 原用户批准对象
     * @param task original executing task / 原执行任务
     * @return canonical absolute file URI / 规范绝对文件URI
     * @throws ApiException if the task, selection or root is unavailable / 任务、选择或根不可用时 */
    @Override
    public URI location(SourceSelection selection, TaskContext task) throws ApiException {
        return access(task, () -> {
            Selection selected;
            synchronized (this) {
                selected = selections.get(selection);
            }
            if (selected == null || selected.task() != task || selected.workspace() != null)
                throw error(ProblemCode.ACCESS_DENIED, "SOURCE_LOCATION_SELECTION",
                        "Only an original user-selected directory has a persistent location");
            return selected.directory().location(cancellation(task));
        });
    }

    /** Binds a real Mod activation to the platform source boundary. / 将真实 Mod 启用绑定到平台来源边界。
     * @param database current engine / 当前引擎
     * @param tasks central task runtime / 中央任务运行时
     * @param security current-user privacy gate / 当前用户隐私门禁
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version exact loaded version / 精确加载版本
     * @param picker platform-owned directory selector / 平台持有的目录选择器 */
    public PlatformSourceAccess(PlatformDatabase database, PlatformTaskRuntime tasks, DesktopSecurityService security,
            String owner, String version, DirectoryPicker picker) {
        this.database = Objects.requireNonNull(database);
        this.tasks = Objects.requireNonNull(tasks);
        this.security = Objects.requireNonNull(security);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
        this.picker = Objects.requireNonNull(picker);
        scope = new TaskCapabilityScope(database, tasks, owner, version, Optional.empty(), "source-access-");
    }

    /** Presents explicit directory consent within the current unlock generation. / 在当前解锁代次内呈现明确目录同意。
     * @param title author-provided selection title / 作者提供选择标题
     * @param task original task / 原任务
     * @return original-object directory capability / 原对象目录能力
     * @throws ApiException if selection, identity or cleanup fails / 选择、身份或清理失败时 */
    @Override
    public SourceSelection selectDirectory(String title, TaskContext task) throws ApiException {
        if (title == null || title.isBlank() || title.length() > 4096)
            throw error(ProblemCode.INVALID_INPUT, "SOURCE_TITLE", "Invalid directory selection title");
        return access(task, () -> {
            var cancel = cancellation(task);
            Path selected = security
                    .human(() -> picker.select(title, tasks.requireExecuting(task, owner, version), cancel), cancel);
            tasks.requireExecuting(task, owner, version);
            if (selected == null)
                throw new java.util.concurrent.CancellationException();
            var directory = new SelectedDirectory(selected);
            var name = directory.root().getFileName();
            var grant = new SourceSelection(UUID.randomUUID(),
                    name == null ? directory.root().toString() : name.toString());
            synchronized (this) {
                var active = tasks.snapshots().stream().map(TaskSnapshot::id)
                        .collect(java.util.stream.Collectors.toSet());
                selections.entrySet().removeIf(entry -> !active.contains(entry.getValue().task().id()));
                selections.put(grant, new Selection(task, directory, null));
            }
            return grant;
        });
    }

    /** Reads only original-task selected files under the public byte ceiling. / 仅在公开字节上限内读取原任务所选文件。
     * @param selection exact host selection / 精确宿主选择
     * @param request explicit relative path and budget / 明确相对路径及预算
     * @param task original task / 原任务
     * @return independent bytes and digest / 独立字节及摘要
     * @throws ApiException if input, boundaries or task state fail / 输入、边界或任务状态失败时 */
    @Override
    public SourceFile read(SourceSelection selection, SourceReadRequest request, TaskContext task) throws ApiException {
        if (request == null)
            throw error(ProblemCode.INVALID_INPUT, "SOURCE_REQUEST", "Source read request is required");
        return access(task,
                () -> selection(selection, task).read(request.relativePath(), request.maxBytes(), cancellation(task)));
    }

    /** Lists only an original-task directory under the public entry ceiling. / 仅在公开子项上限内列举原任务目录。
     * @param selection exact host selection / 精确宿主选择
     * @param relativeDirectory original relative directory or empty root / 原相对目录或空根
     * @param maxEntries explicit entry budget / 明确子项预算
     * @param task original task / 原任务
     * @return complete sorted immediate children / 完整有序直接子项
     * @throws ApiException if authorization, path, budget or reading fails / 授权、路径、预算或读取失败时 */
    @Override
    public List<SourceDirectoryEntry> list(SourceSelection selection, String relativeDirectory, int maxEntries,
            TaskContext task) throws ApiException {
        return access(task, () -> selection(selection, task).list(relativeDirectory, maxEntries, cancellation(task)));
    }

    /** Creates and registers a frozen copy of exactly the author-selected files. / 创建并登记精确作者选定文件的冻结副本。
     * @param selection original selection / 原选择
     * @param request explicit file collection / 明确文件集合
     * @param task original task / 原任务
     * @return snapshot retaining actual file ownership / 保留实际文件归属的快照
     * @throws ApiException if capture or cleanup fails / 捕获或清理失败时 */
    @Override
    public SourceSnapshot snapshot(SourceSelection selection, SnapshotRequest request, TaskContext task)
            throws ApiException {
        if (request == null)
            throw error(ProblemCode.INVALID_INPUT, "SOURCE_REQUEST", "Source snapshot request is required");
        return access(task, () -> {
            var source = selection(selection, task);
            FrozenSourceWork.validate(request);
            var id = UUID.randomUUID();
            var lease = tasks.retain(task, new TaskResource("FILE", PREFIX + id, owner, false));
            var snapshot = new Snapshot(task, id, lease);
            synchronized (this) {
                snapshots.add(snapshot);
            }
            boolean published = false;
            try {
                snapshot.work.prepare();
                snapshot.work.capture(source, request, cancellation(task));
                published = true;
                return snapshot;
            } finally {
                synchronized (snapshot) {
                    snapshot.building = false;
                }
                if (!published)
                    snapshot.close();
            }
        });
    }

    /** Creates writable task material with ownership recorded before filesystem changes. / 文件系统变化前登记归属并创建可写任务素材。
     * @param task original executing task / 原执行任务
     * @return original writable handle / 原可写句柄
     * @throws ApiException if creation, admission or cleanup fails / 创建、准入或清理失败时 */
    @Override
    public SourceWorkspace workspace(TaskContext task) throws ApiException {
        return workspace(task, UUID.randomUUID());
    }

    /** Creates only the first physical container for a preselected original UUID. / 只为原预选 UUID 首次创建物理容器。
     * @param task original executing task / 原执行任务
     * @param id persisted workspace identity / 已持久工作目录身份
     * @return original writable handle / 原可写句柄
     * @throws ApiException if identity was used or creation is unconfirmed / 身份已使用或创建未确认时 */
    @Override
    public SourceWorkspace workspace(TaskContext task, UUID id) throws ApiException {
        Objects.requireNonNull(id);
        return access(task, () -> {
            if (tasks.reconciling(task))
                throw error(ProblemCode.CONTRACT_VIOLATION, "SOURCE_RECOVERY_WRITE",
                        "Recovery cannot create new source work");
            var lease = tasks.retain(task, new TaskResource("FILE", WORKSPACE_PREFIX + id, owner, false), null, true);
            var workspace = new Workspace(task, id, lease);
            synchronized (this) {
                workspaces.add(workspace);
            }
            boolean published = false;
            try {
                workspace.work.prepare();
                var directory = new SelectedDirectory(workspace.work.directory());
                synchronized (this) {
                    selections.put(workspace.grant, new Selection(task, directory, workspace));
                }
                SelectedDirectory.cancelled(cancellation(task));
                published = true;
                return workspace;
            } finally {
                synchronized (workspace) {
                    workspace.building = false;
                }
                if (!published)
                    workspace.close();
            }
        });
    }

    /** Reads only a bounded original file after checking the original lease and marked physical container. / 核对原占用及标记物理容器后才读取有界原文件。
     * @param task original context / 原上下文
     * @param workspaceId original workspace UUID / 原工作目录 UUID
     * @param name simple evidence file name / 简单依据文件名
     * @param maxBytes copied byte bound / 复制字节上限
     * @return copied evidence or absent file / 独立依据或文件不存在
     * @throws ApiException if ownership or physical evidence is unconfirmed / 归属或物理依据未确认时 */
    @Override
    public Optional<byte[]> readWorkspaceEvidence(TaskContext task, UUID workspaceId, String name, int maxBytes)
            throws ApiException {
        if (SwingUtilities.isEventDispatchThread())
            throw error(ProblemCode.CONTRACT_VIOLATION, "SERVICE_WORKER", "Service calls require a worker");
        if (closed)
            throw error(ProblemCode.UNAVAILABLE, "SOURCE_CLOSED", "Source capability is closed");
        if (workspaceId == null || name == null || !name.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,127}") || maxBytes < 1
                || maxBytes > 1_048_576)
            throw error(ProblemCode.INVALID_INPUT, "SOURCE_EVIDENCE_INPUT", "Evidence identity or budget is invalid");
        try {
            return scope.call(task, true, () -> readOriginalEvidence(task, workspaceId, name, maxBytes));
        } catch (SQLException failure) {
            throw error(ProblemCode.UNKNOWN, "SOURCE_EVIDENCE_SCOPE",
                    "Original workspace evidence operation could not be safely released");
        }
    }

    /** Copies one original file inside the cleanup-scoped capability lifetime. / 在清理型能力寿命内复制一个原文件。
     * @param task original context / 原上下文
     * @param workspaceId original workspace identity / 原工作目录身份
     * @param name simple file name / 简单文件名
     * @param maxBytes bounded copy size / 有界复制大小
     * @return copied original bytes or confirmed absence / 复制的原字节或已确认不存在
     * @throws ApiException if original physical identity cannot be proven / 原物理身份无法证明时 */
    private Optional<byte[]> readOriginalEvidence(TaskContext task, UUID workspaceId, String name, int maxBytes)
            throws ApiException {
        try (var connection = database.openConnection();
                var query = connection.prepareStatement(
                        "SELECT owner_id,recovery_json,released_at FROM platform_task_resource WHERE task_id=? AND resource_kind='FILE' AND resource_id=?")) {
            query.setObject(1, task.id());
            query.setString(2, WORKSPACE_PREFIX + workspaceId);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !owner.equals(rows.getString(1))
                        || !"{\"release\":\"EXPLICIT\"}".equals(rows.getString(2)) || rows.getObject(3) != null)
                    throw new java.io.IOException("Original workspace lease is unavailable");
                if (rows.next())
                    throw new java.io.IOException("Original workspace lease is duplicated");
            }
            var work = new SourceWorkDirectory(database.root().resolve("work/source-workspace"), task.id(), workspaceId,
                    owner);
            work.recover();
            Path file = work.directory().resolve(name);
            try {
                Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (java.nio.file.NoSuchFileException absent) {
                work.requireOwned();
                return Optional.empty();
            }
            var copied = new SelectedDirectory(work.directory())
                    .read(name, maxBytes, () -> Thread.currentThread().isInterrupted()).content();
            work.requireOwned();
            return Optional.of(copied);
        } catch (Exception failure) {
            throw error(ProblemCode.UNKNOWN, "SOURCE_EVIDENCE_UNCONFIRMED",
                    "Original workspace evidence could not be safely read");
        }
    }

    /** Authenticates original selection objects without deriving authority from copied UUIDs. / 认证原选择对象，不从复制 UUID 推导权限。
     * @param value original selection / 原选择
     * @param task original task / 原任务
     * @return selected directory / 选定目录
     * @throws ApiException if selection belongs to another task or activation / 选择属于其他任务或启用时 */
    private SelectedDirectory selection(SourceSelection value, TaskContext task) throws ApiException {
        Selection selected;
        synchronized (this) {
            selected = selections.get(value);
        }
        if (selected == null || selected.task() != task)
            throw error(ProblemCode.ACCESS_DENIED, "SOURCE_SELECTION", "Source selection is foreign or expired");
        if (selected.workspace() != null)
            selected.workspace().directory();
        return selected.directory();
    }

    /** Runs bounded synchronous work with actual lifetime accounting and safe failures. / 使用实际寿命记账及安全失败运行有界同步工作。
     * @param task original task / 原任务
     * @param action actual source operation / 实际来源操作
     * @param <T> copied result or owned handle / 独立结果或所属句柄
     * @return source result / 来源结果
     * @throws ApiException if authorization, input or I/O fails / 授权、输入或 I/O 失败时 */
    private <T> T access(TaskContext task, Callable<T> action) throws ApiException {
        try {
            return scope.call(task, false, () -> {
                try {
                    return action.call();
                } catch (ApiException failure) {
                    throw failure;
                } catch (java.util.concurrent.CancellationException cancelled) {
                    throw error(ProblemCode.CANCELLED, "SOURCE_CANCELLED", "Source operation was cancelled");
                } catch (IllegalArgumentException invalid) {
                    throw error(ProblemCode.INVALID_INPUT, "SOURCE_REQUEST", "Source paths or budgets are invalid");
                } catch (Exception failure) {
                    throw error(ProblemCode.IO_FAILURE, "SOURCE_UNAVAILABLE",
                            "Source identity, content or storage could not be verified");
                }
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "SOURCE_LEDGER", "Source resource ownership could not be recorded");
        }
    }

    /** Combines original cancellation with activation revocation. / 合并原取消信号及启用撤销。
     * @param task original task / 原任务
     * @return effective cancellation / 生效取消信号 */
    private CancellationToken cancellation(TaskContext task) {
        var signal = tasks.operationCancellation(task);
        return () -> closed || signal.requested();
    }

    /** Reconciles only this original task's local resources without recopying sources. / 仅核对此原任务本地资源，不重新复制来源。
     * @param task original recovery context / 原恢复上下文
     * @throws ApiException if physical cleanup or durable ownership remains uncertain / 物理清理或持久归属仍不确定时 */
    @Override
    public void closeTaskResources(TaskContext task) throws ApiException {
        scope.retryFinishedReleases(task);
        List<Snapshot> owned;
        List<Workspace> writable;
        synchronized (this) {
            owned = List.copyOf(snapshots);
            writable = List.copyOf(workspaces);
        }
        for (var snapshot : owned)
            if (snapshot.task == task)
                snapshot.close();
        for (var workspace : writable)
            if (workspace.task == task)
                workspace.close();
        try {
            var releases = new LinkedHashMap<UUID, String>();
            try (var connection = database.openConnection();
                    var statement = connection.prepareStatement(
                            "SELECT r.lease_id,r.resource_kind,r.resource_id,r.owner_id,r.recovery_json,t.boot_id FROM platform_task_resource r JOIN platform_task t ON t.task_id=r.task_id WHERE r.task_id=? AND r.released_at IS NULL")) {
                statement.setObject(1, task.id());
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if (database.bootId().equals(rows.getObject(6, UUID.class)))
                            return;
                        if (!owner.equals(rows.getString(4)) || !"{\"release\":\"EXPLICIT\"}".equals(rows.getString(5)))
                            continue;
                        String kind = rows.getString(2), resource = rows.getString(3);
                        if (kind.equals("FILE")
                                && (resource.startsWith(PREFIX) || resource.startsWith(WORKSPACE_PREFIX)))
                            releases.put(rows.getObject(1, UUID.class), resource);
                        else if (kind.equals("CUSTOM") && resource.startsWith("source-access-")) {
                            resourceId(resource.substring("source-access-".length()));
                            releases.put(rows.getObject(1, UUID.class), null);
                        }
                    }
                }
            }
            for (var entry : releases.entrySet()) {
                if (entry.getValue() != null) {
                    String resource = entry.getValue();
                    boolean writableWork = resource.startsWith(WORKSPACE_PREFIX);
                    String prefix = writableWork ? WORKSPACE_PREFIX : PREFIX;
                    var work = new SourceWorkDirectory(
                            database.root().resolve(writableWork ? "work/source-workspace" : "work/source"), task.id(),
                            resourceId(resource.substring(prefix.length())), owner);
                    work.recover();
                    work.close();
                }
                new TaskLedger(database).release(task.id(), entry.getKey());
            }
        } catch (Exception failure) {
            throw error(ProblemCode.UNKNOWN, "SOURCE_RECOVERY", "Original source work could not be safely released");
        }
    }

    /** Accepts only canonical host-generated resource identities. / 仅接受规范宿主生成资源身份。
     * @param value persisted identity / 持久身份
     * @return parsed exact identity / 解析后的精确身份 */
    private static UUID resourceId(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value))
            throw new IllegalArgumentException("Non-canonical source resource identity");
        return id;
    }

    /** Seals new work, preserving still-open snapshots for their owners to close. / 封闭新工作，保留仍打开快照供所有者关闭。
     * @throws ApiException if actual source work remains / 仍有实际来源工作时 */
    @Override
    public void close() throws ApiException {
        closed = true;
        scope.close();
        synchronized (this) {
            selections.clear();
            if (!snapshots.isEmpty() || !workspaces.isEmpty())
                throw error(ProblemCode.BUSY, "SOURCE_SNAPSHOTS_OPEN", "Source handles still retain task files");
        }
    }

    /** Builds safe diagnostics without source contents or filesystem exception text. / 构造不含来源内容或文件系统异常文本的安全诊断。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @param message safe explanation / 安全说明
     * @return public error / 公开错误 */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /** Exact original selection ownership. / 精确原选择归属。
     * @param task original object / 原对象
     * @param directory confirmed boundary / 已确认边界
     * @param workspace optional generated-file owner / 可选生成文件所有者 */
    private record Selection(TaskContext task, SelectedDirectory directory, Workspace workspace) {
    }

    /** Host-owned directory chooser implementation. / 宿主持有的目录选择器实现。 */
    @FunctionalInterface
    public interface DirectoryPicker {
        /** Returns a user-confirmed directory after its owned dialog actually closes. / 所属对话框实际关闭后返回用户确认目录。
         * @param title supplied title / 提供标题
         * @param task original task snapshot / 原任务快照
         * @param cancellation cooperative cancellation / 协作取消
         * @return chosen directory or null on cancellation / 选定目录或取消时 null */
        Path select(String title, TaskSnapshot task, CancellationToken cancellation);
    }

    /** Writable staging handle with the same original-task lifetime as its file lease. / 与文件租约共用原任务寿命的可写暂存句柄。 */
    private final class Workspace implements SourceWorkspace {
        /** Authentic original task. / 真实原任务。 */
        private final TaskContext task;

        /** Stable resource identity. / 稳定资源身份。 */
        private final UUID id;

        /** Original opaque source grant. / 原不透明来源授权。 */
        private final SourceSelection grant;

        /** Exact physical ownership. / 精确物理归属。 */
        private final SourceWorkDirectory work;

        /** Durable task occupancy. / 持久任务占用。 */
        private final ResourceLease lease;

        /** Whether creation is still active. / 创建是否仍在活动。 */
        private boolean building = true;

        /** Whether the grant has been revoked for cleanup. / 授权是否已为清理撤销。 */
        private boolean closing;

        /** Whether physical and durable release completed. / 物理及持久释放是否完成。 */
        private boolean released;

        /** Captures the original lease before creating files. / 创建文件前捕获原租约。
         * @param task original task / 原任务
         * @param id exact resource identity / 精确资源身份
         * @param lease durable lease / 持久租约 */
        private Workspace(TaskContext task, UUID id, ResourceLease lease) {
            this.task = task;
            this.id = id;
            this.lease = lease;
            grant = new SourceSelection(UUID.randomUUID(), "Task source workspace");
            work = new SourceWorkDirectory(database.root().resolve("work/source-workspace"), task.id(), id, owner);
        }

        /** Returns the original resource identity. / 返回原资源身份。
         * @return original resource identity / 原资源身份 */
        @Override
        public UUID id() {
            return id;
        }

        /** Rechecks the executing task and physical directory before writable access. / 可写访问前复核执行任务及物理目录。
         * @return original writable payload / 原可写载荷
         * @throws ApiException if admission or physical identity fails / 准入或物理身份失败时 */
        @Override
        public synchronized Path directory() throws ApiException {
            return access(task, () -> {
                if (building || closing || released)
                    throw error(ProblemCode.ACCESS_DENIED, "SOURCE_WORKSPACE_CLOSED",
                            "Source workspace is unavailable");
                if (tasks.reconciling(task))
                    throw error(ProblemCode.CONTRACT_VIOLATION, "SOURCE_RECOVERY_WRITE",
                            "Recovery cannot reopen writable source work");
                SelectedDirectory.cancelled(cancellation(task));
                return work.directory();
            });
        }

        /** Revalidates before returning the original source grant. / 返回原来源授权前重新核验。
         * @return exact original grant / 精确原授权
         * @throws ApiException if access is unavailable / 访问不可用时 */
        @Override
        public synchronized SourceSelection selection() throws ApiException {
            directory();
            return grant;
        }

        /** Revokes the grant and deletes physical files before releasing durable occupancy. / 释放持久占用前撤销授权并删除物理文件。
         * @throws ApiException if physical or durable cleanup remains uncertain / 物理或持久清理仍不确定时 */
        @Override
        public synchronized void close() throws ApiException {
            if (released)
                return;
            if (SwingUtilities.isEventDispatchThread() || building)
                throw error(ProblemCode.BUSY, "SOURCE_WORKSPACE_ACTIVE",
                        "Workspace cleanup requires returned creation on a worker");
            closing = true;
            synchronized (PlatformSourceAccess.this) {
                selections.remove(grant);
            }
            try {
                work.close();
                lease.close();
            } catch (IOException failure) {
                tasks.reportUncertainEffect(task);
                throw error(ProblemCode.UNKNOWN, "SOURCE_WORKSPACE_CLOSE",
                        "Source work files remain pending safe cleanup");
            }
            released = true;
            synchronized (PlatformSourceAccess.this) {
                workspaces.remove(this);
            }
        }
    }

    /** Snapshot handle whose lifetime extends beyond the synchronous capture callback. / 寿命超过同步捕获回调的快照句柄。 */
    private final class Snapshot implements SourceSnapshot {
        /** Authentic original task. / 真实原任务。 */
        private final TaskContext task;

        /** Unique snapshot identity. / 唯一快照身份。 */
        private final UUID id;

        /** Actual work directory and cleanup state. / 实际工作目录及清理状态。 */
        private final FrozenSourceWork work;

        /** Explicit task file occupancy. / 明确任务文件占用。 */
        private final ResourceLease lease;

        /** Whether physical capture is still running. / 物理捕获是否仍在运行。 */
        private boolean building = true;

        /** Whether actual and durable cleanup both completed. / 实际及持久清理是否均已完成。 */
        private boolean released;

        /** Captures cleanup ownership before touching the filesystem. / 触碰文件系统前捕获清理归属。
         * @param task original task / 原任务
         * @param id snapshot identity / 快照身份
         * @param lease registered file occupancy / 已登记文件占用 */
        private Snapshot(TaskContext task, UUID id, ResourceLease lease) {
            this.task = task;
            this.id = id;
            this.lease = lease;
            work = new FrozenSourceWork(database.root().resolve("work/source"), task.id(), id, owner);
        }

        /** Returns the immutable opaque identity. / 返回不可变不透明身份。
         * @return snapshot identity / 快照身份 */
        @Override
        public UUID id() {
            return id;
        }

        /** Revalidates before returning the aggregate digest. / 返回聚合摘要前重新核验。
         * @return frozen content identity / 冻结内容身份 */
        @Override
        public synchronized String sha256() {
            verify();
            return work.sha256();
        }

        /** Revalidates before exposing the task-owned read-only directory. / 暴露任务所属只读目录前重新核验。
         * @return frozen directory / 冻结目录 */
        @Override
        public synchronized Path directory() {
            verify();
            return work.directory();
        }

        /** Rejects closed, cancelled or changed views away from the EDT. / 在 EDT 之外拒绝已关闭、取消或变化的视图。 */
        private void verify() {
            if (SwingUtilities.isEventDispatchThread() || released || building)
                throw new IllegalStateException("Snapshot access requires a live handle on a worker");
            try {
                tasks.requireContext(task);
                work.verify(cancellation(task));
            } catch (Exception failure) {
                throw new IllegalStateException("Snapshot content or task identity is no longer available");
            }
        }

        /** Removes actual files before releasing durable task occupancy. / 释放持久任务占用前移除实际文件。
         * @throws ApiException if physical or durable release remains / 物理或持久释放仍未完成时 */
        @Override
        public synchronized void close() throws ApiException {
            if (released)
                return;
            if (SwingUtilities.isEventDispatchThread() || building)
                throw error(ProblemCode.BUSY, "SOURCE_CAPTURE_ACTIVE",
                        "Snapshot cleanup requires a returned capture on a worker");
            try {
                work.close();
                lease.close();
            } catch (IOException failure) {
                tasks.reportUncertainEffect(task);
                throw error(ProblemCode.UNKNOWN, "SOURCE_CLOSE", "Snapshot files remain pending safe cleanup");
            }
            released = true;
            synchronized (PlatformSourceAccess.this) {
                snapshots.remove(this);
            }
        }
    }
}
