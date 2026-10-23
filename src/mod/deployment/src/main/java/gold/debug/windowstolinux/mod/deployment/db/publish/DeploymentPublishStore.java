package gold.debug.windowstolinux.mod.deployment.db.publish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentPayloadReader;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseCodec;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseRecord.DeploymentReleaseState;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseRecord;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseSnapshot;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseStore;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentConfigStore;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentReleaseBindings;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentSecretStore;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentUnitRuntimeCodec;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.db.transaction.DeploymentStorageTransaction;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentCurrentRelease;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitRuntimeConfig;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseConnection;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;

/** Atomically records verified component releases and their complete application graph. / 原子记录已验证组件发布及其完整应用图。 */
public final class DeploymentPublishStore {
    /** Original task-bound database capability. / 原任务绑定数据库能力。 */
    private final ModDatabase database;

    /** Binds the original owned generation. / 绑定原所属代次。
     * @param database task-bound author database / 任务绑定作者数据库 */
    public DeploymentPublishStore(ModDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Commits every unit, configuration, exact secret set and topology in one business transaction. / 在一个业务事务内提交所有单元、配置、精确秘密集合及拓扑。
     * @param task original active task / 原活动任务
     * @param graph complete reviewed graph / 完整审阅图
     * @param publications verified records matching every graph component / 匹配每个图组件的已验证记录
     * @param observedAt original successful observation time / 原成功观测时间
     * @throws SQLException if records conflict or SQL fails / 记录冲突或SQL失败时
     * @throws ApiException if task access or transaction acknowledgement is unavailable / 任务访问或事务确认不可用时 */
    public void saveSuccessfulApplication(TaskContext task, DeploymentApplicationGraph graph,
                                          List<DeploymentUnitCommit> publications, Instant observedAt) throws SQLException, ApiException {
        var records = validateInputs(graph, publications);
        Objects.requireNonNull(observedAt);
        var connection = connection(task);
        DeploymentStorageTransaction.execute(connection, () -> save(connection, graph, records, observedAt));
    }

    /** Writes publication facts inside the journal's original transaction. / 在账本原事务内写入发布事实。
     * @param connection original business transaction / 原业务事务
     * @param graph complete reviewed graph / 完整审阅图
     * @param publications exact component records / 精确组件记录
     * @param observedAt successful observation time / 成功观测时间
     * @throws SQLException if transaction ownership, inputs or writes fail / 事务归属、输入或写入失败时 */
    public static void save(Connection connection, DeploymentApplicationGraph graph, List<DeploymentUnitCommit> publications,
                            Instant observedAt) throws SQLException {
        if (connection.getAutoCommit())
            throw new SQLException("Publish requires the original business transaction");
        var records = validateInputs(graph, publications);
        Objects.requireNonNull(observedAt);
        requireOriginalLayout(connection, graph);
        var existing = existingUnits(connection, records);
        for (var record : records)
            saveUnit(connection, record, existing.contains(record.unit().id()));
        for (var record : records) {
            DeploymentConfigStore.saveAndBindRelease(connection, record.configuration(),
                record.release().sha256());
            DeploymentSecretStore.bindRelease(connection, record.unit().id(), record.release().sha256(),
                Set.copyOf(record.secrets()));
        }
        DeploymentGraphStore.save(connection, graph, observedAt);
    }

    /** Reads persisted identity without manufacturing endpoint or trust data. / 读取持久身份，不补造端点或信任数据。
     * @param task original active task / 原活动任务
     * @param unitId exact deployment unit / 精确部署单元
     * @return original unit identity or absent / 原单元身份或不存在
     * @throws SQLException if stored identity is invalid / 存储身份无效时
     * @throws ApiException if task access is unavailable / 任务访问不可用时 */
    public Optional<DeploymentUnitIdentity> findUnit(TaskContext task, String unitId)
        throws SQLException, ApiException {
        Objects.requireNonNull(unitId);
        try (var connection = connection(task);
             var statement = connection
                 .prepareStatement("SELECT * FROM deployment_unit WHERE deployment_unit_id=?")) {
            statement.setString(1, unitId);
            try (var row = statement.executeQuery()) {
                return row.next() ? Optional.of(DeploymentGraphStore.unit(connection, row)) : Optional.empty();
            } catch (IllegalArgumentException failure) {
                throw new SQLException("Stored unit identity is invalid", failure);
            }
        }
    }

    /** Reads the current release without changing any historical binding. / 读取当前发布，不更改任何历史绑定。
     * @param task original active task / 原活动任务
     * @param unitId exact deployment unit / 精确部署单元
     * @return current release or absent / 当前发布或不存在
     * @throws SQLException if stored release data is invalid / 存储发布数据无效时
     * @throws ApiException if task access is unavailable / 任务访问不可用时 */
    public Optional<DeploymentCurrentRelease> findRelease(TaskContext task, String unitId)
        throws SQLException, ApiException {
        Objects.requireNonNull(unitId);
        try (var connection = connection(task);
             var statement = connection.prepareStatement(
                 "SELECT current_release_sha256,current_release_published_at FROM deployment_unit WHERE deployment_unit_id=? AND current_release_sha256 IS NOT NULL")) {
            statement.setString(1, unitId);
            try (var row = statement.executeQuery()) {
                return row.next()
                    ? Optional.of(new DeploymentCurrentRelease(unitId, row.getString(1),
                    Instant.ofEpochMilli(row.getLong(2))))
                    : Optional.empty();
            } catch (IllegalArgumentException failure) {
                throw new SQLException("Stored current release is invalid", failure);
            }
        }
    }

    /** Reads the current bounded activation contract. / 读取当前有界激活契约。
     * @param task original active task / 原活动任务
     * @param unitId exact deployment unit / 精确部署单元
     * @return original runtime or absent / 原运行契约或不存在
     * @throws SQLException if the document is invalid / 文档无效时
     * @throws ApiException if task access is unavailable / 任务访问不可用时 */
    public Optional<DeploymentUnitRuntimeConfig> findRuntime(TaskContext task, String unitId)
        throws SQLException, ApiException {
        try (var connection = connection(task);
             var statement = connection.prepareStatement(
                 "SELECT runtime_payload FROM deployment_unit WHERE deployment_unit_id=? AND runtime_payload IS NOT NULL")) {
            statement.setString(1, Objects.requireNonNull(unitId));
            try (var row = statement.executeQuery()) {
                return row.next()
                    ? Optional.of(new DeploymentUnitRuntimeCodec()
                    .read(DeploymentPayloadReader.read(row, "runtime_payload")))
                    : Optional.empty();
            }
        } catch (IOException failure) {
            throw new SQLException("Stored unit runtime is invalid", failure);
        }
    }

    /** Rejects other owners before opening the original connection. / 打开原连接前拒绝其他所有者。
     * @param task original active task / 原活动任务
     * @return original managed connection / 原受管连接
     * @throws SQLException if JDBC is unavailable / JDBC不可用时
     * @throws ApiException if identity or task access is unavailable / 身份或任务访问不可用时 */
    private Connection connection(TaskContext task) throws SQLException, ApiException {
        DeploymentSchemaIdentity.requireIdentity(database.identity());
        return database.openConnection(task);
    }

    /** Requires exact graph coverage before opening JDBC and uses a stable unit lock order. / 打开JDBC前要求精确图覆盖，并采用稳定单元锁序。
     * @param graph complete reviewed graph / 完整审阅图
     * @param publications original publication records / 原发布记录
     * @return stable complete unit records / 稳定完整单元记录 */
    private static List<DeploymentUnitCommit> validateInputs(DeploymentApplicationGraph graph,
                                                             List<DeploymentUnitCommit> publications) {
        Objects.requireNonNull(graph);
        var layout = graph.components().getFirst().unit().layout();
        if (graph.components().stream().anyMatch(c -> !layout.equals(c.unit().layout())))
            throw new IllegalArgumentException("Application graph requires one managed layout");
        var records = List.copyOf(publications);
        var indexed = new HashMap<String, DeploymentUnitCommit>();
        for (var record : records)
            if (indexed.put(record.unit().id(), record) != null)
                throw new IllegalArgumentException("Publish units must be unique");
        if (records.size() != graph.components().size())
            throw new IllegalArgumentException("Publish must cover the complete graph");
        for (var component : graph.components()) {
            var record = indexed.get(component.unit().id());
            if (record == null || !component.unit().equals(record.unit())
                || !component.runtime().equals(record.runtime()))
                throw new IllegalArgumentException("Publish differs from its reviewed graph");
            if (!record.secrets().containsAll(requiredSecrets(component)))
                throw new IllegalArgumentException("Publish omits a reviewed database secret revision");
        }
        return records.stream().sorted(Comparator.comparing(r -> r.unit().id())).toList();
    }

    /** Keeps the original application's layout even when its component set is replaced. / 即使替换组件集合也保留原应用布局。 */
    private static void requireOriginalLayout(Connection connection, DeploymentApplicationGraph graph) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT application_id FROM managed_application WHERE application_id=?")) {
            statement.setString(1, graph.applicationId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    return;
            }
        }
        var original = DeploymentGraphStore.read(connection, graph.applicationId())
            .orElseThrow(() -> new SQLException("Existing application lacks its complete original identity"));
        if (!original.components().getFirst().unit().server().equals(graph.components().getFirst().unit().server())
            || !original.components().getFirst().unit().layout().equals(graph.components().getFirst().unit().layout()))
            throw new SQLException("Application belongs to another managed layout");
    }

    /** Locks and checks every complete original unit before any publication write. / 任一发布写入前锁定并核验全部完整原单元。 */
    private static Set<String> existingUnits(Connection connection, List<DeploymentUnitCommit> records) throws SQLException {
        var existing = new HashSet<String>();
        for (var record : records) {
            var unit = record.unit();
            try (var statement = connection
                .prepareStatement("SELECT * FROM deployment_unit WHERE deployment_unit_id=? FOR UPDATE")) {
                statement.setString(1, unit.id());
                try (var row = statement.executeQuery()) {
                    if (!row.next())
                        continue;
                    existing.add(unit.id());
                    var stored = DeploymentGraphStore.unit(connection, row);
                    if (!stored.server().equals(unit.server()) || !stored.systemdUnit().equals(unit.systemdUnit())
                        || !stored.releaseRoot().equals(unit.releaseRoot()) || !stored.layout().equals(unit.layout()))
                        throw new SQLException("Deployment unit belongs to another target or layout");
                    if (record.release().sha256().equals(row.getString("current_release_sha256"))
                        && (!stored.equals(unit)
                        || row.getLong("current_release_published_at") != record.release().publishedAt().toEpochMilli()
                        || !record.runtime().equals(new DeploymentUnitRuntimeCodec()
                        .read(DeploymentPayloadReader.read(row, "runtime_payload")))))
                        throw new SQLException("Current release facts differ from the original confirmed publication");
                }
            } catch (IOException | IllegalArgumentException failure) {
                throw new SQLException("Existing deployment unit is invalid", failure);
            }
        }
        return existing;
    }

    /** Writes the proposal only after every existing complete identity was checked and locked. / 全部既有完整身份核验并锁定后才写入提案。 */
    private static void saveUnit(Connection connection, DeploymentUnitCommit record, boolean exists) throws SQLException {
        var unit = record.unit();
        try (var statement = connection.prepareStatement(exists
            ? "UPDATE deployment_unit SET server_profile_id=?,systemd_unit_name=?,release_root_path=?,ownership_manifest_sha256=?,runtime_payload=?,current_release_sha256=?,current_release_published_at=? WHERE deployment_unit_id=?"
            : "INSERT INTO deployment_unit(server_profile_id,systemd_unit_name,release_root_path,ownership_manifest_sha256,runtime_payload,current_release_sha256,current_release_published_at,deployment_unit_id) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, unit.server().id());
            statement.setString(2, unit.systemdUnit());
            statement.setString(3, unit.releaseRoot());
            statement.setString(4, unit.ownershipManifestSha256());
            statement.setBytes(5, new DeploymentUnitRuntimeCodec().write(record.runtime()));
            statement.setString(6, record.release().sha256());
            statement.setLong(7, record.release().publishedAt().toEpochMilli());
            statement.setString(8, unit.id());
            if (statement.executeUpdate() != 1)
                throw new SQLException("Deployment unit write was not confirmed");
        } catch (IOException failure) {
            throw new SQLException("Deployment runtime cannot be persisted", failure);
        }
    }

    /** Collects only explicitly reviewed server-database password revisions. / 仅收集明确审阅的服务型数据库密码修订。
     * @param component original reviewed component / 原审阅组件
     * @return required exact revisions / 必需精确修订 */
    private static Set<DeploymentSecretRef> requiredSecrets(DeploymentApplicationGraph.Part component) {
        var result = new HashSet<DeploymentSecretRef>();
        for (var binding : component.resources().databaseBindings().orElse(List.of()))
            if (binding.connection() instanceof DeploymentDatabaseConnection.Server server)
                result.add(server.passwordReference());
        return result;
    }

    /** Checks current publication facts and reviewed database references without reading platform secrets. / 核验当前发布事实与审阅数据库引用，不读取平台秘密。
     * @param connection original SELECT-only connection / 原仅SELECT连接
     * @throws SQLException if publication metadata is incomplete or invalid / 发布元数据不完整或无效时 */
    public static void validate(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "SELECT u.*,b.configuration_revision,b.is_secret_binding_complete FROM deployment_unit u LEFT JOIN deployment_unit_release_binding b ON b.deployment_unit_id=u.deployment_unit_id AND b.release_sha256=u.current_release_sha256 WHERE u.current_release_sha256 IS NOT NULL")) {
            while (rows.next()) {
                var unit = DeploymentGraphStore.unit(connection, rows);
                new DeploymentCurrentRelease(unit.id(), rows.getString("current_release_sha256"),
                    Instant.ofEpochMilli(rows.getLong("current_release_published_at")));
                new DeploymentUnitRuntimeCodec().read(DeploymentPayloadReader.read(rows, "runtime_payload"));
                if (rows.getObject("configuration_revision") == null || rows.getInt("is_secret_binding_complete") != 1)
                    throw new SQLException("Current publication lacks complete immutable bindings");
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw new SQLException("Current publication facts are invalid", failure);
        }
        try (var statement = connection.createStatement();
             var rows = statement
                 .executeQuery("SELECT application_id FROM managed_application WHERE graph_ready=TRUE")) {
            while (rows.next()) {
                var graph = DeploymentGraphStore.read(connection, rows.getString(1)).orElseThrow();
                for (var component : graph.components()) {
                    var required = requiredSecrets(component);
                    if (required.isEmpty())
                        continue;
                    try (var release = connection.prepareStatement(
                        "SELECT current_release_sha256 FROM deployment_unit WHERE deployment_unit_id=?")) {
                        release.setString(1, component.unit().id());
                        try (var current = release.executeQuery()) {
                            if (!current.next() || current.getString(1) == null
                                || !DeploymentSecretStore
                                .findRelease(connection, component.unit().id(), current.getString(1))
                                .orElse(List.of()).containsAll(required))
                                throw new SQLException(
                                    "Reviewed database secret revisions are not retained by the publication");
                        }
                    }
                }
            }
        }
    }

    /** Encodes the original publication template without creating a new identity. / 编码原发布模板，不另造身份。
     * @param proposal original reviewed publication template / 原已审阅发布模板
     * @return canonical archived template JSON / 规范归档模板 JSON
     * @throws SQLException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static String archiveTemplate(DeploymentPublishProposal proposal) throws SQLException {
        return DeploymentPublishTemplateCodec.encodeTemplate(proposal);
    }

    /** Decodes the original archived publication template and validates its content. / 解码原归档发布模板并校验内容。
     * @param text canonical archived publication document / 规范归档发布文档
     * @param revision captured original model revision / 已捕获原模型修订
     * @return original decoded and digest-verified proposal / 原已解码并核实摘要的提案
     * @throws SQLException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static DeploymentPublishProposal archivedTemplate(String text, String revision) throws SQLException {
        var proposal = DeploymentPublishTemplateCodec.decodeTemplate(text,
            UUID.nameUUIDFromBytes(revision.getBytes(StandardCharsets.UTF_8)));
        var snapshot = DeploymentReleaseSnapshot.capture(proposal.graph(), proposal.units());
        if (!DeploymentReleaseCodec
            .digest(DeploymentReleaseCodec.runtime(snapshot), DeploymentReleaseCodec.health(snapshot))
            .equals(revision))
            throw new SQLException("Archived template differs from original release digest");
        return proposal;
    }

    /** Atomically switches the retained publication using the original connection and bindings. / 使用原连接与绑定原子切换留存发布。
     * @param task original admitted task / 原已准入任务
     * @param expected reviewed active release digest / 已审阅活动发布摘要
     * @param selected retained release selected by the original review / 原审阅选择的留存发布
     * @param publications verified component commits of the original graph / 原整图已核实组件提交
     * @param observedAt exact observation time / 精确观测时间
     * @throws SQLException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public void activateRetained(TaskContext task, String expected, DeploymentReleaseRecord selected,
                                 List<DeploymentUnitCommit> publications, Instant observedAt) throws SQLException, ApiException {
        DeploymentSchemaIdentity.requireIdentity(database.identity());
        var target = new DeploymentReleaseStore(database).snapshot(selected);
        if (!DeploymentReleaseSnapshot.capture(target.graph(), publications).equals(target))
            throw new SQLException("Retained publication differs from original content");
        var connection = database.openConnection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            DeploymentReleaseStore.lockApplication(connection, selected.applicationId(), target.graph().components().getFirst().unit().server().id());
            var current = new ArrayList<DeploymentReleaseRecord>();
            try (var query = connection
                .prepareStatement("SELECT * FROM deployment_release WHERE application_id=? AND state='ACTIVE'")) {
                query.setString(1, selected.applicationId());
                try (var rows = query.executeQuery()) {
                    while (rows.next())
                        current.add(DeploymentReleaseStore.row(rows));
                }
            }
            if (current.size() != 1)
                throw new SQLException("Exactly one active release is required");
            var active = current.getFirst();
            if (active.identity().equals(selected.identity())) {
                if (!new DeploymentReleaseStore(database).snapshot(active).equals(target))
                    throw new SQLException("Committed retained identity differs");
                DeploymentReleaseBindings.require(connection, target.bindings());
                return;
            }
            if (!active.identity().sha256().equals(expected))
                throw new SQLException("Active release changed");
            var retained = DeploymentReleaseStore.read(connection, selected.applicationId(), selected.identity().id()).orElseThrow();
            if (retained.state() != DeploymentReleaseState.RETIRED || !new DeploymentReleaseStore(database).snapshot(retained).equals(target))
                throw new SQLException("Selected retained release changed");
            DeploymentReleaseBindings.require(connection, target.bindings());
            DeploymentPublishStore.save(connection, target.graph(), publications, observedAt);
            try (var retire = connection.prepareStatement(
                "UPDATE deployment_release SET state='RETIRED' WHERE application_id=? AND release_id=? AND state='ACTIVE'");
                 var promote = connection.prepareStatement(
                     "UPDATE deployment_release SET state='ACTIVE' WHERE application_id=? AND release_id=? AND state='RETIRED'")) {
                retire.setString(1, selected.applicationId());
                retire.setObject(2, active.identity().id());
                promote.setString(1, selected.applicationId());
                promote.setObject(2, selected.identity().id());
                if (retire.executeUpdate() != 1 || promote.executeUpdate() != 1)
                    throw new SQLException("Retained history comparison changed");
            }
        });
    }
}
