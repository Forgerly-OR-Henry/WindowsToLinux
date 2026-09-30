package gold.debug.windowstolinux.mod.deployment.db;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Persists immutable secret metadata and exact release associations without reading plaintext. / 持久化不可变秘密元数据和精确发布关联，不读取明文。 */
public final class DeploymentSecretRepository {
    /** Original task-bound database capability. / 原任务绑定数据库能力。 */
    private final ModDatabase database;

    /** Binds the original owned generation. / 绑定原所属代次。
     * @param database task-bound author database / 任务绑定作者数据库 */
    public DeploymentSecretRepository(ModDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Stores immutable metadata after the caller has reserved the actual platform credential. / 调用方预留实际平台凭据后存储不可变元数据。
     * @param task original author task / 原作者任务
     * @param revision exact original metadata / 精确原元数据
     * @throws SQLException if metadata differs or storage fails / 元数据不一致或存储失败时
     * @throws ApiException if task access or transaction acknowledgement is unavailable / 任务访问或事务确认不可用时 */
    public void saveRevision(TaskContext task, DeploymentSecretRevision revision) throws SQLException, ApiException {
        Objects.requireNonNull(revision);
        requireRevision(revision.reference());
        if (revision.purpose().isEmpty())
            throw new IllegalArgumentException("New secret revisions require their original purpose");
        var connection = connection(task);
        DeploymentStorageTransaction.execute(connection, () -> saveRevision(connection, revision));
    }

    /** Reads only the exact requested metadata revision. / 仅读取精确请求的元数据修订。
     * @param task original author task / 原作者任务
     * @param reference exact secret revision / 精确秘密修订
     * @return immutable metadata or absent / 不可变元数据或不存在
     * @throws SQLException if persisted metadata is invalid / 持久元数据无效时
     * @throws ApiException if task access is unavailable / 任务访问不可用时 */
    public Optional<DeploymentSecretRevision> findRevision(TaskContext task, DeploymentSecretReference reference)
            throws SQLException, ApiException {
        requireRevision(reference);
        try (var connection = connection(task)) {
            return readRevision(connection, reference);
        }
    }

    /** Reads the latest immutable revision for one exact business identifier and purpose. / 仅读取一个精确业务标识与用途的最新不可变修订。
     * @param task original author task / 原作者任务
     * @param identifier exact business identifier / 精确业务标识
     * @param purpose exact recorded purpose / 精确已记录用途
     * @return latest exact metadata or absence / 最新精确元数据或不存在
     * @throws SQLException if stored metadata is invalid / 已存元数据无效时
     * @throws ApiException if task access fails / 任务访问失败时 */
    public Optional<DeploymentSecretRevision> latestRevision(TaskContext task, String identifier, String purpose)
            throws SQLException, ApiException {
        new DeploymentSecretReference(identifier, 1);
        if (purpose == null || purpose.isBlank() || purpose.length() > 128)
            throw new IllegalArgumentException("Secret purpose is invalid");
        try (var connection = connection(task);
                var statement = connection.prepareStatement(
                        "SELECT secret_revision FROM secret_revision WHERE secret_id=? ORDER BY secret_revision DESC FETCH FIRST 1 ROW ONLY")) {
            statement.setString(1, identifier);
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    return Optional.empty();
                var latest = readRevision(connection, new DeploymentSecretReference(identifier, rows.getLong(1)));
                if (latest.isPresent() && !latest.orElseThrow().purpose().equals(Optional.of(purpose)))
                    throw new SQLException("Latest secret revision has another purpose");
                return latest;
            }
        }
    }

