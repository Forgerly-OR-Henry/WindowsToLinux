package gold.debug.windowstolinux.app.service.mod.store;

import java.io.IOException;
import java.util.*;

import gold.debug.windowstolinux.app.mod.config.ModManifest;
import gold.debug.windowstolinux.app.mod.version.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStorePlan.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.*;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Finite global search rebuilt from the fixed root for every branch. / 每分支从固定主项重建的有限全局搜索。 */
final class ModStoreResolver {
    /** Complete immutable input observations. / 完整不可变输入观察。 */
    private final ModStoreCatalog catalog;

    /** Exact unmodified user intent. / 精确未擅改用户意图。 */
    private final ModStoreSelection selection;

    /** Actual host API. / 实际宿主 API。 */
    private final SemanticVersion api;

    /** Actual Java feature level. / 实际 Java 主版本。 */
    private final int javaVersion;

    /** Original cooperative cancellation. / 原协作取消。 */
    private final CancelToken cancellation;

    /** Finite search step budget shared by all preference trials. / 全部偏好尝试共用的有限搜索步预算。 */
    private final long budget;

    /** Whole search deadline. / 完整搜索期限。 */
    private final long deadline;

    /** Actual attempted branch count. / 实际尝试分支数。 */
    private long steps;

    /** Actual complete candidate domain. / 实际完整候选域。 */
    private final Map<String, List<Item>> candidates = new TreeMap<>();

    /** Same-version declarations rejected rather than overwritten. / 拒绝而非覆盖的同版本声明。 */
    private final Set<ReleaseKey> mismatched = new HashSet<>();

    /** Specific original failed requirements. / 具体原失败要求。 */
    private final List<Conflict> failures = new ArrayList<>();

    /** Captures finite search inputs and validates source collisions. / 捕获有限搜索输入并核验来源冲突。
     * @param catalog complete actual sources / 完整实际来源
     * @param selection exact user choices / 精确用户选择
     * @param api actual API / 实际 API
     * @param javaVersion actual Java feature / 实际 Java 主版本
     * @param cancellation original request / 原请求
     * @param budget maximum search branches / 搜索分支上限
     * @throws IOException if strict declarations cannot be compared / 无法比较严格声明时 */
    ModStoreResolver(ModStoreCatalog catalog, ModStoreSelection selection, SemanticVersion api, int javaVersion,
                     CancelToken cancellation, long budget) throws IOException {
        this.catalog = catalog;
        this.selection = selection;
        this.api = api;
        this.javaVersion = javaVersion;
        this.cancellation = cancellation;
        this.budget = budget;
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        domain();
    }

    /** Participating original requirement with a reviewable root path. / 带可审阅主项路径的原参与要求。
     * @param consumer exact requiring release / 精确要求发布
     * @param provider required provider / 要求提供方
     * @param range original independent range / 原独立范围
     * @param path exact root-to-consumer path / 精确主项到消费方路径 */
    private record Requirement(ReleaseKey consumer, String provider, VersionRange range, String path) {
    }

    /** One branch expanded only through actual reachable declarations. / 仅经实际可达声明展开的分支。
     * @param chosen reachable assigned candidates / 可达已分配候选
     * @param requirements current incoming requirements / 当前入边要求
     * @param edges current participating relationships / 当前参与关系
     * @param relations actual display edges / 实际展示边
     * @param paths original exact paths / 原精确路径 */
    private record Graph(Map<String, Item> chosen, Map<String, List<Requirement>> requirements,
                         Map<String, Set<String>> edges, List<Relation> relations, Map<String, String> paths) {
    }

