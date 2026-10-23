package gold.debug.windowstolinux.mod.deployment.db.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentPayloadReader;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseRecord.DeploymentReleaseState;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentReleaseBindings;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentHealthCodec;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentResourceCodec;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentRuntimeCodec;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentStoragePathCodec;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentUnitRuntimeCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;

/** Retains original release history in the same transaction as successful publication. / 在成功发布的同一事务中保留原发布历史。 */
public final class DeploymentReleaseStore {
    /** Exact owned author database capability. / 精确所属作者数据库能力。 */
    private final ModDatabase database;

    /** Binds the original author database. / 绑定原作者数据库。
     * @param database original capability / 原能力 */
    public DeploymentReleaseStore(ModDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Reads the original bounded documents, retaining unsupported versions unchanged. / 读取原有界文档，未知版本保持原样。
     * @param task active read context / 活动读取上下文
     * @param applicationId original application / 原应用
     * @param releaseId original history identity / 原历史身份
     * @return original record or absence / 原记录或不存在
     * @throws SQLException if stored data is malformed / 存储数据无效时
     * @throws ApiException if access is unavailable / 访问不可用时 */
    public Optional<DeploymentReleaseRecord> find(TaskContext task, String applicationId, UUID releaseId)
        throws SQLException, ApiException {
        DeploymentSchemaIdentity.requireIdentity(database.identity());
        try (var connection = database.openConnection(task)) {
            return read(connection, Objects.requireNonNull(applicationId), Objects.requireNonNull(releaseId));
        }
    }

    /** Interprets only the retained supported version without consulting current settings. / 仅解释保留的受支持版本，不读取当前设置。
     * @param record original history / 原历史
     * @return complete original snapshot / 完整原快照
     * @throws SQLException if unsupported or malformed / 不支持或无效时 */
    public DeploymentReleaseSnapshot snapshot(DeploymentReleaseRecord record) throws SQLException {
        return DeploymentReleaseCodec.snapshot(record);
    }

    /** Encodes the existing canonical nonsecret publication template for archive transport. / 为归档转交编码既有规范非秘密发布模板。
     * @param proposal exact original proposal / 精确原提案
     * @return canonical template / 规范模板
     * @throws SQLException if original content is invalid or too large / 原内容无效或过大时 */

    /** Decodes an archived template and checks its complete original release digest. / 解码归档模板并核对完整原发布摘要。
     * @param text canonical template / 规范模板
     * @param revision original release digest / 原发布摘要
     * @return verified complete proposal / 已核实完整提案
     * @throws SQLException if format, configuration or complete digest differs / 格式、配置或完整摘要不符时 */

    /** Lists complete retained histories for one exact application. / 列出一个精确应用的完整保留历史。
     * @param task original task / 原任务
     * @param applicationId application identity / 应用身份
     * @return immutable history / 不可变历史
     * @throws SQLException if stored content is invalid / 存储内容无效时
     * @throws ApiException if access fails / 访问失败时 */
    public List<DeploymentReleaseRecord> list(TaskContext task, String applicationId)
        throws SQLException, ApiException {
        DeploymentSchemaIdentity.requireIdentity(database.identity());
        var result = new ArrayList<DeploymentReleaseRecord>();
        try (var connection = database.openConnection(task);
             var query = connection.prepareStatement(
                 "SELECT * FROM deployment_release WHERE application_id=? ORDER BY created_at DESC,release_id")) {
            query.setString(1, applicationId);
            try (var rows = query.executeQuery()) {
                while (rows.next())
                    result.add(row(rows));
            }
        }
        return List.copyOf(result);
    }

    /** Commits a verified retained switch with an exact active-history comparison. / 精确比较活动历史后提交已核实保留发布切换。
     * @param task original task / 原任务
     * @param expected original active digest / 原活动摘要
     * @param selected retained target / 保留目标
     * @param publications exact original component configuration and secrets / 精确原组件配置与秘密
     * @param observedAt actual successful observation / 实际成功观测
     * @throws SQLException if active or immutable content differs / 活动或不可变内容不符时
     * @throws ApiException if access fails / 访问失败时 */

    /** Activates verified content after publication while preserving the first creating execution. / 在发布后激活已验证内容，同时保留首次创建执行。
     * @param connection caller-owned publication transaction / 调用方持有的发布事务
     * @param applicationId exact original application identity / 精确原应用身份
     * @param executionId original execution ledger identity / 原执行账本身份
     * @param server exact original target server / 精确原目标服务器
     * @param snapshot complete verified publication / 完整已验证发布
     * @param observedAt actual verification time / 实际核验时间
     * @return stable original release identity / 稳定原发布身份
     * @throws SQLException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static DeploymentReleaseIdentity activate(Connection connection, String applicationId, UUID executionId, gold.debug.windowstolinux.appapi.server.model.ServerId server,
                                                     DeploymentReleaseSnapshot snapshot, Instant observedAt) throws SQLException {
        if (connection.getAutoCommit())
            throw new SQLException("Release history requires the publication transaction");
        if (!applicationId.equals(snapshot.graph().applicationId())
            || snapshot.graph().components().stream().anyMatch(c -> !c.unit().server().equals(server)))
            throw new SQLException("Release history differs from original execution");
        String runtime = DeploymentReleaseCodec.runtime(snapshot);
        String health = DeploymentReleaseCodec.health(snapshot);
        var proposed = new DeploymentReleaseRecord(applicationId,
            new DeploymentReleaseIdentity(UUID.randomUUID(), DeploymentReleaseCodec.digest(runtime, health)),
            executionId, DeploymentReleaseState.ACTIVE, runtime, health,
            Instant.ofEpochMilli(observedAt.toEpochMilli()));
        DeploymentReleaseCodec.snapshot(proposed);
        lockApplication(connection, applicationId, server.id());
        DeploymentReleaseRecord original = null;
        int active = 0;
        try (var query = connection.prepareStatement("SELECT * FROM deployment_release WHERE application_id=?")) {
            query.setString(1, applicationId);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var retained = row(rows);
                    boolean target = retained.identity().sha256().equals(proposed.identity().sha256());
                    if (retained.state() == DeploymentReleaseState.ACTIVE)
                        active++;
                    if (target || retained.state() == DeploymentReleaseState.ACTIVE) {
                        DeploymentReleaseCodec.snapshot(retained);
                        if (retained.state() != DeploymentReleaseState.ACTIVE
                            && retained.state() != DeploymentReleaseState.RETIRED)
                            throw new SQLException("Unresolved release requires its original reconciliation");
                        if (target)
                            original = retained;
                    }
                }
            }
        }
        if (active > 1)
            throw new SQLException("Application has conflicting active histories");
        if (original != null && (!original.runtimeJson().equals(runtime) || !original.healthJson().equals(health)))
            throw new SQLException("Release identity is immutable");
        var chosen = original == null ? proposed : original;
        DeploymentReleaseBindings.require(connection, snapshot.bindings());
        try (var retire = connection.prepareStatement(
            "UPDATE deployment_release SET state='RETIRED' WHERE application_id=? AND state='ACTIVE' AND release_id<>?")) {
            retire.setString(1, applicationId);
            retire.setObject(2, chosen.identity().id());
            retire.executeUpdate();
        }
        if (original == null)
            insert(connection, proposed);
        else if (original.state() != DeploymentReleaseState.ACTIVE) {
            try (var promote = connection.prepareStatement(
                "UPDATE deployment_release SET state='ACTIVE' WHERE application_id=? AND release_id=? AND state='RETIRED'")) {
                promote.setString(1, applicationId);
                promote.setObject(2, chosen.identity().id());
                if (promote.executeUpdate() != 1)
                    throw new SQLException("Release activation was not confirmed");
            }
        }
        return chosen.identity();
    }

    /** Serializes history changes on the already published application's exact row. / 在已发布应用的精确行上串行化历史变更。
     * @param connection original transaction / 原事务
     * @param applicationId exact original application identity / 精确原应用身份
     * @param serverId original target server identity / 原目标服务器身份
     * @throws SQLException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static void lockApplication(Connection connection, String applicationId, String serverId)
        throws SQLException {
        try (var query = connection.prepareStatement(
            "SELECT server_profile_id,graph_ready FROM managed_application WHERE application_id=? FOR UPDATE")) {
            query.setString(1, applicationId);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !rows.getBoolean("graph_ready")
                    || !rows.getString("server_profile_id").equals(serverId))
                    throw new SQLException("Complete original application is absent");
            }
        }
    }

    /** Inserts original history once; later activations never replace these fields. / 仅插入一次原历史，后续激活不替换这些字段。
     * @param connection original transaction / 原事务
     * @param record original immutable history / 原不可变历史
     * @throws SQLException if insertion fails / 插入失败时 */
    private static void insert(Connection connection, DeploymentReleaseRecord record) throws SQLException {
        var execution = DeploymentExecutionStore.read(connection, record.executionId(), false)
            .orElseThrow(() -> new SQLException("Creating execution is absent before formal publication"));
        String origin = DeploymentReleaseOrigin.encode(execution.identity(), record);
        try (var statement = connection.prepareStatement(
            "INSERT INTO deployment_release(application_id,release_id,release_sha256,execution_id,state,runtime_payload,health_payload,created_at,origin_payload) VALUES(?,?,?,?,'ACTIVE',?,?,?,?)")) {
            statement.setString(1, record.applicationId());
            statement.setObject(2, record.identity().id());
            statement.setString(3, record.identity().sha256());
            statement.setObject(4, record.executionId());
            statement.setBytes(5, record.runtimeJson().getBytes(StandardCharsets.UTF_8));
            statement.setBytes(6, record.healthJson().getBytes(StandardCharsets.UTF_8));
            statement.setLong(7, record.createdAt().toEpochMilli());
            statement.setBytes(8, origin.getBytes(StandardCharsets.UTF_8));
            statement.executeUpdate();
        }
    }

    /** Reads complete immutable identity from the one ACTIVE whole-application manifest. / 从唯一活动整应用清单读取完整不可变身份。
     * @param connection original read or locked publication connection / 原读取或已锁定发布连接
     * @param unitRow original unit projection / 原单元投影
     * @return complete original unit, including all five roots / 包含全部五根的完整原单元
     * @throws SQLException if any original relationship or binding is absent or inconsistent / 原关系或绑定缺失或不一致时 */
    public static DeploymentUnitIdentity activeUnit(Connection connection, ResultSet unitRow) throws SQLException {
        String unitId = unitRow.getString("deployment_unit_id");
        try (var query = connection.prepareStatement(
            "SELECT c.*,a.server_profile_id,a.graph_ready,a.health_component_id,a.health_check_payload FROM managed_application_component c JOIN managed_application a ON a.application_id=c.application_id WHERE c.deployment_unit_id=?")) {
            query.setString(1, unitId);
            try (var relation = query.executeQuery()) {
                if (!relation.next() || !relation.getBoolean("graph_ready"))
                    throw new SQLException("Deployment unit lacks its complete original application");
                String applicationId = relation.getString("application_id");
                String componentId = relation.getString("component_id");
                DeploymentReleaseRecord active = null;
                try (var releases = connection.prepareStatement(
                    "SELECT * FROM deployment_release WHERE application_id=? AND state='ACTIVE'")) {
                    releases.setString(1, applicationId);
                    try (var rows = releases.executeQuery()) {
                        if (!rows.next())
                            throw new SQLException("Deployment unit lacks its original ACTIVE release");
                        active = row(rows);
                        if (rows.next())
                            throw new SQLException("Application has conflicting ACTIVE releases");
                    }
                }
                var snapshot = DeploymentReleaseCodec.snapshot(active);
                var component = snapshot.graph().components().stream()
                    .filter(c -> c.componentId().equals(componentId) && c.unit().id().equals(unitId)).findFirst()
                    .orElseThrow(() -> new SQLException("ACTIVE release differs from its component relation"));
                var unit = component.unit();
                var binding = snapshot.bindings().stream().filter(b -> b.unitId().equals(unitId)).findFirst()
                    .orElseThrow(() -> new SQLException("ACTIVE release lacks its component binding"));
                if (!unit.server().id().equals(relation.getString("server_profile_id"))
                    || !unit.server().id().equals(unitRow.getString("server_profile_id"))
                    || !unit.systemdUnit().equals(unitRow.getString("systemd_unit_name"))
                    || !unit.releaseRoot().equals(unitRow.getString("release_root_path"))
                    || !unit.ownershipManifestSha256().equals(unitRow.getString("ownership_manifest_sha256"))
                    || !binding.releaseSha256().equals(unitRow.getString("current_release_sha256"))
                    || !component.runtime().equals(new DeploymentUnitRuntimeCodec()
                    .read(DeploymentPayloadReader.read(unitRow, "runtime_payload")))
                    || !snapshot.graph().healthComponentId().equals(relation.getString("health_component_id"))
                    || !Arrays.equals(new DeploymentHealthCodec().write(snapshot.graph().healthCheck()),
                    DeploymentPayloadReader.read(relation, "health_check_payload"))
                    || !Arrays.equals(new DeploymentRuntimeCodec().write(component.reviewedRuntime()),
                    DeploymentPayloadReader.read(relation, "reviewed_runtime_payload"))
                    || !Arrays.equals(new DeploymentStoragePathCodec().write(component.dataPaths()),
                    DeploymentPayloadReader.read(relation, "reviewed_data_paths_payload"))
                    || !Arrays.equals(new DeploymentResourceCodec().write(component.resources()),
                    DeploymentPayloadReader.read(relation, "reviewed_resource_bindings_payload")))
                    throw new SQLException("Persisted component differs from its original ACTIVE release");
                var dependencies = new ArrayList<String>();
                try (var queryDependencies = connection.prepareStatement(
                    "SELECT dependency_component_id FROM managed_application_dependency WHERE application_id=? AND component_id=? ORDER BY dependency_component_id")) {
                    queryDependencies.setString(1, applicationId);
                    queryDependencies.setString(2, componentId);
                    try (var rows = queryDependencies.executeQuery()) {
                        while (rows.next()) {
                            if (dependencies.size() == 64)
                                throw new SQLException("Stored dependencies exceed their bound");
                            dependencies.add(rows.getString(1));
                        }
                    }
                }
                if (!dependencies.equals(component.dependencies()) || relation.next())
                    throw new SQLException("Persisted component has a conflicting original relation");
                DeploymentReleaseBindings.require(connection, List.of(binding));
                return unit;
            }
        } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Complete persisted deployment identity is invalid", invalid);
        }
    }

