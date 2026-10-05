package gold.debug.windowstolinux.app.mod.run;

import java.net.URL;
import java.util.*;

import gold.debug.windowstolinux.app.mod.config.ModManifest;
import gold.debug.windowstolinux.app.mod.load.ModClassLoader;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Checks selected enabled releases before loading code, without changing user intent. / 在加载代码前检查已选且启用的版本，不改变用户意图。 */
public final class ModDependencyPlan {
    /** Unambiguous selected author manifests. / 无歧义的已选作者清单。 */
    private final Map<String, ModManifest> selected;

    /** Dependency-first order for independently valid components. / 独立有效部分的依赖优先顺序。 */
    private final List<ModManifest> startupOrder;

    /** Safe failures including unavailable prerequisites and cycles. / 包含不可用前置依赖与循环的安全失败。 */
    private final Map<String, Problem> failures;

    /** Exact public package ownership for each consumer. / 每个使用方的精确公开包归属。 */
    private final Map<String, Map<String, String>> exports;

    /** Freezes a complete resolution without invoking author code. / 冻结完整解析结果，不调用作者代码。
     * @param selected unique selected releases / 唯一已选版本
     * @param order valid dependency-first order / 有效依赖优先顺序
     * @param failures safe per-owner failures / 按所有者保存的安全失败
     * @param exports per-consumer package ownership / 按使用方保存的包归属 */
    private ModDependencyPlan(Map<String, ModManifest> selected, List<ModManifest> order, Map<String, Problem> failures,
                              Map<String, Map<String, String>> exports) {
        this.selected = Map.copyOf(selected);
        this.startupOrder = List.copyOf(order);
        this.failures = Collections.unmodifiableMap(new TreeMap<>(failures));
        this.exports = Map.copyOf(exports);
    }

    /** Resolves only supplied enabled selections; missing providers are never automatically selected. / 仅解析传入的启用选择，缺失提供方从不自动选择。
     * @param manifests strictly parsed enabled selections / 已严格解析的启用选择
     * @param platformApi actual host API release / 实际宿主 API 版本
     * @param javaVersion actual Java feature version / 实际 Java 主版本
     * @return immutable valid order and failures / 不可变有效顺序及失败 */
    public static ModDependencyPlan resolve(Collection<ModManifest> manifests, SemanticVersion platformApi,
                                            int javaVersion) {
        Objects.requireNonNull(platformApi);
        if (javaVersion < 1)
            throw new IllegalArgumentException("Invalid Java version");
        var selected = new TreeMap<String, ModManifest>();
        var failures = new TreeMap<String, Problem>();
        for (var manifest : manifests) {
            if (selected.putIfAbsent(manifest.id(), manifest) != null)
                failures.put(manifest.id(), problem("MOD_SELECTION_AMBIGUOUS", "More than one source selects this Mod",
                    manifest.id(), manifest.id()));
        }
        var exports = new HashMap<String, Map<String, String>>();
        var consumers = new TreeMap<String, Set<String>>();
        for (var manifest : selected.values()) {
            if (javaVersion < manifest.minJavaVersion() || !manifest.platformApiRange().contains(platformApi))
                failures.putIfAbsent(manifest.id(), problem("MOD_HOST_INCOMPATIBLE",
                    "The selected release is incompatible with this host", manifest.id(), manifest.id()));
            var packages = new TreeMap<String, String>();
            for (var dependency : manifest.dependencies()) {
                if (!dependency.optional())
                    consumers.computeIfAbsent(dependency.id(), key -> new TreeSet<>()).add(manifest.id());
                var provider = selected.get(dependency.id());
                if (provider == null) {
                    if (dependency.optional()) continue;
                    failures.putIfAbsent(manifest.id(), problem("MOD_DEPENDENCY_MISSING",
                        "A required Mod has no enabled selection", manifest.id(), dependency.id()));
                    continue;
                }
                if (!dependency.versionRange().contains(provider.version())) {
                    if (dependency.optional()) continue;
                    failures.putIfAbsent(manifest.id(), problem("MOD_DEPENDENCY_INCOMPATIBLE",
                        "The selected dependency release is incompatible", manifest.id(), dependency.id()));
                    continue;
                }
                if (dependency.optional() && (javaVersion < provider.minJavaVersion() || !provider.platformApiRange().contains(platformApi)))
                    continue;
                for (String exported : provider.exports()) {
                    if (overlaps(exported, manifest.rootPackage())
                        || packages.keySet().stream().anyMatch(prior -> overlaps(prior, exported)))
                        failures.putIfAbsent(manifest.id(),
                            problem("MOD_EXPORT_CONFLICT",
                                "Required public packages overlap each other or the consumer package",
                                manifest.id(), dependency.id()));
                    packages.putIfAbsent(exported, dependency.id());
                }
            }
            exports.put(manifest.id(), Map.copyOf(packages));
        }
        var cycleOwners = new HashSet<String>();
        for (var owner : selected.keySet())
            cycles(owner, selected, failures, new ArrayList<>(), new HashSet<>(), cycleOwners);
        for (var owner : cycleOwners)
            failures.putIfAbsent(owner, problem("MOD_DEPENDENCY_CYCLE", "Enabled dependency graph contains a cycle", owner, owner));
        propagateRequiredFailures(consumers, failures);
        var remaining = new HashMap<String, Integer>();
        var ready = new PriorityQueue<String>();
        for (var manifest : selected.values()) {
            if (failures.containsKey(manifest.id()))
                continue;
            int count = (int) manifest.dependencies().stream().filter(d -> selected.containsKey(d.id()) && !failures.containsKey(d.id()) && d.versionRange().contains(selected.get(d.id()).version())).count();
            remaining.put(manifest.id(), count);
            if (count == 0)
                ready.add(manifest.id());
        }
        var order = new ArrayList<ModManifest>();
        while (!ready.isEmpty()) {
            String owner = ready.remove();
            order.add(selected.get(owner));
            remaining.remove(owner);
            for (var consumer : selected.values().stream().filter(m -> m.dependencies().stream().anyMatch(d -> d.id().equals(owner) && d.versionRange().contains(selected.get(owner).version()))).map(ModManifest::id).toList()) {
                if (remaining.containsKey(consumer) && remaining.compute(consumer, (key, count) -> count - 1) == 0)
                    ready.add(consumer);
            }
        }
        for (var owner : new TreeSet<>(remaining.keySet()))
            failures.put(owner,
                problem("MOD_DEPENDENCY_CYCLE", "The required dependency graph contains a cycle", owner, owner));
        return new ModDependencyPlan(selected, order, failures, exports);
    }

