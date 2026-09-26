package gold.debug.windowstolinux.app.main.mod;

import java.net.URL;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.mod.*;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.mod.lifecycle.*;
import gold.debug.windowstolinux.app.mod.loader.ModClassLoader;
import gold.debug.windowstolinux.app.mod.manifest.ModManifest;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.service.mod.PlatformModCatalog;
import gold.debug.windowstolinux.app.ui.shell.ModPageRegistry;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Composes one boot's selected CLASS graph and owns its reverse shutdown. / 装配一次启动的已选 CLASS 图并持有其逆序关闭。 */
public final class PlatformModCoordinator implements AutoCloseable {
    /** Actual platform and original capability factories. / 实际平台及原能力工厂。 */
    private final PlatformRuntime runtime;

    /** Validated explicit source root. / 已验证明示源码根。 */
    private final Path workspace;

    /** Actual visible window's page registry. / 实际可见窗口的页面登记。 */
    private final ModPageRegistry pages;

    /** Actual compiled public API version. / 实际编译公开 API 版本。 */
    private final SemanticVersion api;

    /** Original restart execution ledger. / 原重启执行账本。 */
    private final ModRestartLedger restarts;

    /** Lifecycle ownership without holding a monitor across author callbacks. / 不跨作者回调持有监视器的生命周期归属。 */
    private final AtomicBoolean operation = new AtomicBoolean();

    /** Exact allocated loaders in dependency-first order, including partial failures. / 包含部分失败的精确已分配加载器，按依赖优先排序。 */
    private final Map<String, Slot> slots = new LinkedHashMap<>();

    /** Original summary identities whose actual results still need acknowledgement. / 实际结果仍需确认的原摘要身份。 */
    private final Map<String, Archive> archives = new LinkedHashMap<>();

    /** Pre-author failures awaiting only durable registration. / 仅等待持久登记的作者执行前失败。 */
    private final Map<String, Rejection> rejected = new LinkedHashMap<>();

    /** Source and owner-specific safe startup feedback. / 来源及所有者专属安全启动反馈。 */
    private final Map<String, Problem> issues = new LinkedHashMap<>();

    /** Whether this graph has already attempted its only boot startup. / 此图是否已经尝试其唯一次启动。 */
    private boolean started;

    /** Whether all original lifecycle and archive ownership has ended. / 全部原生命周期及归档归属是否结束。 */
    private boolean closed;

    /** Binds the selected graph after the desktop presenters exist. / 桌面呈现器存在后绑定已选图。
     * @param runtime original host runtime / 原宿主运行时
     * @param workspace explicit verified workspace / 明示已验证工作区
     * @param pages actual window page registry / 实际窗口页面登记
     * @param api actual host API release / 实际宿主 API 版本
     * @throws SQLException if operation logging is unavailable / 操作日志不可用时 */
    public PlatformModCoordinator(PlatformRuntime runtime, Path workspace, ModPageRegistry pages, SemanticVersion api)
            throws SQLException {
        this.runtime = Objects.requireNonNull(runtime);
        this.workspace = Objects.requireNonNull(workspace);
        this.pages = Objects.requireNonNull(pages);
        this.api = Objects.requireNonNull(api);
        restarts = new ModRestartLedger(runtime.persistence().database());
    }

    /** Startup facts; registered or enabled does not mean running. / 启动事实；已登记或已启用不表示正在运行。
     * @param running owners whose actual sessions reached RUNNING / 实际会话到达 RUNNING 的所有者
     * @param problems safe source or owner failures / 安全来源或所有者失败 */
    public record Report(Set<String> running, List<Problem> problems) {
        /** Freezes observed facts for the desktop. / 为桌面冻结已观察事实。
         * @param running actually running owners / 实际运行所有者
         * @param problems safe feedback / 安全反馈 */
        public Report {
            running = Set.copyOf(running);
            problems = List.copyOf(problems);
        }
    }

