package gold.debug.windowstolinux.shared.linux.protocol.database;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Typed remote database capability without transport-specific or backup-format types.
 *
 * <p>不暴露传输实现或备份格式类型的远程数据库能力契约。
 */
public interface RemoteDatabasePort {
    /** Collects database and tool compatibility without mutation. / 只读采集数据库与工具兼容性。 */
    CompatibilityEvidence inspect(BackupRequest request) throws LinuxOperationException;

    /** Exports one database using the exact approved consistency mode. / 以精确批准的一致性方式导出数据库。 */
    BackupArtifact export(BackupRequest request, DatabaseConsistencyMode mode) throws LinuxOperationException;

    /** Restores and reads an isolated candidate without activation. / 恢复并读取隔离候选且不激活。 */
    RestoreEvidence restoreCandidate(RestoreRequest request) throws LinuxOperationException;

    /** Activates one verified candidate and retains the exact previous database. / 激活已验证候选并保留精确旧数据库。 */
    CommitEvidence commitCandidate(RestoreRequest request) throws LinuxOperationException;

    /** Recovers a committed or partially committed candidate and verifies the previous state. / 恢复已提交或部分提交候选并验证旧状态。 */
    RecoveryEvidence recoverCandidate(RestoreRequest request) throws LinuxOperationException;

    /** Removes one isolated database candidate that was never committed. / 移除一个从未提交的隔离数据库候选。 */
    void discardCandidate(RestoreRequest request) throws LinuxOperationException;

    /** Streams an exact remote artifact to a caller-owned destination. / 将精确远程制品流式传到调用方目标。 */
    void copyArtifact(BackupArtifact artifact, OutputStream destination) throws LinuxOperationException;

    /** Streams an exact artifact into controlled remote staging. / 将精确制品流式传入受控远程暂存区。 */
    void stageArtifact(BackupArtifact artifact, InputStream source) throws LinuxOperationException;

    /** Removes one explicitly identified remote artifact. / 移除一个明确标识的远程制品。 */
    void discardArtifact(BackupArtifact artifact) throws LinuxOperationException;

    /** Supported database families. / 支持的数据库族。 */
    enum DatabaseType {
        SQLITE,
        POSTGRESQL,
        MYSQL,
        MARIADB
    }

    /** Consistency method approved by the backup policy. / 备份策略批准的一致性方式。 */
    enum DatabaseConsistencyMode {
        SQLITE_ONLINE_BACKUP,
        SQLITE_WRITES_STOPPED,
        POSTGRESQL_LOGICAL_DUMP,
        MYSQL_TRANSACTION_SNAPSHOT,
        MYSQL_WRITES_STOPPED
    }

    /** Non-secret connection identity. / 不含秘密的连接身份。 */
    sealed interface ConnectionProfile permits ConnectionProfile.Sqlite, ConnectionProfile.Server {
        /** Returns the exact database family. / 返回精确数据库族。 */
        DatabaseType type();

        /** Managed relative SQLite path. / 受管 SQLite 相对路径。 */
        record Sqlite(String bindingId, gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation location, String fileName) implements ConnectionProfile {
            /** Validates a controlled path. / 校验受控路径。 */
            public Sqlite {
                if (!Objects.requireNonNull(bindingId).matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid SQLite storage binding");
            Objects.requireNonNull(location);
            if (location.type() == gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.UNRESOLVED) throw new IllegalArgumentException("SQLite location must be reviewed");
            if (!Objects.requireNonNull(fileName).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IllegalArgumentException("invalid SQLite file name");
            }

            @Override public DatabaseType type() { return DatabaseType.SQLITE; }
        }

        /** Server endpoint with an opaque secret revision reference. / 带不透明秘密修订引用的服务器端点。 */
        record Server(
                DatabaseType type,
                String host,
                int port,
                String database,
                String username,
                String passwordReference,
                long passwordRevision,
                boolean tlsRequired
        ) implements ConnectionProfile {
            /** Validates a supported server connection. / 校验受支持服务器连接。 */
            public Server {
                type = Objects.requireNonNull(type, "type");
                if (type == DatabaseType.SQLITE) throw new IllegalArgumentException("server database type is invalid");
                host = validatedHost(host);
                if (port < 1 || port > 65535) throw new IllegalArgumentException("database port is invalid");
                database = name(database, "database");
                username = name(username, "username");
                passwordReference = secretIdentifier(passwordReference);
                if (passwordRevision < 1) throw new IllegalArgumentException("passwordRevision is invalid");
            }
        }
    }

