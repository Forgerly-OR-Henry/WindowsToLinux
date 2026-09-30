package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.PageSlice;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Reads complete published applications in one owned snapshot, excluding drafts and unconfirmed releases. / 在一份所属快照中读取完整已发布应用，排除草稿及未确认发布。 */
public final class DeploymentApplicationReader {
    /** Exact provider Schema used by the snapshot transaction. / 快照事务使用的精确提供方Schema。 */
    private final ModDatabase database;

    /** Canonical complete release validator. / 规范完整发布校验器。 */
    private final DeploymentReleaseRepository releases;

    /** Binds the original deployment Schema. / 绑定原部署Schema。
     * @param database exact deployment database / 精确部署数据库 */
    public DeploymentApplicationReader(ModDatabase database) {
        this.database = java.util.Objects.requireNonNull(database);
        this.releases = new DeploymentReleaseRepository(database);
    }

    /** Reads one persisted page without claiming live remote status. / 读取一页持久记录，不宣称实时远端状态。
     * @param cursor last application ID or absence / 上一应用ID或无
     * @param limit bounded page size / 有界页大小
     * @param task original task / 原任务
     * @return consistent application page / 一致的应用页
     * @throws ApiException if access or stored evidence fails / 访问或存储依据失败时 */
    public PageSlice<DeploymentApplicationSnapshot> list(Optional<String> cursor, int limit, TaskContext task)
            throws ApiException {
        if (limit < 1 || limit > 100 || cursor == null
                || cursor.isPresent() && !cursor.orElseThrow().matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw problem("DEPLOYMENT_APPLICATION_PAGE_INVALID");
        try (var connection = open(task)) {
            var ids = new ArrayList<String>();
            try (var query = connection.prepareStatement(
                    "SELECT application_id FROM managed_application WHERE graph_ready=TRUE AND application_id>? ORDER BY application_id LIMIT ?")) {
                query.setString(1, cursor.orElse(""));
                query.setInt(2, limit + 1);
                try (var rows = query.executeQuery()) {
                    while (rows.next())
                        ids.add(rows.getString(1));
                }
            }
            boolean more = ids.size() > limit;
            if (more)
                ids.removeLast();
            var values = new ArrayList<DeploymentApplicationSnapshot>();
            long bytes = 0;
            int estimatedNodes = 0;
            for (String id : ids) {
                var value = read(connection, id, task).snapshot();
                long next = bytes + value.runtime().payload().length;
                int nodes = estimatedNodes + 24 + 16 * value.bindings().size();
                if (next > 16L * 1_048_576 || nodes > 3500) {
                    if (values.isEmpty())
                        throw problem("DEPLOYMENT_APPLICATION_PAGE_ENTRY_OVERSIZE");
                    more = true;
                    break;
                }
                values.add(value);
                bytes = next;
                estimatedNodes = nodes;
            }
            connection.rollback();
            return new PageSlice<>(values, more ? Optional.of(values.getLast().application().id()) : Optional.empty());
        } catch (SQLException | IOException failure) {
            throw problem("DEPLOYMENT_APPLICATION_READ_FAILED");
        }
    }

    /** Reads exact current facts from the deployment owner only. / 仅从部署所有者读取精确当前事实。
     * @param application exact application and server / 精确应用与服务器
     * @param task original task / 原任务
     * @return current persisted graph and public copy / 当前持久图与公开副本
     * @throws ApiException if identity or stored evidence fails / 身份或存储依据失败时 */
    public ApplicationFacts find(DeploymentApplicationRef application, TaskContext task) throws ApiException {
        try (var connection = open(task)) {
            var value = read(connection, application.id(), task);
            connection.rollback();
            if (!value.snapshot().application().equals(application))
                throw problem("DEPLOYMENT_APPLICATION_SERVER_CHANGED");
            return value;
        } catch (SQLException | IOException failure) {
            throw problem("DEPLOYMENT_APPLICATION_READ_FAILED");
        }
    }

    /** Opens a transaction snapshot bound to this Mod generation. / 打开绑定此Mod代次的事务快照。
     * @param task authenticated original task / 已认证原任务
     * @return original owned snapshot connection / 原所属快照连接
     * @throws SQLException if the transaction cannot be configured / 事务无法设置时
     * @throws ApiException if original Schema access fails / 原Schema访问失败时 */
    private Connection open(TaskContext task) throws SQLException, ApiException {
        DeploymentSchema.requireIdentity(database.identity());
        var connection = database.openConnection(task);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        return connection;
    }

    /** Reconstructs only an ACTIVE release and its complete original graph. / 仅重建活动发布及其完整原图。
     * @param connection original snapshot transaction / 原快照事务
     * @param id selected application identity / 所选应用身份
     * @param task authenticated original task / 已认证原任务
     * @return complete validated application / 完整已校验应用
     * @throws SQLException if persisted identities differ / 持久身份不符时
     * @throws IOException if complete archive encoding fails / 完整归档编码失败时
     * @throws ApiException if original evidence access fails / 原依据访问失败时 */
    private ApplicationFacts read(Connection connection, String id, TaskContext task)
            throws SQLException, IOException, ApiException {
        DeploymentReleaseRecord record = null;
        try (var query = connection
                .prepareStatement("SELECT * FROM deployment_release WHERE application_id=? AND state='ACTIVE'")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (record != null)
                        throw new SQLException("Multiple active releases");
                    record = release(rows);
                }
            }
        }
        if (record == null)
            throw problem("DEPLOYMENT_APPLICATION_NOT_PUBLISHED");
        DeploymentReleaseSnapshot release = releases.snapshot(record);
        var graph = release.graph();
        if (!graph.applicationId().equals(id))
            throw new SQLException("Release application differs");
        var server = graph.components().getFirst().unit().server();
        String display = id;
        ApplicationRunState state = null;
        Instant observed = record.createdAt();
        for (var component : graph.components()) {
            try (var query = connection.prepareStatement(
                    "SELECT current_release_sha256,runtime_state,is_ownership_verified,display_name,observed_at FROM deployment_unit WHERE deployment_unit_id=?")) {
                query.setString(1, component.unit().id());
                try (var rows = query.executeQuery()) {
                    if (!rows.next())
                        throw new SQLException("Published unit is absent");
                    var binding = release.bindings().stream()
                            .filter(value -> value.unitId().equals(component.unit().id())).findFirst().orElseThrow();
                    if (!binding.releaseSha256().equals(rows.getString(1)))
                        throw new SQLException("Current unit differs from release");
                    String runtimeState = rows.getString(2);
                    boolean owned = rows.getObject(3) != null && rows.getInt(3) == 1;
                    var part = state(runtimeState, owned);
                    state = combine(state, part);
                    String name = rows.getString(4);
                    if (name != null && component.componentId().equals(graph.healthComponentId()))
                        display = name;
                    if (rows.getObject(5) != null) {
                        var at = Instant.ofEpochMilli(rows.getLong(5));
                        if (at.isAfter(observed))
                            observed = at;
                    }
                }
            }
        }
        var bindings = bindings(graph);
        var snapshot = new DeploymentApplicationSnapshot(new DeploymentApplicationRef(id, server), display,
                record.identity().sha256(), ownership(graph), state, observed, archived(release, record, task),
                bindings, sha256(new DeploymentHealthCodec().write(graph.healthCheck())));
        return new ApplicationFacts(snapshot, graph, release);
    }

    /** Captures every original typed configuration and nonsecret credential purpose. / 捕获每份原类型化配置与非秘密凭据用途。
     * @param release complete canonical original release / 完整规范原发布
     * @param record original release identity and time / 原发布身份及时间
     * @param task authenticated original task / 已认证原任务
     * @return bounded complete archive / 有界完整归档
     * @throws SQLException if original configuration or metadata differs / 原配置或元数据不符时
     * @throws IOException if the complete archive exceeds its bound / 完整归档超出界限时
     * @throws ApiException if original metadata access fails / 原元数据访问失败时 */
    private VersionedRuntime archived(DeploymentReleaseSnapshot release, DeploymentReleaseRecord record,
            TaskContext task) throws SQLException, IOException, ApiException {
        var configurations = new DeploymentConfigurationRepository(database);
        var credentials = new DeploymentSecretRepository(database);
        var commits = new ArrayList<DeploymentUnitCommit>();
        var metadata = new java.util.LinkedHashMap<DeploymentSecretReference, DeploymentSecretRevision>();
        for (var component : release.graph().components()) {
            var binding = release.bindings().stream().filter(value -> value.unitId().equals(component.unit().id()))
                    .findFirst().orElseThrow();
            var configuration = configurations.find(task, component.unit().id(), binding.configurationRevision())
                    .orElseThrow(() -> new SQLException("Published configuration is absent"));
            if (!configuration.sha256().equals(binding.configurationSha256()))
                throw new SQLException("Published configuration changed");
            commits.add(new DeploymentUnitCommit(component.unit(), component.runtime(),
                    new DeploymentCurrentRelease(component.unit().id(), binding.releaseSha256(), record.createdAt()),
                    configuration, binding.secrets()));
            for (var secret : binding.secrets())
                metadata.putIfAbsent(secret, credentials.findRevision(task, secret)
                        .orElseThrow(() -> new SQLException("Published credential metadata is absent")));
        }
        var proposal = new DeploymentPublicationProposal(release.graph(), commits);
        if (!DeploymentReleaseSnapshot.capture(proposal.graph(), proposal.units()).equals(release))
            throw new SQLException("Published complete archive differs");
        return DeploymentArchivedRuntimeCodec.encode(proposal, List.copyOf(metadata.values()), releases);
    }

    /** Decodes one retained release without consulting another Schema. / 不读取其他Schema而解码一项保留发布。
     * @param rows selected original release row / 所选原发布行
     * @return complete original release record / 完整原发布记录
     * @throws SQLException if identity or payload is invalid / 身份或载荷无效时 */
    private static DeploymentReleaseRecord release(ResultSet rows) throws SQLException {
        try {
            return new DeploymentReleaseRecord(rows.getString("application_id"),
                    new DeploymentReleaseIdentity(rows.getObject("release_id", java.util.UUID.class),
                            rows.getString("release_sha256")),
                    rows.getObject("execution_id", java.util.UUID.class),
                    DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE,
                    new String(bounded(rows, "runtime_payload", 16 * 1_048_576), StandardCharsets.UTF_8),
                    new String(bounded(rows, "health_payload", 1_048_576), StandardCharsets.UTF_8),
                    Instant.ofEpochMilli(rows.getLong("created_at")));
        } catch (IOException | IllegalArgumentException failure) {
            throw new SQLException("Stored release is invalid", failure);
        }
    }

    /** Reads one non-null BLOB under the original format bound. / 在原格式界限内读取一项非空BLOB。
     * @param rows selected original row / 所选原行
     * @param column original payload column / 原载荷列
     * @param limit complete payload byte bound / 完整载荷字节界限
     * @return complete bounded bytes / 完整有界字节
     * @throws SQLException if the original column cannot be read / 原列无法读取时
     * @throws IOException if the payload is absent or oversized / 载荷缺失或过大时 */
    private static byte[] bounded(ResultSet rows, String column, int limit) throws SQLException, IOException {
        try (var stream = rows.getBinaryStream(column)) {
            if (stream == null)
                throw new IOException("Release payload is absent");
            byte[] value = stream.readNBytes(limit + 1);
            if (value.length > limit)
                throw new IOException("Release payload exceeds its format bound");
            return value;
        }
    }

    /** Maps persisted observations conservatively. / 保守映射持久观察。
     * @param runtime persisted runtime observation / 持久运行观察
     * @param owned verified original ownership / 已核实原归属
     * @return conservative saved state / 保守保存状态 */
    private static ApplicationRunState state(String runtime, boolean owned) {
        if (!owned || runtime == null)
            return ApplicationRunState.UNKNOWN;
        return switch (runtime) {
            case "RUNNING" -> ApplicationRunState.RUNNING;
            case "STOPPED", "INSTALLED" -> ApplicationRunState.STOPPED;
            case "ERROR", "DEGRADED" -> ApplicationRunState.DEGRADED;
            default -> ApplicationRunState.UNKNOWN;
        };
    }

    /** Combines components without treating partial operation as success. / 合并组件，不将部分操作视为成功。
     * @param previous prior aggregate or absence / 先前汇总或无
     * @param next next complete component state / 下一完整组件状态
     * @return conservative aggregate / 保守汇总 */
    private static ApplicationRunState combine(ApplicationRunState previous, ApplicationRunState next) {
        if (previous == null)
            return next;
        if (previous == ApplicationRunState.UNKNOWN || next == ApplicationRunState.UNKNOWN)
            return ApplicationRunState.UNKNOWN;
        if (previous == ApplicationRunState.DEGRADED || next == ApplicationRunState.DEGRADED || previous != next)
            return ApplicationRunState.DEGRADED;
        return previous;
    }

    /** Copies reviewed file and database bindings from the full release graph. / 从完整发布图复制已审阅文件和数据库绑定。
     * @param graph complete reviewed graph / 完整已审阅图
     * @return complete component-qualified bindings / 完整带组件身份的绑定 */
    static List<DeploymentBinding> bindings(DeploymentApplicationGraph graph) {
        var values = new ArrayList<DeploymentBinding>();
        for (var component : graph.components()) {
            for (var file : component.resources().fileBindings())
                values.add(new DeploymentBinding(component.componentId() + ":" + file.bindingId(),
                        component.componentId(),
                        file.resourceType() == DeploymentStorageLocation.StorageResourceType.CONFIGURATION
                                ? "CONFIG"
                                : "FILE",
                        file.physicalPath(component.unit().id()), "",
                        file.dataPath().access() == DeploymentStoragePath.AccessMode.READ_WRITE, Optional.empty()));
            for (var database : component.resources().databaseBindings().orElse(List.of())) {
                var connection = database.connection();
                String path = connection instanceof DeploymentDatabaseConnection.Sqlite sqlite
                        ? sqlite.physicalPath(component.unit().id(), database.databaseId())
                        : connection instanceof DeploymentDatabaseConnection.Server remote
                                ? remote.engine().name().toLowerCase(java.util.Locale.ROOT) + "://" + remote.host()
                                        + ":" + remote.port() + "/" + remote.database()
                                : throwUnsupportedDatabase(connection);
                Optional<String> secret = connection instanceof DeploymentDatabaseConnection.Server remote
                        ? Optional.of(remote.passwordReference().identifier())
                        : Optional.empty();
                values.add(new DeploymentBinding(component.componentId() + ":" + database.databaseId(),
                        component.componentId(), "DATABASE", path, connection.engine().name(),
                        connection.engine() == DeploymentDatabaseEngineType.MYSQL
                                || connection.engine() == DeploymentDatabaseEngineType.MARIADB,
                        secret));
            }
        }
        return values.stream()
                .sorted(Comparator.comparing(DeploymentBinding::componentId).thenComparing(DeploymentBinding::id))
                .toList();
    }

    /** Refuses an unknown binding instead of fabricating a physical path. / 拒绝未知绑定而不捏造物理路径。
     * @param connection unsupported original connection / 不支持的原连接
     * @return no value because the connection is rejected / 因拒绝连接而无返回值
     * @throws IllegalArgumentException for the unsupported connection / 连接不受支持时 */
    private static String throwUnsupportedDatabase(DeploymentDatabaseConnection connection) {
        throw new IllegalArgumentException("Unsupported database connection: " + connection.getClass().getSimpleName());
    }

    /** Hashes complete ordered ownership facts. / 哈希完整有序归属事实。
     * @param graph complete original graph / 完整原图
     * @return exact ownership digest / 精确归属摘要 */
    static String ownership(DeploymentApplicationGraph graph) {
        var text = new StringBuilder("deployment-ownership-v1\n");
        for (var component : graph.components())
            text.append(component.componentId()).append('\n').append(component.unit().id()).append('\n')
                    .append(component.unit().ownershipManifestSha256()).append('\n');
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Computes SHA-256 using the JDK. / 使用JDK计算SHA-256。
     * @param value complete original bytes / 完整原字节
     * @return lowercase complete digest / 小写完整摘要 */
    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** Exposes bounded public failure without leaking SQL or secret content. / 不泄漏SQL或秘密内容的有界公开失败。
     * @param event stable evidence failure / 稳定依据失败
     * @return bounded original evidence exception / 有界原依据异常 */
    private static ApiException problem(String event) {
        return new ApiException(new gold.debug.windowstolinux.appapi.task.Problem(
                gold.debug.windowstolinux.appapi.task.ProblemCode.INCOMPATIBLE, event,
                "Deployment application evidence cannot be confirmed", java.util.Map.of(), Optional.empty()));
    }

    /** Full original facts retained only inside deployment service. / 仅在部署服务内部保留的完整原事实。
     * @param snapshot public copy / 公开副本
     * @param graph original graph / 原图
     * @param release original active release / 原活动发布 */
    public record ApplicationFacts(DeploymentApplicationSnapshot snapshot, DeploymentApplicationGraph graph,
            DeploymentReleaseSnapshot release) {
    }
}
