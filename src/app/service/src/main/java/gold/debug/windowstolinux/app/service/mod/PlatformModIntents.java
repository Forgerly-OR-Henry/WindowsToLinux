package gold.debug.windowstolinux.app.service.mod;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.mod.lifecycle.ModDependencyPlan;
import gold.debug.windowstolinux.app.mod.manifest.*;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;

/** Reviews and atomically saves restart intentions without invoking or unloading author code. / 审阅并原子保存重启意图，不调用或卸载作者代码。 */
public final class PlatformModIntents implements AutoCloseable {
    /** Exact owning boot. / 精确所属启动。 */
    private final UUID boot;

    /** Platform transaction owner shared with task admission. / 与任务准入共用的平台事务所有者。 */
    private final RepositoryConnections connections;

    /** Current-user management authorization. / 当前用户管理授权。 */
    private final DesktopSecurityService security;

    /** Actual public API release. / 实际公开 API 版本。 */
    private final SemanticVersion api;

    /** Existing bounded log writer used inside the same transaction. / 在同一事务中使用的既有有界日志写入器。 */
    private final LogRepository logs;

    /** Fixed platform operation archive target. / 固定平台操作归档目标。 */
    private final LogRepository.Target archive;

    /** Strict durable request parser. / 严格持久请求解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Whether management has closed after accepted operations returned. / 已接纳操作返回后管理能力是否关闭。 */
    private boolean closed;

    /** Binds one host management service to the actual database and API. / 将一个宿主管理服务绑定到实际数据库及 API。
     * @param database original platform database / 原平台数据库
     * @param security current-user authorization / 当前用户授权
     * @param api actual host API release / 实际宿主 API 版本
     * @throws SQLException if the fixed archive target cannot be verified / 无法核验固定归档目标时 */
    public PlatformModIntents(PlatformDatabase database, DesktopSecurityService security, SemanticVersion api)
            throws SQLException {
        boot = database.bootId();
        connections = new RepositoryConnections(database::openConnection);
        this.security = Objects.requireNonNull(security);
        this.api = Objects.requireNonNull(api);
        logs = new LogRepository(database);
        archive = logs.register("app", Optional.empty(),
                new LogCategory("mod_operation", "mod_operation", Optional.empty()));
    }

    /** Whole-Mod choice; an enabled choice always names one installed release. / 整个 Mod 的选择，启用选择始终指向一个已安装版本。
     * @param enabled desired whole-Mod switch / 期望的整个 Mod 开关
     * @param version desired selected release, possibly absent while disabled / 期望单选版本，禁用时可以为空 */
    public record Choice(boolean enabled, Optional<String> version) {
        /** Copies a valid choice without interpreting it as actual runtime state. / 保存合法选择，不将其解释为实际运行状态。
         * @param enabled desired switch / 期望开关
         * @param version desired release / 期望版本 */
        public Choice {
            Objects.requireNonNull(version);
            if (enabled && version.isEmpty())
                throw new IllegalArgumentException("Enabled Mod requires a version");
            version.ifPresent(SemanticVersion::parse);
        }
    }

    /** Opaque review bound to this service and an exact catalog snapshot. / 绑定此服务和精确目录快照的不透明审阅。 */
    public final class Review {
        /** Original catalog snapshot including state and retained operations. / 包含状态及保留操作的原目录快照。 */
        private final Snapshot before;

        /** Explicit changes including confirmed-dependent candidates. / 包含待确认依赖者候选的明确变更。 */
        private final Map<String, Choice> choices;

        /** Additional owners requiring explicit cascade confirmation. / 需要明确连带确认的额外所有者。 */
        private final Set<String> cascade;

        /** Original and target consumers whose new admission must wait. / 新准入必须等待的原使用方及目标使用方。 */
        private final Set<String> affected;

        /** Confirmed migration source and path for each activation. / 每次启用已确认的迁移来源及路径。 */
        private final Map<String, String> dataPlans;

        /** Stable identity for this reviewed request group. / 此审阅请求组的稳定身份。 */
        private final UUID id = UUID.randomUUID();

        /** Whether any commit attempt has begun; unknown commits are never replayed. / 是否开始过提交尝试，未知提交从不重放。 */
        private boolean attempted;

        /** Captures the review without performing state changes. / 捕获审阅，不执行状态变更。
         * @param before exact catalog snapshot / 精确目录快照
         * @param choices complete proposed choices / 完整提议选择
         * @param cascade additional affected owners / 额外受影响所有者
         * @param affected all owners whose admission must wait / 所有准入必须等待的所有者
         * @param dataPlans selected data preparation / 已选数据准备 */
        private Review(Snapshot before, Map<String, Choice> choices, Set<String> cascade, Set<String> affected,
                Map<String, String> dataPlans) {
            this.before = before;
            this.choices = Collections.unmodifiableMap(new TreeMap<>(choices));
            this.cascade = Collections.unmodifiableSet(new TreeSet<>(cascade));
            this.affected = Collections.unmodifiableSet(new TreeSet<>(affected));
            this.dataPlans = Map.copyOf(dataPlans);
        }