    /** Discovers, registers and starts only previously selected enabled releases once. / 仅发现、登记并启动原已选且启用版本一次。
     * @return actual startup facts for presentation / 供呈现的实际启动事实
     * @throws ApiException if another lifecycle call is active or this boot was already attempted / 另一生命周期调用活动中或本次启动已尝试时 */
    public Report start() throws ApiException {
        enter();
        try {
            if (started || closed)
                throw failure("@platform", "MOD_BOOT_ALREADY_ATTEMPTED",
                        "This Mod graph cannot start again in the same boot", ProblemCode.UNAVAILABLE);
            started = true;
            try {
                var discovered = new PlatformModCatalog(runtime.persistence().database()).refresh(workspace);
                discovered.rejections().forEach(r -> issues.put(r.source().toString(), r.problem()));
                var builds = new HashMap<String, PlatformModCatalog.Build>();
                discovered.builds().forEach(b -> builds.put(b.candidate().manifest().id(), b));
                var ready = new LinkedHashMap<String, Prepared>();
                for (var selected : selections()) {
                    Prepared prepared = null;
                    try {
                        prepared = prepare(selected);
                        if (!selected.enabled())
                            continue;
                        var build = builds.get(selected.owner());
                        if (build == null
                                || !Objects.equals(selected.version(),
                                        build.candidate().manifest().version().toString())
                                || !selected.manifest().equals(build.candidate().manifest().json())
                                || !selected.digest().equals(build.contentDigest())) {
                            reject(prepared,
                                    failure(selected.owner(), "MOD_SELECTED_BUILD_UNAVAILABLE",
                                            "The selected release has no matching current development build",
                                            ProblemCode.UNAVAILABLE).problem());
                            continue;
                        }
                        PlatformModCatalog.verify(workspace, build);
                        ready.put(selected.owner(),
                                new Prepared(selected, prepared.schema(), prepared.intent(), build));
                    } catch (Exception | LinkageError | AssertionError failure) {
                        var issue = problem(failure, selected.owner(), "MOD_STARTUP_PREPARATION_UNCONFIRMED");
                        if (prepared != null && selected.enabled())
                            reject(prepared, issue);
                        else
                            issues.put(selected.owner(), issue);
                    }
                }
                var plan = ModDependencyPlan.resolve(
                        ready.values().stream().map(p -> p.build().candidate().manifest()).toList(), api,
                        Runtime.version().feature());
                for (var entry : plan.failures().entrySet())
                    reject(ready.get(entry.getKey()), entry.getValue());
                var providers = new HashMap<String, ModClassLoader>();
                for (var manifest : plan.startupOrder()) {
                    var prepared = ready.get(manifest.id());
                    if (manifest.dependencies().stream().anyMatch(d -> !providers.containsKey(d.id()))) {
                        reject(prepared,
                                failure(manifest.id(), "MOD_PROVIDER_START_FAILED",
                                        "A required original provider did not finish startup", ProblemCode.UNAVAILABLE)
                                        .problem());
                        continue;
                    }
                    Slot slot = null;
                    try {
                        PlatformModCatalog.verify(workspace, prepared.build());
                        var sources = new ArrayList<URL>();
                        for (var source : prepared.build().candidate().descriptor().sources())
                            sources.add(source.toUri().toURL());
                        var loader = plan.createLoader(manifest.id(), sources, providers);
                        slot = new Slot(prepared, loader);
                        slots.put(manifest.id(), slot);
                        slot.session = runtime.modSession(loader, prepared.schema(), ModLaunchMode.NORMAL, pages, api);
                        slot.session.start();
                    } catch (Exception | LinkageError | AssertionError failure) {
                        var problem = problem(failure, manifest.id(), "MOD_START_FAILED");
                        issues.put(manifest.id(), problem);
                        if (slot == null) {
                            reject(prepared, problem);
                        } else {
                            slot.failure = problem;
                            try {
                                if (slot.session != null && slot.session.finalized()) {
                                    slot.unclaimed = failure instanceof SQLException
                                            && failure.getSuppressed().length == 0
                                            && failure.getCause() instanceof ApiException
                                            && !currentFailure(manifest.id());
                                    if (!slot.unclaimed)
                                        confirmFailure(slot.prepared, problem);
                                } else if (slot.session == null && runtime.modContexts().get(manifest.id()) == null) {
                                    slot.loader.close();
                                    confirmFailure(slot.prepared, problem);
                                }
                            } catch (Exception cleanup) {
                                issues.put(manifest.id(),
                                        problem(cleanup, manifest.id(), "MOD_START_CLEANUP_UNCONFIRMED"));
                            }
                        }
                        continue;
                    }
                    if (prepared.intent().isPresent())
                        archives.put(manifest.id(), new Archive(prepared.intent().get(), false));
                    try {
                        archive(manifest.id());
                        providers.put(manifest.id(), slot.loader);
                    } catch (SQLException failure) {
                        issues.put(manifest.id(), problem(failure, manifest.id(), "MOD_START_ARCHIVE_UNCONFIRMED"));
                    }
                }
            } catch (Exception | LinkageError | AssertionError failure) {
                issues.put("@platform", problem(failure, "@platform", "MOD_DISCOVERY_UNAVAILABLE"));
            }
            var running = new TreeSet<String>();
            slots.forEach((owner, slot) -> {
                if (slot.session != null && slot.session.state() == ModRuntimeSession.SessionState.RUNNING)
                    running.add(owner);
            });
            return new Report(running, List.copyOf(issues.values()));
        } finally {
            operation.set(false);
        }
    }

