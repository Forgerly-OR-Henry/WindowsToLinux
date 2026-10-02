package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;

import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationSnapshot;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseRecord.DeploymentReleaseState;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseRepository;

/** Combines saved formal publications and adopted runtimes without observing a remote server. / 合并已存正式发布与接管运行记录，不观察远端服务器。 */
public final class DeploymentApplicationInventory {
    /** Exact active deployment generation for local reads. / 本地读取所用的精确活动部署代次。 */
    private final ModContext context;

    /** Binds only the original local query capabilities. / 仅绑定原本地查询能力。
     * @param context active owner capabilities / 活动所有者能力 */
    public DeploymentApplicationInventory(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** One saved identity retaining its original action payload and actual business dates. / 保留原动作载荷与真实业务时间的一项保存身份。
     * @param key stable origin-qualified selection identity / 稳定且区分来源的选择身份
     * @param server original saved endpoint identity / 原保存端点身份
     * @param name saved display name / 保存的展示名称
     * @param category declared runtime or saved presentation category / 声明的运行或保存展示类别
     * @param accessUrl declared or explicitly overridden business URL / 声明或明确覆盖的业务URL
     * @param deployedAt actual formal active release creation time / 正式活动发布的实际创建时间
     * @param adoptedAt actual original registration time / 原登记的实际接管时间
     * @param managed original complete publication review / 原完整发布审阅
     * @param external original adopted runtime review / 原接管运行审阅 */
    public record Entry(String key, ServerRef server, String name, String category, Optional<String> accessUrl,
                        Optional<Instant> deployedAt, Optional<Instant> adoptedAt,
                        Optional<DeploymentApplicationSnapshot> managed,
                        Optional<DeploymentExternalApplications.Registered> external) {
        /** Requires exactly one original payload and its corresponding real date. / 要求恰好一份原载荷及其对应真实时间。
         * @param key stable selection identity / 稳定选择身份
         * @param server original endpoint / 原端点
         * @param name saved name / 保存名称
         * @param category original category / 原类别
         * @param accessUrl business URL or absence / 业务URL或无值
         * @param deployedAt formal release time / 正式发布时间
         * @param adoptedAt registration time / 登记时间
         * @param managed original complete managed review / 原完整受管审阅
         * @param external original adopted review / 原接管审阅 */
        public Entry {
            Objects.requireNonNull(key);
            Objects.requireNonNull(server);
            Objects.requireNonNull(name);
            Objects.requireNonNull(accessUrl);
            Objects.requireNonNull(deployedAt);
            Objects.requireNonNull(adoptedAt);
            Objects.requireNonNull(managed);
            Objects.requireNonNull(external);
            if (!Set.of("APP", "WEBSITE").contains(category) || managed.isPresent() == external.isPresent()
                || deployedAt.isPresent() != managed.isPresent() || adoptedAt.isPresent() != external.isPresent())
                throw new IllegalArgumentException("Incomplete original inventory identity");
            if (managed.isPresent()
                && (!key.equals("managed:" + managed.orElseThrow().application().id())
                || !server.equals(managed.orElseThrow().application().server()))
                || external.isPresent() && (!key.equals("external:" + external.orElseThrow().id())
                || !server.equals(external.orElseThrow().server())))
                throw new IllegalArgumentException("Inventory selection differs from original payload");
        }

        /** Matches both local filters against saved facts, without issuing another task. / 对保存事实同时匹配两个本地筛选，不发起另一任务。
         * @param category optional exact category / 可选精确类别
         * @param server optional exact saved endpoint / 可选精确保存端点
         * @return whether both filters match / 两项筛选是否都匹配 */
        public boolean matches(Optional<String> category, Optional<ServerRef> server) {
            return category.map(this.category::equals).orElse(true) && server.map(this.server::equals).orElse(true);
        }

        /** Uses only the actual date belonging to this entry's origin. / 仅使用属于该条目来源的真实时间。
         * @return formal deployment or original adoption time / 正式部署或原接管时间 */
        public Instant time() {
            return deployedAt.or(() -> adoptedAt).orElseThrow();
        }
    }

    /** Reads the complete bounded managed inventory, excluding unpublished drafts. / 读取完整且有界的受管清单，排除未发布草稿。
     * @param task authentic local query / 真实本地查询
     * @return original complete snapshots / 原完整快照
     * @throws ApiException if saved evidence cannot be read / 无法读取保存依据时 */
    public List<DeploymentApplicationSnapshot> managed(TaskContext task) throws ApiException {
        var reader = new DeploymentApplicationReader(context.db());
        var result = new ArrayList<DeploymentApplicationSnapshot>();
        Optional<String> cursor = Optional.empty();
        do {
            var page = reader.list(cursor, 100, task);
            result.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor.isPresent() && result.size() < 10000);
        if (cursor.isPresent())
            throw unavailable("DEPLOYMENT_APPLICATION_LIST_LIMIT");
        return List.copyOf(result);
    }

    /** Reads both origins and orders actual dates with a stable identity tie-break. / 读取两类来源，按真实时间及稳定身份次序排序。
     * @param task original local query task / 原本地查询任务
     * @return immutable unified saved inventory / 不可变统一保存清单
     * @throws ApiException if original metadata no longer matches / 原元数据不再匹配时 */
    public List<Entry> list(TaskContext task) throws ApiException {
        var result = new ArrayList<Entry>();
        try {
            for (var snapshot : managed(task))
                result.add(managedEntry(snapshot, task));
            for (var saved : new DeploymentExternalApplications(context).list(task))
                result.add(externalEntry(saved, task));
        } catch (SQLException invalid) {
            throw unavailable("DEPLOYMENT_APPLICATION_INVENTORY_UNAVAILABLE");
        }
        if (result.size() > 10000)
            throw unavailable("DEPLOYMENT_APPLICATION_INVENTORY_LIMIT");
        result.sort(Comparator.comparing(Entry::time).reversed().thenComparing(Entry::key));
        return List.copyOf(result);
    }

    /** Keeps the original release time and distinguishes a cleared display URL from an absent override. / 保留原发布时间，区分已清空展示URL与无覆盖。
     * @param snapshot complete selected publication / 完整所选发布
     * @param task original local task / 原本地任务
     * @return original managed entry / 原受管条目
     * @throws ApiException if complete evidence differs / 完整依据不符时
     * @throws SQLException if metadata is unavailable / 元数据不可用时 */
    private Entry managedEntry(DeploymentApplicationSnapshot snapshot, TaskContext task)
        throws ApiException, SQLException {
        var data = new DeploymentApplicationReader(context.db()).find(snapshot.application(), task);
        if (!snapshot.equals(data.snapshot()))
            throw unavailable("DEPLOYMENT_APPLICATION_INVENTORY_CHANGED");
        var releases = new DeploymentReleaseRepository(context.db()).list(task, snapshot.application().id()).stream()
            .filter(value -> value.state() == DeploymentReleaseState.ACTIVE
                && value.identity().sha256().equals(snapshot.revisionSha256()))
            .toList();
        if (releases.size() != 1)
            throw unavailable("DEPLOYMENT_APPLICATION_INVENTORY_CHANGED");
        var health = data.graph().components().stream()
            .filter(value -> value.componentId().equals(data.graph().healthComponentId())).findFirst()
            .orElseThrow();
        Optional<String> url;
        try (var connection = context.db().openConnection(task);
             var query = connection.prepareStatement(
                 "SELECT has_display_override,access_url,display_name,application_category,current_release_sha256 FROM deployment_unit WHERE deployment_unit_id=? AND server_profile_id=?")) {
            query.setString(1, health.unit().id());
            query.setString(2, snapshot.application().server().id());
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    throw unavailable("DEPLOYMENT_APPLICATION_INVENTORY_CHANGED");
                var binding = data.release().bindings().stream()
                    .filter(value -> value.unitId().equals(health.unit().id())).findFirst().orElseThrow();
                if (!binding.releaseSha256().equals(rows.getString(5))
                    || rows.getInt(1) == 1 && (!snapshot.displayName().equals(rows.getString(3))
                    || !health.runtime().workload().category().name().equals(rows.getString(4))))
                    throw unavailable("DEPLOYMENT_APPLICATION_INVENTORY_CHANGED");
                url = rows.getInt(1) == 1
                    ? Optional.ofNullable(rows.getString(2))
                    : health.runtime().userAccessUrl().map(value -> value.url().toString());
            }
        }
        return new Entry("managed:" + snapshot.application().id(), snapshot.application().server(),
            snapshot.displayName(), health.runtime().workload().category().name(), url,
            Optional.of(releases.getFirst().createdAt()), Optional.empty(), Optional.of(snapshot),
            Optional.empty());
    }