    /** Reads one original history within an existing consistent transaction. / 在已有一致事务中读取一个原历史。
     * @param connection original connection / 原连接
     * @param applicationId original application / 原应用
     * @param releaseId original history UUID / 原历史UUID
     * @return original record or absence / 原记录或不存在
     * @throws SQLException if evidence is malformed / 证据无效时 */
    public static Optional<DeploymentReleaseRecord> read(Connection connection, String applicationId, UUID releaseId)
        throws SQLException {
        try (var query = connection
            .prepareStatement("SELECT * FROM deployment_release WHERE application_id=? AND release_id=?")) {
            query.setString(1, applicationId);
            query.setObject(2, releaseId);
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(row(rows)) : Optional.empty();
            }
        }
    }

    /** Preserves raw documents and checks known formats without automatic repair. / 保留原文档并检查已知格式，不自动修复。
     * @param rows original row / 原行
     * @return bounded original record / 有界原记录
     * @throws SQLException if columns or known content are invalid / 列或已知内容无效时 */
    public static DeploymentReleaseRecord row(ResultSet rows) throws SQLException {
        try {
            var record = new DeploymentReleaseRecord(rows.getString("application_id"),
                new DeploymentReleaseIdentity(rows.getObject("release_id", UUID.class),
                    rows.getString("release_sha256")),
                rows.getObject("execution_id", UUID.class), DeploymentReleaseState.valueOf(rows.getString("state")),
                DeploymentJsonDocument.text(
                    DeploymentPayloadReader.read(rows, "runtime_payload", DeploymentReleaseCodec.LIMIT),
                    DeploymentReleaseCodec.LIMIT),
                DeploymentJsonDocument.text(DeploymentPayloadReader.read(rows, "health_payload")),
                Instant.ofEpochMilli(rows.getLong("created_at")));
            if (DeploymentReleaseCodec.supported(record))
                DeploymentReleaseCodec.snapshot(record);
            return record;
        } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Stored release history is invalid", invalid);
        }
    }
}
