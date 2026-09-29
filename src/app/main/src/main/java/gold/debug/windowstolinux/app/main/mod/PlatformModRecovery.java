package gold.debug.windowstolinux.app.main.mod;

import java.net.URL;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.mod.ModRecoveryLedger;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.mod.lifecycle.*;
import gold.debug.windowstolinux.app.mod.loader.ModClassLoader;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.service.mod.PlatformModPrograms;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Owns isolated original-release recovery groups before normal boot assembly. / 在普通启动装配前持有隔离的原发布恢复组。 */
final class PlatformModRecovery {
    /** Original host capabilities. / 原宿主能力。 */
    private final PlatformModHost runtime;

    /** Exact verified code source. / 精确已核验代码来源。 */
    private final PlatformModPrograms programs;

    /** Existing hidden recovery page scopes. / 已有隐藏恢复页面作用域。 */
    private final ModPageRegistry pages;

    /** Actual host API release. / 实际宿主 API 发布。 */
    private final SemanticVersion api;

    /** Original task facts. / 原任务事实。 */
    private final TaskLedger tasks;

    /** Durable session ownership beyond task archival. / 任务归档之外的持久会话归属。 */
    private final ModRecoveryLedger ledger;

    /** Owners excluded from ordinary activation for this entire boot. / 本次整个启动排除普通启用的所有者。 */
    private final Set<String> blocked = new HashSet<>();

    /** Complete allocated groups, including partial startup failures. / 完整已分配组，包含部分启动失败。 */
    private final List<Group> groups = new ArrayList<>();

    /** Binds the automatic recovery owner to existing platform services. / 将自动恢复所有者绑定到已有平台服务。
     * @param runtime actual host / 实际宿主
     * @param programs exact source resolver / 精确来源解析器
     * @param pages actual page registry / 实际页面登记
     * @param api host API release / 宿主 API 发布
     * @throws SQLException if durable recovery cannot be initialized / 无法初始化持久恢复时 */
    PlatformModRecovery(PlatformModHost runtime, PlatformModPrograms programs, ModPageRegistry pages,
            SemanticVersion api) throws SQLException {
        this.runtime = runtime;
        this.programs = programs;
        this.pages = pages;
        this.api = api;
        tasks = new TaskLedger(runtime.persistence().database());
        ledger = new ModRecoveryLedger(runtime.persistence().database());
    }

    /** Collects original facts, validates complete groups and starts only isolated recovery contexts. / 收集原事实、核验完整组，并仅启动隔离恢复上下文。
     * @return safe per-owner recovery status / 安全按所有者恢复状态
     * @throws SQLException if the durable inventory cannot be established / 无法确立持久清单时 */
    Map<String, Problem> start() throws SQLException {
        var issues = new LinkedHashMap<String, Problem>();
        var candidates = pending(issues);
        var invalid = collectTasks(candidates, issues);
        for (var candidate : merge(candidates)) {
            var graph = new TaskRecoveryGraph(candidate.releases().getFirst().owner(), candidate.releases());
            if (candidate.conflict()
                    || candidate.releases().stream().anyMatch(value -> invalid.contains(value.owner()))) {
                diagnose(issues, graph, "MOD_RECOVERY_GRAPH_CONFLICT");
                continue;
            }
            try {
                var builds = new LinkedHashMap<String, PlatformModPrograms.Program>();
                for (var release : graph.releases()) {
                    var build = programs.read(release.owner(), release.version());
                    if (!release.digest().equals(build.contentDigest())
                            || !release.schema().map(s -> s.schemaVersion()).equals(build.manifest().schemaVersion()))
                        throw new SQLException("Original recovery program differs");
                    builds.put(release.owner(), build);
                }
                for (var original : candidate.originals())
                    closure(original, builds);
                var plan = ModDependencyPlan.resolve(builds.values().stream().map(value -> value.manifest()).toList(),
                        api, Runtime.version().feature());
                if (!plan.failures().isEmpty())
                    throw new SQLException("Original recovery graph is incompatible");
                ModRecoveryLedger.Recovery receipt;
                if (candidate.retained().isEmpty())
                    receipt = ledger.retain(graph);
                else {
                    var old = candidate.retained().getFirst();
                    if (candidate.retained().size() != 1
                            || !new HashSet<>(old.graph().releases()).equals(new HashSet<>(graph.releases())))
                        throw new SQLException("Persisted recovery groups overlap or changed");
                    receipt = ledger.resume(old);
                }
                var group = new Group(receipt);
                groups.add(group);
                activate(group, plan, builds);
                diagnose(issues, graph, "MOD_RECOVERY_READY");
            } catch (Exception | LinkageError | AssertionError failure) {
                diagnose(issues, graph, "MOD_RECOVERY_UNAVAILABLE");
            }
        }
        for (String owner : blocked)
            runtime.tasks().blockOwner(owner, "Original Mod recovery requires a later normal restart");
        try {
            releaseReady();
        } catch (Exception failure) {
            issues.put("@recovery", problem("@platform", "MOD_RECOVERY_CLEANUP_PENDING"));
        }
        issues.replaceAll((owner, issue) -> issue.eventCode().equals("MOD_RECOVERY_READY")
                && !runtime.modContexts().containsKey(owner) ? problem(owner, "MOD_RECOVERY_RESTART_REQUIRED") : issue);
        return issues;
    }

