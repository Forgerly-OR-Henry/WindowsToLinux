package gold.debug.windowstolinux.app.main.mod;

import java.sql.Connection;
import java.util.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.mod.lifecycle.ModRuntimeSession.ContextLease;
import gold.debug.windowstolinux.app.mod.loader.ModClassLoader;
import gold.debug.windowstolinux.app.service.database.TaskModDatabase;
import gold.debug.windowstolinux.app.service.mod.ModHostLease;
import gold.debug.windowstolinux.app.ui.shell.ModPageRegistry;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.db.*;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.secret.SecretService;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.source.SourceService;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.UiService;

/** Owns complete capabilities and staged contributions of one actual Mod activation. / 持有一次实际 Mod 启用的完整能力及暂存扩展。 */
public final class PlatformModContext implements ContextLease {
    /** Actual host capability factory. / 实际宿主能力工厂。 */
    private final PlatformRuntime runtime;

    /** Original prepared loader, owned by the surrounding runtime session. / 原已准备加载器，由外围运行会话持有。 */
    private final ModClassLoader loader;

    /** Exact host-selected schema, absent for stateless Mods. / 宿主精确选定数据结构，无状态 Mod 不存在。 */
    private final Optional<SchemaIdentity> schema;

    /** Immutable original activation identity. / 不可变原启用身份。 */
    private final ModIdentity identity;

    /** Normal or isolated recovery mode. / 普通或隔离恢复模式。 */
    private final ModLaunchMode mode;

    /** Actual desktop page lifetime owner. / 实际桌面页面寿命所有者。 */
    private final ModPageRegistry pages;

    /** Host bookkeeping released only after all actual cleanup succeeds. / 仅在全部实际清理成功后释放的宿主登记。 */
    private final Runnable released;

    /** Partial construction ownership, retained when cleanup fails. / 部分构造归属，清理失败时保留。 */
    private final List<ModHostLease.Contribution> contributions = new ArrayList<>();

    /** Complete context lease, present only after all capabilities were constructed. / 完整上下文占用，仅在全部能力构造完成后存在。 */
    private ModHostLease lease;

    /** Whether preparation was attempted, forbidding accidental replay. / 是否已尝试准备，禁止意外重放。 */
    private boolean attempted;

    /** Whether every owned resource was actually released. / 所有所属资源是否已实际释放。 */
    private boolean closed;

    /** Captures exact host-selected identity before allocating capabilities. / 分配能力前捕获宿主精确选定身份。
     * @param runtime actual platform runtime / 实际平台运行时
     * @param loader prepared original loader / 已准备原加载器
     * @param schema selected original generation / 选定原代次
     * @param mode activation mode / 启用模式
     * @param pages actual desktop page registry / 实际桌面页面登记
     * @param released host release bookkeeping / 宿主释放登记 */
    public PlatformModContext(PlatformRuntime runtime, ModClassLoader loader, Optional<SchemaIdentity> schema,
            ModLaunchMode mode, ModPageRegistry pages, Runnable released) {
        this.runtime = Objects.requireNonNull(runtime);
        this.loader = Objects.requireNonNull(loader);
        this.schema = Objects.requireNonNull(schema);
        this.mode = Objects.requireNonNull(mode);
        this.pages = Objects.requireNonNull(pages);
        this.released = Objects.requireNonNull(released);
        var manifest = loader.manifest();
        identity = new ModIdentity(manifest.id(), manifest.version().toString(), manifest.schemaVersion());
        if (loader.isClosed() || !manifest.schemaVersion().equals(schema.map(SchemaIdentity::schemaVersion))
                || schema.isPresent() && !schema.get().ownerId().equals(identity.id()))
            throw new IllegalArgumentException("Context identity differs from the actual loader and schema");
    }

