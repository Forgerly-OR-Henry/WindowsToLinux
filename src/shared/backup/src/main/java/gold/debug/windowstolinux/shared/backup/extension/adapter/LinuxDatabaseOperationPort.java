package gold.debug.windowstolinux.shared.backup.extension.adapter;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupArtifact;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupRequest;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCommitEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCompatibilityEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseConnectionProfile;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRecoveryEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabase;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;

/**
 * Adapts the public Linux database contract to backup-owned policy types. / 将 Linux 公共数据库契约适配为备份策略类型。
 */
public final class LinuxDatabaseOperationPort implements DatabaseOperationPort {
    /**
     * The credential-free remote.
     * <p>不含凭据的远端。
     */
    private final RemoteDatabasePort remote;

    /**
     * Creates a backup port over one typed remote database capability. / 基于一个类型化远程数据库能力创建备份端口。
     *
     * @param remote the credential-free remote / 不含凭据的远端
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public LinuxDatabaseOperationPort(RemoteDatabasePort remote) {
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    /**
     * Inspects database compatibility evidence.
     * <p>检查数据库兼容性证据。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database compatibility evidence / 构造或解析得到的数据库兼容性证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public DatabaseCompatibilityEvidence inspect(DatabaseBackupRequest request) throws BackupException {
        try {
            RemoteDatabasePort.CompatibilityEvidence evidence = remote.inspect(toRemote(request));
            return new DatabaseCompatibilityEvidence(type(evidence.type()), evidence.engineVersion(),
                    evidence.toolVersion(), evidence.toolAvailable(), evidence.engineVersionCompatible(),
                    evidence.onlineBackupAvailable(), evidence.allTablesTransactional(), evidence.evidence());
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.DATABASE_PREFLIGHT_FAILED,
                    "remote database inspection failed", exception);
        }
    }

    /**
     * Exports the selected database using the admitted consistency strategy.
     * <p>使用已准入一致性策略导出所选数据库。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param consistencyMode consistency mode / 一致性模式
     * @return constructed or resolved database backup artifact / 构造或解析得到的数据库备份制品
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public DatabaseBackupArtifact export(DatabaseBackupRequest request, BackupConsistencyMode consistencyMode)
            throws BackupException {
        try {
            return fromRemote(remote.export(toRemote(request), mode(consistencyMode)));
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.DATABASE_BACKUP_FAILED, "remote database export failed",
                    exception);
        }
    }

    /**
     * Restores candidate.
     * <p>恢复候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database restore evidence / 构造或解析得到的数据库恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public DatabaseRestoreEvidence restoreCandidate(DatabaseRestoreRequest request) throws BackupException {
        try {
            RemoteDatabasePort.RestoreEvidence evidence = remote.restoreCandidate(
                    new RemoteDatabasePort.RestoreRequest(request.applicationId(), request.credentialApplicationId(),
                            request.candidateId(), connection(request.target()), artifact(request.artifact())));
            return new DatabaseRestoreEvidence(evidence.candidateId(), evidence.connectionToken(),
                    evidence.integrityVerified(), evidence.schemaReadable(), evidence.evidence());
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.DATABASE_RESTORE_FAILED,
                    "remote database candidate restore failed", exception);
        }
    }

    /**
     * Commits candidate.
     * <p>提交候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database commit evidence / 构造或解析得到的数据库提交证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public DatabaseCommitEvidence commitCandidate(DatabaseRestoreRequest request) throws BackupException {
        try {
            RemoteDatabasePort.CommitEvidence evidence = remote.commitCandidate(restore(request));
            return new DatabaseCommitEvidence(evidence.candidateId(), evidence.committed(),
                    evidence.previousDatabaseRetained(), evidence.evidence());
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.RESTORE_COMMIT_FAILED,
                    "remote database candidate commit failed", exception);
        }
    }

    /**
     * Recovers candidate.
     * <p>恢复候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database recovery evidence / 构造或解析得到的数据库恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public DatabaseRecoveryEvidence recoverCandidate(DatabaseRestoreRequest request) throws BackupException {
        try {
            RemoteDatabasePort.RecoveryEvidence evidence = remote.recoverCandidate(restore(request));
            return new DatabaseRecoveryEvidence(evidence.candidateId(), evidence.recovered(),
                    evidence.previousDatabaseVerified(), evidence.candidateRemoved(), evidence.evidence());
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.RESTORE_RECOVERY_FAILED,
                    "remote database candidate recovery failed", exception);
        }
    }

    /**
     * Discards candidate.
     * <p>清理候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public void discardCandidate(DatabaseRestoreRequest request) throws BackupException {
        try {
            remote.discardCandidate(restore(request));
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.CLEANUP_FAILED, "remote database candidate cleanup failed",
                    exception);
        }
    }

    /**
     * Copies verified build or backup artifact metadata.
     * <p>复制已验证构建或备份制品元数据。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public void copyArtifact(DatabaseBackupArtifact artifact, OutputStream destination) throws BackupException {
        try {
            remote.copyArtifact(artifact(artifact), destination);
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.DATABASE_BACKUP_FAILED,
                    "remote database artifact read failed", exception);
        }
    }

    /**
     * Stages verified build or backup artifact metadata.
     * <p>暂存已验证构建或备份制品元数据。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public void stageArtifact(DatabaseBackupArtifact artifact, InputStream source) throws BackupException {
        try {
            remote.stageArtifact(artifact(artifact), source);
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.DATABASE_RESTORE_FAILED,
                    "remote database artifact staging failed", exception);
        }
    }

    /**
     * Discards verified build or backup artifact metadata.
     * <p>清理已验证构建或备份制品元数据。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public void discardArtifact(DatabaseBackupArtifact artifact) throws BackupException {
        try {
            remote.discardArtifact(artifact(artifact));
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.CLEANUP_FAILED, "remote database artifact cleanup failed",
                    exception);
        }
    }

    /**
     * Converts the current contract to remote.
     * <p>将当前契约转换为远端。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved backup request / 构造或解析得到的备份请求
     */
    private static RemoteDatabasePort.BackupRequest toRemote(DatabaseBackupRequest request) {
        return new RemoteDatabasePort.BackupRequest(request.applicationId(), connection(request.connection()),
                request.applicationWritesStopped(), request.exclusiveWriterConfirmed());
    }