    /** Reads exact retained task graphs and marks unverifiable identities before loading code. / 在加载代码前读取精确保留任务图并标记不可核验身份。
     * @param candidates original graph collection / 原图集合
     * @param issues safe task recovery feedback / 安全任务恢复反馈
     * @return identities whose original graph cannot be established / 无法确立原图的身份
     * @throws SQLException if retained task inventory cannot be read / 无法读取保留任务清单时 */
    private Set<String> collectTasks(List<Candidate> candidates, Map<String, Problem> issues) throws SQLException {
        var invalid = new HashSet<String>();
        for (UUID task : tasks.queries().retained()) {
            var snapshot = tasks.queries().snapshot(task);
            if (snapshot.ownerId().equals("@platform"))
                continue;
            blocked.add(snapshot.ownerId());
            var affected = new HashSet<String>();
            affected.add(snapshot.ownerId());
            try (var c = runtime.persistence().database().openConnection();
                    var p = c.prepareStatement(
                            "SELECT owner_id FROM platform_task_resource WHERE task_id=? AND released_at IS NULL AND resource_kind IN ('MOD','SCHEMA')")) {
                p.setObject(1, task);
                try (var r = p.executeQuery()) {
                    while (r.next())
                        affected.add(r.getString(1));
                }
            }
            blocked.addAll(affected);
            TaskRecoveryGraph graph = null;
            try {
                graph = tasks.queries().recoveryGraph(task)
                        .orElseThrow(() -> new SQLException("Original recovery graph is absent"));
                block(graph);
                for (var dependency : tasks.queries().dependencies(task).keySet()) {
                    var original = graph.release(dependency.provider());
                    if (!original.version().equals(dependency.version())
                            || !original.schema().equals(dependency.schema()))
                        throw new SQLException("Retained dependency differs from original graph");
                }
                candidates.add(new Candidate(List.of(graph), graph.releases(), List.of(), false));
            } catch (SQLException | IllegalArgumentException | NoSuchElementException failure) {
                invalid.addAll(affected);
                if (graph != null)
                    graph.releases().forEach(value -> invalid.add(value.owner()));
                issues.put(snapshot.ownerId(), problem(snapshot.ownerId(), "MOD_RECOVERY_GRAPH_UNAVAILABLE"));
            }
        }
        return invalid;
    }

    /** Resumes session inventory and archives already verified cleanup without loading author code. / 恢复会话清单，并在不加载作者代码的情况下归档已核验清理。
     * @param issues safe startup feedback / 安全启动反馈
     * @return original unfinished session candidates / 原未完成会话候选
     * @throws SQLException if original inventory is inconsistent / 原清单不一致时 */
    private List<Candidate> pending(Map<String, Problem> issues) throws SQLException {
        var candidates = new ArrayList<Candidate>();
        for (var recovery : ledger.pending()) {
            block(recovery.graph());
            if (recovery.verified()) {
                try {
                    ledger.archive(recovery);
                    diagnose(issues, recovery.graph(), "MOD_RECOVERY_RESTART_REQUIRED");
                } catch (SQLException failure) {
                    groups.add(new Group(recovery));
                    diagnose(issues, recovery.graph(), "MOD_RECOVERY_ARCHIVE_PENDING");
                }
            } else
                candidates.add(new Candidate(List.of(), recovery.graph().releases(), List.of(recovery), false));
        }
        return candidates;
    }