    /** Constructs every real capability while owner admission remains sealed. / 所有者准入保持封闭时构造全部真实能力。
     * @throws Exception if data validation or any capability preparation fails / 数据校验或任一能力准备失败时 */
    public synchronized void prepare() throws Exception {
        worker();
        if (attempted || closed)
            throw new IllegalStateException("Context preparation cannot be repeated");
        attempted = true;
        runtime.tasks().blockOwner(identity.id(), "Mod context is preparing");
        if (schema.isPresent())
            new SchemaCoordinates(schema.get()).requireRegistered(runtime.persistence().database(), Set.of("READY"));
        var pageScope = pages.begin(identity.id(), mode == ModLaunchMode.RECOVERY);
        contributions.add(new OwnedContribution(pageScope::commit, pageScope));
        var services = runtime.services().bind(loader, schema, mode);
        contributions.add(services);
        var logs = own(runtime.logs(identity.id(), identity.version(), schema));
        var remote = own(runtime.remoteCapabilities(identity.id(), identity.version()));
        var servers = own(runtime.serverAccess(identity.id(), identity.version(), schema));
        var ai = own(runtime.ai(identity.id(), identity.version()));
        var secrets = own(runtime.secrets(identity.id(), identity.version(), schema));
        var source = own(runtime.source(identity.id(), identity.version()));
        var processes = own(runtime.processes(identity.id(), identity.version()));
        var tasks = runtime.tasks().bind(identity.id(), identity.version(), mode == ModLaunchMode.RECOVERY);
        ModDatabase database = schema.isPresent()
                ? own(new TaskModDatabase(runtime.persistence().database(), runtime.tasks(), schema.get()))
                : new StatelessDatabase();
        var context = new BoundContext(identity, mode, pageScope, tasks, remote.interaction(), servers, remote.linux(),
                ai, database, secrets, source, logs, processes, services);
        lease = new ModHostLease(context, runtime.tasks(), contributions);
    }

    /** Retains a concrete capability before constructing the next one. / 构造下一个能力前保留具体能力。
     * @param <T> actual capability type / 实际能力类型
     * @param resource original capability / 原能力
     * @return same capability / 同一能力 */
    private <T extends AutoCloseable> T own(T resource) {
        contributions.add(new OwnedContribution(() -> {
        }, resource));
        return resource;
    }

    /** Returns the complete bound public context only while the activation is owned. / 仅在启用被持有时返回完整绑定公开上下文。
     * @return actual public capabilities / 实际公开能力 */
    @Override
    public synchronized ModContext context() {
        if (lease == null || closed)
            throw new IllegalStateException("Mod context is unavailable");
        return lease.context();
    }

    /** Publishes staged pages and services after actual author startup. / 实际作者启动完成后发布暂存页面及服务。
     * @throws Exception if preparation, lifecycle or publication is unavailable / 准备、生命周期或发布不可用时 */
    @Override
    public synchronized void commit() throws Exception {
        worker();
        if (lease == null || closed)
            throw new IllegalStateException("Mod context is unavailable");
        lease.commit();
    }

    /** Seals new task and dependency admission before lifecycle occupancy checks. / 生命周期占用检查前封闭新任务及依赖准入。 */
    @Override
    public synchronized void seal() {
        if (lease != null)
            lease.seal();
        else
            runtime.tasks().blockOwner(identity.id(), "Mod context is stopping");
    }

    /** Reports actual original-owner and provider task blockers. / 报告实际原所有者及提供方任务阻断。
     * @return original retained task descriptions / 原保留任务描述
     * @throws Exception if authoritative occupancy cannot be read / 无法读取权威占用时 */
    @Override
    public List<String> blockers() throws Exception {
        return runtime.tasks().ownerBlockers(identity.id());
    }

    /** Closes completed or partial construction in reverse order without forgetting failed cleanup. / 按逆序关闭完整或部分构造，不遗忘失败清理。
     * @throws Exception if task ownership, physical resources or persistent cleanup remains / 任务归属、物理资源或持久清理仍存在时 */
    @Override
    public synchronized void close() throws Exception {
        worker();
        if (closed)
            return;
        seal();
        if (lease != null)
            lease.close();
        else {
            if (!blockers().isEmpty())
                throw new ApiException(new Problem(ProblemCode.BUSY, "MOD_CONTEXT_RETAINED",
                        "Original Mod tasks still require reconciliation", Map.of(), Optional.empty()));
            while (!contributions.isEmpty()) {
                contributions.getLast().close();
                contributions.removeLast();
            }
            runtime.tasks().revokeOwnerServices(identity.id(), identity.version());
        }
        closed = true;
        released.run();
    }