    /**
     * Restores restore request.
     * <p>恢复恢复请求。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved restore request / 构造或解析得到的恢复请求
     */
    private static RemoteDatabasePort.RestoreRequest restore(DatabaseRestoreRequest request) {
        return new RemoteDatabasePort.RestoreRequest(request.applicationId(), request.credentialApplicationId(),
                request.candidateId(), connection(request.target()), artifact(request.artifact()));
    }

    /**
     * Builds connection profile from the supplied connection inputs.
     * <p>根据所提供连接输入构建连接配置资料。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @return connection profile from the supplied connection inputs / 根据所提供连接输入构建连接配置资料
     */
    private static RemoteDatabasePort.ConnectionProfile connection(DatabaseConnectionProfile profile) {
        if (profile instanceof DatabaseConnectionProfile.Sqlite sqlite) {
            return new RemoteDatabasePort.ConnectionProfile.Sqlite(sqlite.bindingId(), sqlite.location(),
                    sqlite.fileName());
        }
        DatabaseConnectionProfile.Server server = (DatabaseConnectionProfile.Server) profile;
        return new RemoteDatabasePort.ConnectionProfile.Server(type(server.type()), server.host(), server.port(),
                server.database(), server.username(), server.passwordReference().identifier(),
                server.passwordReference().revision(), server.tlsRequired());
    }

    /**
     * Reconstructs the typed contract from remote.
     * <p>从远端重建类型化契约。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @return constructed or resolved database backup artifact / 构造或解析得到的数据库备份制品
     */
    private static DatabaseBackupArtifact fromRemote(RemoteDatabasePort.BackupArtifact artifact) {
        BackupDatabase database = new BackupDatabase(type(artifact.type()), artifact.reference(),
                artifact.engineVersion(), artifact.toolVersion(), mode(artifact.consistencyMode()),
                artifact.limitations());
        return new DatabaseBackupArtifact(artifact.artifactId(), artifact.byteCount(), artifact.sha256(), database,
                artifact.evidence());
    }

