package gold.debug.windowstolinux.shared.linux.sshd.backup.generation.script;

import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Renders only fixed managed-helper database verbs with validated typed arguments. / 仅使用已校验类型化参数渲染固定受管 helper 数据库动词。 */
public final class DatabaseCommandRenderer {
    /** Renders read-only database compatibility inspection. / 渲染只读数据库兼容性检查。 */
    public String inspect(RemoteDatabasePort.BackupRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> arguments = new ArrayList<>();
        arguments.add(request.applicationId());
        appendConnection(arguments, request.connection());
        return command("database-inspect", arguments);
    }

    /** Renders a consistency-mode-bound database export. / 渲染绑定一致性方式的数据库导出。 */
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

    /** Renders isolated candidate restore. / 渲染隔离候选恢复。 */
    public String restore(RemoteDatabasePort.RestoreRequest request) {
        return command("database-restore-candidate", restoreArguments(request));
    }

    /** Renders stopped-write candidate activation. / 渲染停写候选激活。 */
    public String commitCandidate(RemoteDatabasePort.RestoreRequest request) {
        return command("database-commit-candidate", restoreArguments(request));
    }

    /** Renders exact previous-database recovery. / 渲染精确旧数据库恢复。 */
    public String recoverCandidate(RemoteDatabasePort.RestoreRequest request) {
        return command("database-recover-candidate", restoreArguments(request));
    }

    /** Renders cleanup for an isolated database candidate. / 渲染隔离数据库候选清理。 */
    public String discardCandidate(RemoteDatabasePort.RestoreRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> arguments = new ArrayList<>();
        arguments.add(request.applicationId());
        arguments.add(request.credentialApplicationId());
        arguments.add(request.candidateId());
        appendConnection(arguments, request.target());
        return command("database-discard-candidate", arguments);
    }

    /** Renders exact artifact streaming from the controlled remote store. / 渲染从受控远程存储精确流出导出物。 */
    public String readArtifact(RemoteDatabasePort.BackupArtifact artifact) {
        return command("database-read-artifact", List.of(artifact.artifactId(),
                Long.toString(artifact.byteCount()), artifact.sha256()));
    }

    /** Renders exact artifact streaming into the controlled remote store. / 渲染向受控远程存储精确流入导出物。 */
    public String stageArtifact(RemoteDatabasePort.BackupArtifact artifact) {
        return command("database-stage-artifact", List.of(artifact.artifactId(),
                Long.toString(artifact.byteCount()), artifact.sha256()));
    }

    /** Renders controlled artifact removal. / 渲染受控导出物移除。 */
    public String discardArtifact(RemoteDatabasePort.BackupArtifact artifact) {
        return command("database-discard-artifact", List.of(artifact.artifactId(), artifact.sha256()));
    }

    private void appendConnection(List<String> arguments, RemoteDatabasePort.ConnectionProfile connection) {
        arguments.add(typeToken(connection.type()));
        if (connection instanceof RemoteDatabasePort.ConnectionProfile.Sqlite sqlite) {
            arguments.add(sqlite.bindingId()); arguments.add(sqlite.location().type().name());
            arguments.add(sqlite.location().path().isEmpty() ? "-" : sqlite.location().path()); arguments.add(sqlite.fileName());
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

    private String command(String verb, List<String> arguments) {
        StringBuilder command = new StringBuilder("sudo -n ")
                .append(SshCommandExecutor.quote(ManagedHelperBundle.PATH)).append(' ')
                .append(SshCommandExecutor.quote(verb));
        arguments.forEach(argument -> command.append(' ').append(SshCommandExecutor.quote(argument)));
        return command.toString();
    }

    private static String typeToken(RemoteDatabasePort.DatabaseType type) {
        return switch (type) {
            case SQLITE -> "sqlite";
            case POSTGRESQL -> "postgresql";
            case MYSQL -> "mysql";
            case MARIADB -> "mariadb";
        };
    }

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