        /** Returns the exact review identity for later operation lookup. / 返回供之后操作查询使用的精确审阅身份。
         * @return stable request identity / 稳定请求身份 */
        public UUID id() {
            return id;
        }

        /** Returns all proposed whole-Mod choices. / 返回全部提议的整个 Mod 选择。
         * @return immutable proposed targets / 不可变提议目标 */
        public Map<String, Choice> choices() {
            return choices;
        }

        /** Returns additional owners that would be disabled together. / 返回将被一并禁用的额外所有者。
         * @return immutable cascade set / 不可变连带集合 */
        public Set<String> cascade() {
            return cascade;
        }

        /** Returns the exact new-admission scope while existing tasks retain their work. / 返回精确新准入范围，既有任务保留工作。
         * @return immutable affected owners / 不可变受影响所有者 */
        public Set<String> affected() {
            return affected;
        }

        /** Returns the exact service owning the original review. / 返回原审阅所属的精确服务。
         * @return original service / 原服务 */
        private PlatformModIntents owner() {
            return PlatformModIntents.this;
        }
    }

    /** Reviews a switch or version choice against current declarations and data. / 根据当前声明及数据审阅开关或版本选择。
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param target requested whole-Mod choice / 请求的整个 Mod 选择
     * @param source explicitly selected original migration source, if required / 如需迁移则为明确选择的原来源
     * @return concrete review including any dependent disablement / 包含任何依赖者禁用的具体审阅
     * @throws ApiException if selection, dependencies, source or state is invalid / 选择、依赖、来源或状态无效时 */
    public synchronized Review review(String owner, Choice target, Optional<SchemaIdentity> source)
            throws ApiException {
        requireOpen();
        try (var operation = security.enterOperation()) {
            return locked(
                    c -> calculate(read(c), Map.of(owner, target), source.map(s -> Map.of(owner, s)).orElse(Map.of())));
        } catch (SQLException failure) {
            throw publicFailure(failure);
        }
    }

    /** Reviews withdrawal of an entire original pending group without undoing independent requests. / 审阅撤回整个原待执行组，不撤销独立请求。
     * @param requestId original request group identity / 原请求组身份
     * @return current review restoring the group's original choices / 恢复此组原选择的当前审阅
     * @throws ApiException if the group no longer awaits execution or dependencies changed / 此组不再等待执行或依赖发生变化时 */
    public synchronized Review reviewCancellation(UUID requestId) throws ApiException {
        requireOpen();
        try (var operation = security.enterOperation()) {
            return locked(c -> {
                var snapshot = read(c);
                var targets = new TreeMap<String, Choice>();
                for (var pending : snapshot.pending().values())
                    if (pending.requestId().equals(requestId))
                        targets.put(pending.owner(), pending.base());
                if (targets.isEmpty())
                    throw rejected("MOD_REQUEST_NOT_PENDING", "The original request no longer awaits execution");
                return calculate(snapshot, targets, Map.of());
            });
        } catch (SQLException failure) {
            throw publicFailure(failure);
        }
    }

