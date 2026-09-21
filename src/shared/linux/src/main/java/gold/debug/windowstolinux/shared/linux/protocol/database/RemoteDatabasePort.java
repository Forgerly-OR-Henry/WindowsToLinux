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
 *  <p>不暴露传输实现或备份格式类型的远程数据库能力契约。
 */
public interface RemoteDatabasePort {
    /**
     * Collects database and tool compatibility without mutation. / 只读采集数据库与工具兼容性。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved compatibility evidence / 构造或解析得到的兼容性证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    CompatibilityEvidence inspect(BackupRequest request) throws LinuxOperationException;

    /**
     * Exports one database using the exact approved consistency mode. / 以精确批准的一致性方式导出数据库。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @return constructed or resolved backup artifact / 构造或解析得到的备份制品
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    BackupArtifact export(BackupRequest request, DatabaseConsistencyMode mode) throws LinuxOperationException;

    /**
     * Restores and reads an isolated candidate without activation. / 恢复并读取隔离候选且不激活。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved restore evidence / 构造或解析得到的恢复证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    RestoreEvidence restoreCandidate(RestoreRequest request) throws LinuxOperationException;

    /**
     * Activates one verified candidate and retains the exact previous database. / 激活已验证候选并保留精确旧数据库。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved commit evidence / 构造或解析得到的提交证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    CommitEvidence commitCandidate(RestoreRequest request) throws LinuxOperationException;

    /**
     * Recovers a committed or partially committed candidate and verifies the previous state. / 恢复已提交或部分提交候选并验证旧状态。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    RecoveryEvidence recoverCandidate(RestoreRequest request) throws LinuxOperationException;

    /**
     * Removes one isolated database candidate that was never committed. / 移除一个从未提交的隔离数据库候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    void discardCandidate(RestoreRequest request) throws LinuxOperationException;

    /**
     * Streams an exact remote artifact to a caller-owned destination. / 将精确远程制品流式传到调用方目标。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    void copyArtifact(BackupArtifact artifact, OutputStream destination) throws LinuxOperationException;

    /**
     * Streams an exact artifact into controlled remote staging. / 将精确制品流式传入受控远程暂存区。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    void stageArtifact(BackupArtifact artifact, InputStream source) throws LinuxOperationException;

    /**
     * Removes one explicitly identified remote artifact. / 移除一个明确标识的远程制品。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    void discardArtifact(BackupArtifact artifact) throws LinuxOperationException;

    /**
     * Supported database families. / 支持的数据库族。
     */
    enum DatabaseType {
        /**
         * SQLITE classification within database type.
         * <p>数据库类型中的SQLITE分类。
         */
        SQLITE,
        /**
         * POSTGRESQL classification within database type.
         * <p>数据库类型中的POSTGRESQL分类。
         */
        POSTGRESQL,
        /**
         * MYSQL classification within database type.
         * <p>数据库类型中的MYSQL分类。
         */
        MYSQL,
        /**
         * MARIADB classification within database type.
         * <p>数据库类型中的MARIADB分类。
         */
        MARIADB
    }

    /**
     * Consistency method approved by the backup policy. / 备份策略批准的一致性方式。
     */
    enum DatabaseConsistencyMode {
        /**
         * SQLITE ONLINE BACKUP classification within database consistency mode.
         * <p>数据库一致性模式中的SQLITE在线备份分类。
         */
        SQLITE_ONLINE_BACKUP,
        /**
         * SQLITE WRITES STOPPED classification within database consistency mode.
         * <p>数据库一致性模式中的SQLITE写入集合已停止分类。
         */
        SQLITE_WRITES_STOPPED,
        /**
         * POSTGRESQL LOGICAL DUMP classification within database consistency mode.
         * <p>数据库一致性模式中的POSTGRESQL逻辑转储分类。
         */
        POSTGRESQL_LOGICAL_DUMP,
        /**
         * MYSQL TRANSACTION SNAPSHOT classification within database consistency mode.
         * <p>数据库一致性模式中的MYSQL事务快照分类。
         */
        MYSQL_TRANSACTION_SNAPSHOT,
        /**
         * MYSQL WRITES STOPPED classification within database consistency mode.
         * <p>数据库一致性模式中的MYSQL写入集合已停止分类。
         */
        MYSQL_WRITES_STOPPED
    }