    /** Builds a single candidate per exact identity, refusing metadata substitution. / 每精确身份建单一候选，拒绝元数据替换。
     * @throws IOException if manifests cannot be compared / 无法比较清单时 */
    private void domain() throws IOException {
        var byKey = new LinkedHashMap<ReleaseKey, Item>();
        for (var release : catalog.releases()) {
            var key = new ReleaseKey(release.owner(), release.version());
            byKey.put(key, new Item(key, release.manifest(), Source.REMOTE, false));
        }
        for (var installed : catalog.installed()) {
            var manifest = installed.manifest();
            var key = new ReleaseKey(manifest.id(), manifest.version().toString());
            var remote = byKey.get(key);
            if (remote != null && !ModStoreFacts.same(remote.manifest(), manifest)) {
                mismatched.add(key);
                byKey.remove(key);
            } else if (!mismatched.contains(key))
                byKey.put(key, new Item(key, manifest, Source.INSTALLED, false));
        }
        var pending = catalog.installedIssues().stream().filter(issue -> issue.problem().eventCode().equals("MOD_STORE_ORIGINAL_PENDING"))
            .map(issue -> issue.key().owner()).collect(java.util.stream.Collectors.toSet());
        for (var item : byKey.values())
            if (!pending.contains(item.key().owner()))
                candidates.computeIfAbsent(item.key().owner(), ignored -> new ArrayList<>()).add(item);
        candidates.values().forEach(items -> items.sort(Comparator.comparing((Item item) -> item.manifest().version()).reversed()));
    }

    /** Resolves all selected hard constraints, retaining installed identities when globally feasible. / 解析全部选中硬约束，全局可行时保留已装身份。
     * @param revision original service proposal identity / 原服务计划身份
     * @return valid complete proposal or explicit conflict/incompletion / 有效完整计划或明确冲突与未完成 */
    ModStorePlan resolve(long revision) {
        try {
            var forced = installedPreferences();
            Graph solution = solve(forced);
            var relaxed = new ArrayList<String>();
            for (var owner : new ArrayList<>(forced.keySet())) {
                if (solution != null) break;
                forced.remove(owner);
                relaxed.add(owner);
                solution = solve(forced);
            }
            if (solution != null) {
                for (var owner : relaxed) {
                    var trial = new TreeMap<>(forced);
                    trial.put(owner, installedPreferences().get(owner));
                    var restored = solve(trial);
                    if (restored != null) {
                        forced = trial;
                        solution = restored;
                    }
                }
                return new ModStorePlan(revision, catalog.revision(), selection, ordered(solution), solution.relations(), List.of());
            }
            if (failures.isEmpty()) conflict("MOD_STORE_NO_SOLUTION", selection.root().owner(), List.of(),
                "No complete solution for fixed root " + selection.root());
            return new ModStorePlan(revision, catalog.revision(), selection, List.of(), List.of(), List.copyOf(failures));
        } catch (Incomplete exhausted) {
            return new ModStorePlan(revision, catalog.revision(), selection, List.of(), List.of(), List.of(
                new Conflict("MOD_STORE_RESOLUTION_INCOMPLETE", selection.root().owner(), List.of(), "Finite search budget exhausted; no no-solution conclusion")));
        }
    }

    /** Stable eligible installed preference constraints, excluding exact user choices. / 稳定合格已装偏好约束，不覆盖用户精确选择。
     * @return actual preference constraints / 实际偏好约束 */
    private TreeMap<String, String> installedPreferences() {
        var result = new TreeMap<String, String>();
        for (var entry : candidates.entrySet())
            for (var item : entry.getValue())
                if (item.source() == Source.INSTALLED && compatible(item.manifest()) && !selection.pins().containsKey(entry.getKey())
                    && !entry.getKey().equals(selection.root().owner())) {
                    result.put(entry.getKey(), item.key().version());
                    break;
                }
        return result;
    }