    /** Revalidates a review and commits choices, pending operations and replaced-request archives atomically. / 重新核验审阅，并原子提交选择、待执行操作及被替代请求归档。
     * @param review original concrete review / 原具体审阅
     * @param confirmCascade whether its exact dependent disablement was confirmed / 是否已确认其精确依赖者禁用范围
     * @return original request group identity / 原请求组身份
     * @throws ApiException if review changed, confirmation is missing or the commit is unknown / 审阅变化、缺少确认或提交未知时 */
    public synchronized UUID apply(Review review, boolean confirmCascade) throws ApiException {
        requireOpen();
        Objects.requireNonNull(review);
        if (review.owner() != this || review.attempted)
            throw publicFailure(
                    rejected("MOD_REVIEW_USED", "The review does not belong to an unused original request"));
        if (!review.cascade.isEmpty() && !confirmCascade)
            throw publicFailure(rejected("MOD_CASCADE_CONFIRMATION_REQUIRED",
                    "The listed dependent disablement must be confirmed"));
        try (var operation = security.enterOperation()) {
            review.attempted = true;
            return locked(c -> {
                var current = read(c);
                if (!current.equals(review.before))
                    throw rejected("MOD_REVIEW_STALE", "Mod state changed; review the request again");
                long now = System.currentTimeMillis();
                boolean changed = false;
                for (var change : review.choices.entrySet()) {
                    String owner = change.getKey();
                    var prior = current.owners().get(owner);
                    if (prior.choice().equals(change.getValue()))
                        continue;
                    changed = true;
                    var pending = current.pending().get(owner);
                    Choice base = pending == null ? prior.choice() : pending.base();
                    if (pending != null)
                        archiveCancelled(c, pending, now);
                    long revision;
                    try {
                        revision = Math.incrementExact(prior.revision());
                    } catch (ArithmeticException invalid) {
                        throw rejected("MOD_REVISION_EXHAUSTED", "The Mod revision cannot be advanced");
                    }
                    try (var p = c.prepareStatement(
                            "UPDATE mod_registry SET enabled=?,selected_version=?,selection_initialized=TRUE,revision=?,updated_at=? WHERE mod_id=? AND revision=?")) {
                        p.setBoolean(1, change.getValue().enabled());
                        p.setString(2, change.getValue().version().orElse(null));
                        p.setLong(3, revision);
                        p.setLong(4, now);
                        p.setString(5, owner);
                        p.setLong(6, prior.revision());
                        if (p.executeUpdate() != 1)
                            throw rejected("MOD_REVIEW_STALE", "The original Mod revision changed");
                    }
                    if (!base.equals(change.getValue()))
                        insert(c, review, owner, base, change.getValue(), revision, now);
                }
                if (changed) {
                    try (var p = c.prepareStatement(
                            "UPDATE app_state SET intent_revision=intent_revision+1,updated_at=? WHERE singleton_id=1")) {
                        p.setLong(1, now);
                        p.executeUpdate();
                    }
                }
                return review.id;
            });
        } catch (SQLException failure) {
            throw publicFailure(failure);
        }
    }

    /** Computes dependency effects and source requirements without touching persistent intent. / 计算依赖影响及来源要求，不触碰持久意图。
     * @param state locked current catalog / 已锁定当前目录
     * @param requested explicit owner choices / 明确所有者选择
     * @param sources explicitly confirmed migration sources / 明确确认的迁移来源
     * @return concrete immutable review / 具体不可变审阅
     * @throws SQLException if a requested target cannot be accepted / 请求目标无法接纳时 */
    private Review calculate(Snapshot state, Map<String, Choice> requested, Map<String, SchemaIdentity> sources)
            throws SQLException {
        var choices = new TreeMap<String, Choice>(requested);
        var dataPlans = new TreeMap<String, String>();
        for (var change : requested.entrySet()) {
            var prior = state.owners().get(change.getKey());
            if (prior == null)
                throw rejected("MOD_NOT_INSTALLED", "The requested Mod is not installed");
            if (prior.choice().version().isPresent() && change.getValue().version().isEmpty())
                throw rejected("MOD_SELECTION_REQUIRED", "Disabling a Mod must preserve its selected version");
            if (prior.choice().equals(change.getValue())) {
                if (sources.containsKey(change.getKey()))
                    throw rejected("MOD_SOURCE_NOT_APPLICABLE",
                            "An unchanged choice cannot request a new migration source");
                continue;
            }
            boolean activating = change.getValue().enabled()
                    || !prior.choice().version().equals(change.getValue().version());
            if (activating) {
                if (state.busy().contains(change.getKey()))
                    throw rejected("MOD_MAINTENANCE_PENDING",
                            "Mod maintenance or reconciliation must finish before this choice");
                var manifest = selected(state, change.getKey(), change.getValue());
                if (!manifest.platformApiRange().contains(api)
                        || Runtime.version().feature() < manifest.minJavaVersion())
                    throw rejected("MOD_HOST_INCOMPATIBLE",
                            "The selected release is incompatible with the actual host");
                dataPlans.put(change.getKey(), dataPlan(state, manifest, sources.get(change.getKey())));
            }
        }
        var manifests = manifests(state, choices);
        var plan = ModDependencyPlan.resolve(manifests.values(), api, Runtime.version().feature());
        var cascade = new TreeSet<String>();
        var admission = new TreeSet<String>();
        var priorManifests = manifests(state, Map.of());
        for (var change : requested.entrySet()) {
            if (state.owners().get(change.getKey()).choice().equals(change.getValue()))
                continue;
            if (change.getValue().enabled() && plan.failures().containsKey(change.getKey()))
                throw new SQLException("Mod dependency validation failed",
                        new ApiException(plan.failures().get(change.getKey())));
            var affected = new TreeSet<String>();
            affected.add(change.getKey());
            boolean added;
            do {
                added = false;
                for (var manifest : manifests.values())
                    if (!affected.contains(manifest.id())
                            && manifest.dependencies().stream().anyMatch(d -> affected.contains(d.id())))
                        added |= affected.add(manifest.id());
            } while (added);
            admission.addAll(affected);
            boolean previousAdded;
            do {
                previousAdded = false;
                for (var manifest : priorManifests.values())
                    if (!admission.contains(manifest.id())
                            && manifest.dependencies().stream().anyMatch(d -> admission.contains(d.id())))
                        previousAdded |= admission.add(manifest.id());
            } while (previousAdded);
            for (var dependent : affected) {
                if (requested.containsKey(dependent))
                    continue;
                if (!change.getValue().enabled() || plan.failures().containsKey(dependent)) {
                    cascade.add(dependent);
                    choices.put(dependent, new Choice(false, state.owners().get(dependent).choice().version()));
                }
            }
            if (change.getValue().enabled()) {
                var remaining = new ArrayDeque<String>();
                var seen = new HashSet<String>();
                remaining.add(change.getKey());
                while (!remaining.isEmpty()) {
                    String owner = remaining.removeFirst();
                    if (!seen.add(owner))
                        continue;
                    for (var dependency : manifests.get(owner).dependencies()) {
                        var provider = state.owners().get(dependency.id());
                        if (Set.of("FAILED", "UNKNOWN").contains(provider.runtime()))
                            throw rejected("MOD_DEPENDENCY_NOT_RUNNING",
                                    "A required Mod has a failed or unknown runtime; confirm its recovery first");
                        remaining.addLast(dependency.id());
                    }
                }
            }
        }
        return new Review(state, choices, cascade, admission, dataPlans);
    }