    /** Resolves original requests and READY data before dependency loading. / 在依赖加载前解析原请求及 READY 数据。
     * @param selected original saved selection / 原保存选择
     * @return prepared activation facts / 已准备启用事实
     * @throws Exception if original preparation cannot be confirmed / 原准备无法确认时 */
    private Prepared prepare(Selection selected) throws Exception {
        var pending = new ArrayList<UUID>();
        try (var c = runtime.persistence().database().openConnection();
                var p = c
                        .prepareStatement("SELECT operation_id FROM mod_operation WHERE mod_id=? AND state<>'ENDED'")) {
            p.setString(1, selected.owner());
            try (var r = p.executeQuery()) {
                while (r.next())
                    pending.add(r.getObject(1, UUID.class));
            }
        }
        if (pending.size() > 1)
            throw failure(selected.owner(), "MOD_MAINTENANCE_PENDING", "Original maintenance requires reconciliation",
                    ProblemCode.BUSY);
        Optional<SchemaIdentity> schema = Optional.empty();
        if (!pending.isEmpty()) {
            var applying = restarts.begin(pending.getFirst());
            schema = applying.schema();
            if (!selected.enabled()) {
                archives.put(selected.owner(), new Archive(applying.operation(), false));
                archive(selected.owner());
            }
        } else if (selected.enabled() && selected.schema() != null) {
            try (var c = runtime.persistence().database().openConnection();
                    var p = c.prepareStatement(
                            "SELECT generation,physical_name,database_user,state FROM mod_schema WHERE mod_id=? AND schema_version=?")) {
                p.setString(1, selected.owner());
                p.setString(2, selected.schema());
                try (var r = p.executeQuery()) {
                    if (!r.next() || !"READY".equals(r.getString(4)))
                        throw failure(selected.owner(), "MOD_DATA_NOT_READY",
                                "The selected data version still requires preparation or reconciliation",
                                ProblemCode.UNAVAILABLE);
                    var identity = new SchemaIdentity(selected.owner(), selected.schema(), r.getObject(1, UUID.class));
                    var coordinates = new SchemaCoordinates(identity);
                    if (!coordinates.physicalName().equals(r.getString(2))
                            || !coordinates.databaseUser().equals(r.getString(3)))
                        throw failure(selected.owner(), "MOD_DATA_IDENTITY_CHANGED",
                                "The selected data generation does not match its original identity",
                                ProblemCode.UNAVAILABLE);
                    schema = Optional.of(identity);
                }
            }
        }
        return new Prepared(selected, schema, pending.stream().findFirst(), null);
    }

    /** Reads saved switches and selections without treating older registrations as current sources. / 读取保存开关和单选，不把旧登记当成当前来源。
     * @return saved choices in stable owner order / 按稳定所有者排序的保存选择
     * @throws SQLException if the original catalog cannot be read / 无法读取原目录时 */
    private List<Selection> selections() throws SQLException {
        var result = new ArrayList<Selection>();
        try (var c = runtime.persistence().database().openConnection();
                var p = c.createStatement();
                var r = p.executeQuery(
                        "SELECT r.mod_id,r.enabled,r.selected_version,v.schema_version,v.manifest_json,v.content_sha256 FROM mod_registry r LEFT JOIN mod_version v ON v.mod_id=r.mod_id AND v.version=r.selected_version ORDER BY r.mod_id")) {
            while (r.next())
                result.add(new Selection(r.getString(1), r.getBoolean(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6)));
        }
        return result;
    }