    /** Starts each trial at the exact fixed root. / 每次尝试从精确固定主项开始。
     * @param forced currently retained installed constraints / 当前保留已装约束
     * @return complete solution or absent / 完整方案或缺省 */
    private Graph solve(Map<String, String> forced) {
        check();
        var root = candidates.getOrDefault(selection.root().owner(), List.of()).stream()
            .filter(item -> item.key().equals(selection.root())).findFirst();
        if (root.isEmpty()) {
            var pending = catalog.installedIssues().stream().filter(issue -> issue.key().owner().equals(selection.root().owner())
                && issue.problem().eventCode().equals("MOD_STORE_ORIGINAL_PENDING")).findFirst();
            conflict(pending.isPresent() ? "MOD_STORE_ORIGINAL_PENDING" : mismatched.contains(selection.root())
                    ? "MOD_STORE_DECLARATION_CONFLICT" : "MOD_STORE_SOURCE_MISSING", selection.root().owner(), List.of(),
                "Fixed root " + selection.root() + " has no consistent obtainable declaration"
                    + pending.map(issue -> "; original operation " + issue.problem().context().get("operationId")).orElse(""));
            return null;
        }
        return search(Map.of(root.get().key().owner(), root.get()), forced);
    }

    /** Backtracks complete branches without caching by a partial identity. / 回溯完整分支，不按局部身份缓存。
     * @param assignment current branch choices / 当前分支选择
     * @param forced retained installed constraints / 保留已装约束
     * @return complete global solution or absent / 完整全局方案或缺省 */
    private Graph search(Map<String, Item> assignment, Map<String, String> forced) {
        check();
        Graph graph = expand(assignment);
        if (!validate(graph, forced)) return null;
        var missing = graph.requirements().keySet().stream().filter(id -> !graph.chosen().containsKey(id)).sorted().findFirst();
        if (missing.isEmpty()) return complete(graph) ? graph : null;
        String owner = missing.get();
        var requirements = graph.requirements().get(owner);
        boolean attempted = false;
        for (var candidate : candidates.getOrDefault(owner, List.of())) {
            if (!eligible(candidate, requirements, forced)) continue;
            attempted = true;
            var next = new HashMap<>(graph.chosen());
            next.put(owner, candidate);
            var result = search(next, forced);
            if (result != null) return result;
        }
        if (!attempted) rejection(owner, requirements, forced);
        return null;
    }

    /** Rebuilds actual reachable constraints from the fixed root. / 从固定主项重建实际可达约束。
     * @param assignment current branch choices / 当前分支选择
     * @return fresh reachable graph / 全新可达图 */
    private Graph expand(Map<String, Item> assignment) {
        var graph = new Graph(new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new ArrayList<>(), new HashMap<>());
        var queue = new ArrayDeque<String>();
        queue.add(selection.root().owner());
        graph.paths().put(selection.root().owner(), selection.root().owner() + "@" + selection.root().version());
        while (!queue.isEmpty()) {
            check();
            var owner = queue.remove();
            var item = assignment.get(owner);
            if (item == null || graph.chosen().putIfAbsent(owner, item) != null) continue;
            if (!graph.paths().get(owner).endsWith("@" + item.key().version()))
                graph.paths().put(owner, graph.paths().get(owner) + "@" + item.key().version());
            graph.edges().put(owner, new TreeSet<>());
            for (var dependency : item.manifest().dependencies()) edge(graph, queue, item, dependency);
        }
        return graph;
    }

    /** Adds one independent author range without numeric endpoint merging. / 添加一个独立作者范围，不合并数值端点。
     * @param graph current fresh graph / 当前全新图
     * @param queue reachable provider queue / 可达提供方队列
     * @param item exact declaring program / 精确声明程序
     * @param dependency actual author dependency / 实际作者依赖 */
    private void edge(Graph graph, Deque<String> queue, Item item, ModManifest.Dependency dependency) {
        var key = new OptionalKey(item.key().owner(), dependency.id());
        boolean selected = !dependency.optional() || selection.optionals().contains(key);
        if (selected) {
            String path = graph.paths().get(item.key().owner());
            graph.requirements().computeIfAbsent(dependency.id(), ignored -> new ArrayList<>())
                .add(new Requirement(item.key(), dependency.id(), dependency.versionRange(), path));
            graph.edges().get(item.key().owner()).add(dependency.id());
            graph.paths().putIfAbsent(dependency.id(), path + " -> " + dependency.id());
            queue.add(dependency.id());
        }
        String purpose = catalog.releases().stream().filter(r -> r.owner().equals(item.key().owner()) && r.version().equals(item.key().version()))
            .findFirst().map(r -> r.purposes().getOrDefault(dependency.id(), "")).orElse("");
        graph.relations().add(new Relation(key, item.key().version(), dependency.versionRange().toString(),
            dependency.optional(), selected, purpose, Optional.empty()));
    }