    /** Reads adoption time only for the exact saved runtime identity and fingerprint. / 仅为精确保存的运行身份及指纹读取接管时间。
     * @param saved original adopted registration / 原接管登记
     * @param task original local query / 原本地查询
     * @return external entry without an invented deployment date / 不虚构部署时间的外部条目
     * @throws ApiException if the original row changed / 原行改变时
     * @throws SQLException if adoption metadata cannot be read / 无法读取接管元数据时 */
    private Entry externalEntry(DeploymentExternalApplications.Registered saved, TaskContext task)
        throws ApiException, SQLException {
        Instant adopted;
        try (var connection = context.db().openConnection(task);
             var query = connection.prepareStatement(
                 "SELECT adopted_at FROM external_application WHERE external_application_id=? AND server_profile_id=? AND runtime_type=? AND runtime_identity=? AND identity_fingerprint=?")) {
            query.setString(1, saved.id());
            query.setString(2, saved.server().id());
            query.setString(3, saved.target().kind());
            query.setString(4, saved.target().identity());
            query.setString(5, saved.target().fingerprint());
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    throw unavailable("DEPLOYMENT_APPLICATION_INVENTORY_CHANGED");
                adopted = Instant.ofEpochMilli(rows.getLong(1));
            }
        }
        return new Entry("external:" + saved.id(), saved.server(), saved.displayName(), saved.category(),
            saved.accessUrl(), Optional.empty(), Optional.of(adopted), Optional.empty(), Optional.of(saved));
    }

    /** Refuses an incomplete local view without claiming a successful application. / 拒绝不完整本地视图，不宣称应用成功。
     * @param event exact read failure / 精确读取失败
     * @return original known local read rejection / 原已知本地读取拒绝 */
    private static ApiException unavailable(String event) {
        return new ApiException(new Problem(ProblemCode.IO_FAILURE, event,
            "Saved application inventory could not be read consistently", Map.of(), Optional.empty()));
    }
}