    /** Explicit application write-state facts for one export. / 单次导出的显式应用写入状态事实。 */
    record BackupRequest(
            String applicationId,
            ConnectionProfile connection,
            boolean applicationWritesStopped,
            boolean exclusiveWriterConfirmed
    ) {
        /** Validates identity and write-state facts. / 校验身份与写入状态事实。 */
        public BackupRequest {
            applicationId = identifier(applicationId, "applicationId");
            connection = Objects.requireNonNull(connection, "connection");
            if (exclusiveWriterConfirmed && !applicationWritesStopped) {
                throw new IllegalArgumentException("exclusive writer confirmation requires stopped writes");
            }
        }
    }

    /** Compatibility evidence collected by the remote helper. / 远程 helper 采集的兼容性证据。 */
    record CompatibilityEvidence(
            DatabaseType type,
            String engineVersion,
            String toolVersion,
            boolean toolAvailable,
            boolean engineVersionCompatible,
            boolean onlineBackupAvailable,
            boolean allTablesTransactional,
            List<String> evidence
    ) {
        /** Requires bounded explicit evidence. / 要求有界显式证据。 */
        public CompatibilityEvidence {
            type = Objects.requireNonNull(type, "type");
            engineVersion = text(engineVersion, "engineVersion", 128);
            toolVersion = text(toolVersion, "toolVersion", 128);
            evidence = validatedEvidence(evidence);
        }
    }

    /** Opaque remote export with exact content and database evidence. / 带精确内容与数据库证据的不透明远程导出。 */
    record BackupArtifact(
            String artifactId,
            long byteCount,
            String sha256,
            DatabaseType type,
            String reference,
            String engineVersion,
            String toolVersion,
            DatabaseConsistencyMode consistencyMode,
            List<String> limitations,
            List<String> evidence
    ) {
        /** Validates artifact identity and evidence. / 校验制品身份与证据。 */
        public BackupArtifact {
            artifactId = identifier(artifactId, "artifactId");
            if (byteCount < 1) throw new IllegalArgumentException("database artifact must not be empty");
            sha256 = Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("artifact hash is invalid");
            type = Objects.requireNonNull(type, "type");
            reference = text(reference, "reference", 256);
            engineVersion = text(engineVersion, "engineVersion", 128);
            toolVersion = text(toolVersion, "toolVersion", 128);
            consistencyMode = Objects.requireNonNull(consistencyMode, "consistencyMode");
            limitations = texts(limitations, "limitations", true);
            evidence = validatedEvidence(evidence);
        }
    }

    /** Isolated restore request bound to one verified artifact. / 绑定已验证制品的隔离恢复请求。 */
    record RestoreRequest(
            String applicationId,
            String credentialApplicationId,
            String candidateId,
            ConnectionProfile target,
            BackupArtifact artifact
    ) {
        /** Validates managed candidate identity and database type. / 校验受管候选身份与数据库类型。 */
        public RestoreRequest {
            applicationId = identifier(applicationId, "applicationId");
            credentialApplicationId = identifier(credentialApplicationId, "credentialApplicationId");
            candidateId = candidate(applicationId, candidateId);
            target = Objects.requireNonNull(target, "target");
            artifact = Objects.requireNonNull(artifact, "artifact");
            if (target.type() != artifact.type()) throw new IllegalArgumentException("restore database type differs");
        }

        /** Creates a single-component request with one shared application namespace. / 创建使用单一应用命名空间的单组件请求。 */
        public RestoreRequest(String applicationId, String candidateId,
                              ConnectionProfile target, BackupArtifact artifact) {
            this(applicationId, applicationId, candidateId, target, artifact);
        }
    }