    /** Keeps capability assembly and resource closure off the event thread. / 将能力装配及资源关闭保持在事件线程之外。 */
    private static void worker() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Mod context lifecycle requires a worker");
    }

    /** Pairs a concrete publication action with its actual original close owner. / 将具体发布动作与实际原关闭所有者配对。
     * @param publication staged publication / 暂存发布
     * @param resource actual close owner / 实际关闭所有者 */
    private record OwnedContribution(Publication publication,
            AutoCloseable resource) implements ModHostLease.Contribution {
        /** Publishes the already prepared contribution. / 发布已准备扩展。
         * @throws Exception if publication fails / 发布失败时 */
        @Override
        public void publish() throws Exception {
            publication.run();
        }

        /** Closes the actual capability. / 关闭实际能力。
         * @throws Exception if actual cleanup fails / 实际清理失败时 */
        @Override
        public void close() throws Exception {
            resource.close();
        }
    }

    /** Checked staged publication, with no background work implied. / 受检暂存发布，不隐含后台工作。 */
    @FunctionalInterface
    private interface Publication {
        /** Publishes one prepared host contribution. / 发布一个已准备宿主扩展。
         * @throws Exception if publication fails / 发布失败时 */
        void run() throws Exception;
    }

    /** Complete immutable capability references supplied to actual author code. / 提供给实际作者代码的完整不可变能力引用。
     * @param identity actual original identity / 实际原身份
     * @param launchMode normal or recovery mode / 普通或恢复模式
     * @param ui owner-bound pages / 所有者绑定页面
     * @param tasks original task admission / 原任务准入
     * @param interaction exact human review / 精确人工审核
     * @param servers original server access / 原服务器访问
     * @param linux original transport access / 原传输访问
     * @param ai original model access / 原模型访问
     * @param db exact business generation / 精确业务代次
     * @param secrets original secret access / 原秘密访问
     * @param source original source access / 原来源访问
     * @param logs original diagnostic owner / 原诊断所有者
     * @param processes original process owner / 原进程所有者
     * @param services original service activation / 原服务启用 */
    private record BoundContext(ModIdentity identity, ModLaunchMode launchMode, UiService ui, TaskService tasks,
            InteractionService interaction, ServerService servers, LinuxService linux, AiService ai, ModDatabase db,
            SecretService secrets, SourceService source, LogService logs, ProcessService processes,
            ModServices services) implements ModContext {
    }

    /** Explicit unavailable database for an author that declares no schema. / 未声明数据结构的作者所用明确不可用数据库。 */
    private static final class StatelessDatabase implements ModDatabase {
        /** Reports that this Mod intentionally has no business database. / 报告此 Mod 明确没有业务数据库。
         * @return never returns a fabricated identity / 不返回虚构身份
         * @throws ApiException always, because no schema was declared / 总是抛出，因为未声明数据结构 */
        @Override
        public SchemaIdentity identity() throws ApiException {
            throw unavailable();
        }

        /** Refuses connections without allocating any schema or platform connection. / 拒绝连接，不分配任何数据结构或平台连接。
         * @param task supplied task / 提供的任务
         * @return never returns a connection / 不返回连接
         * @throws ApiException always, because no schema was declared / 总是抛出，因为未声明数据结构 */
        @Override
        public Connection openConnection(TaskContext task) throws ApiException {
            throw unavailable();
        }

        /** Builds the explicit unavailable-capability diagnostic. / 构造明确能力不可用诊断。
         * @return structured public rejection / 结构化公开拒绝 */
        private static ApiException unavailable() {
            return new ApiException(new Problem(ProblemCode.UNAVAILABLE, "MOD_NO_SCHEMA",
                    "This Mod has no declared business schema", Map.of(), Optional.empty()));
        }
    }
}