    /** Lists bounded selectable revisions with a recorded purpose, without exposing credential IDs. / 列出有已记录用途的有界可选修订，不暴露凭据 ID。
     * @param task original read-only author task / 原只读作者任务
     * @param limit maximum returned references / 最多返回引用数
     * @return exact immutable business references / 精确不可变业务引用
     * @throws SQLException if storage is unavailable or the list exceeds the bound / 存储不可用或列表超过上限时
     * @throws ApiException if task access is unavailable / 任务访问不可用时 */
    public List<DeploymentSecretSelection> listReferences(TaskContext task, int limit)
            throws SQLException, ApiException {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Secret list limit is invalid");
        try (var connection = connection(task);
                var statement = connection.prepareStatement(
                        "SELECT secret_id,secret_revision,purpose FROM secret_revision WHERE purpose IS NOT NULL AND TRIM(purpose)<>'' ORDER BY secret_id,secret_revision FETCH FIRST ? ROWS ONLY")) {
            statement.setInt(1, limit + 1);
            try (var rows = statement.executeQuery()) {
                var references = new ArrayList<DeploymentSecretSelection>();
                while (rows.next())
                    references.add(new DeploymentSecretSelection(
                            new DeploymentSecretReference(rows.getString(1), rows.getLong(2)), rows.getString(3)));
                if (references.size() > limit)
                    throw new SQLException("Selectable secret revision list exceeds its bound");
                return List.copyOf(references);
            }
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Stored selectable secret metadata is invalid", invalid);
        }
    }

    /** Binds a complete immutable set, including an explicit empty set. / 绑定完整不可变集合，包括显式空集合。
     * @param task original author task / 原作者任务
     * @param unitId exact unit identity / 精确单元身份
     * @param release exact release identity / 精确发布身份
     * @param references complete secret revision set / 完整秘密修订集合
     * @throws SQLException if revisions are absent or bindings conflict / 修订不存在或绑定冲突时
     * @throws ApiException if task access or transaction acknowledgement is unavailable / 任务访问或事务确认不可用时 */
    public void bindRelease(TaskContext task, String unitId, String release, List<DeploymentSecretReference> references)
            throws SQLException, ApiException {
        String unit = DeploymentConfigurationRepository.boundedIdentity(unitId, "unitId");
        String digest = DeploymentConfigurationRepository.boundedIdentity(release, "release");
        var expected = references(references);
        var connection = connection(task);
        DeploymentStorageTransaction.execute(connection, () -> bindRelease(connection, unit, digest, expected));
    }

    /** Distinguishes missing bindings from the successfully bound empty set in one query. / 通过一个查询区分缺失绑定和成功绑定的空集合。
     * @param task original author task / 原作者任务
     * @param unitId exact unit identity / 精确单元身份
     * @param release exact release identity / 精确发布身份
     * @return complete set or absent when not bound / 完整集合，尚未绑定时为空
     * @throws SQLException if stored association facts are invalid / 存储关联事实无效时
     * @throws ApiException if task access is unavailable / 任务访问不可用时 */
    public Optional<List<DeploymentSecretReference>> findRelease(TaskContext task, String unitId, String release)
            throws SQLException, ApiException {
        String unit = DeploymentConfigurationRepository.boundedIdentity(unitId, "unitId");
        String digest = DeploymentConfigurationRepository.boundedIdentity(release, "release");
        try (var connection = connection(task)) {
            return findRelease(connection, unit, digest);
        }
    }