    /** Returns dependency-first order with stable identity ordering for ties. / 返回依赖优先顺序，同级按稳定身份排序。
     * @return valid selected manifests / 有效已选清单 */
    public List<ModManifest> startupOrder() {
        return startupOrder;
    }

    /** Returns safe validation failures without removing the user's selections. / 返回安全校验失败，不移除用户选择。
     * @return immutable per-owner failures / 不可变的按所有者失败集合 */
    public Map<String, Problem> failures() {
        return failures;
    }

    /** Binds a valid consumer to the exact loaders of its already prepared providers. / 将有效使用方绑定到已准备提供方的精确加载器。
     * @param owner selected consumer identity / 已选使用方身份
     * @param sources validated ordered local code sources / 已验证且有序的本地代码来源
     * @param providers original provider loaders indexed by identity / 按身份索引的原提供方加载器
     * @return loader sharing only declared provider exports / 仅共享声明提供方公开包的加载器
     * @throws ApiException if validation failed or a prepared provider changed / 校验失败或已准备提供方发生变化时 */
    public ModClassLoader createLoader(String owner, List<URL> sources, Map<String, ModClassLoader> providers)
        throws ApiException {
        var manifest = selected.get(owner);
        if (manifest == null)
            throw new ApiException(problem("MOD_NOT_SELECTED", "This Mod has no enabled selection", owner, owner));
        if (failures.containsKey(owner))
            throw new ApiException(failures.get(owner));
        var originals = Map.copyOf(providers);
        for (var dependency : manifest.dependencies()) {
            var loader = originals.get(dependency.id());
            var expected = selected.get(dependency.id());
            if (dependency.optional() && (expected == null || failures.containsKey(dependency.id())
                || !dependency.versionRange().contains(expected.version()))) continue;
            if (dependency.optional() && (loader == null || loader.isClosed())) continue;
            if (loader == null || expected == null || loader.isClosed() || !loader.manifest().id().equals(dependency.id())
                || !dependency.versionRange().contains(loader.manifest().version())
                || !loader.manifest().version().equals(expected.version())
                || !loader.manifest().json().equals(expected.json()))
                throw new ApiException(problem("MOD_PROVIDER_CHANGED",
                    "A required original provider loader is unavailable or changed", owner, dependency.id()));
        }
        var bindings = new TreeMap<String, ModClassLoader>();
        exports.get(owner).forEach((name, provider) -> {
            var original = originals.get(provider);
            if (original != null && !original.isClosed() && !failures.containsKey(provider))
                bindings.put(name, original);
        });
        var loader = new ModClassLoader(manifest, sources, bindings);
        try {
            loader.inspectCode();
            return loader;
        } catch (java.io.IOException invalid) {
            try {
                loader.close();
            } catch (java.io.IOException cleanup) {
                invalid.addSuppressed(cleanup);
            }
            throw new ApiException(
                problem("MOD_CODE_INVALID", "Selected Mod code failed static admission", owner, owner));
        }
    }

