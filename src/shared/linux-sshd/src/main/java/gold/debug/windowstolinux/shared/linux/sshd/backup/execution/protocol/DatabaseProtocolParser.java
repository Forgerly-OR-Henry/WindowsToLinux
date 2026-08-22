package gold.debug.windowstolinux.shared.linux.sshd.backup.execution.protocol;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses bounded key-value evidence emitted by fixed database helper verbs. / 解析固定数据库 helper 动词输出的有界键值证据。 */
public final class DatabaseProtocolParser {
    private static final int MAXIMUM_PROTOCOL_LENGTH = 16_384;
    private static final int MAXIMUM_PROTOCOL_LINES = 32;
    private static final Set<String> COMPATIBILITY_KEYS = Set.of(
            "TYPE", "ENGINE_VERSION", "TOOL_VERSION", "TOOL_AVAILABLE", "ENGINE_COMPATIBLE",
            "ONLINE_BACKUP_AVAILABLE", "ALL_TABLES_TRANSACTIONAL");
    private static final Set<String> ARTIFACT_KEYS = Set.of(
            "TYPE", "ENGINE_VERSION", "TOOL_VERSION", "CONSISTENCY_MODE", "ARTIFACT_ID", "BYTE_COUNT",
            "SHA256", "LIMITED_NON_TRANSACTIONAL");
    private static final Set<String> RESTORE_KEYS = Set.of(
            "CANDIDATE_ID", "CONNECTION_TOKEN", "INTEGRITY_VERIFIED", "SCHEMA_READABLE");
    private static final Set<String> COMMIT_KEYS = Set.of(
            "CANDIDATE_ID", "COMMITTED", "PREVIOUS_RETAINED");
    private static final Set<String> RECOVERY_KEYS = Set.of(
            "CANDIDATE_ID", "RECOVERED", "PREVIOUS_VERIFIED", "CANDIDATE_REMOVED");

    /** Parses compatibility evidence. / 解析兼容性证据。 */
    public RemoteDatabasePort.CompatibilityEvidence compatibility(String output) throws LinuxOperationException {
        Map<String, String> values = protocol(output, COMPATIBILITY_KEYS);
        RemoteDatabasePort.DatabaseType type = type(required(values, "TYPE"));
        String engine = required(values, "ENGINE_VERSION");
        String tool = required(values, "TOOL_VERSION");
        boolean available = bool(values, "TOOL_AVAILABLE");
        boolean compatible = bool(values, "ENGINE_COMPATIBLE");
        boolean online = bool(values, "ONLINE_BACKUP_AVAILABLE");
        boolean transactional = bool(values, "ALL_TABLES_TRANSACTIONAL");
        return new RemoteDatabasePort.CompatibilityEvidence(type, engine, tool, available, compatible,
                online, transactional, List.of(
                "database type and version collected by managed helper",
                "backup tool availability and compatibility checked",
                "storage-engine consistency capability checked"));
    }

    /** Parses a completed export and binds it to exact preflight versions. / 解析已完成导出并绑定精确前置版本。 */
    public RemoteDatabasePort.BackupArtifact artifact(String output, String reference) throws LinuxOperationException {
        Map<String, String> values = protocol(output, ARTIFACT_KEYS);
        RemoteDatabasePort.DatabaseType type = type(required(values, "TYPE"));
        RemoteDatabasePort.DatabaseConsistencyMode mode = mode(required(values, "CONSISTENCY_MODE"));
        String engineVersion = required(values, "ENGINE_VERSION");
        String toolVersion = required(values, "TOOL_VERSION");
        List<String> limitations = bool(values, "LIMITED_NON_TRANSACTIONAL")
                ? List.of("non-transactional tables required exclusive stopped writes") : List.of();
        long size = positiveLong(values, "BYTE_COUNT");
        String hash = required(values, "SHA256");
        return new RemoteDatabasePort.BackupArtifact(required(values, "ARTIFACT_ID"), size, hash, type, reference,
                engineVersion, toolVersion, mode, limitations,
                List.of("controlled logical or online export completed", "artifact SHA-256 verified remotely"));
    }

    /** Parses verified isolated restore evidence. / 解析已验证隔离恢复证据。 */
    public RemoteDatabasePort.RestoreEvidence restore(String output) throws LinuxOperationException {
        Map<String, String> values = protocol(output, RESTORE_KEYS);
        return new RemoteDatabasePort.RestoreEvidence(required(values, "CANDIDATE_ID"),
                required(values, "CONNECTION_TOKEN"), bool(values, "INTEGRITY_VERIFIED"),
                bool(values, "SCHEMA_READABLE"),
                List.of("database artifact integrity rechecked before restore",
                        "isolated candidate database schema opened successfully"));
    }