    /** Reports persisted release use without resolving or deleting the platform credential. / 报告持久发布使用情况，不核销或删除平台凭据。
     * @param task original author task / 原作者任务
     * @param reference exact secret revision / 精确秘密修订
     * @return whether any release retains this revision / 是否有发布保留此修订
     * @throws SQLException if storage cannot be read / 无法读取存储时
     * @throws ApiException if task access is unavailable / 任务访问不可用时 */
    public boolean isReferenced(TaskContext task, DeploymentSecretReference reference)
            throws SQLException, ApiException {
        requireRevision(reference);
        try (var connection = connection(task);
                var statement = connection.prepareStatement(
                        "SELECT 1 FROM deployment_unit_release_secret_reference WHERE secret_id=? AND secret_revision=? FETCH FIRST 1 ROW ONLY")) {
            statement.setString(1, reference.identifier());
            statement.setLong(2, reference.revision());
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** Rejects foreign generations before opening a managed connection. / 打开受管连接前拒绝其他所有者代次。
     * @param task original author task / 原作者任务
     * @return owned connection / 所属连接
     * @throws SQLException if JDBC is unavailable / JDBC不可用时
     * @throws ApiException if identity or task access is unavailable / 身份或任务访问不可用时 */
    private Connection connection(TaskContext task) throws SQLException, ApiException {
        DeploymentSchema.requireIdentity(database.identity());
        return database.openConnection(task);
    }

    /** Saves a revision without overwriting an existing immutable identity or timestamp. / 保存修订，不覆盖既有不可变身份或时间。
     * @param connection original transaction / 原事务
     * @param revision original metadata / 原元数据
     * @throws SQLException if metadata cannot be saved exactly / 元数据无法精确保存时 */
    private static void saveRevision(Connection connection, DeploymentSecretRevision revision) throws SQLException {
        var existing = readRevision(connection, revision.reference());
        if (existing.isPresent()) {
            var stored = existing.orElseThrow();
            if (!stored.credentialId().equals(revision.credentialId())
                    || stored.createdAt().toEpochMilli() != revision.createdAt().toEpochMilli()
                    || !stored.purpose().equals(revision.purpose()))
                throw new SQLException("Secret revision metadata is immutable");
            return;
        }
        try (var statement = connection.prepareStatement(
                "INSERT INTO secret_revision(secret_id,secret_revision,credential_id,created_at,purpose) VALUES(?,?,?,?,?)")) {
            statement.setString(1, revision.reference().identifier());
            statement.setLong(2, revision.reference().revision());
            statement.setString(3, revision.credentialId());
            statement.setLong(4, revision.createdAt().toEpochMilli());
            statement.setString(5, revision.purpose().orElseThrow());
            statement.executeUpdate();
        }
    }

    /** Reads validated metadata on the current transaction. / 在当前事务中读取已验证元数据。
     * @param connection original connection / 原连接
     * @param reference exact business revision / 精确业务修订
     * @return original metadata or absent / 原元数据或不存在
     * @throws SQLException if a stored value is invalid / 存储值无效时 */
    static Optional<DeploymentSecretRevision> readRevision(Connection connection, DeploymentSecretReference reference)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT credential_id,created_at,purpose FROM secret_revision WHERE secret_id=? AND secret_revision=?")) {
            statement.setString(1, reference.identifier());
            statement.setLong(2, reference.revision());
            try (var rows = statement.executeQuery()) {
                return rows.next()
                        ? Optional.of(new DeploymentSecretRevision(reference, rows.getString(1),
                                Instant.ofEpochMilli(rows.getLong(2)), Optional.ofNullable(rows.getString(3))))
                        : Optional.empty();
            }
        } catch (IllegalArgumentException failure) {
            throw new SQLException("Stored secret metadata is invalid", failure);
        }
    }