    /** Parses enabled selections, leaving malformed existing owners unavailable without blocking their disablement. / 解析启用选择，既有损坏所有者保持不可用但不阻止禁用。
     * @param state original catalog / 原目录
     * @param choices proposed replacements / 提议替换
     * @return readable enabled manifests / 可读取的启用清单 */
    private Map<String, ModManifest> manifests(Snapshot state, Map<String, Choice> choices) {
        var result = new TreeMap<String, ModManifest>();
        for (var owner : state.owners().entrySet()) {
            var choice = choices.getOrDefault(owner.getKey(), owner.getValue().choice());
            if (choice.enabled()) {
                try {
                    result.put(owner.getKey(), selected(state, owner.getKey(), choice));
                } catch (SQLException unavailable) {
                    /* The requested activation is validated separately. / 请求的启用另行核验。 */ }
            }
        }
        return result;
    }

    /** Resolves one exact READY release with matching stored identity. / 解析一个身份匹配的精确 READY 发布。
     * @param state original catalog / 原目录
     * @param owner requested owner / 请求所有者
     * @param choice requested release / 请求发布
     * @return strictly parsed matching manifest / 严格解析且匹配的清单
     * @throws SQLException if selection or manifest is unavailable / 选择或清单不可用时 */
    private ModManifest selected(Snapshot state, String owner, Choice choice) throws SQLException {
        var release = state.releases().getOrDefault(owner, Map.of()).get(choice.version().orElse(""));
        if (release == null || !release.state().equals("READY"))
            throw rejected("MOD_VERSION_NOT_READY", "The selected installed release is unavailable");
        try {
            var manifest = ManifestReader.read(release.json().getBytes(StandardCharsets.UTF_8));
            if (!manifest.id().equals(owner) || !manifest.version().toString().equals(choice.version().orElseThrow())
                    || !manifest.schemaVersion().equals(Optional.ofNullable(release.schema())))
                throw new IllegalArgumentException("Manifest identity differs from its registration");
            return manifest;
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            throw rejected("MOD_MANIFEST_INVALID", "The selected release does not match its registered manifest");
        }
    }