    /**
     * Non-secret connection identity. / 不含秘密的连接身份。
     */
    sealed interface ConnectionProfile permits ConnectionProfile.Sqlite, ConnectionProfile.Server {
        /**
         * Returns the exact database family. / 返回精确数据库族。
         *
         * @return the exact database family / 精确数据库族
         */
        DatabaseType type();

        /**
         * Managed relative SQLite path. / 受管 SQLite 相对路径。
         *
         * @param bindingId binding id / 绑定标识
         * @param location the remote URI / 远端 URI
         * @param fileName file name / 文件名称
         */
        record Sqlite(String bindingId, gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation location, String fileName) implements ConnectionProfile {
            /**
             * Validates a controlled path. / 校验受控路径。
             *
             * @param bindingId binding id / 绑定标识
             * @param location the remote URI / 远端 URI
             * @param fileName file name / 文件名称
             * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
             * @throws NullPointerException if a required input is absent / 必需输入缺失时
             */
            public Sqlite {
                if (!Objects.requireNonNull(bindingId).matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid SQLite storage binding");
            Objects.requireNonNull(location);
            if (location.type() == gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.UNRESOLVED) throw new IllegalArgumentException("SQLite location must be reviewed");
            if (!Objects.requireNonNull(fileName).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IllegalArgumentException("invalid SQLite file name");
            }

            /**
             * Returns selected member of the supported type set.
             * <p>返回受支持类型集合中的所选项。
             *
             * @return selected member of the supported type set / 受支持类型集合中的所选项
             */
            @Override public DatabaseType type() { return DatabaseType.SQLITE; }
        }

        /**
         * Server endpoint with an opaque secret revision reference. / 带不透明秘密修订引用的服务器端点。
         *
         * @param type selected member of the supported type set / 受支持类型集合中的所选项
         * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
         * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
         * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
         * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
         * @param passwordReference password reference / 密码引用
         * @param passwordRevision password revision / 密码修订
         * @param tlsRequired tls required / tls必需
         */
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
            /**
             * Validates a supported server connection. / 校验受支持服务器连接。
             *
             * @param type selected member of the supported type set / 受支持类型集合中的所选项
             * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
             * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
             * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
             * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
             * @param passwordReference password reference / 密码引用
             * @param passwordRevision password revision / 密码修订
             * @param tlsRequired tls required / tls必需
             * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
             * @throws NullPointerException if a required input is absent / 必需输入缺失时
             */
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

    /**
     * Explicit application write-state facts for one export. / 单次导出的显式应用写入状态事实。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param applicationWritesStopped application writes stopped / 应用写入集合已停止
     * @param exclusiveWriterConfirmed exclusive writer confirmed / 独占写入器已确认
     */
    record BackupRequest(
            String applicationId,
            ConnectionProfile connection,
            boolean applicationWritesStopped,
            boolean exclusiveWriterConfirmed
    ) {
        /**
         * Validates identity and write-state facts. / 校验身份与写入状态事实。
         *
         * @param applicationId managed application identifier / 受管应用标识
         * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
         * @param applicationWritesStopped application writes stopped / 应用写入集合已停止
         * @param exclusiveWriterConfirmed exclusive writer confirmed / 独占写入器已确认
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public BackupRequest {
            applicationId = identifier(applicationId, "applicationId");
            connection = Objects.requireNonNull(connection, "connection");
            if (exclusiveWriterConfirmed && !applicationWritesStopped) {
                throw new IllegalArgumentException("exclusive writer confirmation requires stopped writes");
            }
        }
    }

    /**
     * Compatibility evidence collected by the remote helper. / 远程 helper 采集的兼容性证据。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param engineVersion engine version / 引擎版本
     * @param toolVersion tool version / 工具版本
     * @param toolAvailable tool available / 工具可用
     * @param engineVersionCompatible engine version compatible / 引擎版本兼容
     * @param onlineBackupAvailable online backup available / 在线备份可用
     * @param allTablesTransactional all tables transactional / 全部表集合Transactional
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
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
        /**
         * Requires bounded explicit evidence. / 要求有界显式证据。
         *
         * @param type selected member of the supported type set / 受支持类型集合中的所选项
         * @param engineVersion engine version / 引擎版本
         * @param toolVersion tool version / 工具版本
         * @param toolAvailable tool available / 工具可用
         * @param engineVersionCompatible engine version compatible / 引擎版本兼容
         * @param onlineBackupAvailable online backup available / 在线备份可用
         * @param allTablesTransactional all tables transactional / 全部表集合Transactional
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public CompatibilityEvidence {
            type = Objects.requireNonNull(type, "type");
            engineVersion = text(engineVersion, "engineVersion", 128);
            toolVersion = text(toolVersion, "toolVersion", 128);
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * Opaque remote export with exact content and database evidence. / 带精确内容与数据库证据的不透明远程导出。
     *
     * @param artifactId artifact id / 制品标识
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param reference immutable public secret identity / 不可变公开秘密身份
     * @param engineVersion engine version / 引擎版本
     * @param toolVersion tool version / 工具版本
     * @param consistencyMode consistency mode / 一致性模式
     * @param limitations localized bounded limitations / 本地化有界限制
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
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
        /**
         * Validates artifact identity and evidence. / 校验制品身份与证据。
         *
         * @param artifactId artifact id / 制品标识
         * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
         * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
         * @param type selected member of the supported type set / 受支持类型集合中的所选项
         * @param reference immutable public secret identity / 不可变公开秘密身份
         * @param engineVersion engine version / 引擎版本
         * @param toolVersion tool version / 工具版本
         * @param consistencyMode consistency mode / 一致性模式
         * @param limitations localized bounded limitations / 本地化有界限制
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
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

    /**
     * Isolated restore request bound to one verified artifact. / 绑定已验证制品的隔离恢复请求。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param credentialApplicationId credential application id / 凭据应用标识
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     */
    record RestoreRequest(
            String applicationId,
            String credentialApplicationId,
            String candidateId,
            ConnectionProfile target,
            BackupArtifact artifact
    ) {
        /**
         * Validates managed candidate identity and database type. / 校验受管候选身份与数据库类型。
         *
         * @param applicationId managed application identifier / 受管应用标识
         * @param credentialApplicationId credential application id / 凭据应用标识
         * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
         * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
         * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public RestoreRequest {
            applicationId = identifier(applicationId, "applicationId");
            credentialApplicationId = identifier(credentialApplicationId, "credentialApplicationId");
            candidateId = candidate(applicationId, candidateId);
            target = Objects.requireNonNull(target, "target");
            artifact = Objects.requireNonNull(artifact, "artifact");
            if (target.type() != artifact.type()) throw new IllegalArgumentException("restore database type differs");
        }

        /**
         * Creates a single-component request with one shared application namespace. / 创建使用单一应用命名空间的单组件请求。
         *
         * @param applicationId managed application identifier / 受管应用标识
         * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
         * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
         * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
         */
        public RestoreRequest(String applicationId, String candidateId,
                              ConnectionProfile target, BackupArtifact artifact) {
            this(applicationId, applicationId, candidateId, target, artifact);
        }
    }

    /**
     * Verified but unactivated remote database candidate. / 已验证但未激活的远程数据库候选。
     *
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param connectionToken connection token / 连接令牌
     * @param integrityVerified integrity verified / 完整性已验证
     * @param schemaReadable schema readable / 结构可读
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record RestoreEvidence(
            String candidateId,
            String connectionToken,
            boolean integrityVerified,
            boolean schemaReadable,
            List<String> evidence
    ) {
        /**
         * Requires complete candidate verification. / 要求完整候选验证。
         *
         * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
         * @param connectionToken connection token / 连接令牌
         * @param integrityVerified integrity verified / 完整性已验证
         * @param schemaReadable schema readable / 结构可读
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public RestoreEvidence {
            candidateId = identifier(candidateId, "candidateId");
            connectionToken = identifier(connectionToken, "connectionToken");
            if (!integrityVerified || !schemaReadable) {
                throw new IllegalArgumentException("unverified database candidate cannot be returned");
            }
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * Successful database activation evidence. / 数据库成功激活证据。
     *
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param committed committed / 已提交
     * @param previousDatabaseRetained previous database retained / 此前数据库已保留
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record CommitEvidence(String candidateId, boolean committed, boolean previousDatabaseRetained,
                          List<String> evidence) {
        /**
         * Requires complete activation evidence. / 要求完整激活证据。
         *
         * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
         * @param committed committed / 已提交
         * @param previousDatabaseRetained previous database retained / 此前数据库已保留
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public CommitEvidence {
            candidateId = identifier(candidateId, "candidateId");
            if (!committed || !previousDatabaseRetained) {
                throw new IllegalArgumentException("database commit evidence is incomplete");
            }
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * Verified rollback or uncommitted cleanup evidence. / 已验证回滚或未提交清理证据。
     *
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param recovered recovered / 已恢复
     * @param previousDatabaseVerified previous database verified / 此前数据库已验证
     * @param candidateRemoved candidate removed / 候选已移除
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record RecoveryEvidence(String candidateId, boolean recovered, boolean previousDatabaseVerified,
                            boolean candidateRemoved, List<String> evidence) {
        /**
         * Requires a complete safe recovery result. / 要求完整安全恢复结果。
         *
         * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
         * @param recovered recovered / 已恢复
         * @param previousDatabaseVerified previous database verified / 此前数据库已验证
         * @param candidateRemoved candidate removed / 候选已移除
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public RecoveryEvidence {
            candidateId = identifier(candidateId, "candidateId");
            if (!recovered || !previousDatabaseVerified || !candidateRemoved) {
                throw new IllegalArgumentException("database recovery evidence is incomplete");
            }
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    /**
     * Checks candidate syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查候选语法及边界。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return candidate text / 候选文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String candidate(String applicationId, String value) {
        value = Objects.requireNonNull(value, "candidateId").trim();
        if (!value.matches(Pattern.quote(applicationId) + "-[0-9a-f]{16}")) {
            throw new IllegalArgumentException("candidateId is not bound to the application");
        }
        return value;
    }

    /**
     * Checks secret identifier syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查秘密标识语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return secret identifier text / 秘密标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String secretIdentifier(String value) {
        value = Objects.requireNonNull(value, "passwordReference").trim();
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("passwordReference is invalid");
        }
        return value;
    }

    /**
     * Checks name syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查名称语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return name text / 名称文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String name(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_$.-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    /**
     * Validates and produces validated host for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的已验证主机。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return validated host text / 已验证主机文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String validatedHost(String value) {
        value = Objects.requireNonNull(value, "host").trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) {
            throw new IllegalArgumentException("database host is invalid");
        }
        return value;
    }

    /**
     * Validates and produces validated relative path for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的已验证相对路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return validated relative path text / 已验证相对路径文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Validates textual content against the declared length and character constraints.
     * <p>按声明的长度及字符约束校验文本内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @param maximumLength maximum length / 最大长度
     * @return text text / 文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String text(String value, String field, int maximumLength) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty() || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must contain bounded printable text");
        }
        return value;
    }

    /**
     * Checks returned evidence against the exact requested operation.
     * <p>按精确请求操作检查返回证据。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static List<String> validatedEvidence(List<String> values) {
        List<String> result = texts(values, "evidence", false);
        if (result.isEmpty()) throw new IllegalArgumentException("database evidence is incomplete");
        return result;
    }

    /**
     * Validates bounded distinct protocol strings and enforces the caller's empty-list policy.
     * <p>校验有界且不重复的协议字符串，并执行调用方的空列表策略。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @param allowEmpty allow empty / allow空
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
