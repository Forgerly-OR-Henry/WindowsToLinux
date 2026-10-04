package gold.debug.windowstolinux.mod.deployment.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseRecord.DeploymentReleaseState;

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
        DeploymentSchema.requireIdentity(database.identity());
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
    public String archiveTemplate(DeploymentPublishProposal proposal) throws SQLException {
        return DeploymentPublishTemplateCodec.encodeTemplate(proposal);
    }

    /** Decodes an archived template and checks its complete original release digest. / 解码归档模板并核对完整原发布摘要。
     * @param text canonical template / 规范模板
     * @param revision original release digest / 原发布摘要
     * @return verified complete proposal / 已核实完整提案
     * @throws SQLException if format, configuration or complete digest differs / 格式、配置或完整摘要不符时 */
    public DeploymentPublishProposal archivedTemplate(String text, String revision) throws SQLException {
        var proposal = DeploymentPublishTemplateCodec.decodeTemplate(text,
            UUID.nameUUIDFromBytes(revision.getBytes(StandardCharsets.UTF_8)));
        var snapshot = DeploymentReleaseSnapshot.capture(proposal.graph(), proposal.units());
        if (!DeploymentReleaseCodec
            .digest(DeploymentReleaseCodec.runtime(snapshot), DeploymentReleaseCodec.health(snapshot))
            .equals(revision))
            throw new SQLException("Archived template differs from original release digest");
        return proposal;
    }

    /** Lists complete retained histories for one exact application. / 列出一个精确应用的完整保留历史。
     * @param task original task / 原任务
     * @param applicationId application identity / 应用身份
     * @return immutable history / 不可变历史
     * @throws SQLException if stored content is invalid / 存储内容无效时
     * @throws ApiException if access fails / 访问失败时 */
    public List<DeploymentReleaseRecord> list(TaskContext task, String applicationId)
        throws SQLException, ApiException {
        DeploymentSchema.requireIdentity(database.identity());
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
    public void activateRetained(TaskContext task, String expected, DeploymentReleaseRecord selected,
                                 List<DeploymentUnitCommit> publications, Instant observedAt) throws SQLException, ApiException {
        DeploymentSchema.requireIdentity(database.identity());
        var target = snapshot(selected);
        if (!DeploymentReleaseSnapshot.capture(target.graph(), publications).equals(target))
            throw new SQLException("Retained publication differs from original content");
        var connection = database.openConnection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            var execution = new DeploymentExecutionIdentity(task.id(), task.id(), Optional.empty(),
                selected.applicationId(), target.graph().components().getFirst().unit().server(),
                DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, expected);
            lockApplication(connection, execution);
            var current = new ArrayList<DeploymentReleaseRecord>();
            try (var query = connection
                .prepareStatement("SELECT * FROM deployment_release WHERE application_id=? AND state='ACTIVE'")) {
                query.setString(1, selected.applicationId());
                try (var rows = query.executeQuery()) {
                    while (rows.next())
                        current.add(row(rows));
                }
            }
            if (current.size() != 1)
                throw new SQLException("Exactly one active release is required");
            var active = current.getFirst();
            if (active.identity().equals(selected.identity())) {
                if (!snapshot(active).equals(target))
                    throw new SQLException("Committed retained identity differs");
                DeploymentReleaseChecker.bindings(connection, target);
                return;
            }
            if (!active.identity().sha256().equals(expected))
                throw new SQLException("Active release changed");
            var retained = read(connection, selected.applicationId(), selected.identity().id()).orElseThrow();
            if (retained.state() != DeploymentReleaseState.RETIRED || !snapshot(retained).equals(target))
                throw new SQLException("Selected retained release changed");
            DeploymentReleaseChecker.bindings(connection, target);
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

    /** Activates verified content after publication while preserving the first creating execution. / 在发布后激活已验证内容，同时保留首次创建执行。
     * @param connection caller-owned publication transaction / 调用方持有的发布事务
     * @param execution original committing execution / 原提交执行
     * @param snapshot complete verified publication / 完整已验证发布
     * @param observedAt actual verification time / 实际核验时间
     * @return stable original release identity / 稳定原发布身份
     * @throws SQLException if facts conflict or history cannot be changed / 事实冲突或历史无法变更时 */
    static DeploymentReleaseIdentity activate(Connection connection, DeploymentExecutionIdentity execution,
                                              DeploymentReleaseSnapshot snapshot, Instant observedAt) throws SQLException {
        if (connection.getAutoCommit())
            throw new SQLException("Release history requires the publication transaction");
        if (!execution.applicationId().equals(snapshot.graph().applicationId())
            || snapshot.graph().components().stream().anyMatch(c -> !c.unit().server().equals(execution.server())))
            throw new SQLException("Release history differs from original execution");
        String runtime = DeploymentReleaseCodec.runtime(snapshot);
        String health = DeploymentReleaseCodec.health(snapshot);
        var proposed = new DeploymentReleaseRecord(execution.applicationId(),
            new DeploymentReleaseIdentity(UUID.randomUUID(), DeploymentReleaseCodec.digest(runtime, health)),
            execution.executionId(), DeploymentReleaseState.ACTIVE, runtime, health,
            Instant.ofEpochMilli(observedAt.toEpochMilli()));
        DeploymentReleaseCodec.snapshot(proposed);
        lockApplication(connection, execution);
        DeploymentReleaseRecord original = null;
        int active = 0;
        try (var query = connection.prepareStatement("SELECT * FROM deployment_release WHERE application_id=?")) {
            query.setString(1, execution.applicationId());
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
        DeploymentReleaseChecker.bindings(connection, snapshot);
        try (var retire = connection.prepareStatement(
            "UPDATE deployment_release SET state='RETIRED' WHERE application_id=? AND state='ACTIVE' AND release_id<>?")) {
            retire.setString(1, execution.applicationId());
            retire.setObject(2, chosen.identity().id());
            retire.executeUpdate();
        }
        if (original == null)
            insert(connection, proposed);
        else if (original.state() != DeploymentReleaseState.ACTIVE) {
            try (var promote = connection.prepareStatement(
                "UPDATE deployment_release SET state='ACTIVE' WHERE application_id=? AND release_id=? AND state='RETIRED'")) {
                promote.setString(1, execution.applicationId());
                promote.setObject(2, chosen.identity().id());
                if (promote.executeUpdate() != 1)
                    throw new SQLException("Release activation was not confirmed");
            }
        }
        return chosen.identity();
    }

    /** Serializes history changes on the already published application's exact row. / 在已发布应用的精确行上串行化历史变更。
     * @param connection original transaction / 原事务
     * @param execution original identity / 原身份
     * @throws SQLException if the published application differs / 已发布应用不符时 */
    private static void lockApplication(Connection connection, DeploymentExecutionIdentity execution)
        throws SQLException {
        try (var query = connection.prepareStatement(
            "SELECT server_profile_id,graph_ready FROM managed_application WHERE application_id=? FOR UPDATE")) {
            query.setString(1, execution.applicationId());
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !rows.getBoolean("graph_ready")
                    || !rows.getString("server_profile_id").equals(execution.server().id()))
                    throw new SQLException("Complete original application is absent");
            }
        }
    }

    /** Inserts original history once; later activations never replace these fields. / 仅插入一次原历史，后续激活不替换这些字段。
     * @param connection original transaction / 原事务
     * @param record original immutable history / 原不可变历史
     * @throws SQLException if insertion fails / 插入失败时 */
    private static void insert(Connection connection, DeploymentReleaseRecord record) throws SQLException {
        try (var statement = connection.prepareStatement(
            "INSERT INTO deployment_release(application_id,release_id,release_sha256,execution_id,state,runtime_payload,health_payload,created_at) VALUES(?,?,?,?,'ACTIVE',?,?,?)")) {
            statement.setString(1, record.applicationId());
            statement.setObject(2, record.identity().id());
            statement.setString(3, record.identity().sha256());
            statement.setObject(4, record.executionId());
            statement.setBytes(5, record.runtimeJson().getBytes(StandardCharsets.UTF_8));
            statement.setBytes(6, record.healthJson().getBytes(StandardCharsets.UTF_8));
            statement.setLong(7, record.createdAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    /** Reads one original history within an existing consistent transaction. / 在已有一致事务中读取一个原历史。
     * @param connection original connection / 原连接
     * @param applicationId original application / 原应用
     * @param releaseId original history UUID / 原历史UUID
     * @return original record or absence / 原记录或不存在
     * @throws SQLException if evidence is malformed / 证据无效时 */
    static Optional<DeploymentReleaseRecord> read(Connection connection, String applicationId, UUID releaseId)
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
    static DeploymentReleaseRecord row(ResultSet rows) throws SQLException {
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