    /** Validates every assigned candidate against all current original requirements. / 按全部当前原要求核验每个已分配候选。
     * @param graph current reachable graph / 当前可达图
     * @param forced retained installed constraints / 保留已装约束
     * @return whether current assignments are viable / 当前分配是否可行 */
    private boolean validate(Graph graph, Map<String, String> forced) {
        for (var item : graph.chosen().values()) {
            var requirements = graph.requirements().getOrDefault(item.key().owner(), List.of());
            if (!eligible(item, requirements, forced)) {
                rejection(item.key().owner(), requirements, forced);
                return false;
            }
        }
        return acyclic(graph);
    }

    /** Evaluates original ranges and exact pins independently. / 独立判断原范围及精确固定。
     * @param item actual candidate / 实际候选
     * @param requirements every participating requirement / 每个参与要求
     * @param forced retained installed constraints / 保留已装约束
     * @return whether this candidate satisfies all constraints / 此候选是否满足全部约束 */
    private boolean eligible(Item item, List<Requirement> requirements, Map<String, String> forced) {
        String pin = selection.pins().get(item.key().owner());
        String retained = forced.get(item.key().owner());
        return compatible(item.manifest()) && (pin == null || pin.equals(item.key().version()))
            && (retained == null || retained.equals(item.key().version()))
            && requirements.stream().allMatch(requirement -> requirement.range().contains(item.manifest().version()));
    }

    /** Checks actual host compatibility. / 检查实际宿主兼容性。
     * @param manifest actual candidate declaration / 实际候选声明
     * @return whether actual Java and API satisfy the declaration / 实际 Java 与 API 是否满足声明 */
    private boolean compatible(ModManifest manifest) {
        return javaVersion >= manifest.minJavaVersion() && manifest.platformApiRange().contains(api);
    }

    /** Detects cycles only in the current reachable branch. / 仅检测当前可达分支的循环。
     * @param graph current graph / 当前图
     * @return whether assigned participating edges are acyclic / 已分配参与边是否无环 */
    private boolean acyclic(Graph graph) {
        var remaining = new TreeMap<String, Set<String>>();
        graph.edges().forEach((owner, providers) -> {
            var selected = new TreeSet<>(providers);
            selected.retainAll(graph.chosen().keySet());
            remaining.put(owner, selected);
        });
        while (!remaining.isEmpty()) {
            var ready = remaining.entrySet().stream().filter(entry -> entry.getValue().isEmpty()).map(Map.Entry::getKey).toList();
            if (ready.isEmpty()) {
                String owner = remaining.firstKey();
                conflict("MOD_STORE_CYCLE", owner, graph.requirements().getOrDefault(owner, List.of()),
                    "Cycle in " + graph.paths().get(owner) + " at exact " + graph.chosen().get(owner).key());
                return false;
            }
            ready.forEach(remaining::remove);
            remaining.values().forEach(providers -> providers.removeAll(ready));
        }
        return true;
    }

    /** Requires every selected optional intent to remain actually reachable. / 要求每个选中可选意图仍实际可达。
     * @param graph complete current graph / 完整当前图
     * @return whether all explicit intents exist / 全部明确意图是否存在 */
    private boolean complete(Graph graph) {
        for (var optional : selection.optionals())
            if (graph.relations().stream().noneMatch(relation -> relation.key().equals(optional) && relation.selected())) {
                conflict("MOD_STORE_OPTIONAL_UNREACHABLE", optional.consumer(), List.of(),
                    "Selected relationship " + optional + " disappeared from fixed root " + selection.root());
                return false;
            }
        graph.relations().replaceAll(relation -> new Relation(relation.key(), relation.consumerVersion(), relation.range(),
            relation.optional(), relation.selected(), relation.purpose(), relation.selected()
            ? Optional.of(graph.chosen().get(relation.key().dependency()).key()) : Optional.empty()));
        return true;
    }