    /** Selects reuse, first initialization or an explicitly confirmed unique migration path. / 选择复用、首次初始化或明确确认的唯一迁移路径。
     * @param state original catalog / 原目录
     * @param manifest selected release / 已选发布
     * @param source explicitly selected source, or null / 明确选择的来源或空
     * @return durable data preparation JSON / 持久数据准备 JSON
     * @throws SQLException if data or source/path requires correction / 数据、来源或路径需更正时 */
    private String dataPlan(Snapshot state, ModManifest manifest, SchemaIdentity source) throws SQLException {
        var schemas = state.schemas().getOrDefault(manifest.id(), Map.of());
        var plan = JSON.createObjectNode();
        if (manifest.schemaVersion().isEmpty()) {
            if (source != null)
                throw rejected("MOD_SOURCE_NOT_APPLICABLE", "A stateless Mod cannot select a migration source");
            return plan.put("mode", "NONE").toString();
        }
        String target = manifest.schemaVersion().orElseThrow();
        var ready = schemas.get(target);
        if (ready != null) {
            if (!ready.state().equals("READY"))
                throw rejected("MOD_SCHEMA_RECONCILIATION_REQUIRED",
                        "The existing target needs its original migration recovery");
            if (source != null)
                throw rejected("MOD_SOURCE_NOT_APPLICABLE",
                        "An existing ready target must be reused without migration");
            requireCoordinates(manifest.id(), target, ready);
            return plan.put("mode", "REUSE").put("target", target).put("generation", ready.generation().toString())
                    .toString();
        }
        if (schemas.isEmpty()) {
            if (source != null)
                throw rejected("MOD_SOURCE_NOT_AVAILABLE", "There is no registered source to migrate");
            return plan.put("mode", "INITIALIZE").put("target", target).toString();
        }
        if (source == null)
            throw rejected("MOD_MIGRATION_SOURCE_REQUIRED", "Select and confirm an existing source before this change");
        var original = schemas.get(source.schemaVersion());
        if (!manifest.id().equals(source.ownerId()) || original == null || !original.state().equals("READY")
                || !original.generation().equals(source.generation()))
            throw rejected("MOD_SOURCE_NOT_AVAILABLE", "The confirmed original data source is unavailable");
        requireCoordinates(manifest.id(), source.schemaVersion(), original);
        var paths = new HashMap<String, List<ModManifest.Migration>>();
        var counts = new HashMap<String, Integer>();
        paths.put(source.schemaVersion(), List.of());
        counts.put(source.schemaVersion(), 1);
        var edges = new ArrayList<>(manifest.migrations());
        edges.sort(Comparator.comparing(e -> new java.math.BigInteger(e.from())));
        for (var edge : edges) {
            int count = counts.getOrDefault(edge.from(), 0);
            if (count == 0)
                continue;
            counts.merge(edge.to(), count, (a, b) -> Math.min(2, a + b));
            var steps = new ArrayList<>(paths.get(edge.from()));
            steps.add(edge);
            paths.put(edge.to(), List.copyOf(steps));
        }
        if (counts.getOrDefault(target, 0) != 1)
            throw rejected("MOD_MIGRATION_PATH_INVALID",
                    "The selected source needs exactly one declared migration path");
        plan.put("mode", "MIGRATE").put("target", target).put("source", source.schemaVersion()).put("generation",
                source.generation().toString());
        plan.set("steps", JSON.valueToTree(paths.get(target)));
        return plan.toString();
    }

    /** Verifies original physical data coordinates before accepting reuse or migration. / 接纳复用或迁移前核验原物理数据坐标。
     * @param owner exact owner / 精确所有者
     * @param version logical data version / 逻辑数据版本
     * @param data stored data identity / 已存数据身份
     * @throws SQLException if coordinates differ / 坐标不符时 */
    private static void requireCoordinates(String owner, String version, Data data) throws SQLException {
        var coordinates = new SchemaCoordinates(new SchemaIdentity(owner, version, data.generation()));
        if (!coordinates.physicalName().equals(data.physical()) || !coordinates.databaseUser().equals(data.user()))
            throw rejected("MOD_SCHEMA_IDENTITY", "The registered data identity does not match its original owner");
    }

