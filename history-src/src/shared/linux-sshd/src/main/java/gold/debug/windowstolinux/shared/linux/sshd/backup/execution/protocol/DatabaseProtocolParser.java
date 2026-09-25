package gold.debug.windowstolinux.shared.linux.sshd.backup.execution.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;

/**
 * Parses bounded key-value evidence emitted by fixed database helper verbs. / 解析固定数据库 helper 动词输出的有界键值证据。
 */
public final class DatabaseProtocolParser {
    /**
     * MAXIMUM PROTOCOL LENGTH.
     * <p>最大协议长度。
     */
    private static final int MAXIMUM_PROTOCOL_LENGTH = 16_384;

    /**
     * MAXIMUM PROTOCOL LINES.
     * <p>最大协议行集合。
     */
    private static final int MAXIMUM_PROTOCOL_LINES = 32;

    /**
     * COMPATIBILITY KEYS.
     * <p>兼容性键集合。
     */
    private static final Set<String> COMPATIBILITY_KEYS = Set.of("TYPE", "ENGINE_VERSION", "TOOL_VERSION",
            "TOOL_AVAILABLE", "ENGINE_COMPATIBLE", "ONLINE_BACKUP_AVAILABLE", "ALL_TABLES_TRANSACTIONAL");

    /**
     * ARTIFACT KEYS.
     * <p>制品键集合。
     */
    private static final Set<String> ARTIFACT_KEYS = Set.of("TYPE", "ENGINE_VERSION", "TOOL_VERSION",
            "CONSISTENCY_MODE", "ARTIFACT_ID", "BYTE_COUNT", "SHA256", "LIMITED_NON_TRANSACTIONAL");

    /**
     * RESTORE KEYS.
     * <p>恢复键集合。
     */
    private static final Set<String> RESTORE_KEYS = Set.of("CANDIDATE_ID", "CONNECTION_TOKEN", "INTEGRITY_VERIFIED",
            "SCHEMA_READABLE");

    /**
     * COMMIT KEYS.
     * <p>提交键集合。
     */
    private static final Set<String> COMMIT_KEYS = Set.of("CANDIDATE_ID", "COMMITTED", "PREVIOUS_RETAINED");

    /**
     * RECOVERY KEYS.
     * <p>恢复键集合。
     */
    private static final Set<String> RECOVERY_KEYS = Set.of("CANDIDATE_ID", "RECOVERED", "PREVIOUS_VERIFIED",
            "CANDIDATE_REMOVED");