    /** Propagates failure only to required consumers. / 仅向必需消费方传播失败。
     * @param consumers original required consumer edges / 原必需消费方边
     * @param failures established dependency failures / 已确立依赖失败 */
    private static void propagateRequiredFailures(Map<String, Set<String>> consumers, Map<String, Problem> failures) {
        var rejected = new ArrayDeque<>(failures.keySet());
        while (!rejected.isEmpty()) {
            var unavailable = rejected.removeFirst();
            for (var consumer : consumers.getOrDefault(unavailable, Set.of())) {
                if (failures.putIfAbsent(consumer, problem("MOD_DEPENDENCY_UNAVAILABLE",
                    "A required Mod failed dependency validation", consumer, unavailable)) == null)
                    rejected.addLast(consumer);
            }
        }
    }

    /** Marks actual enabled cycle members while leaving outside optional consumers independent. / 标记实际启用环成员并保持外部可选消费方独立。
     * @param owner exact original Mod owner / 精确原 Mod 所有者
     * @param selected enabled selected author manifests / 启用选择作者清单
     * @param failures already established invalid selections / 已确立无效选择
     * @param path active original dependency path / 活动原依赖路径
     * @param done already inspected graph nodes / 已检验图节点
     * @param cyclic exact cycle members / 精确环成员
     */
    private static void cycles(String owner, Map<String, ModManifest> selected, Map<String, Problem> failures,
                               List<String> path, Set<String> done, Set<String> cyclic) {
        int at = path.indexOf(owner);
        if (at >= 0) {
            cyclic.addAll(path.subList(at, path.size()));
            return;
        }
        if (!done.add(owner) || failures.containsKey(owner)) return;
        path.add(owner);
        for (var d : selected.get(owner).dependencies()) {
            var provider = selected.get(d.id());
            if (provider != null && d.versionRange().contains(provider.version()))
                cycles(d.id(), selected, failures, path, done, cyclic);
        }
        path.removeLast();
    }

    /** Checks equal or containing package names without treating textual prefixes as packages. / 检查相同或包含包名，不将文本前缀误当作包。
     * @param first first package / 首个包
     * @param second second package / 第二个包
     * @return whether either package contains the other / 任一包是否包含另一包 */
    private static boolean overlaps(String first, String second) {
        return first.equals(second) || first.startsWith(second + ".") || second.startsWith(first + ".");
    }

    /** Creates bounded public evidence from strictly validated identities. / 根据严格校验的身份创建有界公开证据。
     * @param event stable event code / 稳定事件码
     * @param message safe explanation / 安全说明
     * @param owner affected selection / 受影响选择
     * @param dependency implicated provider / 涉及的提供方
     * @return safe public evidence / 安全公开证据 */
    private static Problem problem(String event, String message, String owner, String dependency) {
        ProblemCode code = switch (event) {
            case "MOD_HOST_INCOMPATIBLE", "MOD_DEPENDENCY_INCOMPATIBLE", "MOD_DEPENDENCY_CYCLE" ->
                ProblemCode.INCOMPATIBLE;
            case "MOD_SELECTION_AMBIGUOUS", "MOD_EXPORT_CONFLICT", "MOD_CODE_INVALID" -> ProblemCode.CONTRACT_VIOLATION;
            default -> ProblemCode.UNAVAILABLE;
        };
        return new Problem(code, event, message, Map.of("ownerId", owner, "dependencyId", dependency),
            Optional.empty());
    }
}