    /** Reads the complete decision-relevant catalog under the platform lock. / 在平台锁内读取完整决策相关目录。
     * @param c owning transaction / 所属事务
     * @return immutable comparable decision snapshot / 不可变可比较决策快照
     * @throws SQLException if records or pending requests are invalid / 记录或待执行请求无效时 */
    private Snapshot read(Connection c) throws SQLException {
        long revision;
        try (var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT intent_revision,boot_id,shutdown_state FROM app_state WHERE singleton_id=1")) {
            if (!r.next() || !boot.equals(r.getObject(2, UUID.class)) || !"RUNNING".equals(r.getString(3)))
                throw rejected("PLATFORM_NOT_RUNNING", "Mod management requires the original running platform boot");
            revision = r.getLong(1);
        }
        var owners = new TreeMap<String, Owner>();
        try (var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT mod_id,enabled,selected_version,revision,last_boot_id,last_runtime_state,actual_version FROM mod_registry ORDER BY mod_id")) {
            while (r.next())
                owners.put(r.getString(1), new Owner(new Choice(r.getBoolean(2), Optional.ofNullable(r.getString(3))),
                        r.getLong(4), r.getObject(5, UUID.class), r.getString(6), r.getString(7)));
        }
        var releases = new TreeMap<String, Map<String, Release>>();
        try (var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT mod_id,version,state,schema_version,manifest_json,content_sha256 FROM mod_version ORDER BY mod_id,version")) {
            while (r.next())
                releases.computeIfAbsent(r.getString(1), k -> new TreeMap<>()).put(r.getString(2),
                        new Release(r.getString(3), r.getString(4), r.getString(5), r.getString(6)));
        }
        var schemas = new TreeMap<String, Map<String, Data>>();
        try (var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT mod_id,schema_version,generation,state,physical_name,database_user,ever_used,validated_at,validation_digest FROM mod_schema ORDER BY mod_id,schema_version")) {
            while (r.next())
                schemas.computeIfAbsent(r.getString(1), k -> new TreeMap<>()).put(r.getString(2),
                        new Data(r.getObject(3, UUID.class), r.getString(4), r.getString(5), r.getString(6),
                                r.getBoolean(7), r.getObject(8, Long.class), r.getString(9)));
        }
        var pending = new TreeMap<String, Pending>();
        var busy = new TreeSet<String>();
        try (var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT operation_id,mod_id,operation_type,state,phase,outcome,request_json,archive_log_id,accepted_revision,source_version,target_version FROM mod_operation WHERE state<>'ENDED' ORDER BY operation_id")) {
            while (r.next()) {
                String owner = r.getString(2);
                if (!Set.of("ENABLE", "DISABLE", "SWITCH").contains(r.getString(3)) || !"WAITING".equals(r.getString(4))
                        || !"WAIT_RESTART".equals(r.getString(5)) || r.getString(6) != null) {
                    busy.add(owner);
                    continue;
                }
                var item = pending(r.getObject(1, UUID.class), owner, r.getString(7), UUID.fromString(r.getString(8)));
                if (!owners.containsKey(owner) || r.getObject(9) == null || owners.get(owner).revision() != r.getLong(9)
                        || !item.base().version().equals(Optional.ofNullable(r.getString(10)))
                        || !item.target().version().equals(Optional.ofNullable(r.getString(11)))
                        || !owners.get(owner).choice().equals(item.target())
                        || pending.putIfAbsent(owner, item) != null)
                    throw rejected("MOD_PENDING_INVALID",
                            "Pending intent no longer matches its original registry revision");
            }
        }
        releases.replaceAll((k, v) -> Map.copyOf(v));
        schemas.replaceAll((k, v) -> Map.copyOf(v));
        return new Snapshot(revision, Map.copyOf(owners), Map.copyOf(releases), Map.copyOf(schemas),
                Map.copyOf(pending), Set.copyOf(busy));
    }