    /** Retains a validation rejection and records it without author construction. / 保留校验拒绝并在不构造作者实例的情况下记录。
     * @param prepared original activation evidence / 原启用证据
     * @param problem known safe failure / 已知安全失败 */
    private void reject(Prepared prepared, Problem problem) {
        String owner = prepared.selection().owner();
        issues.put(owner, problem);
        rejected.put(owner, new Rejection(prepared, problem));
        try {
            confirmFailure(prepared, problem);
        } catch (SQLException failure) {
            if (failure.getSuppressed().length == 0 && failure.getCause() instanceof ApiException)
                rejected.remove(owner);
            issues.put(owner, problem(failure, owner, "MOD_FAILURE_RECORD_UNCONFIRMED"));
        }
    }

    /** Confirms a fully closed failure, retrying only platform metadata. / 确认完全关闭的失败，仅重试平台元数据。
     * @param prepared original selected activation / 原已选启用
     * @param problem original safe diagnostic / 原安全诊断
     * @throws SQLException if failure or its archive cannot be acknowledged / 无法确认失败或其归档时 */
    private void confirmFailure(Prepared prepared, Problem problem) throws SQLException {
        String owner = prepared.selection().owner();
        if (!currentFailure(owner))
            new ModActivationLedger(runtime.persistence().database(), owner, prepared.selection().version(),
                    prepared.schema()).rejectedBeforeStart(problem);
        rejected.remove(owner);
        if (prepared.intent().isPresent())
            archives.put(owner, new Archive(prepared.intent().get(), true));
        archive(owner);
    }

    /** Checks durable evidence without claiming another boot's failed or unknown resources. / 检查持久证据，不取得其他启动失败或未知资源的归属。
     * @param owner exact original owner / 精确原所有者
     * @return whether this boot recorded a fully closed failure / 本次启动是否记录了完全关闭的失败
     * @throws SQLException if evidence cannot be read / 无法读取证据时 */
    private boolean currentFailure(String owner) throws SQLException {
        try (var c = runtime.persistence().database().openConnection();
                var p = c.prepareStatement(
                        "SELECT COUNT(*) FROM mod_registry WHERE mod_id=? AND last_boot_id=? AND last_runtime_state='FAILED' AND actual_version IS NULL AND failure_code IS NOT NULL")) {
            p.setString(1, owner);
            p.setObject(2, runtime.persistence().database().bootId());
            try (var r = p.executeQuery()) {
                r.next();
                return r.getLong(1) == 1;
            }
        }
    }

    /** Completes only the original retained summary without invoking an author callback. / 仅完成原保留摘要，不调用作者回调。
     * @param owner exact original owner / 精确原所有者
     * @throws SQLException if original result remains unconfirmed / 原结果仍未确认时 */
    private void archive(String owner) throws SQLException {
        var pending = archives.get(owner);
        if (pending == null)
            return;
        if (pending.failed())
            restarts.failed(pending.id());
        else
            restarts.applied(pending.id());
        archives.remove(owner);
    }

    /** Closes consumers before providers and preserves failed original cleanup for another attempt. / 在提供方之前关闭使用方，并为再次尝试保留失败的原清理。
     * @throws Exception if startup is still active, original work remains or acknowledgement fails / 启动仍活动、原工作残留或确认失败时 */
    @Override
    public void close() throws Exception {
        enter();
        try {
            if (closed)
                return;
            for (var pending : List.copyOf(rejected.values()))
                confirmFailure(pending.prepared(), pending.problem());
            for (var slot : new ArrayList<>(slots.values()).reversed()) {
                String owner = slot.prepared.selection().owner();
                if (slot.failure == null)
                    archive(owner);
                if (slot.session != null) {
                    slot.session.stop(StopReason.APPLICATION_EXIT);
                } else {
                    var context = runtime.modContexts().get(owner);
                    if (context != null) {
                        if (!context.ownsLoader(slot.loader))
                            throw failure(owner, "MOD_CLEANUP_OWNER_CHANGED", "Original context ownership changed",
                                    ProblemCode.UNKNOWN);
                        context.close();
                    }
                    slot.loader.close();
                }
                if (slot.failure != null && !slot.unclaimed)
                    confirmFailure(slot.prepared, slot.failure);
            }
            for (var owner : List.copyOf(archives.keySet()))
                archive(owner);
            closed = true;
        } finally {
            operation.set(false);
        }
    }