    /** Verified but unactivated remote database candidate. / 已验证但未激活的远程数据库候选。 */
    record RestoreEvidence(
            String candidateId,
            String connectionToken,
            boolean integrityVerified,
            boolean schemaReadable,
            List<String> evidence
    ) {
        /** Requires complete candidate verification. / 要求完整候选验证。 */
        public RestoreEvidence {
            candidateId = identifier(candidateId, "candidateId");
            connectionToken = identifier(connectionToken, "connectionToken");
            if (!integrityVerified || !schemaReadable) {
                throw new IllegalArgumentException("unverified database candidate cannot be returned");
            }
            evidence = validatedEvidence(evidence);
        }
    }

    /** Successful database activation evidence. / 数据库成功激活证据。 */
    record CommitEvidence(String candidateId, boolean committed, boolean previousDatabaseRetained,
                          List<String> evidence) {
        /** Requires complete activation evidence. / 要求完整激活证据。 */
        public CommitEvidence {
            candidateId = identifier(candidateId, "candidateId");
            if (!committed || !previousDatabaseRetained) {
                throw new IllegalArgumentException("database commit evidence is incomplete");
            }
            evidence = validatedEvidence(evidence);
        }
    }

    /** Verified rollback or uncommitted cleanup evidence. / 已验证回滚或未提交清理证据。 */
    record RecoveryEvidence(String candidateId, boolean recovered, boolean previousDatabaseVerified,
                            boolean candidateRemoved, List<String> evidence) {
        /** Requires a complete safe recovery result. / 要求完整安全恢复结果。 */
        public RecoveryEvidence {
            candidateId = identifier(candidateId, "candidateId");
            if (!recovered || !previousDatabaseVerified || !candidateRemoved) {
                throw new IllegalArgumentException("database recovery evidence is incomplete");
            }
            evidence = validatedEvidence(evidence);
        }
    }

    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static String candidate(String applicationId, String value) {
        value = Objects.requireNonNull(value, "candidateId").trim();
        if (!value.matches(Pattern.quote(applicationId) + "-[0-9a-f]{16}")) {
            throw new IllegalArgumentException("candidateId is not bound to the application");
        }
        return value;
    }

    private static String secretIdentifier(String value) {
        value = Objects.requireNonNull(value, "passwordReference").trim();
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("passwordReference is invalid");
        }
        return value;
    }

    private static String name(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_$.-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String validatedHost(String value) {
        value = Objects.requireNonNull(value, "host").trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) {
            throw new IllegalArgumentException("database host is invalid");
        }
        return value;
    }

    private static String validatedRelativePath(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9._/-]{1,255}") || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*") || value.contains("\\") || value.contains("//")) {
            throw new IllegalArgumentException(field + " is not a controlled relative path");
        }
        for (String segment : value.split("/")) {
            if (segment.equals(".") || segment.equals("..") || segment.isEmpty()) {
                throw new IllegalArgumentException(field + " contains traversal");
            }
        }
        return value;
    }

    private static String text(String value, String field, int maximumLength) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty() || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must contain bounded printable text");
        }
        return value;
    }

    private static List<String> validatedEvidence(List<String> values) {
        List<String> result = texts(values, "evidence", false);
        if (result.isEmpty()) throw new IllegalArgumentException("database evidence is incomplete");
        return result;
    }

    private static List<String> texts(List<String> values, String field, boolean allowEmpty) {
        Objects.requireNonNull(values, field);
        if ((!allowEmpty && values.isEmpty()) || values.size() > 64) {
            throw new IllegalArgumentException(field + " count is invalid");
        }
        List<String> result = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String item = text(value, field, 512);
            if (!unique.add(item)) throw new IllegalArgumentException(field + " contains duplicates");
            result.add(item);
        }
        return List.copyOf(result);
    }
}