    /** Parses a bounded original pending intent without accepting unknown fields. / 解析有界原待执行意图，不接受未知字段。
     * @param id operation identity / 操作身份
     * @param owner registered owner / 登记所有者
     * @param json original request JSON / 原请求 JSON
     * @param archiveId fixed archive identity / 固定归档身份
     * @return validated pending request / 已校验待执行请求
     * @throws SQLException if the request is malformed / 请求格式错误时 */
    private static Pending pending(UUID id, String owner, String json, UUID archiveId) throws SQLException {
        try {
            if (json.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
                throw new IllegalArgumentException();
            var node = JSON.readTree(json);
            var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            if (!names.equals(
                    Set.of("formatVersion", "requestId", "bootId", "ownerId", "base", "target", "data", "affected"))
                    || !node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1
                    || !owner.equals(node.path("ownerId").asText()))
                throw new IllegalArgumentException();
            if (!UUID.fromString(node.required("bootId").textValue()).toString()
                    .equals(node.required("bootId").textValue())
                    || !UUID.fromString(node.required("requestId").textValue()).toString()
                            .equals(node.required("requestId").textValue()))
                throw new IllegalArgumentException();
            if (!node.path("affected").isArray() || node.path("affected").isEmpty())
                throw new IllegalArgumentException();
            return new Pending(id, owner, UUID.fromString(node.required("requestId").textValue()),
                    choice(node.required("base")), choice(node.required("target")), archiveId, json);
        } catch (java.io.IOException | RuntimeException invalid) {
            throw rejected("MOD_PENDING_INVALID", "The retained intent request cannot be validated");
        }
    }

    /** Reads exactly the persisted switch and selection fields. / 精确读取持久开关及选择字段。
     * @param node stored choice / 已存选择
     * @return validated choice / 已校验选择 */
    private static Choice choice(JsonNode node) {
        if (!node.isObject() || node.size() != 2 || !node.has("version") || !node.path("enabled").isBoolean()
                || !(node.get("version").isNull() || node.get("version").isTextual()))
            throw new IllegalArgumentException();
        return new Choice(node.get("enabled").booleanValue(), Optional.ofNullable(node.get("version").textValue()));
    }

    /** Encodes a choice without serializing Optional implementation details. / 编码选择，不序列化 Optional 实现细节。
     * @param choice original choice / 原选择
     * @return exact JSON fields / 精确 JSON 字段 */
    private static JsonNode json(Choice choice) {
        return JSON.createObjectNode().put("enabled", choice.enabled()).put("version", choice.version().orElse(null));
    }

    /** Inserts only an unapplied restart request with a stable group identity. / 仅插入具有稳定组身份的未应用重启请求。
     * @param c owning transaction / 所属事务
     * @param review exact original review / 精确原审阅
     * @param owner affected owner / 受影响所有者
     * @param base choice before the pending chain / 待执行链之前的选择
     * @param target desired choice / 期望选择
     * @param revision accepted owner revision / 接纳的所有者修订
     * @param now transaction timestamp / 事务时间戳
     * @throws SQLException if the insert fails / 插入失败时 */
    private void insert(Connection c, Review review, String owner, Choice base, Choice target, long revision, long now)
            throws SQLException {
        var request = JSON.createObjectNode().put("formatVersion", 1).put("requestId", review.id.toString())
                .put("bootId", boot.toString()).put("ownerId", owner);
        request.set("base", json(base));
        request.set("target", json(target));
        request.set("affected", JSON.valueToTree(review.affected));
        try {
            request.set("data", JSON.readTree(review.dataPlans.getOrDefault(owner, "{}")));
        } catch (java.io.IOException invalid) {
            throw new SQLException("Reviewed data preparation is invalid", invalid);
        }
        if (request.toString().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
            throw rejected("MOD_REQUEST_TOO_LARGE", "The reviewed request exceeds its durable evidence limit");
        String type = base.enabled() != target.enabled() ? target.enabled() ? "ENABLE" : "DISABLE" : "SWITCH";
        var data = request.path("data");
        var original = review.before.releases().getOrDefault(owner, Map.of()).get(base.version().orElse(""));
        var selected = review.before.releases().getOrDefault(owner, Map.of()).get(target.version().orElse(""));
        String sourceSchema = "MIGRATE".equals(data.path("mode").asText())
                ? data.path("source").asText()
                : original == null ? null : original.schema();
        UUID targetGeneration = "REUSE".equals(data.path("mode").asText())
                ? UUID.fromString(data.path("generation").asText())
                : null;
        try (var p = c.prepareStatement(
                "INSERT INTO mod_operation(operation_id,mod_id,operation_type,source_version,target_version,source_schema_version,target_schema_version,target_generation,accepted_revision,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'WAITING','WAIT_RESTART',?,'{}',?,?,?)")) {
            p.setObject(1, UUID.randomUUID());
            p.setString(2, owner);
            p.setString(3, type);
            p.setString(4, base.version().orElse(null));
            p.setString(5, target.version().orElse(null));
            p.setString(6, sourceSchema);
            p.setString(7, selected == null ? null : selected.schema());
            p.setObject(8, targetGeneration);
            p.setLong(9, revision);
            p.setString(10, request.toString());
            p.setString(11, UUID.randomUUID().toString());
            p.setLong(12, now);
            p.setLong(13, now);
            p.executeUpdate();
        }
    }

    /** Archives an unexecuted replaced request and removes it in the same transaction. / 在同一事务中归档未执行的被替代请求并移除它。
     * @param c owning transaction / 所属事务
     * @param pending exact original pending request / 精确原待执行请求
     * @param now transaction timestamp / 事务时间戳
     * @throws SQLException if archive or cleanup is unconfirmed / 归档或清理未确认时 */
    private void archiveCancelled(Connection c, Pending pending, long now) throws SQLException {
        logs.store(c, archive,
                List.of(new LogRecordCodec.Record(pending.archive(), now, "app", "mod_operation", LogLevel.INFO,
                        "MOD_INTENT_CANCELLED", "Pending Mod change was superseded before execution", null,
                        pending.owner(), pending.target().version().orElse(null), null, pending.id(), Outcome.CANCELLED,
                        false)),
                100);
        try (var p = c.prepareStatement(
                "DELETE FROM mod_operation WHERE operation_id=? AND state='WAITING' AND phase='WAIT_RESTART'")) {
            p.setObject(1, pending.id());
            if (p.executeUpdate() != 1)
                throw new SQLException("Original pending request changed");
        }
    }

    /** Serializes one bounded management operation with task admission. / 将一次有界管理操作与任务准入串行化。
     * @param <T> result type / 结果类型
     * @param work non-author platform work / 非作者平台工作
     * @return result after the transaction commits / 事务提交后的结果
     * @throws SQLException if work or commit fails / 工作或提交失败时 */
    private <T> T locked(SqlWork<T> work) throws SQLException {
        var value = new AtomicReference<T>();
        connections.transaction(() -> {
            try (var c = connections.open()) {
                RepositoryConnections.lockPlatformState(c);
                value.set(work.run(c));
            }
        });
        return value.get();
    }

    /** Returns a safe rejected request without exposing storage details. / 返回安全拒绝请求，不暴露存储细节。
     * @param event stable event / 稳定事件
     * @param message safe explanation / 安全说明
     * @return internal transactional signal carrying public evidence / 携带公开证据的内部事务信号 */
    private static SQLException rejected(String event, String message) {
        return new SQLException(message,
                new ApiException(new Problem(ProblemCode.UNAVAILABLE, event, message, Map.of(), Optional.empty())));
    }

    /** Preserves known rejection, otherwise reports uncertain persistence without replay. / 保留已知拒绝，否则报告不确定持久化且不重放。
     * @param failure original platform failure / 原平台失败
     * @return safe public failure / 安全公开失败 */
    private static ApiException publicFailure(SQLException failure) {
        if (failure.getSuppressed().length == 0 && failure.getCause() instanceof ApiException api)
            return api;
        return new ApiException(new Problem(ProblemCode.UNKNOWN, "MOD_INTENT_STORAGE_UNCONFIRMED",
                "Mod management storage could not be confirmed; reread the saved state", Map.of(),
                Optional.of(UUID.randomUUID())));
    }

    /** Rejects EDT database access and use after closure. / 拒绝 EDT 数据库访问及关闭后使用。 */
    private void requireOpen() {
        if (closed || SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Mod management requires an open background service");
    }

    /** Seals management after accepted calls return. / 已接纳调用返回后封闭管理。 */
    @Override
    public synchronized void close() {
        closed = true;
    }

    /** Internal SQL work without author callbacks. / 不含作者回调的内部 SQL 工作。
     * @param <T> result type / 结果类型 */
    @FunctionalInterface
    private interface SqlWork<T> {
        /** Performs one locked operation. / 执行一个已锁定操作。
         * @param connection original transaction / 原事务
         * @return operation result / 操作结果
         * @throws SQLException if the operation cannot complete / 操作无法完成时 */
        T run(Connection connection) throws SQLException;
    }

    /** Decision-relevant owner state. / 决策相关所有者状态。
     * @param choice saved intent / 已存意图
     * @param revision original revision / 原修订
     * @param boot last activation boot / 上次启用启动
     * @param runtime last actual state / 上次实际状态
     * @param actual original loaded release / 原加载发布 */
    private record Owner(Choice choice, long revision, UUID boot, String runtime, String actual) {
    }

    /** Registered release facts. / 登记发布事实。
     * @param state install state / 安装状态
     * @param schema declared data version / 声明数据版本
     * @param json exact manifest / 精确清单
     * @param digest registered content digest / 登记内容摘要 */
    private record Release(String state, String schema, String json, String digest) {
    }

    /** Registered data generation. / 登记数据代次。
     * @param generation original generation / 原代次
     * @param state preparation state / 准备状态
     * @param physical physical schema / 物理 Schema
     * @param user exact database owner / 精确数据库所有者
     * @param used whether handed to business / 是否曾交付业务
     * @param validatedAt original accepted validation time / 原接纳验证时间
     * @param validationDigest original accepted validation receipt / 原接纳验证凭据 */
    private record Data(UUID generation, String state, String physical, String user, boolean used, Long validatedAt,
            String validationDigest) {
    }

    /** Retained original restart request. / 保留的原重启请求。
     * @param id operation identity / 操作身份
     * @param owner affected owner / 受影响所有者
     * @param requestId original group / 原组
     * @param base original intent / 原意图
     * @param target requested intent / 请求意图
     * @param archive original archive identity / 原归档身份
     * @param json original request evidence / 原请求证据 */
    private record Pending(UUID id, String owner, UUID requestId, Choice base, Choice target, UUID archive,
            String json) {
    }

    /** Immutable complete review snapshot. / 不可变完整审阅快照。
     * @param revision platform intent revision / 平台意图修订
     * @param owners saved owner facts / 已存所有者事实
     * @param releases installed release facts / 已安装发布事实
     * @param schemas original data generations / 原数据代次
     * @param pending unexecuted requests / 未执行请求
     * @param busy owners retaining other operations / 保留其他操作的所有者 */
    private record Snapshot(long revision, Map<String, Owner> owners, Map<String, Map<String, Release>> releases,
            Map<String, Map<String, Data>> schemas, Map<String, Pending> pending, Set<String> busy) {
    }
}