    /** Rejects EDT blocking and concurrent lifecycle work without interrupting author code. / 拒绝 EDT 阻塞和并发生命周期工作，不中断作者代码。
     * @throws ApiException if another lifecycle operation remains / 另一生命周期操作尚未结束时 */
    private void enter() throws ApiException {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Mod graph lifecycle requires a worker thread");
        if (!operation.compareAndSet(false, true))
            throw failure("@platform", "MOD_GRAPH_BUSY", "Mod startup or cleanup is still running", ProblemCode.BUSY);
    }

    /** Creates a safe host failure without parser, SQL or author exception text. / 创建不含解析器、SQL 或作者异常文本的安全宿主失败。
     * @param owner affected owner / 受影响所有者
     * @param event stable event / 稳定事件
     * @param message constant explanation / 常量说明
     * @param code public failure category / 公开失败类别
     * @return safe boundary exception / 安全边界异常 */
    private static ApiException failure(String owner, String event, String message, ProblemCode code) {
        return new ApiException(new Problem(code, event, message, Map.of("ownerId", owner), Optional.empty()));
    }

    /** Preserves original structured evidence or supplies a bounded unconfirmed-state diagnosis. / 保留原结构化证据或提供有界未确认状态诊断。
     * @param failure original internal failure / 原内部失败
     * @param owner affected owner / 受影响所有者
     * @param event fallback event / 回退事件
     * @return safe diagnostic / 安全诊断 */
    private static Problem problem(Throwable failure, String owner, String event) {
        if (failure instanceof SQLException && failure.getSuppressed().length == 0
                && failure.getCause() instanceof ApiException api)
            return api.problem();
        return failure instanceof ApiException api
                ? api.problem()
                : new Problem(ProblemCode.UNKNOWN, event,
                        "Mod startup or cleanup could not be confirmed; inspect the retained state",
                        Map.of("ownerId", owner), Optional.of(UUID.randomUUID()));
    }

    /** Saved selection independent of actual code discovery. / 独立于实际代码发现的保存选择。
     * @param owner stable identity / 稳定身份
     * @param enabled desired switch / 期望开关
     * @param version selected release / 选定版本
     * @param schema declared data version / 声明数据版本
     * @param manifest saved declaration / 保存声明
     * @param digest saved actual content digest / 保存实际内容摘要 */
    private record Selection(String owner, boolean enabled, String version, String schema, String manifest,
            String digest) {
    }

    /** Exact preparation facts for this boot. / 本次启动的精确准备事实。
     * @param selection original choice / 原选择
     * @param schema ready generation / 就绪代次
     * @param intent original pending execution / 原待执行操作
     * @param build current source, absent before source validation / 当前来源，来源核验前可不存在 */
    private record Prepared(Selection selection, Optional<SchemaIdentity> schema, Optional<UUID> intent,
            PlatformModCatalog.Build build) {
    }

    /** Original archive obligation. / 原归档义务。
     * @param id original operation / 原操作
     * @param failed whether a known closed failure is being archived / 是否归档已知已关闭失败 */
    private record Archive(UUID id, boolean failed) {
    }

    /** Metadata-only failure acknowledgement still requiring persistence. / 仍需持久化的仅元数据失败确认。
     * @param prepared original activation / 原启用
     * @param problem original safe failure / 原安全失败 */
    private record Rejection(Prepared prepared, Problem problem) {
    }

    /** Original allocated resources, retained even when construction or cleanup fails. / 原已分配资源，即使构造或清理失败也保留。 */
    private static final class Slot {
        /** Original activation facts. / 原启用事实。 */
        private final Prepared prepared;

        /** Actual allocated code owner. / 实际已分配代码所有者。 */
        private final ModClassLoader loader;

        /** Actual original session, absent when context construction failed. / 实际原会话，上下文构造失败时不存在。 */
        private ModRuntimeSession session;

        /** Safe original startup failure, absent after success. / 安全原启动失败，成功后不存在。 */
        private Problem failure;

        /** Known rejected admission that never acquired a current activation. / 从未取得当前启用的已知被拒准入。 */
        private boolean unclaimed;
        /** Retains the loader before any later allocation can fail. / 在任一后续分配可能失败前保留加载器。
         * @param prepared original facts / 原事实
         * @param loader actual loader / 实际加载器 */
        private Slot(Prepared prepared, ModClassLoader loader) {
            this.prepared = prepared;
            this.loader = loader;
        }
    }
}