    /** Parses stopped-write activation evidence. / 解析停写激活证据。 */
    public RemoteDatabasePort.CommitEvidence commit(String output) throws LinuxOperationException {
        Map<String, String> values = protocol(output, COMMIT_KEYS);
        return new RemoteDatabasePort.CommitEvidence(required(values, "CANDIDATE_ID"),
                bool(values, "COMMITTED"), bool(values, "PREVIOUS_RETAINED"),
                List.of("database candidate activated after application writes stopped",
                        "previous database retained as a rollback point"));
    }

    /** Parses verified database rollback evidence. / 解析已验证数据库回滚证据。 */
    public RemoteDatabasePort.RecoveryEvidence recovery(String output) throws LinuxOperationException {
        Map<String, String> values = protocol(output, RECOVERY_KEYS);
        return new RemoteDatabasePort.RecoveryEvidence(required(values, "CANDIDATE_ID"),
                bool(values, "RECOVERED"), bool(values, "PREVIOUS_VERIFIED"),
                bool(values, "CANDIDATE_REMOVED"),
                List.of("database activation rollback completed", "previous database state verified"));
    }

    private static String required(Map<String, String> values, String key) throws LinuxOperationException {
        String value = values.getOrDefault(key, "").trim();
        if (value.isEmpty() || value.length() > 512) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID,
                    "managed helper omitted or exceeded database evidence field " + key);
        }
        return value;
    }

    private static Map<String, String> protocol(String output, Set<String> expected) throws LinuxOperationException {
        if (output == null || output.length() > MAXIMUM_PROTOCOL_LENGTH) {
            throw invalid("managed helper database evidence exceeds its protocol boundary");
        }
        List<String> lines = output.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty() || lines.size() > MAXIMUM_PROTOCOL_LINES) {
            throw invalid("managed helper database evidence line count is invalid");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (separator < 1 || separator == line.length() - 1) {
                throw invalid("managed helper returned malformed database evidence");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!key.matches("[A-Z][A-Z0-9_]{0,63}") || value.length() > 512
                    || value.chars().anyMatch(Character::isISOControl) || values.putIfAbsent(key, value) != null) {
                throw invalid("managed helper returned duplicate or unsafe database evidence");
            }
        }
        if (!values.keySet().equals(expected)) {
            throw invalid("managed helper database evidence schema is incomplete or unknown");
        }
        return Map.copyOf(values);
    }

    private static LinuxOperationException invalid(String diagnostic) {
        return LinuxOperationException.create(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID, diagnostic);
    }

    private static boolean bool(Map<String, String> values, String key) throws LinuxOperationException {
        String value = required(values, key);
        if (!value.equals("0") && !value.equals("1")) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID,
                    "managed helper returned a non-boolean database evidence field");
        }
        return value.equals("1");
    }

    private static long positiveLong(Map<String, String> values, String key) throws LinuxOperationException {
        try {
            long value = Long.parseLong(required(values, key));
            if (value < 1) throw new NumberFormatException("non-positive");
            return value;
        } catch (NumberFormatException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID,
                    "managed helper returned invalid database artifact size", exception);
        }
    }

    private static RemoteDatabasePort.DatabaseType type(String value) throws LinuxOperationException {
        return switch (value) {
            case "sqlite" -> RemoteDatabasePort.DatabaseType.SQLITE;
            case "postgresql" -> RemoteDatabasePort.DatabaseType.POSTGRESQL;
            case "mysql" -> RemoteDatabasePort.DatabaseType.MYSQL;
            case "mariadb" -> RemoteDatabasePort.DatabaseType.MARIADB;
            default -> throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID,
                    "managed helper returned an unsupported database type");
        };
    }

    private static RemoteDatabasePort.DatabaseConsistencyMode mode(String value) throws LinuxOperationException {
        return switch (value) {
            case "sqlite-online" -> RemoteDatabasePort.DatabaseConsistencyMode.SQLITE_ONLINE_BACKUP;
            case "sqlite-stopped" -> RemoteDatabasePort.DatabaseConsistencyMode.SQLITE_WRITES_STOPPED;
            case "postgresql-logical" -> RemoteDatabasePort.DatabaseConsistencyMode.POSTGRESQL_LOGICAL_DUMP;
            case "mysql-transaction" -> RemoteDatabasePort.DatabaseConsistencyMode.MYSQL_TRANSACTION_SNAPSHOT;
            case "mysql-stopped" -> RemoteDatabasePort.DatabaseConsistencyMode.MYSQL_WRITES_STOPPED;
            default -> throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID,
                    "managed helper returned an unsupported consistency mode");
        };
    }
}