    /** Produces dependency-first items and separately marks the root required closure. / 生成依赖先序项，独立标记主项必需闭包。
     * @param graph complete actual graph / 完整实际图
     * @return exact ordered proposal / 精确有序计划 */
    private List<Item> ordered(Graph graph) {
        var required = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(selection.root().owner());
        while (!queue.isEmpty()) {
            String owner = queue.remove();
            if (!required.add(owner)) continue;
            graph.chosen().get(owner).manifest().dependencies().stream().filter(d -> !d.optional()).forEach(d -> queue.add(d.id()));
        }
        var remaining = new TreeMap<>(graph.chosen());
        var result = new ArrayList<Item>();
        var done = new HashSet<String>();
        while (!remaining.isEmpty()) {
            var ready = remaining.keySet().stream().filter(owner -> done.containsAll(graph.edges().get(owner))).toList();
            for (var owner : ready) {
                var item = remaining.remove(owner);
                result.add(new Item(item.key(), item.manifest(), item.source(), required.contains(owner)));
                done.add(owner);
            }
        }
        return result;
    }

    /** Records specific host, source, pin and independent incoming requirements. / 记录具体宿主、来源、固定及独立入边要求。
     * @param owner rejected provider / 被拒提供方
     * @param requirements actual incoming requirements / 实际入边要求
     * @param forced retained installed constraints / 保留已装约束 */
    private void rejection(String owner, List<Requirement> requirements, Map<String, String> forced) {
        String pin = selection.pins().get(owner);
        String detail = "Root " + selection.root() + "; host API " + api + "/Java " + javaVersion + "; provider " + owner
            + "; exact pin " + pin + "; retained installed " + forced.get(owner) + "; obtainable candidates "
            + candidates.getOrDefault(owner, List.of()).stream().map(item -> item.key().version()).toList();
        for (var requirement : requirements)
            detail += "; " + requirement.path() + " -> " + requirement.consumer()
                + " requires " + owner + " " + requirement.range();
        var pending = catalog.installedIssues().stream().filter(issue -> issue.key().owner().equals(owner)
            && issue.problem().eventCode().equals("MOD_STORE_ORIGINAL_PENDING")).findFirst();
        if (pending.isPresent())
            detail += "; original operation " + pending.get().problem().context().get("operationId");
        conflict(pending.isPresent() ? "MOD_STORE_ORIGINAL_PENDING" : pin != null ? "MOD_STORE_PIN_CONFLICT"
                : candidates.getOrDefault(owner, List.of()).isEmpty() ? "MOD_STORE_SOURCE_MISSING" : "MOD_STORE_REQUIREMENT_CONFLICT",
            owner, requirements, detail);
    }

    /** Preserves bounded concrete failed branch evidence. / 保留有界具体失败分支依据。
     * @param code stable reason / 稳定原因
     * @param owner affected identity / 受影响身份
     * @param requirements actual requirements / 实际要求
     * @param detail reviewable exact path / 可审阅精确路径 */
    private void conflict(String code, String owner, List<Requirement> requirements, String detail) {
        if (failures.size() == 16) failures.removeFirst();
        failures.add(new Conflict(code, owner, requirements.stream().map(r ->
            new Constraint(r.consumer().owner(), r.provider(), r.range().toString())).toList(),
            detail.substring(0, Math.min(detail.length(), 8192))));
    }

    /** Stops at the original cancellation, deadline or finite branch limit. / 在原取消、期限或有限分支上限停止。 */
    private void check() {
        ModStoreFacts.cancellation(cancellation);
        if (++steps > budget || System.nanoTime() >= deadline) throw new Incomplete();
    }

    /** Explicit incomplete search, never a no-solution proof. / 明确未完成搜索，不是无解证明。 */
    private static final class Incomplete extends RuntimeException {
        /** Stable serial identity. / 稳定序列身份。 */
        private static final long serialVersionUID = 1L;
    }
}