    /**
     * Builds backup artifact from the supplied artifact inputs.
     * <p>根据所提供制品输入构建备份制品。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @return backup artifact from the supplied artifact inputs / 根据所提供制品输入构建备份制品
     */
    private static RemoteDatabasePort.BackupArtifact artifact(DatabaseBackupArtifact artifact) {
        BackupDatabase database = artifact.database();
        return new RemoteDatabasePort.BackupArtifact(artifact.artifactId(), artifact.byteCount(), artifact.sha256(),
                type(database.type()), database.reference(), database.engineVersion(), database.toolVersion(),
                mode(database.consistencyMode()), database.limitations(), artifact.evidence());
    }

    /**
     * Maps the supplied engine or protocol discriminator to the supported type contract.
     * <p>将提供的引擎或协议判别码映射为受支持的类型契约。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @return constructed or resolved database type / 构造或解析得到的数据库类型
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static RemoteDatabasePort.DatabaseType type(BackupDatabaseType type) {
        return switch (type) {
            case SQLITE -> RemoteDatabasePort.DatabaseType.SQLITE;
            case POSTGRESQL -> RemoteDatabasePort.DatabaseType.POSTGRESQL;
            case MYSQL -> RemoteDatabasePort.DatabaseType.MYSQL;
            case MARIADB -> RemoteDatabasePort.DatabaseType.MARIADB;
            case NONE -> throw new IllegalArgumentException("database operation requires a concrete type");
        };
    }

    /**
     * Maps the supplied engine or protocol discriminator to the supported type contract.
     * <p>将提供的引擎或协议判别码映射为受支持的类型契约。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @return constructed or resolved backup database type / 构造或解析得到的备份数据库类型
     */
    private static BackupDatabaseType type(RemoteDatabasePort.DatabaseType type) {
        return switch (type) {
            case SQLITE -> BackupDatabaseType.SQLITE;
            case POSTGRESQL -> BackupDatabaseType.POSTGRESQL;
            case MYSQL -> BackupDatabaseType.MYSQL;
            case MARIADB -> BackupDatabaseType.MARIADB;
        };
    }

    /**
     * Maps the consistency-mode representation across the database protocol boundary.
     * <p>在数据库协议边界两侧映射一致性模式表示。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @return constructed or resolved database consistency mode / 构造或解析得到的数据库一致性模式
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static RemoteDatabasePort.DatabaseConsistencyMode mode(BackupConsistencyMode mode) {
        return switch (mode) {
            case SQLITE_ONLINE_BACKUP -> RemoteDatabasePort.DatabaseConsistencyMode.SQLITE_ONLINE_BACKUP;
            case SQLITE_WRITES_STOPPED -> RemoteDatabasePort.DatabaseConsistencyMode.SQLITE_WRITES_STOPPED;
            case POSTGRESQL_LOGICAL_DUMP -> RemoteDatabasePort.DatabaseConsistencyMode.POSTGRESQL_LOGICAL_DUMP;
            case MYSQL_TRANSACTION_SNAPSHOT -> RemoteDatabasePort.DatabaseConsistencyMode.MYSQL_TRANSACTION_SNAPSHOT;
            case MYSQL_WRITES_STOPPED -> RemoteDatabasePort.DatabaseConsistencyMode.MYSQL_WRITES_STOPPED;
            case NOT_APPLICABLE -> throw new IllegalArgumentException("database export requires a consistency mode");
        };
    }

    /**
     * Maps the consistency-mode representation across the database protocol boundary.
     * <p>在数据库协议边界两侧映射一致性模式表示。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @return constructed or resolved backup consistency mode / 构造或解析得到的备份一致性模式
     */
    private static BackupConsistencyMode mode(RemoteDatabasePort.DatabaseConsistencyMode mode) {
        return switch (mode) {
            case SQLITE_ONLINE_BACKUP -> BackupConsistencyMode.SQLITE_ONLINE_BACKUP;
            case SQLITE_WRITES_STOPPED -> BackupConsistencyMode.SQLITE_WRITES_STOPPED;
            case POSTGRESQL_LOGICAL_DUMP -> BackupConsistencyMode.POSTGRESQL_LOGICAL_DUMP;
            case MYSQL_TRANSACTION_SNAPSHOT -> BackupConsistencyMode.MYSQL_TRANSACTION_SNAPSHOT;
            case MYSQL_WRITES_STOPPED -> BackupConsistencyMode.MYSQL_WRITES_STOPPED;
        };
    }
}