    /** Locks the shared release row and fills only its original secret association. / 锁定共用发布行，仅补入其原秘密关联。
     * @param connection original active transaction / 原活动事务
     * @param unit exact unit identity / 精确单元身份
     * @param release exact release identity / 精确发布身份
     * @param expected complete unique secret set / 完整唯一秘密集合
     * @throws SQLException if metadata or immutable associations conflict / 元数据或不可变关联冲突时 */
    static void bindRelease(Connection connection, String unit, String release, Set<DeploymentSecretReference> expected)
            throws SQLException {
        if (connection.getAutoCommit())
            throw new SQLException("Secret binding requires a business transaction");
        for (var reference : expected) {
            requireRevision(reference);
            if (readRevision(connection, reference).isEmpty())
                throw new SQLException("Release secret revision has not been registered");
        }
        boolean exists;
        try (var statement = connection.prepareStatement(
                "SELECT is_secret_binding_complete FROM deployment_unit_release_binding WHERE deployment_unit_id=? AND release_sha256=? FOR UPDATE")) {
            statement.setString(1, unit);
            statement.setString(2, release);
            try (var row = statement.executeQuery()) {
                exists = row.next();
            }
        }
        var stored = findRelease(connection, unit, release);
        if (stored.isPresent()) {
            if (!Set.copyOf(stored.orElseThrow()).equals(expected))
                throw new SQLException("Release secret references are immutable");
            return;
        }
        if (!exists) {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO deployment_unit_release_binding(deployment_unit_id,release_sha256) VALUES(?,?)")) {
                statement.setString(1, unit);
                statement.setString(2, release);
                statement.executeUpdate();
            }
        }
        try (var statement = connection.prepareStatement(
                "INSERT INTO deployment_unit_release_secret_reference(deployment_unit_id,release_sha256,secret_id,secret_revision) VALUES(?,?,?,?)")) {
            for (var reference : expected.stream().sorted(Comparator.comparing(DeploymentSecretReference::identifier)
                    .thenComparingLong(DeploymentSecretReference::revision)).toList()) {
                statement.setString(1, unit);
                statement.setString(2, release);
                statement.setString(3, reference.identifier());
                statement.setLong(4, reference.revision());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (var statement = connection.prepareStatement(
                "UPDATE deployment_unit_release_binding SET is_secret_binding_complete=1 WHERE deployment_unit_id=? AND release_sha256=? AND is_secret_binding_complete=0")) {
            statement.setString(1, unit);
            statement.setString(2, release);
            if (statement.executeUpdate() != 1)
                throw new SQLException("Secret binding completion was not confirmed");
        }
    }

    /** Reads the complete set and rejects partially written associations. / 读取完整集合并拒绝部分写入的关联。
     * @param connection original read connection / 原读取连接
     * @param unit exact unit identity / 精确单元身份
     * @param release exact release identity / 精确发布身份
     * @return complete set or unbound result / 完整集合或未绑定结果
     * @throws SQLException if persisted associations are invalid / 持久关联无效时 */
    static Optional<List<DeploymentSecretReference>> findRelease(Connection connection, String unit, String release)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT b.is_secret_binding_complete,r.secret_id,r.secret_revision FROM deployment_unit_release_binding b LEFT JOIN deployment_unit_release_secret_reference r ON r.deployment_unit_id=b.deployment_unit_id AND r.release_sha256=b.release_sha256 WHERE b.deployment_unit_id=? AND b.release_sha256=? ORDER BY r.secret_id,r.secret_revision")) {
            statement.setString(1, unit);
            statement.setString(2, release);
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    return Optional.empty();
                boolean complete = rows.getInt(1) == 1;
                var references = new ArrayList<DeploymentSecretReference>();
                do {
                    if (rows.getString(2) != null) {
                        if (!complete)
                            throw new SQLException("Incomplete secret binding contains references");
                        references.add(new DeploymentSecretReference(rows.getString(2), rows.getLong(3)));
                    }
                } while (rows.next());
                return complete ? Optional.of(List.copyOf(references)) : Optional.empty();
            }
        } catch (IllegalArgumentException failure) {
            throw new SQLException("Stored release secret references are invalid", failure);
        }
    }

    /** Copies and validates the exact caller set before JDBC. / 在JDBC前复制并校验精确调用集合。
     * @param values original secret revisions / 原秘密修订
     * @return unique immutable set / 唯一不可变集合 */
    private static Set<DeploymentSecretReference> references(List<DeploymentSecretReference> values) {
        var expected = Set.copyOf(values);
        if (expected.size() != values.size())
            throw new IllegalArgumentException("Release secret references must be unique");
        expected.forEach(DeploymentSecretRepository::requireRevision);
        return expected;
    }

    /** Enforces the documented H2 INTEGER storage range without truncation. / 执行文档H2 INTEGER存储范围，不截断。
     * @param reference exact requested revision / 精确请求修订 */
    private static void requireRevision(DeploymentSecretReference reference) {
        Objects.requireNonNull(reference);
        if (reference.revision() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Secret revision exceeds its stored integer range");
    }

    /** Validates owned secret metadata and release-set completion without resolving platform references. / 校验所属秘密元数据和发布集合完整性，不核销平台引用。
     * @param connection original SELECT-only connection / 原仅SELECT连接
     * @throws SQLException if stored metadata or associations are invalid / 存储元数据或关联无效时 */
    static void validate(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT secret_id,secret_revision,credential_id,created_at,purpose FROM secret_revision")) {
            while (rows.next())
                new DeploymentSecretRevision(new DeploymentSecretReference(rows.getString(1), rows.getLong(2)),
                        rows.getString(3), Instant.ofEpochMilli(rows.getLong(4)),
                        Optional.ofNullable(rows.getString(5)));
        } catch (IllegalArgumentException failure) {
            throw new SQLException("Stored secret revisions are invalid", failure);
        }
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT deployment_unit_id,release_sha256 FROM deployment_unit_release_binding")) {
            while (rows.next())
                findRelease(connection, rows.getString(1), rows.getString(2));
        }
    }
}