    /** Excludes affected owners even after their recovery completes in this boot. / 即使恢复在本次启动完成，也排除受影响所有者。
     * @param owner selected owner / 已选所有者
     * @return whether normal startup is excluded / 是否排除普通启动 */
    boolean blocks(String owner) {
        return blocked.contains(owner);
    }

    /** Stops only completely drained connected groups, preserving originals on failure. / 仅停止完全排空的连通组，失败时保留原对象。
     * @throws Exception if actual cleanup or its acknowledgement fails / 实际清理或其确认失败时 */
    void releaseReady() throws Exception {
        for (var group : List.copyOf(groups)) {
            boolean busy = false;
            for (var release : group.receipt.graph().releases())
                if (!runtime.tasks().ownerBlockers(release.owner()).isEmpty()) {
                    busy = true;
                    break;
                }
            if (busy)
                continue;
            if (!group.receipt.verified()) {
                for (var slot : new ArrayList<>(group.slots.values()).reversed()) {
                    if (slot.session != null)
                        slot.session.stop(StopReason.RECOVERY_FINISHED);
                    else {
                        var context = runtime.modContexts().get(slot.loader.manifest().id());
                        if (context != null) {
                            if (!context.ownsLoader(slot.loader))
                                throw new SQLException("Original recovery context changed");
                            context.close();
                        }
                        slot.loader.close();
                    }
                }
                group.receipt = ledger.released(group.receipt);
            }
            ledger.archive(group.receipt);
            groups.remove(group);
        }
    }

    /** Finishes original recovery ownership before platform exit. / 在平台退出前完成原恢复归属。
     * @throws Exception if original tasks, resources or metadata remain / 原任务、资源或元数据残留时 */
    void close() throws Exception {
        releaseReady();
        if (!groups.isEmpty())
            throw new SQLException("Original Mod recovery still owns resources");
    }

    /** Starts dependency-first while retaining each loader before further allocation. / 按依赖优先启动，并在继续分配前保留每个加载器。
     * @param group durable original group / 持久原组
     * @param plan validated original dependency order / 已核验原依赖顺序
     * @param builds verified original programs / 已核验原程序
     * @throws Exception if original startup fails / 原启动失败时 */
    private void activate(Group group, ModDependencyPlan plan, Map<String, PlatformModPrograms.Program> builds)
            throws Exception {
        var providers = new HashMap<String, ModClassLoader>();
        for (var manifest : plan.startupOrder()) {
            var build = builds.get(manifest.id());
            programs.verify(build);
            var sources = new ArrayList<URL>();
            for (var source : build.sources())
                sources.add(source.toUri().toURL());
            var loader = plan.createLoader(manifest.id(), sources, providers);
            var slot = new Slot(loader);
            group.slots.put(manifest.id(), slot);
            slot.session = runtime.modSession(loader, group.receipt.graph().release(manifest.id()).schema(),
                    ModLaunchMode.RECOVERY, pages, api);
            slot.session.start();
            providers.put(manifest.id(), loader);
        }
    }

    /** Requires each admitted task graph to contain exactly its original transitive declarations. / 要求每个准入任务图恰好包含其原传递声明。
     * @param graph original admission snapshot / 原准入快照
     * @param builds verified original programs / 已核验原程序
     * @throws SQLException if declarations are missing or unrelated members were inserted / 声明缺失或插入无关成员时 */
    private static void closure(TaskRecoveryGraph graph, Map<String, PlatformModPrograms.Program> builds)
            throws SQLException {
        var reached = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(graph.owner());
        while (!queue.isEmpty()) {
            String owner = queue.removeFirst();
            if (!reached.add(owner))
                continue;
            var build = builds.get(owner);
            if (build == null)
                throw new SQLException("Original provider is absent");
            build.manifest().dependencies().forEach(dependency -> queue.add(dependency.id()));
        }
        if (!reached.equals(graph.releases().stream().map(TaskRecoveryGraph.Release::owner)
                .collect(java.util.stream.Collectors.toSet())))
            throw new SQLException("Original declaration closure differs");
    }