    /**
     * Parses compatibility evidence. / 解析兼容性证据。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return compatibility evidence / 兼容性证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public RemoteDatabasePort.CompatibilityEvidence compatibility(String output) throws LinuxOperationException {
        Map<String, String> values = protocol(output, COMPATIBILITY_KEYS);
        RemoteDatabasePort.DatabaseType type = type(required(values, "TYPE"));
        String engine = required(values, "ENGINE_VERSION");
        String tool = required(values, "TOOL_VERSION");
        boolean available = bool(values, "TOOL_AVAILABLE");
        boolean compatible = bool(values, "ENGINE_COMPATIBLE");
        boolean online = bool(values, "ONLINE_BACKUP_AVAILABLE");
        boolean transactional = bool(values, "ALL_TABLES_TRANSACTIONAL");
        return new RemoteDatabasePort.CompatibilityEvidence(type, engine, tool, available, compatible, online,
                transactional,
                List.of("database type and version collected by managed helper",
                        "backup tool availability and compatibility checked",
                        "storage-engine consistency capability checked"));
    }

    /**
     * Parses a completed export and binds it to exact preflight versions. / 解析已完成导出并绑定精确前置版本。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param reference immutable public secret identity / 不可变公开秘密身份
     * @return a completed export and binds it to exact preflight versions / 已完成导出并绑定精确前置版本
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public RemoteDatabasePort.BackupArtifact artifact(String output, String reference) throws LinuxOperationException {
        Map<String, String> values = protocol(output, ARTIFACT_KEYS);
        RemoteDatabasePort.DatabaseType type = type(required(values, "TYPE"));
        RemoteDatabasePort.DatabaseConsistencyMode mode = mode(required(values, "CONSISTENCY_MODE"));
        String engineVersion = required(values, "ENGINE_VERSION");
        String toolVersion = required(values, "TOOL_VERSION");
        List<String> limitations = bool(values, "LIMITED_NON_TRANSACTIONAL")
                ? List.of("non-transactional tables required exclusive stopped writes")
                : List.of();
        long size = positiveLong(values, "BYTE_COUNT");
        String hash = required(values, "SHA256");
        return new RemoteDatabasePort.BackupArtifact(required(values, "ARTIFACT_ID"), size, hash, type, reference,
                engineVersion, toolVersion, mode, limitations,
                List.of("controlled logical or online export completed", "artifact SHA-256 verified remotely"));
    }

    /**
     * Parses verified isolated restore evidence. / 解析已验证隔离恢复证据。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return verified isolated restore evidence / 已验证隔离恢复证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public RemoteDatabasePort.RestoreEvidence restore(String output) throws LinuxOperationException {
        Map<String, String> values = protocol(output, RESTORE_KEYS);
        return new RemoteDatabasePort.RestoreEvidence(required(values, "CANDIDATE_ID"),
                required(values, "CONNECTION_TOKEN"), bool(values, "INTEGRITY_VERIFIED"),
                bool(values, "SCHEMA_READABLE"), List.of("database artifact integrity rechecked before restore",
                        "isolated candidate database schema opened successfully"));
    }

    /**
     * Parses stopped-write activation evidence. / 解析停写激活证据。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return stopped-write activation evidence / 停写激活证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public RemoteDatabasePort.CommitEvidence commit(String output) throws LinuxOperationException {
        Map<String, String> values = protocol(output, COMMIT_KEYS);
        return new RemoteDatabasePort.CommitEvidence(required(values, "CANDIDATE_ID"), bool(values, "COMMITTED"),
                bool(values, "PREVIOUS_RETAINED"),
                List.of("database candidate activated after application writes stopped",
                        "previous database retained as a rollback point"));
    }

    /**
     * Parses verified database rollback evidence. / 解析已验证数据库回滚证据。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return verified database rollback evidence / 已验证数据库回滚证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public RemoteDatabasePort.RecoveryEvidence recovery(String output) throws LinuxOperationException {
        Map<String, String> values = protocol(output, RECOVERY_KEYS);
        return new RemoteDatabasePort.RecoveryEvidence(required(values, "CANDIDATE_ID"), bool(values, "RECOVERED"),
                bool(values, "PREVIOUS_VERIFIED"), bool(values, "CANDIDATE_REMOVED"),
                List.of("database activation rollback completed", "previous database state verified"));
    }

    /**
     * Requires the named input to be present and valid before continuing.
     * <p>继续前要求具名输入存在且有效。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return required text / 必需文本
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static String required(Map<String, String> values, String key) throws LinuxOperationException {
        String value = values.getOrDefault(key, "").trim();
        if (value.isEmpty() || value.length() > 512) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID,
                    "managed helper omitted or exceeded database evidence field " + key);
        }
        return value;
    }

    /**
     * Checks protocol syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查协议语法及边界。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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

    /**
     * Creates the owning module's failure for rejected input or evidence.
     * <p>为被拒绝输入或证据创建所属模块的失败。
     *
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return the owning module's failure for rejected input or evidence / 为被拒绝输入或证据创建所属模块的失败
     */
    private static LinuxOperationException invalid(String diagnostic) {
        return LinuxOperationException.create(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID, diagnostic);
    }

    /**
     * Parses the supported boolean representation of a contract field.
     * <p>解析契约字段支持的布尔表示。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return true when parses the supported boolean representation of a contract field, false otherwise / 解析契约字段支持的布尔表示时为 true，否则为 false
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static boolean bool(Map<String, String> values, String key) throws LinuxOperationException {
        String value = required(values, key);
        if (!value.equals("0") && !value.equals("1")) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID,
                    "managed helper returned a non-boolean database evidence field");
        }
        return value.equals("1");
    }

    /**
     * Parses a strictly positive long value from protocol text.
     * <p>从协议文本解析严格为正的长整型数值。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return a strictly positive long value from protocol text / 从协议文本解析严格为正的长整型数值
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static long positiveLong(Map<String, String> values, String key) throws LinuxOperationException {
        try {
            long value = Long.parseLong(required(values, key));
            if (value < 1)
                throw new NumberFormatException("non-positive");
            return value;
        } catch (NumberFormatException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID,
                    "managed helper returned invalid database artifact size", exception);
        }
    }

    /**
     * Maps the supplied engine or protocol discriminator to the supported type contract.
     * <p>将提供的引擎或协议判别码映射为受支持的类型契约。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved database type / 构造或解析得到的数据库类型
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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

    /**
     * Maps the consistency-mode representation across the database protocol boundary.
     * <p>在数据库协议边界两侧映射一致性模式表示。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved database consistency mode / 构造或解析得到的数据库一致性模式
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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
