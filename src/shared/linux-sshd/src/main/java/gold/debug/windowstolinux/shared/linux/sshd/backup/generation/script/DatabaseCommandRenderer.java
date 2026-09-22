package gold.debug.windowstolinux.shared.linux.sshd.backup.generation.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;

/**
 * Renders only fixed managed-helper database verbs with validated typed arguments. / 仅使用已校验类型化参数渲染固定受管 helper 数据库动词。
 */
public final class DatabaseCommandRenderer {
    /**
     * Renders read-only database compatibility inspection. / 渲染只读数据库兼容性检查。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return inspect text / 检查文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public String inspect(RemoteDatabasePort.BackupRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> arguments = new ArrayList<>();
        arguments.add(request.applicationId());
        appendConnection(arguments, request.connection());
        return command("database-inspect", arguments);
    }

    /**
     * Renders a consistency-mode-bound database export. / 渲染绑定一致性方式的数据库导出。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @return export text / 导出文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public String export(RemoteDatabasePort.BackupRequest request, RemoteDatabasePort.DatabaseConsistencyMode mode) {
        Objects.requireNonNull(request, "request");
        List<String> arguments = new ArrayList<>();
        arguments.add(request.applicationId());
        arguments.add(modeToken(mode));
        arguments.add(request.applicationWritesStopped() ? "1" : "0");
        arguments.add(request.exclusiveWriterConfirmed() ? "1" : "0");
        appendConnection(arguments, request.connection());
        return command("database-export", arguments);
    }

    /**
     * Renders isolated candidate restore. / 渲染隔离候选恢复。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return restore text / 恢复文本
     */
    public String restore(RemoteDatabasePort.RestoreRequest request) {
        return command("database-restore-candidate", restoreArguments(request));
    }

    /**
     * Renders stopped-write candidate activation. / 渲染停写候选激活。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return commit candidate text / 提交候选文本
     */
    public String commitCandidate(RemoteDatabasePort.RestoreRequest request) {
        return command("database-commit-candidate", restoreArguments(request));
    }

    /**
     * Renders exact previous-database recovery. / 渲染精确旧数据库恢复。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return recover candidate text / 恢复候选文本
     */
    public String recoverCandidate(RemoteDatabasePort.RestoreRequest request) {
        return command("database-recover-candidate", restoreArguments(request));
    }

    /**
     * Renders cleanup for an isolated database candidate. / 渲染隔离数据库候选清理。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return discard candidate text / 丢弃候选文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public String discardCandidate(RemoteDatabasePort.RestoreRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> arguments = new ArrayList<>();
        arguments.add(request.applicationId());
        arguments.add(request.credentialApplicationId());
        arguments.add(request.candidateId());
        appendConnection(arguments, request.target());
        return command("database-discard-candidate", arguments);
    }

    /**
     * Renders exact artifact streaming from the controlled remote store. / 渲染从受控远程存储精确流出导出物。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @return read artifact text / 读取制品文本
     */
    public String readArtifact(RemoteDatabasePort.BackupArtifact artifact) {
        return command("database-read-artifact",
                List.of(artifact.artifactId(), Long.toString(artifact.byteCount()), artifact.sha256()));
    }

    /**
     * Renders exact artifact streaming into the controlled remote store. / 渲染向受控远程存储精确流入导出物。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @return stage artifact text / 阶段制品文本
     */
    public String stageArtifact(RemoteDatabasePort.BackupArtifact artifact) {
        return command("database-stage-artifact",
                List.of(artifact.artifactId(), Long.toString(artifact.byteCount()), artifact.sha256()));
    }

    /**
     * Renders controlled artifact removal. / 渲染受控导出物移除。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @return discard artifact text / 丢弃制品文本
     */
    public String discardArtifact(RemoteDatabasePort.BackupArtifact artifact) {
        return command("database-discard-artifact", List.of(artifact.artifactId(), artifact.sha256()));
    }

    /**
     * Appends only the supported SQLite or server connection fields to the helper argument vector; secret material uses its separate channel.
     * <p>仅向 helper 参数向量追加受支持 SQLite 或服务器连接字段；秘密素材使用独立通道。
     *
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     */
    private void appendConnection(List<String> arguments, RemoteDatabasePort.ConnectionProfile connection) {
        arguments.add(typeToken(connection.type()));
        if (connection instanceof RemoteDatabasePort.ConnectionProfile.Sqlite sqlite) {
            arguments.add(sqlite.bindingId());
            arguments.add(sqlite.location().type().name());
            arguments.add(sqlite.location().path().isEmpty() ? "-" : sqlite.location().path());
            arguments.add(sqlite.fileName());
            return;
        }
        RemoteDatabasePort.ConnectionProfile.Server server = (RemoteDatabasePort.ConnectionProfile.Server) connection;
        arguments.add(server.host());
        arguments.add(Integer.toString(server.port()));
        arguments.add(server.database());
        arguments.add(server.username());
        arguments.add(server.passwordReference());
        arguments.add(Long.toString(server.passwordRevision()));
        arguments.add(server.tlsRequired() ? "1" : "0");
    }

    /**
     * Restores literal arguments passed to the fixed command or message template.
     * <p>恢复传给固定命令或消息模板的字面参数。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private List<String> restoreArguments(RemoteDatabasePort.RestoreRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> arguments = new ArrayList<>();
        arguments.add(request.applicationId());
        arguments.add(request.credentialApplicationId());
        arguments.add(request.candidateId());
        arguments.add(request.artifact().artifactId());
        appendConnection(arguments, request.target());
        return arguments;
    }

    /**
     * Renders a fixed helper invocation with individually quoted reviewed arguments; does not execute it.
     * <p>使用逐项引用的已审阅参数渲染固定 helper 调用，不执行该调用。
     *
     * @param verb verb / 操作动词
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @return command text / 命令文本
     */
    private String command(String verb, List<String> arguments) {
        StringBuilder command = new StringBuilder("sudo -n ")
                .append(gold.debug.windowstolinux.shared.linux.command.CommandText.quote(ManagedHelperBundle.PATH))
                .append(' ').append(gold.debug.windowstolinux.shared.linux.command.CommandText.quote(verb));
        arguments.forEach(argument -> command.append(' ')
                .append(gold.debug.windowstolinux.shared.linux.command.CommandText.quote(argument)));
        return command.toString();
    }

    /**
     * Maps a supported database type to its lowercase helper token.
     * <p>将受支持数据库类型映射为小写 helper 令牌。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @return type token text / 类型令牌文本
     */
    private static String typeToken(RemoteDatabasePort.DatabaseType type) {
        return switch (type) {
            case SQLITE -> "sqlite";
            case POSTGRESQL -> "postgresql";
            case MYSQL -> "mysql";
            case MARIADB -> "mariadb";
        };
    }

    /**
     * Maps the database consistency strategy to its fixed helper protocol token.
     * <p>将数据库一致性策略映射为固定 helper 协议令牌。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @return mode token text / 模式令牌文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String modeToken(RemoteDatabasePort.DatabaseConsistencyMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case SQLITE_ONLINE_BACKUP -> "sqlite-online";
            case SQLITE_WRITES_STOPPED -> "sqlite-stopped";
            case POSTGRESQL_LOGICAL_DUMP -> "postgresql-logical";
            case MYSQL_TRANSACTION_SNAPSHOT -> "mysql-transaction";
            case MYSQL_WRITES_STOPPED -> "mysql-stopped";
        };
    }
}