    /** Merges shared providers without accepting conflicting original identities. / 合并共享提供方，但不接受冲突原身份。
     * @param candidates original task and session graphs / 原任务及会话图
     * @return independent connected groups / 独立连通组 */
    private static List<Candidate> merge(List<Candidate> candidates) {
        var result = new ArrayList<Candidate>();
        for (var candidate : candidates) {
            var releases = new TreeMap<String, TaskRecoveryGraph.Release>();
            candidate.releases().forEach(value -> releases.put(value.owner(), value));
            var originals = new ArrayList<>(candidate.originals());
            var retained = new ArrayList<>(candidate.retained());
            boolean conflict = candidate.conflict();
            boolean changed;
            do {
                changed = false;
                for (var previous : List.copyOf(result)) {
                    if (previous.releases().stream().noneMatch(value -> releases.containsKey(value.owner())))
                        continue;
                    for (var release : previous.releases()) {
                        var old = releases.putIfAbsent(release.owner(), release);
                        if (old != null && !old.equals(release))
                            conflict = true;
                    }
                    conflict |= previous.conflict();
                    originals.addAll(previous.originals());
                    retained.addAll(previous.retained());
                    result.remove(previous);
                    changed = true;
                }
            } while (changed);
            result.add(new Candidate(originals, List.copyOf(releases.values()), retained, conflict));
        }
        return result;
    }

    /** Captures every affected identity before any ordinary preparation can run. / 在任一普通准备运行前捕获全部受影响身份。
     * @param graph original graph / 原图 */
    private void block(TaskRecoveryGraph graph) {
        graph.releases().forEach(value -> blocked.add(value.owner()));
    }

    /** Reports the same safe group status for all its original members. / 为全部原成员报告相同安全组状态。
     * @param issues startup feedback / 启动反馈
     * @param graph affected original graph / 受影响原图
     * @param event stable status / 稳定状态 */
    private static void diagnose(Map<String, Problem> issues, TaskRecoveryGraph graph, String event) {
        graph.releases().forEach(value -> issues.put(value.owner(), problem(value.owner(), event)));
    }

    /** Creates bounded feedback without author, SQL or path details. / 创建不含作者、SQL 或路径详情的有界反馈。
     * @param owner affected identity / 受影响身份
     * @param event stable event / 稳定事件
     * @return safe recovery status / 安全恢复状态 */
    private static Problem problem(String owner, String event) {
        if (event.equals("MOD_RECOVERY_RESTART_REQUIRED"))
            return new Problem(ProblemCode.BUSY, event,
                    "Original recovery completed; restart the application for normal activation",
                    Map.of("ownerId", owner), Optional.empty());
        return new Problem(event.equals("MOD_RECOVERY_READY") ? ProblemCode.BUSY : ProblemCode.UNAVAILABLE, event,
                event.equals("MOD_RECOVERY_READY")
                        ? "Original recovery session is ready; reconcile retained tasks before restarting normally"
                        : "Original Mod recovery remains unavailable or incomplete; retained ownership is preserved",
                Map.of("ownerId", owner), Optional.empty());
    }

    /** Graphs grouped by their shared exact providers. / 按共享精确提供方分组的图。
     * @param originals original per-task closures / 原各任务闭包
     * @param releases merged original identities / 合并原身份
     * @param retained existing durable session groups / 已有持久会话组
     * @param conflict whether shared identities differ / 共享身份是否不同 */
    private record Candidate(List<TaskRecoveryGraph> originals, List<TaskRecoveryGraph.Release> releases,
            List<ModRecoveryLedger.Recovery> retained, boolean conflict) {
    }

    /** Original durable group and its actually allocated loaders. / 原持久组及实际分配加载器。 */
    private static final class Group {
        /** Latest acknowledged cleanup receipt. / 最近已确认清理凭据。 */
        private ModRecoveryLedger.Recovery receipt;

        /** Dependency-first actual allocations. / 依赖优先实际分配。 */
        private final Map<String, Slot> slots = new LinkedHashMap<>();
        /** Retains durable ownership before loading code. / 在加载代码前保留持久归属。
         * @param receipt original group receipt / 原组凭据 */
        private Group(ModRecoveryLedger.Recovery receipt) {
            this.receipt = receipt;
        }
    }

    /** Exact allocation retained even when context construction fails. / 即使上下文构造失败也保留的精确分配。 */
    private static final class Slot {
        /** Original code loader. / 原代码加载器。 */
        private final ModClassLoader loader;

        /** Actual session, absent before successful context construction. / 实际会话，成功构造上下文前不存在。 */
        private ModRuntimeSession session;
        /** Owns a loader before allocating its context. / 在分配上下文前持有加载器。
         * @param loader original loader / 原加载器 */
        private Slot(ModClassLoader loader) {
            this.loader = loader;
        }
    }
}
